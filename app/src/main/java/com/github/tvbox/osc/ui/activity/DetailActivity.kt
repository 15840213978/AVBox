@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.ui.playerDim
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.page.jumpToDetail
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.player.PlayerTipBridge
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.SearchHelper
import com.github.tvbox.osc.util.SubtitleHelper
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

/** 退出全屏后系统栏过渡(旋转 + 系统栏滑入)耗时,过渡结束后补一次状态栏图标外观断言 */
private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

/**
 * 详情/播放页(avbox-mobile-ui-spec §4.4,Compose 重写):
 * 顶部 16:9 内嵌播放器(AndroidView 包 PlayContainer,dkplayer 内核)
 * → 标题/元信息/收藏 → 简介可展开 → 清晰度/线路 chips → 选集行(全部→bottom sheet)
 * → 换源行(同名片源 chips)→ 相关推荐(聚合搜索非同名结果)。
 * 全屏 = 横屏沉浸(隐藏系统栏),返回退回竖屏预览。
 */
class DetailActivity : BaseActivity() {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? = null
        private set
    private var fullScreen = false

    /**
     * 本地字幕选择(SAF 系统文件选择器,2026-09-12):零权限、Android 13+/16 全兼容。
     * 替换 obsez ChooserDialog——其自检 WRITE_EXTERNAL_STORAGE,而 Android 13+ 该权限
     * 被系统静默拒绝,导致"本地字幕"永远弹 "You denied..." 且选择器无法打开。
     * mime 用通配全部类型:字幕扩展名(srt/ass 等)在 SAF 中常被标为 octet-stream,
     * 严格 mime 过滤会把字幕文件筛掉。
     */
    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    fun launchLocalSubtitlePicker() {
        try {
            localSubtitlePicker.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 竖屏保留系统栏(§3),全屏沉浸时由 applyFullscreen 走基类沉浸逻辑
        if (fullScreen) super.hideSysBar()
    }

    /**
     * 竖屏状态栏区域为纯黑,图标必须白色(§4.4 补丁⑤;进入全屏后系统栏隐藏,此值不影响)。
     * 系统 ROM(装机实测 vivo OriginOS,米系 HyperOS 同类)会在沉浸退出/横竖屏过渡与回前台时按主题重设图标外观(浅色主题 → 深色图标),
     * 深色图标在纯黑底上等于"消失"(主页同类问题见 MainActivity.onResume 的重新断言),故关键时机反复断言。
     */
    private fun applyStatusBarAppearance() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
    }

    override fun init() {
        enableEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (fullScreen) {
                    // YouTube 式返回：菜单唤出时第一次手势先收菜单(控制器消费)，
                    // 菜单收起后再滑才退出全屏回竖屏
                    if (container != null && container.onBackPressed()) return
                    vm.setFullScreen(false)
                } else {
                    container?.setPlayTitle(false)
                    container?.setExitingPreview(true)
                    finish()
                }
            }
        })
        vm.initFromIntent(intent)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.compose_view).setContent {
            // 纯黑状态栏页面:图标恒白由本页 applyStatusBarAppearance 断言,主题不接管
            AVBoxTheme(manageStatusBarIcons = false) {
                DetailScreen(activity = this, vm = vm)
            }
        }
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                // 首次进入详情页即为竖屏预览态(旧版 setPreviewMode 仅在全屏切换时调用,导致首次呼出仍带菜单行)
                it.setPreviewMode(true)
            }
        }
        return playContainer!!
    }

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    /** 把当前选中的集投给播放容器(对应旧 jumpToPlay 的下半段) */
    fun playCurrent() {
        val container = playContainer ?: return
        val bundle = vm.preparePlayBundle()
        if (bundle == null) {
            // 组装不出播放数据:别把"正在切换片源"提示留在播放器上
            container.clearSourceSwitchTip()
            return
        }
        container.setData(bundle)
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        fullScreen = full
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (full) {
            hideSysBar()
        } else {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            // 退回竖屏后状态栏区域仍是纯黑,保持白色图标;同步断言可能被系统的过渡结束态覆盖,
            // 故等旋转/系统栏过渡结束后再兜底断言一次
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        val container = playContainer
        if (container != null) {
            container.setAutoSwitchLineEnabled(!full)
        }
        // 预览态覆盖层与字幕字号不再跟 full 当帧切:统一由"实际形态"驱动(syncFullBoxSideEffects),
        // 旋转过渡期保持原样、落地(onConfigurationChanged)后再同步,避免半新半旧
        syncFullBoxSideEffects()
    }

    /** 旋转落地回调(2026-09-13 方案 A 的"落地"信号):清过渡态 + 把随形态联动的东西同步过来 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.rotating.value = false
        syncFullBoxSideEffects()
    }

    /**
     * 当前布局形态是否为「全屏铺满」——与 [DetailScreen] 的 `fullBox` 必须同一判定。
     * 过渡期(rotating)跟随**当前方向**:横屏=全屏样、竖屏=预览样;旋转落地后才切到目标态 [fullScreen]。
     * 这样横屏窗口里永远不会去算竖屏的预览盒(反之亦然)。
     * 兜底:万一系统没下发 onConfigurationChanged,形态退化为"当前方向的自然形态",不会卡死。
     */
    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (vm.rotating.value) !landNow else fullScreen
    }

    /** 随形态联动的非布局项:预览态覆盖层(底栏菜单行/预览暂停钮/边距)与预览字幕 0.6 倍 */
    private fun syncFullBoxSideEffects() {
        val preview = !isFullBox()
        playContainer?.setPreviewMode(preview)
        applySubtitleTextSize(preview)
    }

    /** 预览态字幕按 0.6 倍缩放(旧 toggleSubtitleTextSize;2026-09-13 改为按实际形态传参,由 syncFullBoxSideEffects 调用) */
    private fun applySubtitleTextSize(preview: Boolean) {
        var size = SubtitleHelper.getTextSize(this)
        if (preview) size = (size * 0.6).toInt()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, size))
    }

    /** 旧 DetailActivity.startDetailFallbackAfterLinesExhausted:线路耗尽后由播放容器回调 */
    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
        // 系统回前台会按主题重设状态栏图标外观,在首帧前重新断言(§4.4 补丁⑤)
        applyStatusBarAppearance()
        playContainer?.hostResume()
    }

    override fun onPause() {
        playContainer?.hostPause()
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayContainer()
        vm.destroyEngine()
        super.onDestroy()
    }
}

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

    // ---- 换源引擎(旧 detail fallback 移植):聚合搜同名进候选池,自动兜底手动 chips 两用 ----
    private val fallbackCandidates = ArrayList<Movie.Video>()
    private val candidateKeys = HashSet<String>()
    private val triedKeys = HashSet<String>()
    private val usedSourceKeys = HashSet<String>()
    // BugReview #24:聚合搜索信号态从 companion 静态收敛到 ViewModel 实例,
    // 防多轮搜索/多实例互相污染配额与 pending 表项
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
     * 换源前快照:手动换源点击即释放播放(新源详情返回前旧源不再出声),
     * 新源确认可用后丢弃;失败则回退原源,从停播时落盘的进度继续。
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
        // 目标方向与实际方向不一致 → 进入旋转过渡态:布局形态等落地再切(方案 A,见 isFullBox)
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

    /** 读取并清除手动选线标记(供 preparePlayBundle 写入播放容器) */
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
        // 换源引擎激活但未在加载候选时,忽略详情结果(旧 DetailActivity 对账逻辑)
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

            // 恢复历史进度(旧逻辑)
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
        searchToken += 1
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
                            // BugReview #24:覆盖表项时 complete 旧 deferred,旧任务立即归还许可而非挂满 30s
                            pendingSearchDone.put(bean.key, done)?.complete(Unit)
                            try {
                                withTimeoutOrNull(SOURCE_SEARCH_TIMEOUT_MS) {
                                    // getSearch 爬虫分支阻塞调用线程,必须在 IO 线程调用(Step 3 结论)
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
                relatedVideos.value = relatedVideos.value + related
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
     * 手动点选换源 chips:点击即停当前播放(旧源不再出声,进度由容器落盘),
     * 并记下回滚快照 —— 新源不可用时回退原源、从停播处继续。
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
     * 换源点击即停:立即释放播放实例并记快照(未起播时无需停播,也不做回滚)。
     * 已有快照时不覆盖:换源窗口内连点/上一源"能解析但无可播集"时,回滚目标始终是最后一次可播状态。
     */
    private fun stopPlaybackForSwitch() {
        val info = vodInfo ?: return
        if (switchSnapshot == null) {
            switchSnapshot = SwitchSnapshot(info, vodId, sourceKey, firstsourceKey, vodName, vodPicture)
        }
        playContainerRef?.stopForSourceSwitch("正在切换片源")
    }

    /**
     * 手动换源失败的回滚:恢复原源与原播放信息并重播(进度已随停播落盘,同键直接续播)。
     * 无快照(非手动换源)或原片源已不可播时返回 false,由调用方沿用原空态/关页处理。
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

    /** 自动兜底加载候选详情(不重置引擎、不重启聚合搜索) */
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

    /** 组装交给播放容器的数据(旧 jumpToPlay 的 App.setVodInfo + bundle) */
    fun preparePlayBundle(): Bundle? {
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
        val bundle = Bundle()
        bundle.putString("sourceKey", sourceKey)
        bundle.putBoolean(PlayContainer.EXTRA_USER_PICKED_LINE, consumeManualLineSwitch())
        return bundle
    }

    // ============ 集数匹配工具(旧 findSameEpisodeIndex 系列) ============

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
        private const val DETAIL_FALLBACK_DETAIL_TIMEOUT_MS = 6000L
        private const val SOURCE_SEARCH_TIMEOUT_MS = 30_000L
        private const val SOURCE_SEARCH_CONCURRENCY = 6
    }
}

// ================= UI =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val revision by vm.revision.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val toast by vm.toastEvent.collectAsState()
    val finish by vm.finishEvent.collectAsState()

    // 播放器区形态(2026-09-13 方案 A,与 DetailActivity.isFullBox() 同一判定):
    // 旋转过渡期跟随**实际方向**(横屏=全屏样、竖屏=预览样),旋转落地后才切到目标态 full ——
    // 否则会在横屏窗口里算出竖屏的 16:9 盒(高度超屏 → 视频缩小/跳动),或在竖屏窗口里直接铺满(黑屏几百 ms)
    val configuration = LocalConfiguration.current
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = if (rotating) isLandscapeNow else full
    // 预览态播放区高度(2026-09-13 方案 B,对齐 fongmi changeHeight):短边 × 16:9,并钳制在 [150dp, 长边/2],
    // 与当前窗口方向无关 —— 即使形态被切,几何也永远是合法小矩形(不再"宽推高 → 高度超过屏幕")
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewBoxHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))

    // 容器随首次组合创建;Activity 重建(configChanges 之外)时 remember 重置,自动重建并补播
    val container = remember { activity.ensurePlayContainer().also { vm.playContainerRef = it } }

    LaunchedEffect(container, playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(activity, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(finish) {
        if (finish) {
            vm.consumeFinish()
            activity.finish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        // 顶部 16:9 播放器:全屏形态占满整屏;预览形态高度由 previewBoxHeight 显式给出(方案 B)
        Box(
            modifier = if (fullBox) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewBoxHeight)
                    .background(Color.Black)
            },
        ) {
            AndroidView(
                factory = { container },
                modifier = Modifier.fillMaxSize(),
            )
            if (pageState is DetailViewModel.PageState.Loading && !fullBox) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    // 玩家区为纯黑底:沿用白色系配色(容器用低透明白),默认 secondaryContainer 在纯黑上过亮
                    ContainedLoadingIndicator(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        indicatorColor = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            // 播放器提示覆盖层(2026-09-11 替代旧 xml 提示):取流 loading / 播放错误;
            // 纯显示层无 pointer 处理,触摸穿透到控制器;声明在全屏入口之前,不遮挡其点击
            PlayerTipOverlay()
            // 竖屏预览态:不再盖透明点击层(此前 clickable 层会拦掉下方控制器全部触摸,
            // 导致中央三键/底栏点不动),单击显隐改由 ComposeVideoController 预览态手势处理
            if (!fullBox) {
                // 右下角全屏入口(未全屏时常驻,贴右下角,预览态进度行右侧已预留空间不重叠)。
                // 垂直位置与进度条水平线对齐:底栏进度行中心 = 列 bottomPadding(16dp) + 进度条
                // Canvas 高(playerDim(vs_30))/2;图标盒 40dp 取半 20dp,故 bottom = 16 + vs_30/2 - 20
                Icon(
                    painter = painterResource(R.drawable.ic_player_expand),
                    contentDescription = "全屏播放",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp + playerDim(R.dimen.vs_30) / 2 - 20.dp)
                        .size(40.dp)
                        .clickable { vm.setFullScreen(true) }
                        .padding(9.dp),
                )
            }
        }

        if (!fullBox) {
            when (val state = pageState) {
                is DetailViewModel.PageState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }

                is DetailViewModel.PageState.Empty -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = state.msg ?: "暂无片源,可尝试换源或搜索",
                            errorText = "",
                            retryText = "",
                            modifier = Modifier.weight(1f),
                        )
                        SourceSection(vm, currentSourceName = null, revision = revision)
                    }
                }

                is DetailViewModel.PageState.Ready -> {
                    DetailContent(activity, vm, revision)
                }
            }
        }
    }

    EpisodeSheet(vm, revision)
}

@Composable
private fun DetailContent(activity: DetailActivity, vm: DetailViewModel, revision: Int) {
    val info = vm.vodInfo ?: return
    // revision 仅用于触发重组(vodInfo 为可变 bean)
    @Suppress("UNUSED_EXPRESSION") revision

    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val qualityOptions by vm.qualityOptions.collectAsState()
    val qualitySelected by vm.qualitySelected.collectAsState()
    val collected by vm.collected.collectAsState()
    var descExpanded by rememberSaveable { mutableStateOf(false) }

    val currentSource = ApiConfig.get().getSource(vm.firstsourceKey)
    val displaySourceName = currentSource?.name ?: vm.firstsourceKey

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ---- 标题 / 来源 / 简介：surfaceBright 圆角卡片(距屏 16dp) ----
        val desc = removeHtmlTag(info.des)
        item(key = "header") {
            Column(
                modifier = Modifier
                    .padding(start = 6.dp, end = 6.dp, top = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 标题 + 投屏 + 收藏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.name ?: "TVBox",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 投屏(2026-09-13 用户要求):图标取 .tubiao/投屏.svg;点击复用播放器「投屏」面板
                    // (PlayContainer.showCast → CastSheet,Dialog 弹窗 + DLNA/TVBox 扫描投送全同一条链路)
                    IconButton(onClick = { activity.playContainer?.showCast() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_detail_cast),
                            contentDescription = "投屏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(onClick = { vm.toggleCollect() }) {
                        // 未收藏＝描边星，已收藏＝实心星＋主题色（描边 tint 对空心图标不直观，用户反馈）
                        AnimatedContent(
                            targetState = collected,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith
                                        (scaleOut(targetScale = 0.6f) + fadeOut())
                            },
                            label = "collectIcon",
                        ) { isCollected ->
                            Icon(
                                painter = painterResource(
                                    if (isCollected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect
                                ),
                                contentDescription = if (isCollected) "取消收藏" else "加入收藏",
                                tint = if (isCollected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                // 来源 pill(surfaceContainer) + 其余元信息
                val metaParts = listOfNotNull(
                    if (info.year > 0) info.year.toString() else null,
                    info.area?.takeIf { it.isNotBlank() },
                    info.type?.takeIf { it.isNotBlank() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "来源：$displaySourceName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                    }
                }
                // 简介：surfaceContainer 圆角块 + 右下角 展开/收起(向下箭头)
                if (desc.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { descExpanded = !descExpanded },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { descExpanded = !descExpanded },
                        ) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (descExpanded) "收起" else "展开",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (descExpanded) 180f else 0f),
                            )
                        }
                    }
                }
            }
        }

        // ---- 清晰度(仅多清晰度时显示) ----
        if (qualityOptions.size > 1) {
            item(key = "quality") {
                ChipRow(title = "清晰度") {
                    itemsIndexed(qualityOptions) { index, option ->
                        FilterChip(
                            selected = index == qualitySelected,
                            onClick = { vm.onQualityClick(index) },
                            label = { Text(option) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
        }

        // ---- 线路 ----
        if (flags.size > 1) {
            item(key = "flags") {
                ChipRow(title = "线路") {
                    // key 拼入索引:线路名可能为空或重复,纯 name 拼接会撞 key 崩溃
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            label = { Text(flag.name ?: "") },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
        }

        // ---- 选集横向行 ----
        if (episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeRow(vm, info, episodes, playIndex, currentFlag)
            }
        }

        // ---- 换源行 ----
        item(key = "sources") {
            SourceSection(vm, currentSourceName = displaySourceName, revision = revision)
        }

        // ---- 相关推荐 ----
        item(key = "related") {
            RelatedSection(activity, vm)
        }
    }
}

@Composable
private fun EpisodeRow(
    vm: DetailViewModel,
    info: VodInfo,
    episodes: List<VodInfo.VodSeries>,
    playIndex: Int,
    currentFlag: String?,
) {
    // surfaceBright 圆角卡片(圆角 16dp,距屏 6dp)
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选集",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            PillAction(
                iconRes = R.drawable.ic_episode_reverse,
                text = if (info.reverseSort) "正序" else "倒序",
                onClick = { vm.toggleReverse() },
            )
            Spacer(Modifier.width(8.dp))
            PillAction(
                iconRes = R.drawable.ic_episode_grid_all,
                text = "全部",
                onClick = { vm.showEpisodeSheet() },
            )
        }
        val listState = rememberLazyListState()
        // 倒序/正序切换后回到新顺序顶部(主流 app 行为:倒序让最新集立即可见,当前集仅保持高亮);
        // 其余场景(进页/切线路/切集)仍定位到当前集
        var prevReverseSort by remember { mutableStateOf(info.reverseSort) }
        LaunchedEffect(playIndex, currentFlag, episodes.size, info.reverseSort) {
            if (episodes.isEmpty()) return@LaunchedEffect
            val reverseChanged = info.reverseSort != prevReverseSort
            prevReverseSort = info.reverseSort
            if (reverseChanged) {
                listState.scrollToItem(0)
            } else if (playIndex >= 0) {
                listState.scrollToItem(minOf(playIndex, episodes.size - 1))
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(episodes) { index, ep ->
                FilterChip(
                    selected = index == playIndex,
                    onClick = { vm.onEpisodeClick(index) },
                    label = {
                        Text(
                            text = ep.name ?: (index + 1).toString(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

/** surfaceContainer 药丸按钮：左 icon + 右文字（选集卡片 倒序/全部） */
@Composable
private fun PillAction(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SourceSection(vm: DetailViewModel, currentSourceName: String?, revision: Int) {
    // revision 仅用于触发重组
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsState()
    val sourcesSearching by vm.sourcesSearching.collectAsState()
    if (!sourcesSearching && sourceChips.isEmpty()) return
    val listState = rememberLazyListState()
    // 换源后把行首的当前源 chip 滚回视野(行滚动位置会跨数据更新保留)
    LaunchedEffect(currentSourceName) {
        if (currentSourceName != null) listState.scrollToItem(0)
    }
    // surfaceBright 圆角卡片(圆角 16dp,距屏 6dp)
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "换源",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (sourcesSearching) {
                Text(
                    text = "寻找片源中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (currentSourceName != null) {
                item(key = "current") {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(currentSourceName) },
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
            itemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                FilterChip(
                    selected = false,
                    onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                    label = { Text(chip.name) },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RelatedSection(activity: DetailActivity, vm: DetailViewModel) {
    val relatedVideos by vm.relatedVideos.collectAsState()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = "相关推荐",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.jumpToDetail(video.id, video.sourceKey, video.name, video.pic) },
                    onLongClick = {},
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

/**
 * chips 分区行:surfaceBright 圆角卡片(圆角 16dp,距屏 6dp,同选集/换源卡)+ 标题 + LazyRow(content 为 LazyListScope DSL)。
 * 卡片宽度必须撑满:title 是 wrapContent 的 Text、LazyRow 在 chips 放得下时也是 wrapContent,
 * 两者都不给宽度时卡片会缩成标题/chips 的宽度(2026-09-11 装机反馈:只有两条线路时「线路」卡比「选集」卡窄一截);
 * 选集/换源卡是靠表头 Row 的 fillMaxWidth 撑开的,这里显式给 Column + LazyRow 各自 fillMaxWidth,
 * 既保证卡片与选集卡等宽,也让横向滑动的手势区覆盖整条卡片(不是只有 chips 那几个字的范围)。
 * 线路多时 LazyRow 依旧按视口宽度滚动(超出部分横向滑动查看),fillMaxWidth 不影响该行为。
 */
@Composable
private fun ChipRow(title: String, content: LazyListScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]"), "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(Regex("\\s"), "")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EpisodeSheet(vm: DetailViewModel, revision: Int) {
    // revision 仅用于触发重组
    @Suppress("UNUSED_EXPRESSION") revision
    val show by vm.episodeSheet.collectAsState()
    if (!show) return
    val info = vm.vodInfo ?: return
    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex

    val groupCount = when {
        episodes.size > 400 -> 120
        episodes.size > 100 -> 60
        else -> 20
    }
    val groups = if (episodes.size > groupCount) {
        val result = ArrayList<String>()
        var i = 0
        while (i < episodes.size) {
            val end = minOf(i + groupCount, episodes.size)
            result.add("${i + 1} - $end")
            i += groupCount
        }
        result
    } else {
        emptyList()
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var selectedGroup by rememberSaveable { mutableStateOf(0) }

    // 打开时定位到当前集所在分组;之后点分组跳转
    LaunchedEffect(show, currentFlag, playIndex) {
        if (show && playIndex >= 0) {
            val target = (playIndex / groupCount) * groupCount
            selectedGroup = playIndex / groupCount
            if (target in episodes.indices) gridState.scrollToItem(target)
        }
    }
    LaunchedEffect(selectedGroup) {
        if (selectedGroup > 0) {
            val target = selectedGroup * groupCount
            if (target in episodes.indices) gridState.scrollToItem(target)
        }
    }

    // 自适应多列网格(2026-09-08 二次定案,替代单列方案):纯数字/短集名一行 4~6 个,
    // 长集名单元格内跑马灯不截断丢失;仍 lazy 且保留 scrollToItem 定位当前集

    AVBoxBottomSheet(
        onDismissRequest = { vm.dismissEpisodeSheet() },
        title = if (info.name.isNullOrEmpty()) "选集" else "${info.name} 选集",
        // 内容自带横向 LazyRow 与 LazyVerticalGrid(height 自适应 + 560dp 上限),滚动交给它们
        isScrollable = false,
    ) {
        // 集卡点击改走「带动画关闭」(2026-09-13):先切集播放,面板滑出后再移除;
        // 此处读取发生在 SheetOverlay 的 provider 作用域内
        val dismissAnimated = LocalSheetDismiss.current
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            if (flags.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // key 拼入索引:线路名可能为空或重复,纯 name 拼接会撞 key 崩溃
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            label = { Text(flag.name ?: "") },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(groups) { index, label ->
                        FilterChip(
                            selected = index == selectedGroup,
                            onClick = { selectedGroup = index },
                            label = { Text(label) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // 列数随集名长度自适应(2026-09-10 参考设计定稿):
            // 短集名(纯数字/短码)一行 4 列,中等长度 2 列,长文件名单列全宽少截断
            val maxNameLength = episodes.maxOfOrNull { it.name?.length ?: 0 } ?: 0
            val gridColumnCount = when {
                maxNameLength <= 4 -> 4
                maxNameLength <= 12 -> 2
                else -> 1
            }
            // 高度随集数收缩、上限 560dp 超出滚动(2026-09-10:修复短列表也撑满全屏的问题)
            val rowCount = if (episodes.isEmpty()) 0 else (episodes.size + gridColumnCount - 1) / gridColumnCount
            val gridContentHeight = (rowCount * 40).dp + (((rowCount - 1).coerceAtLeast(0)) * 8).dp
            val gridHeight = minOf(560.dp, gridContentHeight)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = gridState,
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumnCount),
                modifier = Modifier.fillMaxWidth().height(gridHeight),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(episodes) { index, ep ->
                    FilterChip(
                        selected = index == playIndex,
                        onClick = {
                            vm.onEpisodeClick(index)
                            dismissAnimated()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        label = {
                            Text(
                                text = ep.name ?: (index + 1).toString(),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                // 2026-09-11:4 列下每格约 76dp,两位集数(第10~26集)实测需 41~42dp/14sp,
                                // 仅差约 3dp 就溢出 —— 原先的 basicMarquee 正是捕捉到这 3dp 而滚动(停帧成 ")集 第")。
                                // 折中方案(用户定稿):字号 14sp→13sp(需 39dp)+ 左右内边距 8dp→6dp(多出 4dp),
                                // 余量约 9dp;同时去掉跑马灯改用省略号,保证两位集数完整显示、集数不再横向滚动。
                                fontSize = 13.sp,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        // 收窄 chip 水平内边距(默认 FilterChipDefaults.ContentPadding = 8dp),换取文字可用宽度
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 播放器提示覆盖层(2026-09-11 替代 view_play_container.xml 旧提示,状态读 [PlayerTipBridge]):
 * loading = 白色系 ContainedLoadingIndicator(与竖屏玩家区 Loading 同配色),err = 错误图标 + 文案。
 * 纯黑底铺满播放器区域;无 pointer 处理 → 触摸穿透,不拦控制器与全屏入口。
 */
@Composable
private fun PlayerTipOverlay() {
    val tip = PlayerTipBridge.state
    if (!tip.loading && !tip.err) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (tip.loading) {
                // 纯黑底白色系,同竖屏玩家区 Loading(默认 secondaryContainer 在黑底上过亮)
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_error),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (tip.msg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = tip.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}
