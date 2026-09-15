@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.Epginfo
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.bean.LivePlayerManager
import com.github.tvbox.osc.bean.LiveSettingGroup
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.controller.ComposeLiveController
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.EpgUtil
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.live.TxtSubscribe
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.github.tvbox.osc.util.KV
import org.json.JSONException
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Date
import java.util.Hashtable
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

/**
 * 直播页(avbox-mobile-ui-spec §4.5,Step 5 Compose 重写):
 * 竖屏 16:9 播放器(MyVideoView + ComposeLiveController,AndroidView 包壳)
 * → 频道信息区(频道号/名称/直播或回看/线路/当前与下个节目)
 * → 频道分组折叠列表(点选换台,密码锁分组走旧 LivePasswordDialog)。
 * EPG 节目单与直播设置均为 bottom sheet;数字选台与全部 DPAD/MENU/INFO 逻辑随 TV 代码删除。
 * 全屏 = 点播放器进横屏沉浸(同详情页);左右快滑切上一/下一频道(§4.5)。
 * EPG 加载/解析(JSON+XML)、时移回看 URL 构建、自动换源状态机等业务逻辑自旧 Java 版 1:1 移植。
 */
internal class LiveListRow(
    val group: LiveChannelGroup?,
    val channel: LiveChannelItem?,
    val channelPos: Int,
    val key: String,
)

class LivePlayActivity : BaseActivity() {

    companion object {
        private const val TAG = "LivePlayActivity"
        /** 退出全屏后系统栏过渡(旋转 + 系统栏滑入)耗时,过渡结束后补一次状态栏图标外观断言(对齐详情页 §4.4 补丁⑤) */
        private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L
        private const val EPG_LOAD_DELAY = 1200L
        private const val RESOLUTION_INFO_MAX_RETRY = 10
        private const val RESOLUTION_INFO_RETRY_DELAY = 300L
        private const val RESOLUTION_INFO_HIDE_DELAY = 3000L
        private const val OVERLAY_HIDE_DELAY = 6000L // 旧 postTimeout
        private const val CONNECT_TIMEOUT_SWITCH_DELAY = 3500L
        private const val DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}"
        private val FORMAT_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val FORMAT_DATE1 = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    internal enum class PageState { LOADING, EMPTY, READY }

    // ============================================================
    // Compose 状态
    // ============================================================

    internal var pageState by mutableStateOf(PageState.LOADING)
    internal var playState by mutableStateOf(VideoView.STATE_IDLE)
    internal var snapshotVisible by mutableStateOf(false)
    internal var snapshotBitmap by mutableStateOf<Bitmap?>(null)
    private var fullScreen by mutableStateOf(false)
    /** 旋转过渡态:已下发方向切换、等系统旋转落地,布局形态延后切换(见 isFullBox) */
    private var rotating by mutableStateOf(false)
    internal var overlayVisible by mutableStateOf(false)
    internal var isBackState by mutableStateOf(false) // 旧 isBack(回看中)
    internal var epgSheetVisible by mutableStateOf(false)
    internal var settingsSheetVisible by mutableStateOf(false)
    // 频道分组密码弹窗目标:(groupIndex, liveChannelIndex),null=隐藏
    internal var passwordDialogTarget by mutableStateOf<Pair<Int, Int>?>(null)
    internal var settingsVersion by mutableIntStateOf(0)
    internal var channelVersion by mutableIntStateOf(0)
    internal var epgVersion by mutableIntStateOf(0)
    internal var scrollTick by mutableIntStateOf(0)
    internal var resolutionText by mutableStateOf("")
    internal var resolutionVisible by mutableStateOf(false)
    internal var showTimeOn by mutableStateOf(false)
    internal var showNetSpeedOn by mutableStateOf(false)
    internal var timeText by mutableStateOf("")
    internal var netSpeedText by mutableStateOf("")
    internal var gestureHintText by mutableStateOf<String?>(null)
    internal var tsPosition by mutableIntStateOf(0)
    internal var tsDuration by mutableIntStateOf(0)
    internal var channelInfoUi by mutableStateOf(ChannelInfoUi())
    internal val expandedGroups = mutableStateListOf<Int>()

    internal data class ChannelInfoUi(
        val name: String = "",
        val num: Int = 0,
        val sourceText: String = "",
        val currentEpgTime: String = "",
        val currentEpgTitle: String = "",
        val nextEpgTime: String = "",
        val nextEpgTitle: String = "",
    )

    // ============================================================
    // 业务状态(自旧 Java 版移植)
    // ============================================================

    internal var mVideoView: MyVideoView? = null
    /** 直播自己的控制层:点播页接管播放器后会被顶掉,回前台要重新挂上(见 rebindLiveControllerIfNeeded) */
    private var liveController: ComposeLiveController? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private val liveChannelGroupList = ArrayList<LiveChannelGroup>()
    internal var currentChannelGroupIndex = 0
    internal var currentLiveChannelIndex = -1
    internal var currentLiveLookBackIndex = -1
    private var currentLiveChangeSourceTimes = 0
    private var allowLiveSwitchPlayer = true
    private var currentLiveChannelItem: LiveChannelItem? = null
    private var pendingLiveRefreshChannelName: String? = null
    private var pendingLiveRefreshSourceIndex = -1
    private var refreshingLiveChannelList = false
    private var liveConfigRequestId = 0
    private val livePlayerManager = LivePlayerManager()
    private val channelGroupPasswordConfirmed = ArrayList<Int>()
    internal var channelName: LiveChannelItem? = null // 旧 channel_Name
    private val hsEpg = Hashtable<String, ArrayList<Epginfo>>()
    internal var epgdata = ArrayList<Epginfo>()
    private var epgStringAddress = ""
    private var catchup: JsonObject? = null
    private var logoUrl: String? = null
    private var isSHIYI = false
    private var playUrl: String? = null
    private var shiyiTimeC = 0
    private var selectedChannelGroupIndex = 0
    private var firstLiveEpgLoad = true
    private var resolutionInfoRetryCount = 0
    private var resolutionInfoPending = false
    private var exitingLivePlay = false
    private var loadingLiveConfigOnEnter = false
    private var liveSettingGroupList: List<LiveSettingGroup> = ArrayList()
    private var nowday = Date()
    private var epgDayPresented = "" // 旧 liveEpgDateAdapter 仅含"今天"单条目的等价物

    // ============================================================
    // 生命周期
    // ============================================================

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 竖屏保留系统栏(§3),全屏沉浸时走基类逻辑
        if (fullScreen) super.hideSysBar()
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        // 播放器与状态栏均为纯黑,状态栏图标强制白色(§3/Step 4 定稿⑤)
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // bottom sheet 有独立窗口,返回键由其自行处理,正常不会走到这里
                    epgSheetVisible -> epgSheetVisible = false
                    settingsSheetVisible -> settingsSheetVisible = false
                    // 全屏时侧滑返回一步到位退出横屏;浮层本会自动隐藏,无需单独消费一次返回
                    fullScreen -> applyFullscreen(false)
                    isBackState -> backToLiveFromEpg() // 旧:退出回看回到直播(不再顺带换线,见 spec 记录)
                    else -> {
                        exitingLivePlay = true
                        finish()
                    }
                }
            }
        })
        epgStringAddress = getConfiguredEpgAddress()
        nowday = Date()
        epgDayPresented = FORMAT_DATE1.format(nowday)
        initVideoView()
        // 直播/点播标记不在这里写:改由引擎的模式切换写(见 PlaybackEngine.setLiveFlag)——
        // Activity 生命周期与"引擎已切回点播"没有时序关系,IjkMediaPlayer 在 prepare 时会读到滞后的直播参数
        findViewById<ComposeView>(R.id.compose_view).setContent {
            // 纯黑状态栏页面:图标恒白由本页 init/沉浸退出逻辑断言,主题不接管
            AVBoxTheme(manageStatusBarIcons = false) {
                LiveScreen(activity = this)
            }
        }
        initLiveChannelList()
        initLiveSettingGroupList()
    }

    override fun onResume() {
        super.onResume()
        // 系统回前台会按主题重设状态栏图标外观,在首帧前重新断言(对齐详情页 §4.4 补丁⑤)
        applyStatusBarAppearance()
        exitingLivePlay = false
        // P4:回到前台时确保引擎仍是"直播人格"。被点播页接管过(返回 true)则内核已被释放、
        // 内容不可信 —— 重播当前频道;否则照旧恢复播放(直播退后台被 onPause 暂停的那一路)
        val takenOverByVod = PlaybackService.peek()?.enterLiveState() ?: false
        rebindLiveControllerIfNeeded()
        if (takenOverByVod) {
            replayCurrentChannelAfterTakeover()
        } else {
            mVideoView?.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!exitingLivePlay) mVideoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 同上:标记改由引擎(exitLive → exitLiveState / 引擎释放)复位,页面不再直接写
        hideSwitchChannelSnapshot()
        // P4:播放器归引擎 —— 只退出直播模式(还回点播进度管理器、清直播控制器),实例留给点播复用
        PlaybackService.peek()?.exitLive()
        mVideoView = null
        mHandler.removeCallbacksAndMessages(null)
    }

    // ============================================================
    // 播放器与控制层
    // ============================================================

    private fun initVideoView() {
        val controller = ComposeLiveController(this)
        controller.setListener(liveControlListener)
        liveController = controller
        // P4:与点播共用同一播放器实例 —— 引擎切到"直播人格"(撤点播进度管理器、结束点播媒体会话、
        // 清掉上一部点播的残留帧/封面),直播自己的控制层与切换逻辑不变
        val view = PlaybackService.engine(this).also { it.enterLive() }.player()
        view.setVideoController(controller)
        view.setProgressManager(null)
        mVideoView = view
    }

    /**
     * 直播自己的控制层:点播页 attach 时会 `setVideoController(点播控制器)`,
     * 把直播控制器从播放器上顶掉;此前直播页没有任何恢复点,从点播页返回后手势/菜单/时移/清晰度
     * 全部失效。这里在回前台时按"当前挂的是不是直播控制器"补挂一次。
     */
    private fun rebindLiveControllerIfNeeded() {
        val view = mVideoView ?: return
        val controller = liveController ?: return
        if (view.videoController !== controller) {
            view.setVideoController(controller)
            LOG.i("echo-p4 re-bind live controller")
        }
    }

    /**
     * 被点播页接管后回到直播(见 PlaybackEngine.enterLiveState):内核已被释放,直播流无法
     * 续播(地址早已失效或被点播替换)—— 等同一次"不换台号的强制切台",重播当前频道。
     * 与 playChannel 的差别仅在于绕过"同频道不重播"守卫;时移/回看状态复位与切台一致。
     */
    private fun replayCurrentChannelAfterTakeover() {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        currentLiveLookBackIndex = -1
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        stopTimeshiftTicker()
        hideSwitchChannelSnapshot()
        videoView.setUrl(item.url, liveChannelHeader())
        videoView.start()
        showResolutionAfterChannelSwitch()
        loadEpgAfterChannelStarted()
        epgVersion++
    }

    /**
     * 释放播放内核(切台 / 换解码器 / 换源 / 时移进出)。
     *
     * <p>所有权收口:播放器归引擎,页面只表达"我要换内核"的意图。
     * 行为与改造前 `videoView.release()` 完全一致(释放内核 + 渲染视图,下次 start 新建),
     * 但走引擎后引擎自己知道内核没了,不会把预载/会话/接管标记留在"还在播"的假象上。
     */
    private fun releasePlayerKernel() {
        val eng = PlaybackService.peek()
        if (eng != null && !eng.isReleased()) eng.releasePlayer()
        else mVideoView?.release()
    }

    private val liveControlListener = object : ComposeLiveController.LiveControlListener {
        override fun onSingleTap(): Boolean {
            if (fullScreen) {
                overlayVisible = !overlayVisible
                if (overlayVisible) scheduleOverlayHide()
            } else {
                applyFullscreen(true)
            }
            return true
        }

        override fun onLongPress() {
            if (isBackState) {
                overlayVisible = true
                scheduleOverlayHide()
            } else {
                openSettingsSheet()
            }
        }

        override fun onPlayStateChanged(playState: Int) {
            this@LivePlayActivity.playState = playState
            handleAutoSourceSwitch(playState)
        }

        override fun onHorizontalFling(direction: Int) {
            // §4.5:左右滑视频区切上一/下一频道
            if (direction > 0) playNext() else playPrevious()
        }

        override fun onGesturePercent(isBrightness: Boolean, percent: Int) {
            gestureHintText = (if (isBrightness) "亮度" else "音量") + " " + percent + "%"
            mHandler.removeCallbacks(mHideGestureHintRun)
            mHandler.postDelayed(mHideGestureHintRun, 1000)
        }
    }

    /** 旧 LiveControlListener.playStateChanged 的自动换源状态机,1:1 移植 */
    private fun handleAutoSourceSwitch(state: Int) {
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        when (state) {
            VideoView.STATE_IDLE, VideoView.STATE_PAUSED -> {}
            VideoView.STATE_PREPARED, VideoView.STATE_BUFFERED, VideoView.STATE_PLAYING -> {
                hideSwitchChannelSnapshot()
                if (resolutionInfoPending) {
                    resolutionInfoRetryCount = 0
                    mHandler.removeCallbacks(mUpdateResolutionInfoRun)
                    mHandler.post(mUpdateResolutionInfoRun)
                }
                currentLiveChangeSourceTimes = 0
                allowLiveSwitchPlayer = true
            }
            VideoView.STATE_ERROR, VideoView.STATE_PLAYBACK_COMPLETED -> {
                hideSwitchChannelSnapshot()
                mHandler.postDelayed(mConnectTimeoutChangeSourceRun, CONNECT_TIMEOUT_SWITCH_DELAY)
            }
            VideoView.STATE_PREPARING, VideoView.STATE_BUFFERING -> {
                mHandler.postDelayed(
                    mConnectTimeoutChangeSourceRun,
                    (KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L,
                )
            }
            else -> LOG.i("echo-Unexpected live_play state: $state")
        }
    }

    fun applyFullscreen(full: Boolean) {
        if (fullScreen == full) return
        // 目标方向与实际方向不一致 → 进旋转过渡态:布局形态等落地再切
        rotating = (full != (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
        fullScreen = full
        requestedOrientation = if (full) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (full) {
            overlayVisible = true
            scheduleOverlayHide()
            super.hideSysBar()
        } else {
            overlayVisible = false
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            // 退回竖屏后状态栏区域仍是纯黑,保持白色图标;同步断言可能被系统的过渡结束态覆盖
            // (vivo OriginOS 实测会在横竖屏过渡时按主题重设图标外观,浅色主题 → 深色图标,
            //  深色图标在纯黑底上"消失"),故等旋转/系统栏过渡结束后再兜底断言一次(§4.4 补丁⑤)
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
    }

    /**
     * 竖屏状态栏区域为纯黑,图标必须白色(对齐详情页 §4.4 补丁⑤;全屏沉浸时系统栏隐藏,此值不影响)。
     * 系统 ROM 会在沉浸退出/横竖屏过渡与回前台时按主题重设图标外观(浅色主题 → 深色图标),
     * 深色图标在纯黑底上等于"消失",故关键时机(init/onResume/旋转落地/退出全屏)反复断言。
     * 导航键图标按应用主题断言(状态栏恒白不受影响):本页 manageStatusBarIcons=false 主题不接管,
     * 而 light() 导航栏样式使 EdgeToEdge 恒设深色图标,深色主题下压深色内容几乎不可见。
     */
    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    /** 旋转落地回调:清过渡态,布局形态在这一帧才真正切换 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rotating = false
        // 旋转落地是 ROM 重设状态栏图标外观的时机之一(快速横竖切换时同步断言必被覆盖),落地即重新断言
        applyStatusBarAppearance()
    }

    /**
     * 当前布局形态是否为「全屏铺满」——与 Compose 侧([LiveScreen]/[LiveReadyContent]/[PlayerArea])的判断必须一致。
     * 过渡期跟随**当前方向**(横屏=全屏样、竖屏=直播竖屏样),旋转落地后才切到目标态 [fullScreen]。
     * 兜底:万一系统没下发 onConfigurationChanged,形态退化为"当前方向的自然形态",不会卡死。
     */
    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (rotating) landNow else fullScreen
    }

    // ============================================================
    // 播放链路(playChannel/playNext/换源/自动兜底,1:1 移植)
    // ============================================================

    private fun playChannel(channelGroupIndex: Int, liveChannelIndex: Int, changeSource: Boolean): Boolean {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
            || (changeSource && currentLiveChannelItem?.sourceNum == 1)
        ) {
            return true
        }
        val groupChannels = getLiveChannels(channelGroupIndex)
        if (groupChannels == null || groupChannels.isEmpty() || liveChannelIndex < 0 || liveChannelIndex >= groupChannels.size) {
            return false
        }
        val showPreviousFrame = currentLiveChannelItem != null && mVideoView?.isPlaying == true
        val previousLivePlayerType = livePlayerManager.livePlayerType
        allowLiveSwitchPlayer = true
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex
            currentLiveChannelIndex = liveChannelIndex
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex)?.get(currentLiveChannelIndex)
            KV.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem?.channelName ?: "")
        }
        channelName = currentLiveChannelItem
        currentLiveLookBackIndex = -1
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        stopTimeshiftTicker() // 切台即退出回看:一并停掉时移进度刷新
        val item = currentLiveChannelItem ?: return false
        item.include_back = canCurrentChannelCatchup()
        updateChannelInfoUi()
        val videoView = mVideoView
        if (videoView != null) {
            val reusePlayer = canReusePlayer(previousLivePlayerType)
            val keepExoFrame = reusePlayer && previousLivePlayerType == 2
            if (showPreviousFrame && !keepExoFrame) {
                showSwitchChannelSnapshot()
            } else {
                hideSwitchChannelSnapshot()
            }
            val liveUrl = item.url
            if (reusePlayer) {
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.replay(true)
            } else {
                releasePlayerKernel()
                videoView.setUrl(liveUrl, liveChannelHeader())
                videoView.start()
            }
            showResolutionAfterChannelSwitch()
        }
        loadEpgAfterChannelStarted()
        epgVersion++
        return true
    }

    private fun canReusePlayer(previousLivePlayerType: Int): Boolean {
        val videoView = mVideoView ?: return false
        return videoView.currentPlayState != VideoView.STATE_IDLE &&
                previousLivePlayerType == livePlayerManager.livePlayerType
    }

    private fun loadEpgAfterChannelStarted() {
        mHandler.removeCallbacks(mLoadEpgRun)
        if (!hasEpgAddress()) {
            epgdata = ArrayList()
            epgVersion++
            return
        }
        if (hasCurrentEpgCache()) {
            firstLiveEpgLoad = false
            return
        }
        if (firstLiveEpgLoad) {
            firstLiveEpgLoad = false
            mHandler.postDelayed(mLoadEpgRun, EPG_LOAD_DELAY)
        } else {
            getEpg(Date())
        }
    }

    private fun hasCurrentEpgCache(): Boolean {
        val channel = channelName ?: return false
        return hsEpg.containsKey(channel.channelName + "_" + epgDayPresented)
    }

    private fun playNext() {
        if (!isCurrentLiveChannelValid()) return
        val next = getNextChannel(1)
        playChannel(next[0], next[1], false)
    }

    private fun playPrevious() {
        if (!isCurrentLiveChannelValid()) return
        val next = getNextChannel(-1)
        playChannel(next[0], next[1], false)
    }

    private fun playNextSource() {
        if (!isCurrentLiveChannelValid()) return
        currentLiveChannelItem?.nextSource()
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
    }

    private val mConnectTimeoutChangeSourceRun = Runnable {
        if (switchLivePlayerAndReplay()) {
            return@Runnable
        }
        currentLiveChangeSourceTimes++
        if (currentLiveChannelItem?.sourceNum == currentLiveChangeSourceTimes) {
            currentLiveChangeSourceTimes = 0
            val next = getNextChannel(if (KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) -1 else 1)
            playChannel(next[0], next[1], false)
        } else {
            playNextSource()
        }
    }

    private fun switchLivePlayerAndReplay(): Boolean {
        val videoView = mVideoView
        // 取局部变量:currentLiveChannelItem 是 var,判空后无法 smart-cast,
        // 原写法在下面用 !! 取值;改为一次取值,后续不再依赖字段中途不变
        val item = currentLiveChannelItem ?: return false
        if (!allowLiveSwitchPlayer || videoView == null) {
            return false
        }
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        releasePlayerKernel()
        if (!livePlayerManager.switchLivePlayer(videoView)) {
            allowLiveSwitchPlayer = false
            return false
        }
        allowLiveSwitchPlayer = false
        val retryUrl = if (isSHIYI && !TextUtils.isEmpty(playUrl)) playUrl!! else item.url
        videoView.setUrl(retryUrl, liveChannelHeader())
        videoView.start()
        return true
    }

    private fun getNextChannel(direction: Int): Array<Int> {
        var channelGroupIndex = currentChannelGroupIndex
        var liveChannelIndex = currentLiveChannelIndex
        if (direction > 0) {
            liveChannelIndex++
            if (liveChannelIndex >= (getLiveChannels(channelGroupIndex)?.size ?: 0)) {
                liveChannelIndex = 0
                if (KV.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    do {
                        channelGroupIndex++
                        if (channelGroupIndex >= liveChannelGroupList.size) channelGroupIndex = 0
                    } while (liveChannelGroupList.getOrNull(channelGroupIndex)?.groupPassword?.isNotEmpty() != false ||
                        channelGroupIndex == currentChannelGroupIndex
                    )
                }
            }
        } else {
            liveChannelIndex--
            if (liveChannelIndex < 0) {
                if (KV.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    do {
                        channelGroupIndex--
                        if (channelGroupIndex < 0) channelGroupIndex = liveChannelGroupList.size - 1
                    } while (liveChannelGroupList.getOrNull(channelGroupIndex)?.groupPassword?.isNotEmpty() != false ||
                        channelGroupIndex == currentChannelGroupIndex
                    )
                }
                liveChannelIndex = (getLiveChannels(channelGroupIndex)?.size ?: 1) - 1
            }
        }
        return arrayOf(channelGroupIndex, liveChannelIndex)
    }

    private fun getFirstChannelByName(keyword: String?): IntArray? {
        if (TextUtils.isEmpty(keyword)) return null
        val upperKeyword = keyword!!.uppercase(Locale.US)
        for (group in liveChannelGroupList) {
            if (isNeedInputPassword(group.groupIndex)) continue
            val groupChannels = group.liveChannels ?: continue
            if (groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                val name = item.channelName ?: continue
                if (name.uppercase(Locale.US).contains(upperKeyword)) {
                    return intArrayOf(group.groupIndex, item.channelIndex)
                }
            }
        }
        return null
    }

    private fun getFirstNoPasswordChannelGroup(): Int {
        for (group in liveChannelGroupList) {
            if (group.groupPassword.isEmpty()) return group.groupIndex
        }
        return -1
    }

    private fun isCurrentLiveChannelValid(): Boolean {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance(), "请先选择频道", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // ============================================================
    // 频道列表 / 分组 / 密码
    // ============================================================

    private fun selectChannelGroup(groupIndex: Int, liveChannelIndex: Int) {
        selectedChannelGroupIndex = groupIndex
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, liveChannelIndex)
            return
        }
        if (liveChannelIndex > -1) {
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex)
        } else {
            if (!expandedGroups.contains(groupIndex)) expandedGroups.add(groupIndex)
            channelVersion++
        }
    }

    fun toggleChannelGroup(groupIndex: Int) {
        if (expandedGroups.contains(groupIndex)) {
            expandedGroups.remove(groupIndex)
            return
        }
        if (isNeedInputPassword(groupIndex)) {
            showPasswordDialog(groupIndex, -1)
            return
        }
        expandedGroups.add(groupIndex)
    }

    fun selectChannel(groupIndex: Int, position: Int) {
        selectedChannelGroupIndex = groupIndex
        clickLiveChannel(position)
    }

    private fun clickLiveChannel(position: Int) {
        playChannel(selectedChannelGroupIndex, position, false)
    }

    private fun loadChannelGroupDataAndPlay(groupIndex: Int, liveChannelIndex: Int) {
        selectedChannelGroupIndex = groupIndex
        if (!expandedGroups.contains(groupIndex)) expandedGroups.add(groupIndex)
        channelVersion++
        scrollTick++
        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex)
        }
    }

    private fun showPasswordDialog(groupIndex: Int, liveChannelIndex: Int) {
        passwordDialogTarget = groupIndex to liveChannelIndex
    }

    internal fun onPasswordConfirmed(password: String) {
        val target = passwordDialogTarget ?: return
        passwordDialogTarget = null
        val groupIndex = target.first
        if (password == liveChannelGroupList.getOrNull(groupIndex)?.groupPassword) {
            channelGroupPasswordConfirmed.add(groupIndex)
            channelVersion++
            loadChannelGroupDataAndPlay(groupIndex, target.second)
        } else {
            Toast.makeText(App.getInstance(), "密码错误", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNeedInputPassword(groupIndex: Int): Boolean {
        val group = liveChannelGroupList.getOrNull(groupIndex) ?: return false
        return group.groupPassword.isNotEmpty() && !isPasswordConfirmed(groupIndex)
    }

    private fun isPasswordConfirmed(groupIndex: Int): Boolean {
        for (confirmed in channelGroupPasswordConfirmed) {
            if (confirmed == groupIndex) return true
        }
        return false
    }

    fun getLiveChannels(groupIndex: Int): ArrayList<LiveChannelItem>? {
        val group = liveChannelGroupList.getOrNull(groupIndex) ?: return null
        return if (!isNeedInputPassword(groupIndex)) group.liveChannels else ArrayList()
    }

    // ============================================================
    // 直播配置加载链(1:1 移植)
    // ============================================================

    private fun initLiveChannelList() {
        if (ApiConfig.get().shouldReloadLiveConfig()) {
            loadLiveConfigOnEnter()
            return
        }
        val list = ApiConfig.get().channelGroupList
        if (list.isEmpty()) {
            loadLiveConfigOnEnter()
            return
        }
        initLiveObj()
        if (list.size == 1 && list[0].groupName.startsWith("http://127.0.0.1")) {
            loadProxyLives(list[0].groupName)
        } else {
            applyLiveChannelGroups(ArrayList(list))
        }
    }

    internal fun loadLiveConfigOnEnter() {
        if (loadingLiveConfigOnEnter) return
        loadingLiveConfigOnEnter = true
        pageState = PageState.LOADING
        ApiConfig.get().loadLiveConfig(true, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                mHandler.post {
                    loadingLiveConfigOnEnter = false
                    initLiveChannelList()
                    initLiveSettingGroupList()
                }
            }

            override fun error(msg: String) {
                mHandler.post {
                    loadingLiveConfigOnEnter = false
                    setEmptyLiveChannelList()
                }
            }

            override fun notice(msg: String) {
                mHandler.post {
                    Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun loadProxyLives(url: String) {
        var realUrl = url
        try {
            val parsedUrl = Uri.parse(realUrl)
            realUrl = String(
                Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                charset("UTF-8"),
            )
        } catch (th: Throwable) {
            if (!realUrl.startsWith("http://127.0.0.1")) {
                setEmptyLiveChannelList()
                return
            }
        }
        if (!isValidLiveProxyUrl(realUrl)) {
            setEmptyLiveChannelList()
            return
        }
        if (!refreshingLiveChannelList) {
            pageState = PageState.LOADING
        }
        LOG.i("echo-live-url:$realUrl")
        if (realUrl.contains(".py") || realUrl.contains(".js")) {
            val finalUrl = realUrl
            val waitResponse = Runnable {
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit(java.util.concurrent.Callable<String> {
                    val sp = ApiConfig.get().getLiveCSP(finalUrl)
                    sp.liveContent(finalUrl)
                })
                var sortJson: String? = null
                try {
                    sortJson = future.get(ApiConfig.get().liveConnectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    e.printStackTrace()
                    future.cancel(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    if (sortJson.isNullOrEmpty()) {
                        mHandler.post { setEmptyLiveChannelList() }
                        return@Runnable
                    }
                    // 解析(纯函数)留在后台线程;写共享的 liveChannelGroupList 与刷新 UI 一律回主线程
                    // (修复竞态:loadLives 会对 ApiConfig.liveChannelGroupList 做 clear/add,
                    //  原先在后台线程执行,与主线程读同一 list 并发;纯 URL 分支本就在主线程做,这里对齐)
                    val livesArray = TxtSubscribe.parseToJsonArray(sortJson)
                    mHandler.post {
                        ApiConfig.get().loadLives(livesArray)
                        val list = ApiConfig.get().channelGroupList
                        if (list.isEmpty()) {
                            setEmptyLiveChannelList()
                        } else {
                            applyLiveChannelGroups(ArrayList(list))
                        }
                    }
                    try {
                        executor.shutdown()
                    } catch (th: Throwable) {
                        th.printStackTrace()
                    }
                }
            }
            Executors.newSingleThreadExecutor().execute(waitResponse)
        } else {
            OkGo.get<String>(realUrl).execute(object : AbsCallback<String>() {
                override fun convertResponse(response: okhttp3.Response): String {
                    return response.body.string()
                }

                override fun onSuccess(response: Response<String>) {
                    val livesArray = TxtSubscribe.parseToJsonArray(response.body())
                    ApiConfig.get().loadLives(livesArray)
                    val list = ApiConfig.get().channelGroupList
                    if (list.isEmpty()) {
                        mHandler.post { setEmptyLiveChannelList() }
                        return
                    }
                    val loadedGroups = ArrayList(list)
                    mHandler.post { applyLiveChannelGroups(loadedGroups) }
                }

                override fun onError(response: Response<String>) {
                    mHandler.post { setEmptyLiveChannelList() }
                }
            })
        }
    }

    private fun isValidLiveProxyUrl(url: String?): Boolean {
        if (TextUtils.isEmpty(url)) return false
        val lowerUrl = url!!.trim { it <= ' ' }.lowercase(Locale.US)
        return lowerUrl.startsWith("http://") ||
                lowerUrl.startsWith("https://") ||
                lowerUrl.startsWith("rtsp://") ||
                lowerUrl.startsWith("rtmp://") ||
                lowerUrl.startsWith("rtp://")
    }

    private fun applyLiveChannelGroups(groups: List<LiveChannelGroup>) {
        liveChannelGroupList.clear()
        liveChannelGroupList.addAll(groups)
        pageState = PageState.READY
        initLiveState()
    }

    private fun initLiveState() {
        refreshingLiveChannelList = false
        val lastChannelName = pendingLiveRefreshChannelName ?: KV.get(HawkConfig.LIVE_CHANNEL, "")
        val sourceIndex = pendingLiveRefreshSourceIndex
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1

        var lastChannelGroupIndex = -1
        var lastLiveChannelIndex = -1
        var lastLiveChannelItem: LiveChannelItem? = null
        for (group in liveChannelGroupList) {
            val groupChannels = group.liveChannels
            if (groupChannels == null || groupChannels.isEmpty()) continue
            for (item in groupChannels) {
                if (item.channelName == lastChannelName) {
                    lastChannelGroupIndex = group.groupIndex
                    lastLiveChannelIndex = item.channelIndex
                    lastLiveChannelItem = item
                    break
                }
            }
            if (lastChannelGroupIndex != -1) break
        }
        if (lastChannelGroupIndex == -1) {
            val cctv1Channel = getFirstChannelByName("CCTV1")
            if (cctv1Channel != null) {
                lastChannelGroupIndex = cctv1Channel[0]
                lastLiveChannelIndex = cctv1Channel[1]
            } else {
                lastChannelGroupIndex = getFirstNoPasswordChannelGroup()
                if (lastChannelGroupIndex == -1) lastChannelGroupIndex = 0
                lastLiveChannelIndex = 0
            }
        }
        if (lastLiveChannelItem != null && sourceIndex >= 0 && lastLiveChannelItem.sourceNum > 0) {
            lastLiveChannelItem.sourceIndex = minOf(sourceIndex, lastLiveChannelItem.sourceNum - 1)
        }

        mVideoView?.let { livePlayerManager.init(it) }
        showTime()
        showNetSpeed()
        currentLiveChannelIndex = -1
        expandedGroups.clear()
        channelVersion++
        selectChannelGroup(lastChannelGroupIndex, lastLiveChannelIndex)
    }

    private fun refreshLiveChannelListAndPlay(channelName: String?, sourceIndex: Int) {
        refreshingLiveChannelList = true
        pendingLiveRefreshChannelName = channelName
        pendingLiveRefreshSourceIndex = sourceIndex
        currentLiveLookBackIndex = -1
        currentLiveChangeSourceTimes = 0
        allowLiveSwitchPlayer = true
        channelGroupPasswordConfirmed.clear()
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        mHandler.removeCallbacks(mLoadEpgRun)
        hideSwitchChannelSnapshot()
        expandedGroups.clear()
        isBackState = false
        overlayVisible = false
        channelVersion++
        epgVersion++
        initLiveChannelList()
        initLiveSettingGroupList()
    }

    private fun clearLiveChannelList(releasePlayer: Boolean) {
        refreshingLiveChannelList = false
        pendingLiveRefreshChannelName = null
        pendingLiveRefreshSourceIndex = -1
        currentLiveChannelItem = null
        currentLiveChannelIndex = -1
        currentLiveLookBackIndex = -1
        currentLiveChangeSourceTimes = 0
        liveChannelGroupList.clear()
        ApiConfig.get().channelGroupList.clear()
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        mHandler.removeCallbacks(mLoadEpgRun)
        hideSwitchChannelSnapshot()
        if (releasePlayer) releasePlayerKernel()
        expandedGroups.clear()
        selectedChannelGroupIndex = 0
        channelName = null
        epgdata = ArrayList()
        channelInfoUi = ChannelInfoUi()
        channelVersion++
        epgVersion++
        pageState = PageState.EMPTY
    }

    private fun setEmptyLiveChannelList(releasePlayer: Boolean = true) {
        clearLiveChannelList(releasePlayer)
    }


    private fun initLiveSettingGroupList() {
        liveSettingGroupList = ApiConfig.get().liveSettingGroupList
    }

    private fun loadCurrentSourceList() {
        val items = ArrayList<com.github.tvbox.osc.bean.LiveSettingItem>()
        val sourceNames = currentLiveChannelItem?.channelSourceNames
        if (sourceNames != null) {
            for (j in sourceNames.indices) {
                val item = com.github.tvbox.osc.bean.LiveSettingItem()
                item.itemIndex = j
                item.itemName = sourceNames[j]
                items.add(item)
            }
        }
        liveSettingGroupList.getOrNull(0)?.liveSettingItems = items
    }

    fun visibleSettingGroups(): List<LiveSettingGroup> {
        val showChannelOptions = hasCurrentLiveChannelSource()
        return liveSettingGroupList.filter { group ->
            !(group.groupIndex in 0..2 && !showChannelOptions)
        }
    }

    private fun hasCurrentLiveChannelSource(): Boolean {
        val item = currentLiveChannelItem ?: return false
        return item.channelUrls != null && item.sourceNum > 0 &&
                item.sourceIndex >= 0 && item.sourceIndex < item.channelUrls.size
    }

    internal fun openSettingsSheet() {
        ApiConfig.get().refreshLiveApiHistoryItems()
        loadCurrentSourceList()
        settingsVersion++
        settingsSheetVisible = true
    }

    fun settingSelectedIndex(groupIndex: Int): Int {
        return when (groupIndex) {
            0 -> currentLiveChannelItem?.sourceIndex ?: -1
            1 -> livePlayerManager.livePlayerScale
            2 -> livePlayerManager.livePlayerType
            3 -> KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)
            5 -> ApiConfig.getLiveGroupIndex()
            6 -> getCurrentLiveConfigIndex()
            else -> -1
        }
    }

    fun removeLiveConfigHistory(itemIndex: Int) {
        val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        if (itemIndex < 0 || itemIndex >= history.size) return
        if (history[itemIndex] == KV.get(HawkConfig.LIVE_API_URL, "")) {
            Toast.makeText(this, "当前使用中的配置不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        history.removeAt(itemIndex)
        KV.put(HawkConfig.LIVE_API_HISTORY, history)
        ApiConfig.get().refreshLiveApiHistoryItems()
        settingsVersion++
        Toast.makeText(this, "已从历史中删除", Toast.LENGTH_SHORT).show()
    }

    fun settingChecked(position: Int): Boolean {
        return when (position) {
            0 -> KV.get(HawkConfig.LIVE_SHOW_TIME, false)
            1 -> KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
            2 -> KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)
            3 -> KV.get(HawkConfig.LIVE_CROSS_GROUP, false)
            else -> false
        }
    }

    /**
     * 直播设置「配置切换」组的选中项:
     * 第 0 项 = 合成的「跟随点播源」;其后为直播配置历史,历史第 i 项在该组里的 itemIndex = i + 1。
     */
    private fun getCurrentLiveConfigIndex(): Int {
        if (ApiConfig.isLiveFollowVod()) return 0
        val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        val index = history.indexOf(KV.get(HawkConfig.LIVE_API_URL, ""))
        return if (index < 0) -1 else index + 1
    }

    internal fun clickSettingItem(groupIndex: Int, position: Int) {
        if (groupIndex in 0..2 && !isCurrentLiveChannelValid()) return
        when (groupIndex) {
            0 -> { // 线路切换
                val item = currentLiveChannelItem ?: return
                if (position < 0 || position >= item.sourceNum || position == item.sourceIndex) return
                item.sourceIndex = position
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true)
            }
            1 -> { // 画面比例
                if (position == livePlayerManager.livePlayerScale) return
                mVideoView?.let { livePlayerManager.changeLivePlayerScale(it, position) }
            }
            2 -> { // 播放解码
                if (position == livePlayerManager.livePlayerType) return
                val videoView = mVideoView ?: return
                releasePlayerKernel()
                livePlayerManager.changeLivePlayerType(videoView, position)
                currentLiveChannelItem?.let { videoView.setUrl(it.url, liveChannelHeader()) }
                videoView.start()
            }
            3 -> { // 超时换源
                if (position == KV.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)) return
                KV.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position)
            }
            4 -> { // 偏好设置
                when (position) {
                    0 -> KV.put(HawkConfig.LIVE_SHOW_TIME, !KV.get(HawkConfig.LIVE_SHOW_TIME, false)).also { showTime() }
                    1 -> KV.put(HawkConfig.LIVE_SHOW_NET_SPEED, !KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)).also { showNetSpeed() }
                    2 -> KV.put(HawkConfig.LIVE_CHANNEL_REVERSE, !KV.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                    3 -> KV.put(HawkConfig.LIVE_CROSS_GROUP, !KV.get(HawkConfig.LIVE_CROSS_GROUP, false))
                }
            }
            5 -> { // 多源切换
                if (position == ApiConfig.getLiveGroupIndex()) return
                val currentChannelName = getPreferredLiveRefreshChannelName()
                val currentSourceIndex = getPreferredLiveRefreshSourceIndex()
                val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
                if (liveGroups == null || position >= liveGroups.size()) return
                liveConfigRequestId++
                val livesOBJ = liveGroups.get(position).asJsonObject
                ApiConfig.setLiveGroupIndex(position)
                ApiConfig.get().loadLiveApi(livesOBJ)
                if (ApiConfig.get().channelGroupList.isEmpty()) {
                    releasePlayerKernel()
                    setEmptyLiveChannelList(false)
                    return
                }
                refreshLiveChannelListAndPlay(currentChannelName, currentSourceIndex)
            }
            6 -> { // 配置切换:第 0 项 =「跟随点播源」,其后为直播配置历史
                val history = KV.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
                val target: String
                if (position == 0) {
                    if (ApiConfig.isLiveFollowVod()) return
                    target = "" // 空 = 跟随当前点播源
                } else {
                    if (position - 1 >= history.size) return
                    target = history[position - 1]
                    if (target == KV.get(HawkConfig.LIVE_API_URL, "")) return
                }
                val configChannelName = getPreferredLiveRefreshChannelName()
                val configSourceIndex = getPreferredLiveRefreshSourceIndex()
                val requestId = ++liveConfigRequestId
                KV.put(HawkConfig.LIVE_API_URL, target)
                if (target.isNotEmpty()) HistoryHelper.setLiveApiHistory(target)
                ApiConfig.get().invalidateLiveConfig()
                ApiConfig.get().refreshLiveApiHistoryItems()
                ApiConfig.get().loadLiveConfig(false, object : ApiConfig.LoadConfigCallback {
                    override fun success() {
                        mHandler.post {
                            if (requestId != liveConfigRequestId || isFinishing) return@post
                            refreshLiveChannelListAndPlay(configChannelName, configSourceIndex)
                        }
                    }

                    override fun error(msg: String) {
                        mHandler.post {
                            if (requestId != liveConfigRequestId || isFinishing) return@post
                            releasePlayerKernel()
                            ApiConfig.get().refreshLiveApiHistoryItems()
                            setEmptyLiveChannelList(false)
                            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun notice(msg: String) {
                        mHandler.post {
                            if (requestId != liveConfigRequestId || isFinishing) return@post
                            Toast.makeText(this@LivePlayActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                })
            }
        }
        settingsVersion++
    }

    private fun getPreferredLiveRefreshChannelName(): String? {
        currentLiveChannelItem?.let { return it.channelName }
        return KV.get(HawkConfig.LIVE_CHANNEL, "")
    }

    private fun getPreferredLiveRefreshSourceIndex(): Int {
        currentLiveChannelItem?.let { return it.sourceIndex }
        return -1
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return KV.get(HawkConfig.LIVE_WEB_HEADER)
    }

    private fun liveChannelHeader(): HashMap<String, String>? {
        val item = currentLiveChannelItem ?: return liveWebHeader()
        val header = HashMap<String, String>()
        liveWebHeader()?.let { header.putAll(it) }
        item.headers?.let { header.putAll(it) }
        if (item.channelFormat.isNotEmpty()) {
            header[ExoMediaSourceHelper.HEADER_FORMAT] = item.channelFormat
        }
        return if (header.isEmpty()) null else header
    }

    /** 旧 initLiveObj:读取当前多源的 catchup/logo/type=3 爬虫 jar 配置 */
    private fun initLiveObj() {
        catchup = null
        logoUrl = null
        val position = ApiConfig.getLiveGroupIndex()
        val liveGroups = KV.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
        if (liveGroups == null || liveGroups.size() == 0 || position < 0 || position >= liveGroups.size()) {
            return
        }
        val livesOBJ = liveGroups.get(position).asJsonObject
        val type = if (livesOBJ.has("type")) livesOBJ.get("type").asString else "0"
        if (livesOBJ.has("catchup") && livesOBJ.get("catchup").isJsonObject) {
            catchup = livesOBJ.getAsJsonObject("catchup")
            LOG.i("echo-catchup :$catchup")
        }
        if (livesOBJ.has("logo")) {
            logoUrl = livesOBJ.get("logo").asString
        }
        if (type == "3") {
            var pyJar = ""
            if (livesOBJ.has("jar")) {
                pyJar = livesOBJ.get("jar").asString
            } else if (livesOBJ.has("api")) {
                pyJar = livesOBJ.get("api").asString
                val ext = if (livesOBJ.has("ext") &&
                    (livesOBJ.get("ext").isJsonObject || livesOBJ.get("ext").isJsonArray)
                ) {
                    livesOBJ.get("ext").toString()
                } else {
                    DefaultConfig.safeJsonString(livesOBJ, "ext", "")
                }
                LOG.i("echo-ext:$ext")
                if (ext.isNotEmpty()) pyJar = "$pyJar?extend=$ext"
            }
            ApiConfig.get().setLiveJar(pyJar)
        }
    }

    // ============================================================
    // 时移回看(EPG 点击 → catchup URL 播放)
    // ============================================================

    /** EPG 行点击:不再在内部关闭节目单(sheet 置 false 会跳过滑出动画),
     * 改由组合层在返回 true 时走 LocalSheetDismiss 带动画关闭。
     * @return true = 已切换播放(回直播或开始回看);false = 无变化(重复点击/条件不满足) */
    internal fun onEpgRowClicked(position: Int): Boolean {
        if (position == currentLiveLookBackIndex) return false
        val selectedData = epgdata.getOrNull(position) ?: return false
        if (selectedData.startdateTime == null || selectedData.enddateTime == null) return false
        val now = Date()
        if (now.before(selectedData.startdateTime)) return false
        if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return false
        currentLiveLookBackIndex = position
        var switched = false
        if (!now.before(selectedData.startdateTime) && !now.after(selectedData.enddateTime)) {
            // 正在播出 → 回直播
            backToLiveFromEpg()
            switched = true
        } else if (canCurrentChannelCatchup()) {
            startCatchupReplay(selectedData)
            switched = true
        }
        epgVersion++
        return switched
    }

    private fun startCatchupReplay(epg: Epginfo) {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        releasePlayerKernel()
        isSHIYI = true
        val shiyiUrl = buildCatchupUrl(item.url, epg)
        if (TextUtils.isEmpty(shiyiUrl)) return
        LOG.i("echo-回看地址playUrl :$shiyiUrl")
        playUrl = shiyiUrl
        videoView.setUrl(playUrl, liveChannelHeader())
        videoView.start()
        shiyiTimeC = LiveEpgParser.getCatchupDurationSeconds(epg)
        tsDuration = PlayerUtils.safeTimeMs(shiyiTimeC.toLong() * 1000)
        tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition)
        // 时移条每秒跟随播放前进(此前该 Runnable 从未被 post,
        // 回看时滑块与「位置/时长」文本只有拖动才更新)
        startTimeshiftTicker()
        isBackState = true
        overlayVisible = true
        scheduleOverlayHide()
        epgVersion++
    }

    private fun backToLiveFromEpg() {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        stopTimeshiftTicker()
        releasePlayerKernel()
        isSHIYI = false
        isBackState = false
        overlayVisible = false
        videoView.setUrl(item.url, liveChannelHeader())
        videoView.start()
        epgVersion++
    }

    // ============================================================
    // 时移条 / 全屏浮层显隐
    // ============================================================

    private val mHideOverlayRun = Runnable { overlayVisible = false }

    private fun scheduleOverlayHide() {
        mHandler.removeCallbacks(mHideOverlayRun)
        mHandler.postDelayed(mHideOverlayRun, OVERLAY_HIDE_DELAY)
    }

    private val mUpdateTimeshiftRun = object : Runnable {
        override fun run() {
            val videoView = mVideoView ?: return
            // 已退出回看则不再自续(兜底:即使某条退出路径漏了 removeCallbacks 也会停下)
            if (!isSHIYI) return
            tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition)
            mHandler.postDelayed(this, 1000)
        }
    }

    /** 启动时移进度刷新(进回看时调用;先 remove 再 post,避免重复进入时叠加多个 ticker) */
    private fun startTimeshiftTicker() {
        mHandler.removeCallbacks(mUpdateTimeshiftRun)
        mHandler.postDelayed(mUpdateTimeshiftRun, 1000)
    }

    /** 停止时移进度刷新(退出回看 / 切台 / 销毁时调用) */
    private fun stopTimeshiftTicker() {
        mHandler.removeCallbacks(mUpdateTimeshiftRun)
    }

    fun onTimeshiftSeek(progress: Float) {
        val videoView = mVideoView ?: return
        val target = progress.toInt().coerceIn(0, tsDuration.coerceAtLeast(1))
        videoView.seekTo(target.toLong())
        tsPosition = target
        scheduleOverlayHide()
    }

    fun onTimeshiftTogglePlay() {
        val videoView = mVideoView ?: return
        if (videoView.isPlaying) videoView.pause() else videoView.start()
        scheduleOverlayHide()
    }

    // ============================================================
    // 切台快照 / 清晰度 / 时间与网速
    // ============================================================

    private fun showSwitchChannelSnapshot() {
        var bitmap: Bitmap? = null
        try {
            bitmap = mVideoView?.doScreenShot()
        } catch (ignored: Throwable) {
        }
        snapshotBitmap = bitmap
        snapshotVisible = true
    }

    private fun hideSwitchChannelSnapshot() {
        snapshotVisible = false
        snapshotBitmap = null
    }

    private fun showResolutionAfterChannelSwitch() {
        resolutionInfoPending = true
        resolutionInfoRetryCount = 0
        resolutionText = ""
        resolutionVisible = false
        mHandler.removeCallbacks(mHideResolutionInfoRun)
        mHandler.removeCallbacks(mUpdateResolutionInfoRun)
        mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
    }

    private val mHideResolutionInfoRun = Runnable {
        resolutionVisible = false
    }

    private val mUpdateResolutionInfoRun = Runnable {
        val videoView = mVideoView ?: return@Runnable
        if (videoView.currentPlayState != VideoView.STATE_PREPARED &&
            videoView.currentPlayState != VideoView.STATE_BUFFERED &&
            videoView.currentPlayState != VideoView.STATE_PLAYING
        ) {
            retryOrHideResolutionInfo()
            return@Runnable
        }
        val videoSize = videoView.videoSize
        if (videoSize != null && videoSize.size >= 2 && videoSize[0] > 0 && videoSize[1] > 0) {
            resolutionInfoPending = false
            resolutionText = videoSize[0].toString() + " x " + videoSize[1]
            resolutionVisible = true
            mHandler.removeCallbacks(mHideResolutionInfoRun)
            mHandler.postDelayed(mHideResolutionInfoRun, RESOLUTION_INFO_HIDE_DELAY)
            return@Runnable
        }
        retryOrHideResolutionInfo()
    }

    private fun retryOrHideResolutionInfo() {
        if (resolutionInfoPending && resolutionInfoRetryCount++ < RESOLUTION_INFO_MAX_RETRY) {
            mHandler.postDelayed(mUpdateResolutionInfoRun, RESOLUTION_INFO_RETRY_DELAY)
        } else {
            resolutionVisible = false
        }
    }

    private fun showTime() {
        showTimeOn = KV.get(HawkConfig.LIVE_SHOW_TIME, false)
        mHandler.removeCallbacks(mUpdateTimeRun)
        if (showTimeOn) mHandler.post(mUpdateTimeRun)
    }

    private val mUpdateTimeRun = object : Runnable {
        override fun run() {
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            mHandler.postDelayed(this, 1000)
        }
    }

    private fun showNetSpeed() {
        showNetSpeedOn = KV.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
        mHandler.removeCallbacks(mUpdateNetSpeedRun)
        if (showNetSpeedOn) mHandler.post(mUpdateNetSpeedRun)
    }

    private val mUpdateNetSpeedRun = object : Runnable {
        override fun run() {
            val videoView = mVideoView ?: return
            netSpeedText = PlayerHelper.getDisplaySpeedBps(videoView.tcpSpeed, true)
            mHandler.postDelayed(this, 1000)
        }
    }

    private val mHideGestureHintRun = Runnable { gestureHintText = null }

    // ============================================================
    // 频道信息区(旧 showBottomEpg/setDefaultBottomEpg 移植,常驻不再自动隐藏)
    // ============================================================

    private fun updateChannelInfoUi() {
        if (isSHIYI) return
        val channel = channelName ?: return
        val name = channel.channelName ?: return
        var ui = ChannelInfoUi(name = name, num = channel.channelNum)
        ui = if (channel.sourceNum <= 0) {
            ui.copy(sourceText = "1/1")
        } else {
            ui.copy(sourceText = "线路" + (channel.sourceIndex + 1) + "/" + channel.sourceNum)
        }
        var current = ""
        var currentTitle = ""
        var next = ""
        var nextTitle = ""
        val savedEpgKey = name + "_" + epgDayPresented
        val arrayList = hsEpg[savedEpgKey]
        if (arrayList != null && arrayList.isNotEmpty()) {
            epgdata = arrayList
        } else {
            epgdata = ArrayList()
        }
        // 默认值:当前整点时段(旧 setDefaultBottomEpg)
        val timeZone = TimeZone.getTimeZone("GMT+8:00")
        val currentStart = Calendar.getInstance(timeZone)
        currentStart.set(Calendar.MINUTE, 0)
        currentStart.set(Calendar.SECOND, 0)
        currentStart.set(Calendar.MILLISECOND, 0)
        val currentEnd = (currentStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val nextStart = (currentEnd.clone() as Calendar).apply { add(Calendar.MINUTE, 1) }
        val nextEnd = (nextStart.clone() as Calendar).apply { add(Calendar.MINUTE, 59) }
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeFormat.timeZone = timeZone
        var hasInfo = false
        val list = epgdata
        if (list.isNotEmpty()) {
            val date = Date()
            var size = list.size - 1
            while (size >= 0) {
                val info = list[size]
                if (info.startdateTime != null && info.enddateTime != null &&
                    date.after(info.startdateTime) && date.before(info.enddateTime)
                ) {
                    current = info.start + "-" + info.end
                    currentTitle = info.title
                    if (size != list.size - 1) {
                        next = list[size + 1].start + "-" + list[size + 1].end
                        nextTitle = list[size + 1].title
                    } else {
                        next = info.end + "-23:59"
                        nextTitle = "精彩节目-暂无节目预告信息"
                    }
                    hasInfo = true
                    break
                } else {
                    size--
                }
            }
        }
        if (!hasInfo) {
            current = timeFormat.format(currentStart.time) + "-" + timeFormat.format(currentEnd.time)
            currentTitle = "精彩节目"
            next = timeFormat.format(nextStart.time) + "-" + timeFormat.format(nextEnd.time)
            nextTitle = "暂无节目预告信息"
        }
        channelInfoUi = ui.copy(
            currentEpgTime = current,
            currentEpgTitle = currentTitle,
            nextEpgTime = next,
            nextEpgTitle = nextTitle,
        )
        epgVersion++
    }

    // ============================================================
    // EPG 加载与解析(1:1 移植)
    // ============================================================

    private val mLoadEpgRun = Runnable {
        if (channelName != null) getEpg(Date())
    }

    fun getEpg(date: Date) {
        val channel = channelName ?: return
        val channelNameStr = channel.channelName ?: return
        val channelNameReal = LiveEpgParser.normalizeEpgChannelName(LiveEpgParser.getFirstPartBeforeSpace(channelNameStr) ?: "")
        val timeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("GMT+8:00")
        }
        var epgTagName = channelNameReal
        if (logoUrl.isNullOrEmpty()) {
            val epgInfo = EpgUtil.getEpgInfo(channelNameReal)
            if (epgInfo != null && epgInfo[1].isNotEmpty()) {
                epgTagName = epgInfo[1]
            }
        }
        if (!hasEpgAddress()) {
            epgdata = ArrayList()
            epgVersion++
            return
        }
        val epgQueryNames = LiveEpgParser.buildEpgQueryNames(channelNameStr, channelNameReal, epgTagName)
        val url = LiveEpgParser.buildEpgUrl(epgStringAddress, epgQueryNames[0], date, timeFormat)
        val savedEpgKey = channelNameStr + "_" + epgDayPresented
        if (hsEpg.containsKey(savedEpgKey)) {
            showEpg(date, hsEpg[savedEpgKey])
            updateChannelInfoUi()
            return
        }
        epgdata = ArrayList()
        epgVersion++
        requestEpg(url, date, channelNameReal, epgTagName, savedEpgKey, epgQueryNames, timeFormat, 0)
    }

    private fun showEpg(@Suppress("UNUSED_PARAMETER") date: Date, arrayList: ArrayList<Epginfo>?) {
        epgdata = if (arrayList != null && arrayList.isNotEmpty()) arrayList else ArrayList()
        epgVersion++
    }

    private fun getConfiguredEpgAddress(): String {
        val userEpgAddress: String = KV.get(HawkConfig.EPG_URL, "")
        if (userEpgAddress.trim { it <= ' ' }.length >= 5) {
            return userEpgAddress.trim { it <= ' ' }
        }
        return DEFAULT_EPG_ADDRESS
    }

    private fun hasEpgAddress(): Boolean {
        return epgStringAddress.isNotEmpty() && epgStringAddress.trim { it <= ' ' }.isNotEmpty()
    }

    private fun requestEpg(
        url: String,
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        var client = OkGoHelper.getDefaultClient()
        if (client == null) client = com.github.catvod.net.OkHttp.client()
        client.newCall(okhttp3.Request.Builder().url(url).build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                mHandler.post {
                    onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.code != 200) {
                    response.close()
                    mHandler.post {
                        onEpgRequestFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                    }
                    return
                }
                val body = try {
                    response.body.string()
                } finally {
                    response.close()
                }
                mHandler.post {
                    onEpgRequestResponse(body, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)
                }
            }
        })
    }

    private fun onEpgRequestFailure(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        if (requestDefaultEpgOnFailure(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        epgdata = ArrayList()
        epgVersion++
    }

    private fun onEpgRequestResponse(
        paramString: String?,
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ) {
        if (!isCurrentEpgRequest(savedEpgKey)) return
        if (paramString.isNullOrEmpty() || paramString.trim { it <= ' ' }.isEmpty()) {
            epgdata = ArrayList()
            epgVersion++
            return
        }
        LOG.i("echo-epgTagName:$channelNameReal")
        var arrayList = ArrayList<Epginfo>()
        try {
            if (LiveEpgParser.isXmlEpgResponse(paramString)) {
                arrayList = LiveEpgParser.parseXmlEpg(paramString, finalEpgTagName, date)
            } else if (paramString.contains("epg_data") || paramString.trim { it <= ' ' }.startsWith("{")) {
                arrayList = LiveEpgParser.parseJsonEpg(paramString, date)
            }
        } catch (jsonException: JSONException) {
            jsonException.printStackTrace()
        }
        if (arrayList.isEmpty() && requestNextEpgQueryName(date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, queryIndex)) {
            return
        }
        hsEpg[savedEpgKey] = arrayList
        if (!isCurrentEpgRequest(savedEpgKey)) return
        showEpg(date, arrayList)
        updateChannelInfoUi()
    }

    private fun requestDefaultEpgOnFailure(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ): Boolean {
        if (DEFAULT_EPG_ADDRESS == epgStringAddress || queryIndex >= epgQueryNames.size) {
            return false
        }
        val fallbackUrl = LiveEpgParser.buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames[0], date, timeFormat)
        LOG.i("echo-epg fallback default address")
        requestEpg(fallbackUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, epgQueryNames.size)
        return true
    }

    private fun requestNextEpgQueryName(
        date: Date,
        channelNameReal: String,
        finalEpgTagName: String,
        savedEpgKey: String,
        epgQueryNames: ArrayList<String>,
        timeFormat: SimpleDateFormat,
        queryIndex: Int,
    ): Boolean {
        if (!LiveEpgParser.isTemplateEpgAddress(epgStringAddress) || queryIndex + 1 >= epgQueryNames.size) {
            return false
        }
        val nextIndex = queryIndex + 1
        val nextUrl = LiveEpgParser.buildEpgUrl(epgStringAddress, epgQueryNames[nextIndex], date, timeFormat)
        LOG.i("echo-epg retry query name:" + epgQueryNames[nextIndex])
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex)
        return true
    }

    private fun isCurrentEpgRequest(savedEpgKey: String): Boolean {
        val channel = channelName ?: return false
        return savedEpgKey == channel.channelName + "_" + epgDayPresented
    }

    // ============================================================
    // 时移回看 URL 构建(1:1 移植)
    // ============================================================

    private fun currentChannelHasCatchup(): Boolean {
        return currentLiveChannelItem != null && LiveEpgParser.hasCatchupSource(currentLiveChannelItem?.channelCatchup)
    }

    private fun currentCatchup(): JsonObject? {
        if (currentChannelHasCatchup()) return currentLiveChannelItem!!.channelCatchup
        return catchup
    }

    internal fun canCurrentChannelCatchup(): Boolean {
        val item = currentLiveChannelItem ?: return false
        val url = item.url
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            val regex = LiveEpgParser.getCatchupValue(catchupObj, "regex")
            if (TextUtils.isEmpty(regex)) return true
            return try {
                url.contains(regex) || Pattern.compile(regex).matcher(url).find()
            } catch (ignored: Throwable) {
                false
            }
        }
        return url.contains("/PLTV/")
    }

    private fun buildCatchupUrl(url: String, epg: Epginfo?): String {
        if (TextUtils.isEmpty(url) || epg == null || epg.startdateTime == null || epg.enddateTime == null) return ""
        val catchupObj = currentCatchup()
        if (LiveEpgParser.hasCatchupSource(catchupObj)) {
            return LiveEpgParser.formatCatchupUrl(url, catchupObj!!, epg)
        }
        if (!url.contains("/PLTV/")) return ""
        val source = "?playseek=" + LiveEpgParser.formatCatchupTime(epg.startdateTime!!, "yyyyMMddHHmmss") +
                "-" + LiveEpgParser.formatCatchupTime(epg.enddateTime!!, "yyyyMMddHHmmss")
        return LiveEpgParser.appendCatchupUrl(url, "/PLTV/,/TVOD/", source)
    }

    // ============================================================
    // EPG/回看:纯解析已抽到同包 LiveEpgParser(可在纯 JVM 单测里直接调用)
    // 本文件保留页面态、网络编排与代际校验
    // ============================================================

    // ============================================================
    // 频道列表行数据与密码态查询(供 Compose 侧消费)
    // ============================================================

    internal fun buildChannelRows(): List<LiveListRow> {
        val rows = ArrayList<LiveListRow>()
        for (group in liveChannelGroupList) {
            rows.add(LiveListRow(group, null, -1, "g" + group.groupIndex))
            if (expandedGroups.contains(group.groupIndex)) {
                val channels = getLiveChannels(group.groupIndex)
                if (channels != null) {
                    for (i in channels.indices) {
                        rows.add(LiveListRow(group, channels[i], i, "c" + group.groupIndex + "_" + channels[i].channelIndex))
                    }
                }
            }
        }
        return rows
    }

    fun isPasswordConfirmedForUi(groupIndex: Int): Boolean = isPasswordConfirmed(groupIndex)

    // Compose UI 已拆到同包 LiveScreens.kt:setContent 里调用 LiveScreen(this)
}
