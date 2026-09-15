package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.PlaybackSession
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lzy.okgo.OkGo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DetailViewModel : ViewModel() {

    sealed interface PageState {
        data object Loading : PageState
        data class Empty(val msg: String? = null) : PageState
        data object Ready : PageState
    }

    data class SourceChip(val key: String, val name: String)

    // ---- 页面状态 ----
    val pageState = MutableStateFlow<PageState>(PageState.Loading)
    /** vodInfo 为可变 Java bean,字段变化以 revision 触发重组 */
    val revision = MutableStateFlow(0)
    val fullScreen = MutableStateFlow(false)
    /** 旋转过渡态:已下发方向切换、等系统旋转落地(布局形态延后切换,见 DetailActivity.isFullBox) */
    val rotating = MutableStateFlow(false)
    /** 播放请求信号:容器就绪后由 UI 消费发起 setData */
    val playSignal = MutableStateFlow(0)
    val collected = MutableStateFlow(false)
    val qualityOptions = MutableStateFlow<List<String>>(emptyList())
    val qualitySelected = MutableStateFlow(0)
    val sourceChips = MutableStateFlow<List<SourceChip>>(emptyList())
    val sourcesSearching = MutableStateFlow(false)
    val relatedVideos = MutableStateFlow<List<Movie.Video>>(emptyList())
    val episodeSheet = MutableStateFlow(false)
    val toastEvent = MutableStateFlow<String?>(null)
    val finishEvent = MutableStateFlow(false)

    /** 播放容器引用(Activity 持有,清晰度切换等需要) */
    var playContainerRef: PlayContainer? = null

    // ---- 与旧 DetailActivity 对齐的可变状态 ----
    var vodInfo: VodInfo? = null; private set
    var previewVodInfo: VodInfo? = null; private set
    var vodId = ""; private set
    var sourceKey = ""; private set
    var firstsourceKey = ""; private set

    /** 手动点选“线路”标记:仅作用于紧接着的一次取流,失败/超时不自动换线换源,直接报错停留 */
    private var manualLineSwitchPending = false

    private var vodName = ""
    private var vodPicture = ""
    private var fromCollect = false

    private val sourceViewModel = SourceViewModel()
    private val detailObserver = androidx.lifecycle.Observer<AbsXml> { onDetailResult(it) }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---- 换源引擎:聚合搜同名进候选池,自动兜底与手动 chips 共用 ----
    private val fallbackCandidates = ArrayList<Movie.Video>()
    private val candidateKeys = HashSet<String>()
    private val triedKeys = HashSet<String>()
    private val usedSourceKeys = HashSet<String>()
    // 信号态是实例级:防多轮搜索/多实例互相污染配额与 pending 表项
    private val semaphore = Semaphore(SOURCE_SEARCH_CONCURRENCY)
    private val pendingSearchDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val searchCaller = SourceViewModel()
    private var fallbackKeepCurrentDetail = false
    private var fallbackLoadingCandidate = false
    private var fallbackActive = false
    private var fallbackAutoSwitch = false
    private var fallbackEpisode: VodInfo.VodSeries? = null
    private var fallbackEpisodeIndex = -1
    private var detailTimeoutScheduled = false

    // ---- 手动换源:点击即停 + 失败回滚 ----
    /**
     * 换源前快照:新源可用后丢弃,失败则回退原源并从停播时落盘的进度继续。
     */
    private class SwitchSnapshot(
        val vodInfo: VodInfo,
        val vodId: String,
        val sourceKey: String,
        val firstsourceKey: String,
        val vodName: String,
        val vodPicture: String,
    )

    private var switchSnapshot: SwitchSnapshot? = null

    // ---- 换源/相关推荐聚合搜索 ----
    /**
     * 本轮聚合搜索编号(token 形如 "detail_3",取值 = 进程级自增 [SEARCH_SEQ])。
     * ⚠️ 不能改用实例内自增:详情页可叠加(相关推荐新建实例、旧实例不销毁仍会 post),
     * 两个实例的首搜都会是 "detail_1",旧实例的迟到结果会被新实例当成自己的结果。
     */
    private var searchToken = 0
    private var searchTitle = ""

    init {
        EventBus.getDefault().register(this)
        // SourceViewModel 经 LiveData 回传详情(爬虫分支在工作线程 post)
        sourceViewModel.detailResult.observeForever(detailObserver)
    }

    fun initFromIntent(intent: Intent?) {
        if (vodId.isNotEmpty()) return
        val bundle = intent?.extras ?: return
        vodName = bundle.getString("title", "")
        vodPicture = bundle.getString("picture", "")
        fromCollect = bundle.getBoolean("collect", false)
        loadDetail(bundle.getString("id", ""), bundle.getString("sourceKey", ""))
        if (vodName.isNotEmpty()) startSourceSearch()
    }

    fun setFullScreen(full: Boolean) {
        // 目标方向与实际不一致 → 进旋转过渡态:布局形态等落地再切(见 isFullBox)
        val landNow = playContainerRef?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        rotating.value = (full != landNow)
        fullScreen.value = full
    }

    fun bumpRevision() {
        revision.value += 1
    }

    fun requestPlay() {
        playSignal.value += 1
    }

    /** 读取并清除手动选线标记(供 preparePlaySession 写入播放容器) */
    private fun consumeManualLineSwitch(): Boolean {
        val pending = manualLineSwitchPending
        manualLineSwitchPending = false
        return pending
    }

    fun showEpisodeSheet() {
        episodeSheet.value = true
    }

    fun dismissEpisodeSheet() {
        episodeSheet.value = false
    }

    fun clearToast() {
        toastEvent.value = null
    }

    fun consumeFinish() {
        finishEvent.value = false
    }

    // ============ 详情加载 ============

    private fun loadDetail(vid: String, key: String) {
        vodId = vid.orEmpty()
        sourceKey = key.orEmpty()
        firstsourceKey = sourceKey
        usedSourceKeys.add(firstsourceKey)
        collected.value = RoomDataManger.isVodCollect(sourceKey, vodId)
        if (vodId.isEmpty() || vodId.startsWith("msearch:") || ApiConfig.get().getSource(sourceKey) == null) {
            onDetailUnavailable()
            return
        }
        pageState.value = PageState.Loading
        sourceViewModel.getDetail(sourceKey, vodId)
    }

    fun retry() {
        if (vodId.isEmpty()) return
        loadDetail(vodId, sourceKey)
        if (searchTitle.isNotEmpty() && !sourcesSearching.value) startSourceSearch()
    }

    /** 详情不可用:尝试自动换源兜底,无路可走才回滚原源/进空态 */
    private fun onDetailUnavailable() {
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
        } else if (!startFallbackIfNeeded(auto = true)) {
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    fun onDetailResult(absXml: AbsXml?) {
        // 换源引擎激活但未在加载候选时,忽略详情结果
        if (fallbackActive && !fallbackLoadingCandidate) return
        if (absXml != null && !absXml.sourceKey.isNullOrEmpty() && absXml.sourceKey != sourceKey) return
        val videoList = absXml?.movie?.videoList
        if (videoList != null && videoList.isNotEmpty()) {
            val wasFallback = fallbackLoadingCandidate
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
            }
            if (wasFallback) {
                val fallbackSource = ApiConfig.get().getSource(sourceKey)
                toastEvent.value = "站点切换至" + (fallbackSource?.name ?: sourceKey)
            }
            if (!absXml.msg.isNullOrEmpty() && absXml.msg != "数据列表") {
                // 目标源报错:优先回滚原源(提示带上源站原因),无快照才进空态
                if (!rollbackManualSwitch(absXml.msg)) {
                    toastEvent.value = absXml.msg
                    enterEmpty(absXml.msg)
                }
                return
            }
            val mVideo = videoList[0]
            mVideo.id = vodId
            if (mVideo.name.isNullOrEmpty()) mVideo.name = vodName
            if (mVideo.name.isNullOrEmpty()) mVideo.name = "TVBox"
            if ((mVideo.pic == null || mVideo.pic.isEmpty()) && vodPicture.isNotEmpty()) {
                mVideo.pic = vodPicture
            }
            val info = VodInfo()
            info.setVideo(mVideo)
            info.sourceKey = mVideo.sourceKey
            sourceKey = mVideo.sourceKey ?: sourceKey

            val record = RoomDataManger.getVodInfo(sourceKey, vodId)
            if (record != null) {
                info.playIndex = maxOf(record.playIndex, 0)
                info.playFlag = record.playFlag
                info.playerCfg = record.playerCfg
                info.reverseSort = record.reverseSort
            } else {
                info.playIndex = 0
                info.playFlag = null
                info.playerCfg = ""
                info.reverseSort = false
            }
            if (info.reverseSort) info.reverse()
            if (info.playFlag == null || info.seriesMap?.containsKey(info.playFlag) != true) {
                info.playFlag = info.seriesMap?.keys?.firstOrNull()
            }
            restoreFallbackEpisode(info)
            resetEngineState(keepChips = true)
            val playingList = info.seriesMap?.get(info.playFlag)
            if (playingList != null) {
                info.playIndex = info.playIndex.coerceIn(0, playingList.size - 1)
                for (flag in info.seriesFlags) {
                    flag.selected = flag.name == info.playFlag
                }
            }
            vodInfo = info
            if (searchTitle.isEmpty() && !info.name.isNullOrEmpty()) {
                searchTitle = info.name.trim()
                startSourceSearch()
            }
            vodName = mVideo.name ?: vodName
            // 换源落地(含自动兜底换到其他候选源):有可播列表才作废回滚快照;
            // 该源详情能解析但没有可播集时保留快照,让后续兜底全败仍能回原源
            if (!playingList.isNullOrEmpty()) switchSnapshot = null
            pageState.value = PageState.Ready
            bumpRevision()
            requestPlay()
            if (playingList.isNullOrEmpty()) {
                // 无可播列表:自动换源兜底(旧行为)
                startFallbackIfNeeded(auto = true)
            }
        } else {
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                cancelDetailTimeout()
                loadNextFallbackCandidate()
                return
            }
            handleEmptyDetail(absXml)
        }
    }

    private fun handleEmptyDetail(data: AbsXml?) {
        val shouldFinish = data != null && !data.msg.isNullOrEmpty()
        if (shouldFinish || fromCollect) {
            // 源站直接报错:手动换源则回滚原源继续播,不再直接关页
            if (shouldFinish && rollbackManualSwitch(data.msg)) return
            resetEngineState(keepChips = false)
            if (shouldFinish) toastEvent.value = data.msg
            finishEvent.value = true
            return
        }
        if (fallbackActive) {
            fallbackLoadingCandidate = false
            loadNextFallbackCandidate()
        } else if (!startFallbackIfNeeded(auto = true)) {
            if (!rollbackManualSwitch()) enterEmpty()
        }
    }

    // ============ 换源 / 相关推荐:统一聚合搜索 ============

    /** 进入详情即后台聚合搜索:同名 → 换源候选;非同名 → 相关推荐 */
    private fun startSourceSearch() {
        val title = searchTitle.ifEmpty { vodName.trim() }
        if (title.isEmpty()) return
        if (sourcesSearching.value && searchTitle == title) return
        searchTitle = title
        // 进程级自增:保证跨实例(详情页叠加)的 token 不复用,旧实例迟到结果会被校验丢弃
        searchToken = SEARCH_SEQ.incrementAndGet()
        val myToken = searchToken
        val tokenStr = "detail_$myToken"
        val checked = SearchHelper.getSourcesForSearch()
        val home = ApiConfig.get().getHomeSourceBean()
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && it.isQuickSearch() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
        sourcesSearching.value = sources.isNotEmpty()
        relatedVideos.value = emptyList()
        if (sources.isEmpty()) return
        viewModelScope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            val done = CompletableDeferred<Unit>()
                            // 覆盖表项时 complete 旧 deferred,旧任务立即归还许可而非挂满 30s
                            pendingSearchDone.put(bean.key, done)?.complete(Unit)
                            try {
                                withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                                    // 爬虫分支阻塞调用线程,必须切 IO
                                    withContext(Dispatchers.IO) {
                                        searchCaller.getSearch(bean.key, title, tokenStr)
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSearchDone.remove(bean.key)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (tokenStr == currentTokenStr()) {
                sourcesSearching.value = false
                if (fallbackAutoSwitch && !fallbackLoadingCandidate) loadNextFallbackCandidate()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onSearchResultEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            val data = event.obj as? AbsXml ?: return
            if (data.searchToken != currentTokenStr()) return
            pendingSearchDone.remove(data.sourceKey)?.complete(Unit)
            val videos = data.movie?.videoList.orEmpty()
            val fresh = videos.filter {
                !it.id.isNullOrEmpty() && it.name?.trim() == searchTitle
                        && !usedSourceKeys.contains(it.sourceKey)
                        && it.sourceKey != sourceKey
            }.filter { candidateKeys.add(candidateKey(it)) }
            if (fresh.isNotEmpty()) {
                synchronized(fallbackCandidates) { fallbackCandidates.addAll(fresh) }
                publishSourceChips()
                if (fallbackAutoSwitch && !fallbackLoadingCandidate) loadNextFallbackCandidate()
            }
            val related = videos.filter {
                !it.id.isNullOrEmpty() && it.name?.trim() != searchTitle
                        && !(it.sourceKey == sourceKey && it.id == vodId)
            }
            if (related.isNotEmpty()) {
                // 必须去重:同一 sourceKey|id 出现两次会让 RelatedSection 的 LazyRow
                // item key 撞车("Key ... was already used" 闪退)
                val seen = relatedVideos.value.mapTo(HashSet()) { candidateKey(it) }
                val deduped = related.filter { seen.add(candidateKey(it)) }
                if (deduped.isNotEmpty()) relatedVideos.value = relatedVideos.value + deduped
            }
        } else if (event.type == RefreshEvent.TYPE_PLAY_QUALITY) {
            updateQualityOptions(event.obj as? org.json.JSONObject)
        }
    }

    private fun currentTokenStr(): String = "detail_$searchToken"

    private fun publishSourceChips() {
        val candidates = synchronized(fallbackCandidates) { fallbackCandidates.toList() }
        sourceChips.value = candidates
            .filter { !usedSourceKeys.contains(it.sourceKey) && it.sourceKey != sourceKey }
            .map { video ->
                val key = video.sourceKey.orEmpty()
                // chip 展示源名称(候选片名全部相同,展示片名会全是同名)
                SourceChip(key, ApiConfig.get().getSource(key)?.name ?: key)
            }
            .distinctBy { it.key }
    }

    fun candidateForKey(key: String): Movie.Video? =
        synchronized(fallbackCandidates) { fallbackCandidates.firstOrNull { it.sourceKey == key } }

    /**
     * 手动点选换源 chips:点击即停当前播放(进度由容器落盘)并记回滚快照。
     */
    fun switchSource(video: Movie.Video) {
        stopPlaybackForSwitch()
        usedSourceKeys.add(video.sourceKey.orEmpty())
        vodName = video.name ?: vodName
        vodPicture = video.pic ?: vodPicture
        resetEngineState(keepChips = true)
        loadDetail(video.id.orEmpty(), video.sourceKey.orEmpty())
    }

    /**
     * 换源点击即停:释放播放实例并记快照。
     * 已有快照时不覆盖 —— 换源窗口内连点、或源"能解析但无可播集"时,回滚目标始终是最后一次可播状态。
     */
    private fun stopPlaybackForSwitch() {
        val info = vodInfo ?: return
        if (switchSnapshot == null) {
            switchSnapshot = SwitchSnapshot(info, vodId, sourceKey, firstsourceKey, vodName, vodPicture)
        }
        playContainerRef?.stopForSourceSwitch("正在切换片源")
    }

    /**
     * 手动换源失败的回滚:恢复原源与原播放信息并重播(进度已落盘,同键直接续播)。
     * 无快照或原片源已不可播时返回 false,由调用方沿用原空态/关页处理。
     */
    private fun rollbackManualSwitch(reason: String? = null): Boolean {
        val snapshot = switchSnapshot ?: return false
        if (snapshot.vodInfo.seriesMap?.get(snapshot.vodInfo.playFlag).isNullOrEmpty()) return false
        switchSnapshot = null
        vodInfo = snapshot.vodInfo
        vodId = snapshot.vodId
        sourceKey = snapshot.sourceKey
        firstsourceKey = snapshot.firstsourceKey
        vodName = snapshot.vodName
        vodPicture = snapshot.vodPicture
        resetEngineState(keepChips = true)
        toastEvent.value =
            if (reason.isNullOrEmpty()) "换源失败，继续原片源" else "换源失败：$reason，继续原片源"
        pageState.value = PageState.Ready
        bumpRevision()
        requestPlay()
        return true
    }

    /** 进空态:顺带清掉"正在切换片源"提示(容器可能已随换源停播置起该提示) */
    private fun enterEmpty(msg: String? = null) {
        playContainerRef?.clearSourceSwitchTip()
        pageState.value = PageState.Empty(msg)
    }

    /** 播放容器线路耗尽后的自动换源入口 */
    fun startFallbackAfterLinesExhausted(): Boolean = startFallbackIfNeeded(auto = true, fromLinesExhausted = true)

    private fun startFallbackIfNeeded(auto: Boolean, fromLinesExhausted: Boolean = false): Boolean {
        val currentSource = ApiConfig.get().getSource(sourceKey)
        if (currentSource == null || !currentSource.isChangeable()) return false
        if (fallbackActive) return true
        val title = (if (vodInfo?.name.isNullOrEmpty()) vodName else vodInfo?.name).orEmpty().trim()
        if (title.isEmpty()) return false
        fallbackKeepCurrentDetail = fromLinesExhausted && vodInfo != null && !vodInfo?.seriesMap.isNullOrEmpty()
        captureFallbackEpisode()
        searchTitle = title
        usedSourceKeys.add(sourceKey)
        fallbackActive = true
        fallbackAutoSwitch = auto
        triedKeys.add(candidateKey(sourceKey, vodId))
        loadNextFallbackCandidate()
        // 与旧实现对齐:候选为空且无可保持详情时返回 false,由播放容器走错误提示
        return fallbackActive
    }

    private fun loadNextFallbackCandidate() {
        while (true) {
            val video = synchronized(fallbackCandidates) {
                if (fallbackCandidates.isEmpty()) null else fallbackCandidates.removeAt(0)
            } ?: break
            val cKey = candidateKey(video)
            if (usedSourceKeys.contains(video.sourceKey) || !triedKeys.add(cKey)) continue
            fallbackLoadingCandidate = true
            fallbackActive = true
            fallbackAutoSwitch = true
            usedSourceKeys.add(video.sourceKey.orEmpty())
            vodName = video.name ?: vodName
            vodPicture = video.pic ?: vodPicture
            publishSourceChips()
            scheduleDetailTimeout()
            loadDetailInternal(video.id.orEmpty(), video.sourceKey.orEmpty())
            return
        }
        publishSourceChips()
        if (!sourcesSearching.value) finishFallbackWithoutResult()
    }

    /** 加载候选详情(不重置引擎、不重启聚合搜索) */
    private fun loadDetailInternal(vid: String, key: String) {
        vodId = vid
        sourceKey = key
        firstsourceKey = key
        collected.value = RoomDataManger.isVodCollect(sourceKey, vodId)
        sourceViewModel.getDetail(sourceKey, vodId, true)
    }

    private fun finishFallbackWithoutResult() {
        val keep = fallbackKeepCurrentDetail
        resetEngineState(keepChips = true)
        // 手动换源的自动兜底也全败:回滚原源继续播,不停在空态
        if (!keep && rollbackManualSwitch()) return
        if (!keep && pageState.value != PageState.Ready) {
            enterEmpty()
        }
    }

    private fun scheduleDetailTimeout() {
        if (detailTimeoutScheduled) return
        detailTimeoutScheduled = true
        mainHandler.postDelayed({
            detailTimeoutScheduled = false
            if (fallbackLoadingCandidate) {
                fallbackLoadingCandidate = false
                OkGo.getInstance().cancelTag("detail")
                loadNextFallbackCandidate()
            }
        }, DETAIL_FALLBACK_DETAIL_TIMEOUT_MS)
    }

    private fun cancelDetailTimeout() {
        detailTimeoutScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun captureFallbackEpisode() {
        val info = vodInfo
        fallbackEpisode = null
        fallbackEpisodeIndex = -1
        if (info?.seriesMap == null || info.playFlag.isNullOrEmpty()) return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.isEmpty()) return
        fallbackEpisodeIndex = info.playIndex.coerceIn(0, list.size - 1)
        fallbackEpisode = list[fallbackEpisodeIndex]
    }

    private fun restoreFallbackEpisode(info: VodInfo) {
        val episode = fallbackEpisode
        if (episode == null || fallbackEpisodeIndex < 0 || info.seriesMap == null) return
        val preferredFlag = info.playFlag
        val preferredList = info.seriesMap?.get(preferredFlag)
        var matched = findMatchingEpisodeIndex(episode, preferredList)
        if (matched >= 0) {
            info.playIndex = matched
            return
        }
        for (flag in info.seriesFlags) {
            if (flag.name.isNullOrEmpty() || flag.name == preferredFlag) continue
            matched = findMatchingEpisodeIndex(episode, info.seriesMap?.get(flag.name))
            if (matched >= 0) {
                info.playFlag = flag.name
                info.playIndex = matched
                return
            }
        }
        if (preferredList != null && preferredList.isNotEmpty()) {
            info.playIndex = fallbackEpisodeIndex.coerceIn(0, preferredList.size - 1)
        }
    }

    private fun resetEngineState(keepChips: Boolean) {
        fallbackActive = false
        fallbackAutoSwitch = false
        fallbackKeepCurrentDetail = false
        fallbackLoadingCandidate = false
        detailTimeoutScheduled = false
        cancelDetailTimeout()
        triedKeys.clear()
        if (!keepChips) {
            synchronized(fallbackCandidates) { fallbackCandidates.clear() }
            candidateKeys.clear()
        }
        publishSourceChips()
    }

    fun destroyEngine() {
        cancelDetailTimeout()
        OkGo.getInstance().cancelTag("detail")
        OkGo.getInstance().cancelTag("search")
    }

    private fun candidateKey(video: Movie.Video): String =
        (video.sourceKey ?: "") + "|" + (video.id ?: "")

    private fun candidateKey(key: String, id: String): String = "$key|$id"

    // ============ 选集 / 线路 / 清晰度 / 收藏 ============

    fun onEpisodeClick(position: Int) {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (position < 0 || position >= list.size || position == info.playIndex) return
        info.playIndex = position
        list.forEachIndexed { index, series -> series.selected = index == position }
        bumpRevision()
        requestPlay()
    }

    fun onFlagClick(flagName: String) {
        val info = vodInfo ?: return
        if (info.playFlag == flagName) return
        val oldList = info.seriesMap?.get(info.playFlag)
        val currentIndex = info.playIndex.coerceAtLeast(0)
        val currentSeries = oldList?.getOrNull(currentIndex)
        info.playFlag = flagName
        val newList = info.seriesMap?.get(flagName)
        if (newList != null && newList.isNotEmpty()) {
            info.playIndex = findSameEpisodeIndex(currentSeries, newList, currentIndex)
            newList.forEachIndexed { index, series -> series.selected = index == info.playIndex }
        }
        info.seriesFlags.forEach { it.selected = it.name == flagName }
        manualLineSwitchPending = true
        bumpRevision()
        requestPlay()
    }

    fun toggleReverse() {
        val info = vodInfo ?: return
        val list = info.seriesMap?.get(info.playFlag) ?: return
        if (list.size <= 1) return
        info.reverseSort = !info.reverseSort
        info.reverse()
        info.playIndex = (list.size - 1) - info.playIndex
        bumpRevision()
    }

    fun toggleCollect() {
        val info = vodInfo ?: return
        if (collected.value) {
            RoomDataManger.deleteVodCollect(sourceKey, info)
            toastEvent.value = "已移除收藏夹"
        } else {
            RoomDataManger.insertVodCollect(sourceKey, info)
            toastEvent.value = "已加入收藏夹"
        }
        collected.value = !collected.value
        // 通知收藏页实时刷新(此前漏发事件,收藏页不更新)
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
    }

    private fun updateQualityOptions(result: org.json.JSONObject?) {
        val options = ArrayList<String>()
        try {
            val value = result?.opt("url")
            val urls = when (value) {
                is org.json.JSONArray -> value
                is String -> org.json.JSONArray(value)
                else -> null
            }
            if (urls != null) {
                var i = 0
                while (i + 1 < urls.length()) {
                    options.add(urls.optString(i))
                    i += 2
                }
            }
        } catch (_: Throwable) {
        }
        if (options == qualityOptions.value) return
        qualityOptions.value = options
        qualitySelected.value = 0
    }

    fun onQualityClick(position: Int) {
        if (position == qualitySelected.value) {
            setFullScreen(true)
            return
        }
        if (playContainerRef?.selectQuality(position) == true) {
            qualitySelected.value = position
        }
    }

    // ============ 播放事件同步(容器 → 页面) ============

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type != RefreshEvent.TYPE_REFRESH) return
        val info = vodInfo ?: return
        when (val obj = event.obj) {
            is VodInfo -> syncPlayingVodInfo(obj)
            is Int -> {
                val list = info.seriesMap?.get(info.playFlag) ?: return
                list.forEachIndexed { index, series -> series.selected = index == obj }
                info.playIndex = obj
                insertVod()
                bumpRevision()
            }
            is org.json.JSONObject -> {
                info.playerCfg = obj.toString()
                insertVod()
                bumpRevision()
            }
        }
    }

    private fun syncPlayingVodInfo(playing: VodInfo) {
        val info = vodInfo ?: return
        val newFlag = playing.playFlag
        if (newFlag.isNullOrEmpty() || info.seriesMap?.containsKey(newFlag) != true) return
        val newList = info.seriesMap?.get(newFlag) ?: return
        if (newList.isEmpty()) return
        val playingList = playing.seriesMap?.get(newFlag)
        val playingSeries = playingList?.getOrNull(playing.playIndex.coerceIn(0, playingList.size - 1))
        val newIndex = findSameEpisodeIndex(playingSeries, newList, playing.playIndex)
        info.playFlag = newFlag
        info.playIndex = newIndex
        if (playing.playerCfg != null) info.playerCfg = playing.playerCfg
        info.seriesFlags.forEach { it.selected = it.name == newFlag }
        info.seriesMap?.values?.forEach { list -> list.forEach { it.selected = false } }
        newList[newIndex].selected = true
        insertVod()
        bumpRevision()
        LOG.i("echo-detail sync -> $newFlag/$newIndex")
    }

    private fun insertVod() {
        val info = vodInfo ?: return
        try {
            info.playNote = info.seriesMap?.get(info.playFlag)?.get(info.playIndex)?.name ?: ""
        } catch (_: Throwable) {
            info.playNote = ""
        }
        RoomDataManger.insertVodRecord(firstsourceKey, info)
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    /**
     * 组装交给播放容器的会话数据。[PlaybackSession] 取代了原本"全局单槽 + Bundle"两个隐式通道;
     * `App.setVodInfo` 仍要写 —— 本地 HTTP 服务(RemoteServer 弹幕接口)靠它取当前片名。
     */
    fun preparePlaySession(): PlaybackSession? {
        val info = vodInfo ?: return null
        val list = info.seriesMap?.get(info.playFlag) ?: return null
        if (list.isEmpty()) return null
        insertVod()
        val preview = previewVodInfo ?: VodInfo()
        preview.id = info.id
        preview.name = info.name
        preview.pic = info.pic
        preview.sourceKey = info.sourceKey
        preview.playNote = info.playNote
        preview.seriesFlags = info.seriesFlags
        preview.seriesMap = info.seriesMap
        preview.playerCfg = info.playerCfg
        preview.playFlag = info.playFlag
        preview.playIndex = info.playIndex
        previewVodInfo = preview
        App.getInstance().setVodInfo(preview)
        return PlaybackSession(preview, sourceKey, consumeManualLineSwitch())
    }

    // ============ 集数匹配工具 ============

    private fun findSameEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>, fallback: Int): Int {
        if (target.isEmpty()) return 0
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) {
            return fallback.coerceIn(0, target.size - 1)
        }
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return if (matched >= 0) matched else fallback.coerceIn(0, target.size - 1)
    }

    private fun findMatchingEpisodeIndex(current: VodInfo.VodSeries?, target: List<VodInfo.VodSeries>?): Int {
        if (target.isNullOrEmpty()) return -1
        if (target.size == 1) return 0
        if (current == null || current.name.isNullOrEmpty()) return -1
        val currentEpisode = extractEpisodeNumber(current.name)
        var matched = -1
        var best = 0
        target.forEachIndexed { i, series ->
            val score = episodeMatchScore(current.name, currentEpisode, series.name)
            if (score > best) {
                best = score
                matched = i
            }
        }
        return matched
    }

    private fun episodeMatchScore(currentName: String?, currentEpisode: Int, targetName: String?): Int {
        if (currentName.isNullOrEmpty() || targetName.isNullOrEmpty()) return 0
        if (targetName.equals(currentName, ignoreCase = true)) return 100
        if (currentEpisode >= 0 && extractEpisodeNumber(targetName) == currentEpisode) return 80
        val currentLower = currentName.lowercase(Locale.ROOT)
        val targetLower = targetName.lowercase(Locale.ROOT)
        if (currentEpisode < 0 && currentName.length >= 2 && targetLower.contains(currentLower)) return 70
        if (currentEpisode < 0 && targetName.length >= 2 && currentLower.contains(targetLower)) return 60
        return 0
    }

    private fun extractEpisodeNumber(name: String?): Int {
        if (name.isNullOrEmpty()) return -1
        return try {
            var text = name.replace(Regex("\\[.*?]|\\(.*?\\)"), "")
            text = text.replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            text = text.lowercase(Locale.ROOT).replace(Regex("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4"), "")
            val matcher = Regex("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})").find(text)
            if (matcher != null) {
                matcher.groupValues[1].toInt()
            } else {
                val number = text.replace(Regex("\\D+"), "")
                if (number.isNotEmpty()) number.toInt() else -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    override fun onCleared() {
        sourceViewModel.detailResult.removeObserver(detailObserver)
        EventBus.getDefault().unregister(this)
        destroyEngine()
        super.onCleared()
    }

    companion object {
        /** 进程级聚合搜索序号:保证 token 跨实例不复用(见 [searchToken]) */
        private val SEARCH_SEQ = java.util.concurrent.atomic.AtomicInteger(0)

        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L
        private const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L
        private const val SOURCE_SEARCH_CONCURRENCY = 6
    }
}
