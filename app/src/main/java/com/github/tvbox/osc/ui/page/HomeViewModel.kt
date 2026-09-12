package com.github.tvbox.osc.ui.page

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.viewmodel.SourceViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import kotlin.coroutines.resume

class HomeViewModel : ViewModel() {
    sealed interface PartitionState {
        data object Loading : PartitionState
        data object Empty : PartitionState
        data object Ready : PartitionState
        /** 2026-09-11:加载看门狗超时态——spider 线程被卡死时 sortResult 永不回调,用 Error 打破永久骨架屏 */
        data object Error : PartitionState
    }

    data class Partition(
        val sort: MovieSort.SortData,
        val state: PartitionState,
        val videos: List<Movie.Video>,
        val nextPage: Int,
        val maxPage: Int,
    ) {
        companion object {
            /** 首屏页码与旧 GridFragment 一致从 1 开始(爬虫 categoryContent 不接受 0) */
            const val FIRST_PAGE = 1
        }

        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    data class Rec(val state: PartitionState, val videos: List<Movie.Video>)

    val currentSource = MutableStateFlow<SourceBean?>(null)
    val sources = MutableStateFlow<List<SourceBean>>(emptyList())
    val allSorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val sorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val rec = MutableStateFlow(Rec(PartitionState.Loading, emptyList()))
    val partitions = MutableStateFlow<List<Partition>>(emptyList())

    /** 2026-09-12 用户定稿:整页加载中(进 App 首次/切源/下拉刷新共用 loadHome 触发)。
     * true 时首页内容区不渲染,改为页面中心圆形加载指示器;全部就绪或看门狗超时转 false。
     * 初始即 true:主界面提前进入组合(已删全屏 BootLoading),页心转圈统一覆盖
     * "配置/jar 后台加载 + 首页数据"两段,直到数据就绪 */
    val pageLoading = MutableStateFlow(true)
    /** 分类列表是否已返回:getSort 回调前 partitions 恒为空列表,不引入此标记
     * "完成"判定会在分类未到时误成立(rec 已非 Loading + 空列表 none{Loading}) */
    private val sortsLoaded = MutableStateFlow(false)
    /** 配置是否就绪(2026-09-13 切源竞态修复):切源时 onApiUrlChanged 先 invalidateVodConfig
     * (sources 被清空)再异步拉新配置,窗口内 getSort(null) 会瞬时返回 Empty——
     * 若不阻断,完成判定提前成立 → pageLoading=false 且 sources 为空 → 首页闪「尚未配置订阅接口」。
     * 就绪前整页完成判定恒不成立,窗口内保持页心转圈 */
    private val bootReady = MutableStateFlow(false)
    /** 整页/分区加载失败事件(看门狗超时,携带提示文案),页面层收集后弹 Toast */
    val pageErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val scope = viewModelScope
    private val sortViewModel = SourceViewModel()
    // 注意:actionViewModel 必须在 init 块之前声明(Kotlin 按声明顺序初始化,
    // init 块里要注册它的观察者)
    private val actionViewModel = SourceViewModel()
    private val loaders = HashMap<String, PartitionLoader>()
    /** 分区第一页并发限流(§4.1:限流 2~3) */
    private val loadSemaphore = Semaphore(2)
    private var loadingSourceKey: String? = null
    /** 2026-09-11:首页加载看门狗。spider 线程池被卡死时 sortResult/listResult 永不回调,
     * 之前会永久停留在骨架屏;超时后把 Loading 态改写为 Error,UI 显示错误+重试 */
    private var watchdogJob: Job? = null

    /** BugReview #13:进程内一次性标记。MainContent 因 Boot 回 Loading 重进组合时,
     * remember 状态会丢失而 ViewModel 仍在,用此标记防 LaunchedEffect 重放拉起直播页 */
    var defaultLiveLaunched = false
    /** 双击退出计时:跨组合重建保留,防 Boot 重进后计时被重置 */
    var lastBackTime = 0L

    private val sortObserver = Observer<AbsSortXml> { absXml: AbsSortXml? -> onSortResult(absXml) }

    /** BugReview #14:action 卡片结果事件流。旧 GridFragment 观察 actionResult → Toast + forceRefresh,
     * Compose 版补回该链路,避免点击后静默死交互 */
    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // actionResult 失败时会 postValue(null)(SourceViewModel),泛型必须声明可空,
    // 否则 Kotlin 对 lambda 参数插入非空检查直接 NPE(本项目既有约定)
    private val actionObserver = Observer<JSONObject?> { json ->
        val msg = json?.optString("msg").orEmpty()
        if (msg.isNotEmpty()) actionMessages.tryEmit(msg)
    }

    init {
        EventBus.getDefault().register(this)
        sortViewModel.sortResult.observeForever(sortObserver)
        actionViewModel.actionResult.observeForever(actionObserver)
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        currentSource.value = ApiConfig.get().getHomeSourceBean()
        // 配置(重)加载完成即刷新首页;首次 Ready 与后续接口变更后的 Ready 都走这里
        scope.launch {
            AppBootstrap.state.collect {
                bootReady.value = it is AppBootstrap.Boot.Ready
                if (it is AppBootstrap.Boot.Ready) loadHome()
            }
        }
        // 整页加载完成判定:bootReady + 分类已返回 + 推荐区非 Loading + 全部分区非 Loading。
        // 只负责置 false(结束);置 true 只发生在 loadHome(),避免 refreshPartitions/
        // applyFilter 等局部重载误触发整页 Loading
        scope.launch {
            combine(bootReady, rec, partitions, sortsLoaded) { ready, r, ps, loaded ->
                ready && loaded && r.state != PartitionState.Loading &&
                    ps.none { it.state == PartitionState.Loading }
            }.collect { ready ->
                if (ready && pageLoading.value) {
                    pageLoading.value = false
                    // 加载已完成:取消看门狗,防止 20s 定时器到点误发"加载失败"(2026-09-12 用户反馈)
                    watchdogJob?.cancel()
                }
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
        sortViewModel.sortResult.removeObserver(sortObserver)
        actionViewModel.actionResult.removeObserver(actionObserver)
        loaders.values.forEach { it.release() }
        loaders.clear()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            reload()
        }
    }

    fun reload() {
        SourceViewModel.clearRuntimeCache()
        loadHome()
    }

    fun switchSource(bean: SourceBean) {
        ApiConfig.get().setSourceBean(bean)
        currentSource.value = bean
        loadHome()
    }

    fun loadHome() {
        // 配置(重)加载后源列表可能变化,每次一并刷新
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        val home = ApiConfig.get().getHomeSourceBean()
        loadingSourceKey = if (home.key.isNullOrEmpty()) null else home.key
        currentSource.value = home
        pageLoading.value = true
        sortsLoaded.value = false
        rec.value = Rec(PartitionState.Loading, emptyList())
        partitions.value = emptyList()
        loaders.values.forEach { it.release() }
        loaders.clear()
        // 重启看门狗:20s 内未完成整页加载则 Loading 转 Error 态
        // (2026-09-12 用户定稿 45s→20s;兜底 spider 线程池卡死永不回调,防永久加载)
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(20_000)
            onHomeLoadTimeout()
        }
        sortViewModel.getSort(loadingSourceKey)
    }

    /** 看门狗超时:仍在 Loading 的推荐区/分区改写为 Error,打破永久加载(2026-09-11)。
     * 若数据在超时后才陆续到达,loadRec/applyPartitionResult 会自然以 Ready 覆盖 Error。
     * 2026-09-12:超时仅在真有 Loading 未完成时生效并按范围发提示——推荐区还没出=整页失败,
     * 仅个别分区卡住=部分失败;否则(早已全部就绪)直接返回,不再误弹"加载失败" */
    private fun onHomeLoadTimeout() {
        val recLoading = rec.value.state == PartitionState.Loading
        val partitionLoading = partitions.value.any { it.state == PartitionState.Loading }
        if (!recLoading && !partitionLoading) return
        if (recLoading) {
            rec.value = Rec(PartitionState.Error, emptyList())
        }
        if (partitionLoading) {
            partitions.value = partitions.value.map { p ->
                if (p.state == PartitionState.Loading) p.copy(state = PartitionState.Error) else p
            }
        }
        pageErrorEvents.tryEmit(
            if (recLoading) "首页加载失败，请检查网络后重试"
            else "部分内容加载超时，可下拉刷新重试"
        )
        pageLoading.value = false
    }

    /** 分区错误重试:仅重置该分区并重拉第一页(2026-09-11) */
    fun retryPartition(partition: Partition) {
        if (partition.state != PartitionState.Error) return
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(partition, Partition.FIRST_PAGE)
    }

    private fun onSortResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey
        if (key == null) {
            // 未配置接口:置空态,避免推荐区永远转圈
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }
        if (absXml?.sourceKey != null && absXml.sourceKey != key) return

        val adjusted = if (absXml?.classes?.sortList != null) {
            DefaultConfig.adjustSort(key, absXml.classes.sortList, true)
        } else {
            DefaultConfig.adjustSort(key, ArrayList(), true)
        }
        allSorts.value = adjusted

        // 推荐分区(第一个 my0)
        val recSort = adjusted.firstOrNull { it.id == "my0" }
        if (recSort != null) {
            loadRec(absXml)
        } else {
            rec.value = Rec(PartitionState.Empty, emptyList())
        }

        val visible = adjusted.filter { it.id != "my0" }
        sorts.value = visible
        val newPartitions = visible.map { Partition(it, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0) }
        partitions.value = newPartitions
        // 分类与分区列表就绪标记:放在 partitions 赋值后,完成判定才不会提前成立
        sortsLoaded.value = true
        newPartitions.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    // ---- 推荐分区 ----

    private fun loadRec(absXml: AbsSortXml?) {
        val videos = absXml?.videoList ?: emptyList()
        rec.value = if (videos.isEmpty()) Rec(PartitionState.Empty, videos) else Rec(PartitionState.Ready, videos)
    }

    // ---- 分类分区 ----

    /** PartitionLoader 请求结果；stale=true 表示该请求已被新请求覆盖或 loader 已释放，不应写回状态 */
    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private fun requestPartition(current: Partition, page: Int) {
        val loader = loaders.getOrPut(current.sort.id) { PartitionLoader(current.sort) }
        scope.launch {
            loadSemaphore.withPermit {
                val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                    loader.request(page) { r -> if (cont.isActive) cont.resume(r) }
                }
                // 被覆盖/释放的旧请求只归还信号量许可，不写分区状态（防续体悬挂致许可泄漏）
                if (!result.stale) {
                    applyPartitionResult(current.sort.id, page, result.absXml)
                }
            }
        }
    }

    private fun applyPartitionResult(sortId: String, page: Int, absXml: AbsXml?) {
        val videos = absXml?.movie?.videoList ?: emptyList()
        val maxPage = absXml?.movie?.pagecount ?: 0
        partitions.value = partitions.value.map { p ->
            if (p.sort.id != sortId) {
                p
            } else if (videos.isEmpty() && page == Partition.FIRST_PAGE) {
                Partition(p.sort, PartitionState.Empty, emptyList(), Partition.FIRST_PAGE, maxPage)
            } else {
                val merged = if (page == 0) videos else p.videos + videos
                Partition(p.sort, PartitionState.Ready, merged, page + 1, maxPage)
            }
        }
    }

    fun loadMorePartition(partition: Partition) {
        if (partition.state != PartitionState.Ready || !partition.hasMore) return
        val loader = loaders[partition.sort.id] ?: return
        // 在途防抖:LazyList 条目滚出/滚回视口会重复触发 onLoadMore,在途时直接忽略
        if (loader.busy) return
        requestPartition(partition, partition.nextPage)
    }

    fun applyFilter(partition: Partition, filterSelect: Map<String, String>) {
        partition.sort.filterSelect = HashMap(filterSelect)
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) {
                Partition(it.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                it
            }
        }
        requestPartition(partition.copy(sort = partition.sort), Partition.FIRST_PAGE)
    }

    /** action 卡片(旧 GridFragment 同款行为) */
    fun handleAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    /** BugReview #14:action 结果刷新(旧 forceRefresh 语义:重置全部分区并重请求第一页) */
    fun refreshPartitions() {
        partitions.value = partitions.value.map {
            Partition(it.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
        }
        partitions.value.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    private inner class PartitionLoader(val sort: MovieSort.SortData) {
        private val svm = SourceViewModel()
        // listResult 无法区分页码，同一 loader 同时只允许一个在途请求；
        // 新请求覆盖旧 pending 时，旧续体以 stale 结果立即完成：归还信号量许可且不写状态，
        // 修复「pending 被覆盖 → 续体永不 resume → 许可泄漏 → 首页永久 Loading」
        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        /** 是否有在途请求 */
        @Volatile
        var busy: Boolean = false
            private set

        private val observer = Observer<AbsXml> { abs: AbsXml? ->
            val current = pending
            pending = null
            busy = false
            current?.invoke(LoaderResult(stale = false, absXml = abs))
        }

        init {
            svm.listResult.observeForever(observer)
        }

        fun request(page: Int, onDone: (LoaderResult) -> Unit) {
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = onDone
            busy = true
            svm.getList(sort, page)
        }

        fun release() {
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = null
            busy = false
            svm.listResult.removeObserver(observer)
        }
    }
}
