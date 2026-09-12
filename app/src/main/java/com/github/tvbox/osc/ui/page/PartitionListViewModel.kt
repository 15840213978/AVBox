package com.github.tvbox.osc.ui.page

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.viewmodel.SourceViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 栏目资源二级页(2026-09-09:首页分区/搜索结果分区右侧「全部 >」进入)。
 * partition 模式:按 SortData(含 filterSelect)经 SourceViewModel.getList 分页拉全量;
 * search 模式:结果列表由 Intent 直接传入,不走本 VM 的加载链。
 */
class PartitionListVM : ViewModel() {
    sealed interface State {
        data object Loading : State
        data object Empty : State
        data object Ready : State
    }

    data class UiState(
        val state: State = State.Loading,
        val videos: List<Movie.Video> = emptyList(),
        val nextPage: Int = FIRST_PAGE,
        val maxPage: Int = 0,
    ) {
        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    companion object {
        /** 首屏页码与首页分区一致从 1 开始(爬虫 categoryContent 不接受 0) */
        const val FIRST_PAGE = 1
    }

    val ui = MutableStateFlow(UiState())

    /** 当前栏目分类(含筛选选择);partition 模式经 initIfNeed 赋值 */
    var sort: MovieSort.SortData? = null
        private set

    private val scope = viewModelScope
    private var initialized = false

    /** action 卡结果提示流(与 HomeViewModel 同策略:只有非空 msg 才提示) */
    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val actionViewModel = SourceViewModel()

    // actionResult 失败时会 postValue(null)(SourceViewModel),泛型必须声明可空,
    // 否则 Kotlin 对 lambda 参数插入非空检查直接 NPE(本项目既有约定)
    private val actionObserver = Observer<JSONObject?> { json ->
        val msg = json?.optString("msg").orEmpty()
        if (msg.isNotEmpty()) actionMessages.tryEmit(msg)
        refresh()
    }

    init {
        actionViewModel.actionResult.observeForever(actionObserver)
    }

    /** 请求结果;stale=true 表示已被新请求覆盖或已释放,不应写回状态(与 HomeViewModel.PartitionLoader 同策略) */
    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private val loader = object {
        private val svm = SourceViewModel()

        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        @Volatile
        var busy: Boolean = false
            private set

        // 必须显式 AbsXml?:listResult 失败时会 post null,Kotlin lambda 默认推断非空参数
        // 会触发 intrinsic NPE(与 HomeViewModel.PartitionLoader 写法对齐)
        private val observer = Observer<AbsXml> { abs: AbsXml? ->
            val current = pending
            pending = null
            busy = false
            current?.invoke(LoaderResult(false, abs))
        }

        init {
            svm.listResult.observeForever(observer)
        }

        fun release() {
            pending?.invoke(LoaderResult(true, null))
            pending = null
            busy = false
            svm.listResult.removeObserver(observer)
        }

        fun request(page: Int, data: MovieSort.SortData, onDone: (LoaderResult) -> Unit) {
            pending?.invoke(LoaderResult(true, null))
            pending = onDone
            busy = true
            svm.getList(data, page)
        }
    }

    override fun onCleared() {
        loader.release()
        actionViewModel.actionResult.removeObserver(actionObserver)
    }

    /** action 卡(如网盘配置卡「登入 / 清除缓存」):执行后由 actionObserver 提示 + 刷新当前列表 */
    fun runAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    /** 回到第一页重拉(action 后刷新 / 目录内容刷新) */
    fun refresh() {
        if (!initialized) return
        ui.value = UiState(State.Loading)
        request(FIRST_PAGE)
    }

    /** 幂等初始化(Activity 重建后 VM 仍在则跳过重放) */
    fun initIfNeed(sort: MovieSort.SortData) {
        if (initialized) return
        initialized = true
        this.sort = sort
        request(FIRST_PAGE)
    }

    fun applyFilter(selection: Map<String, String>) {
        if (!initialized) return
        sort?.filterSelect = HashMap(selection)
        ui.value = UiState(State.Loading)
        request(FIRST_PAGE)
    }

    fun loadMore() {
        if (!initialized) return
        val s = ui.value
        if (s.state != State.Ready || !s.hasMore || loader.busy) return
        request(s.nextPage)
    }

    private fun request(page: Int) {
        val data = sort ?: return
        scope.launch {
            val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                loader.request(page, data) { r -> if (cont.isActive) cont.resume(r) }
            }
            if (!result.stale) applyResult(page, result.absXml)
        }
    }

    private fun applyResult(page: Int, abs: AbsXml?) {
        val videos = abs?.movie?.videoList ?: emptyList()
        val maxPage = abs?.movie?.pagecount ?: 0
        val cur = ui.value
        ui.value = when {
            videos.isEmpty() && page == FIRST_PAGE -> UiState(State.Empty, emptyList(), FIRST_PAGE, maxPage)
            else -> UiState(State.Ready, if (page == 0) videos else cur.videos + videos, page + 1, maxPage)
        }
    }
}
