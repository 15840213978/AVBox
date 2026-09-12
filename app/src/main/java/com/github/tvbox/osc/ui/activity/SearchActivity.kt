@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.page.ManageActionIcon
import com.github.tvbox.osc.ui.page.jumpToDetail
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.UA
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.github.catvod.crawler.JsLoader
import com.orhanobut.hawk.Hawk
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume

/**
* 搜索页(avbox-mobile-ui-spec §4.6):系统输入法 TextField + 搜索历史 chips + 各源结果分区。
* 继承 BaseActivity 以复用 AutoSize,使旧勾选源对话框渲染正常。
*/
class SearchActivity : BaseActivity() {

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 手机端保留系统栏(§3)
    }

    override fun init() {
        enableEdgeToEdge()
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SearchScreen()
            }
        }
    }
}

class SearchViewModel : ViewModel() {

    enum class ResultState { Pending, Done }

    data class SourceResult(
        val sourceKey: String,
        val sourceName: String,
        val state: ResultState,
        val videos: List<Movie.Video>,
    )

    val results = MutableStateFlow<List<SourceResult>>(emptyList())
    val running = MutableStateFlow(false)
    val searchedTitle = MutableStateFlow("")

    /** 热搜榜:豆瓣当日热播片名 Top10(Hawk 缓存 home_hot/home_hot_day,当日有效) */
    val hotSearch = MutableStateFlow<List<String>>(emptyList())

    private var token = 0
    // 搜索线程数(2026-09-12):设置页滑块可调(16/32/48/64),search() 入口对比 Hawk 变化后重建;
    // 旧协程持有旧实例引用,release 后旧实例即被 GC,无泄漏
    private var semaphorePermits = Hawk.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
    private var semaphore = Semaphore(semaphorePermits)
    private val pendingSources = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val scope = viewModelScope

    companion object {
        /** 单源超时:与 SourceViewModel 内部 future.get(30s) 约定一致 */
        private const val SEARCH_TIMEOUT_MS = 30_000L

        /** 豆瓣热播接口 */
        private const val DOUBAN_HOT_URL =
            "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range="

        /** 热搜榜展示条数 */
        private const val HOT_SEARCH_LIMIT = 10

        /** 勾选搜索源(会话级,与旧 SearchActivity 静态字段一致);null = 全部可搜源 */
        @Volatile
        var checkedSources: HashMap<String, String>? = null
    }

    init {
        // 搜索结果经 EventBus 分发(SourceViewModel 对 searchResult 不走 LiveData)
        org.greenrobot.eventbus.EventBus.getDefault().register(this)
        fetchHotSearch()
    }

    override fun onCleared() {
        org.greenrobot.eventbus.EventBus.getDefault().unregister(this)
    }

    /** 热搜榜:优先当日 Hawk 缓存(与首页共享),过期则请求豆瓣并回写缓存,失败退旧缓存 */
    private fun fetchHotSearch() {
        scope.launch(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(java.util.Date())
            val cached = Hawk.get("home_hot", "")
            if (Hawk.get("home_hot_day", "") == today && cached.isNotEmpty()) {
                hotSearch.value = parseHotTitles(cached)
                return@launch
            }
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            OkGo.get<String>(DOUBAN_HOT_URL + year + "," + year)
                .headers("User-Agent", UA.randomOne())
                .execute(object : AbsCallback<String>() {
                    override fun onSuccess(response: com.lzy.okgo.model.Response<String>) {
                        val body = response.body().orEmpty()
                        if (body.isNotEmpty()) {
                            Hawk.put("home_hot", body)
                            Hawk.put("home_hot_day", today)
                        }
                        hotSearch.value = parseHotTitles(body)
                    }

                    override fun convertResponse(response: okhttp3.Response): String =
                        response.body?.string().orEmpty()

                    override fun onError(response: com.lzy.okgo.model.Response<String>) {
                        super.onError(response)
                        hotSearch.value = parseHotTitles(Hawk.get("home_hot", ""))
                    }
                })
        }
    }

    private fun parseHotTitles(json: String): List<String> = try {
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return emptyList()
        (0 until minOf(arr.length(), HOT_SEARCH_LIMIT))
            .mapNotNull { arr.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotEmpty() } }
    } catch (_: Throwable) {
        emptyList()
    }

    fun search(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        // 设置页改了搜索线程数时重建信号量:旧协程持有旧实例引用,release 后旧实例即被 GC
        val configured = Hawk.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT)
        if (configured != semaphorePermits) {
            semaphorePermits = configured
            semaphore = Semaphore(configured)
        }
        token += 1
        val myToken = token
        val tokenStr = myToken.toString()
        searchedTitle.value = t
        HistoryHelper.setSearchHistory(t)
        // 与旧引擎一致:重搜前停掉在途爬虫
        try {
            JsLoader.stopAll()
        } catch (_: Throwable) {
        }
        // BugReview #23:取消在途搜索请求;否则旧任务继续占用信号量许可直到 30s 超时,
        // 新搜索仅前 6 个源能启动(假卡死)
        try {
            com.lzy.okgo.OkGo.getInstance().cancelTag("search")
        } catch (_: Throwable) {
        }
        // BugReview #23:完成旧 pending 表项,旧协程的 done.await() 立即返回并归还许可
        // (直接 clear 会让旧续体悬到超时)
        for (entry in pendingSources) {
            entry.value.complete(Unit)
        }
        pendingSources.clear()
        val home = ApiConfig.get().getHomeSourceBean()
        val checked = checkedSources
        val sources = ApiConfig.get().getSourceBeanList()
            .filter { it.isSearchable() && (checked == null || checked.containsKey(it.key)) }
            .sortedBy { it.key != home.key }
        results.value = sources.map { SourceResult(it.key, it.name.orEmpty(), ResultState.Pending, emptyList()) }
        if (sources.isEmpty()) {
            running.value = false
            return
        }
        running.value = true
        scope.launch {
            coroutineScope {
                sources.map { bean ->
                    async {
                        semaphore.withPermit {
                            if (myToken != token) return@async
                            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                            pendingSources[bean.key] = done
                            try {
                                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                    // getSearch 的爬虫分支在调用线程阻塞执行,必须在 IO 线程调用;
                                    // 结果统一经 EventBus(TYPE_SEARCH_RESULT,主线程)回调
                                    withContext(Dispatchers.IO) {
                                        searchCaller.getSearch(bean.key, t, tokenStr)
                                    }
                                    done.await()
                                }
                            } finally {
                                pendingSources.remove(bean.key)
                            }
                        }
                    }
                }.awaitAll()
            }
            if (myToken == token) running.value = false
        }
    }

    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    fun onSearchResultEvent(event: com.github.tvbox.osc.event.RefreshEvent) {
        if (event.type != com.github.tvbox.osc.event.RefreshEvent.TYPE_SEARCH_RESULT) return
        val data = event.obj as? AbsXml ?: return
        val myToken = token
        if (data.searchToken != myToken.toString()) return
        val sourceKey = data.sourceKey ?: return
        if (results.value.none { it.sourceKey == sourceKey }) return
        pendingSources.remove(sourceKey)?.complete(Unit)
        // 精确匹配排前(旧高匹配策略的简化)
        val videos = data.movie?.videoList.orEmpty()
            .sortedByDescending { it.name?.trim() == searchedTitle.value }
        updateResult(sourceKey, videos)
    }

    private fun updateResult(sourceKey: String, videos: List<Movie.Video>) {
        results.value = results.value.map {
            if (it.sourceKey == sourceKey) {
                SourceResult(sourceKey, it.sourceName, ResultState.Done, videos)
            } else {
                it
            }
        }
    }

    private val searchCaller = SourceViewModel()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val results by vm.results.collectAsState()
    val running by vm.running.collectAsState()
    val hotSearch by vm.hotSearch.collectAsState()
    var query by remember { mutableStateOf("") }
    // 结果源筛选:null = 全部(下方横向 chips 单选,新搜索时重置)
    var selectedSource by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(Hawk.get(HawkConfig.SEARCH_HISTORY, ArrayList<String>())) }
    val searchedTitle by vm.searchedTitle.collectAsState()

    // 外部带标题进入(历史/兜底跳转)自动搜索;勾选源从持久化恢复(与旧行为一致)
    LaunchedEffect(Unit) {
        if (SearchViewModel.checkedSources == null) {
            SearchViewModel.checkedSources = SearchHelper.getSourcesForSearch()
        }
        val initTitle = activity?.intent?.getStringExtra("title")
        if (!initTitle.isNullOrEmpty()) {
            query = initTitle
            vm.search(initTitle)
        }
    }

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar
    val resultListState = rememberLazyListState()

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        query = t
        hideIme(activity)
        selectedSource = null
        vm.search(t)
        history = Hawk.get(HawkConfig.SEARCH_HISTORY, ArrayList())
    }

    AppTopBarScaffold(
        // 顶栏不折叠(2026-09-12):搜索框胶囊常驻,不随结果列表滚动折叠
        collapseEnabled = false,
        titleContent = {
            // 40dp 胶囊输入框(§4.6;2026-09-10 改 BasicTextField 自绘,修复固定高度下文字被裁)
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { submit(query) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        navigationIcon = {
            // 返回按钮(40dp 圆形容器 surfaceBright,图标来自 .tubiao/左箭头.svg,2026-09-11)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceBright)
                    .clickable { activity?.finish() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { topPad, _ ->
        if (results.isEmpty() && !running) {
            // 未搜索:搜索历史 + 热搜榜,各自圆角卡片容器(2026-09-10);内容延伸至状态栏下
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 顶部占位 = 顶栏高度 - 20dp(与下方历史卡自身 28dp 上边距合计 = 顶栏 + 8dp,
                // 首卡与顶栏间距与设置页一致;2026-09-12 用户定稿,原 -8+28=+20)
                Spacer(Modifier.height(topPad - 20.dp))
                // 搜索历史卡片(28dp 圆角;距顶部搜索栏 28dp,2026-09-11 用户定稿)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.cardContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionIconBadge(R.drawable.ic_search_history, "搜索历史")
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "搜索历史",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // 右上角删除控件(与管理页同款 40dp 圆形):点击清空全部搜索历史
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = "清空搜索历史",
                            onClick = {
                                HistoryHelper.clearSearchHistory()
                                history = ArrayList()
                            },
                        )
                    }
                    if (history.isEmpty()) {
                        // 空态文案居中(2026-09-11 用户要求)
                        Text(
                            text = "暂无搜索历史",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        // FlowRow 自动换行纵向排列(单行 LazyRow 超出裁切且显拥挤,2026-09-11 用户定稿);
                        // 标题行与 chips 间距 8dp 呼吸感
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            history.forEach { word ->
                                HistoryChip(
                                    word = word,
                                    onClick = { submit(word) },
                                    onLongClick = {
                                        // 长按删除单条搜索历史
                                        HistoryHelper.removeSearchHistory(word)
                                        history = Hawk.get(HawkConfig.SEARCH_HISTORY, ArrayList())
                                    },
                                )
                            }
                        }
                    }
                }
                // 热搜榜卡片(28dp 圆角):双列排位,前三名高亮,点击直接搜索
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.cardContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionIconBadge(R.drawable.ic_hot_search, "热搜榜")
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "热搜榜",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (hotSearch.isEmpty()) {
                        // 空态文案居中(2026-09-11 用户要求)
                        Text(
                            text = "暂无热搜数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    } else {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            hotSearch.chunked(2).forEachIndexed { rowIdx, pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    pair.forEachIndexed { colIdx, title ->
                                        val rank = rowIdx * 2 + colIdx + 1
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { submit(title) }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = rank.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (rank <= 3) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.width(20.dp),
                                            )
                                            Text(
                                                text = title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(start = 10.dp),
                                            )
                                        }
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val done = results.filter { it.videos.isNotEmpty() }
            val shown = if (selectedSource == null) done else done.filter { it.sourceKey == selectedSource }
            if (done.isEmpty() && !running) {
                LoadStateBox(
                    state = LoadState.Empty,
                    emptyText = "「${searchedTitle}」暂无搜索结果",
                    errorText = "",
                    retryText = "",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad),
                )
            } else {
                LazyColumn(
                    state = resultListState,
                    modifier = Modifier.fillMaxSize(),
                    // 顶部留白 = 顶栏高度 - 4dp(进度条/chips 自带 12dp 内边距,合计 = 顶栏 + 8dp,
                    // 首项与顶栏间距与其他页面一致;2026-09-12 用户定稿,原 -8=+4)
                    contentPadding = PaddingValues(top = topPad - 4.dp, bottom = 12.dp),
                    // 组间距不再用统一 spacedBy(24dp):其会让 chips 与首个分组间空出一大块
                    // (chips 底 4dp + 24dp = 28dp);改为 item 自带 top padding,首组 12dp/其余 24dp(2026-09-12 用户定稿)
                ) {
                    // 前导区:波浪线进度条 + 结果源筛选 chips 合并为一个 item ——
                    // 二者原有的紧邻关系(12dp 内边距)在合并后保持,不被列表 spacedBy(24dp) 额外拉开
                    if (running || done.size > 1) {
                        item(key = "search_leading") {
                            Column {
                                if (running) {
                                    // 波浪线不定长进度条(2026-09-11):水平与搜索框对齐(左右 16dp),
                                    // 上下各留 12dp,避免贴住顶部搜索控件与下方源筛选 chips / 结果卡片
                                    LinearWavyProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                                // 结果源筛选(2026-09-11 用户要求):横向滑动 chips(「全部」+ 有结果的源),单选过滤下方分区;
                                // 仅 1 个源时不显示(无可筛选余地);未在跑进度条时顶部补 12dp 与搜索框留白
                                if (done.size > 1) {
                                    LazyRow(
                                        modifier = Modifier.padding(top = if (running) 0.dp else 12.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        item(key = "filter_all") {
                                            FilterChip(
                                                selected = selectedSource == null,
                                                onClick = { selectedSource = null },
                                                label = { Text("全部") },
                                                shape = RoundedCornerShape(20.dp),
                                            )
                                        }
                                        items(done, key = { "filter_${it.sourceKey}" }) { result ->
                                            FilterChip(
                                                selected = selectedSource == result.sourceKey,
                                                onClick = {
                                                    // 再点已选中的源 = 取消筛选,回到「全部」
                                                    selectedSource = if (selectedSource == result.sourceKey) null else result.sourceKey
                                                },
                                                label = { Text(result.sourceName) },
                                                shape = RoundedCornerShape(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 每分区一个 item(标题+横滑卡片行),按 sourceKey 稳定复用,结果陆续到达时增量插入;
                    // shown = 源筛选后的可见分区(未筛选时等于 done);
                    // 组间距 = item 自带 top padding(首组 12dp 紧贴 chips,其余 24dp;2026-09-12 用户定稿)
                    itemsIndexed(shown, key = { _, r -> r.sourceKey }) { index, result ->
                        Column(modifier = Modifier.padding(top = if (index == 0) 12.dp else 24.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = result.sourceName,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                // 2026-09-09:源分区「全部 >」入口,进二级页网格展示该源全部搜索结果
                                // 2026-09-11:补加半透明 surface 容器背景(与首页「全部 >」统一样式)
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                        .clickable {
                                            PartitionListActivity.startForSearch(context, result.videos, result.sourceName)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "全部",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsIndexed(result.videos) { _, video ->
                                    VodCard(
                                        video = video,
                                        onClick = {
                                            context.jumpToDetail(video.id, video.sourceKey, video.name, video.pic)
                                        },
                                        onLongClick = {},
                                        modifier = Modifier.width(110.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}

/**
 * 搜索页卡片左上角圆形角标图标:40dp 圆形容器 primaryContainer 底(动态取色),
 * 图标 22dp onPrimaryContainer,与右侧删除控件 40dp 尺寸对齐(2026-09-11)。
 */
@Composable
private fun SectionIconBadge(iconRes: Int, contentDescription: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 搜索历史 chip(视觉沿用原 FilterChip 未选中样式:透明底 + outline 描边):
 * 点击发起搜索,长按删除该条记录(2026-09-11)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryChip(
    word: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = word,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * 搜索输入框(40dp 胶囊):BasicTextField 自绘替代 OutlinedTextField——
 * 固定 40dp 高度下 OutlinedTextField 默认内边距会压缩/裁切文字(2026-09-10 BugFix)
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.cardContainer)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.height(40.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索片名、演员",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "清空",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onQueryChange("") }
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}

private fun hideIme(activity: android.app.Activity?) {
    if (activity == null) return
    val view = activity.window?.currentFocus ?: return
    val imm = activity.getSystemService(InputMethodManager::class.java)
    imm?.hideSoftInputFromWindow(view.windowToken, 0)
}
