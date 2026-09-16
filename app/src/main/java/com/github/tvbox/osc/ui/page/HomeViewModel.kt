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

    val pageLoading = MutableStateFlow(true)
    private val sortsLoaded = MutableStateFlow(false)
    private val bootReady = MutableStateFlow(false)
    val pageErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val scope = viewModelScope
    private val sortViewModel = SourceViewModel()
    private val actionViewModel = SourceViewModel()
    private val loaders = HashMap<String, PartitionLoader>()
    private val loadSemaphore = Semaphore(2)
    private var loadGeneration = 0
    private var loadingSourceKey: String? = null
    private var watchdogJob: Job? = null

    var defaultLiveLaunched = false
    var lastBackTime = 0L

    private val sortObserver = Observer<AbsSortXml> { absXml: AbsSortXml? -> onSortResult(absXml) }

    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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
        scope.launch {
            AppBootstrap.state.collect {
                bootReady.value = it is AppBootstrap.Boot.Ready
                if (it is AppBootstrap.Boot.Ready) loadHome()
            }
        }
        scope.launch {
            combine(bootReady, rec, partitions, sortsLoaded) { ready, r, ps, loaded ->
                ready && loaded && r.state != PartitionState.Loading &&
                    ps.none { it.state == PartitionState.Loading }
            }.collect { ready ->
                if (ready && pageLoading.value) {
                    pageLoading.value = false
                    watchdogJob?.cancel()
                }
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
        sortViewModel.sortResult.removeObserver(sortObserver)
        actionViewModel.actionResult.removeObserver(actionObserver)
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
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
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        val home = ApiConfig.get().getHomeSourceBean()
        loadingSourceKey = if (home.key.isNullOrEmpty()) null else home.key
        currentSource.value = home
        pageLoading.value = true
        sortsLoaded.value = false
        rec.value = Rec(PartitionState.Loading, emptyList())
        partitions.value = emptyList()
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
        loadGeneration++
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(20_000)
            onHomeLoadTimeout()
        }
        sortViewModel.getSort(loadingSourceKey)
    }

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
        sortsLoaded.value = true
        newPartitions.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    private fun loadRec(absXml: AbsSortXml?) {
        val videos = absXml?.videoList ?: emptyList()
        rec.value = if (videos.isEmpty()) Rec(PartitionState.Empty, videos) else Rec(PartitionState.Ready, videos)
    }

    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private fun requestPartition(current: Partition, page: Int) {
        val generation = loadGeneration
        val loader = loaders.getOrPut(current.sort.id) { PartitionLoader(current.sort) }
        scope.launch {
            loadSemaphore.withPermit {
                if (generation != loadGeneration || loader.released) return@withPermit
                val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                    loader.request(page) { r -> if (cont.isActive) cont.resume(r) }
                }
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

    fun handleAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    fun refreshPartitions() {
        partitions.value = partitions.value.map {
            Partition(it.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
        }
        partitions.value.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    private inner class PartitionLoader(val sort: MovieSort.SortData) {
        private val svm = SourceViewModel()
        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        @Volatile
        var busy: Boolean = false
            private set

        @Volatile
        var released: Boolean = false
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
            released = true
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = null
            busy = false
            svm.listResult.removeObserver(observer)
        }
    }
}
