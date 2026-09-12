@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.github.tvbox.osc.player.controller.ComposeLiveController
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.cardContainer
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
import com.orhanobut.hawk.Hawk
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import xyz.doikki.videoplayer.exo.ExoMediaSourceHelper
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import java.io.StringReader
import java.net.URLEncoder
import java.text.ParseException
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
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

/**
 * 直播页(avbox-mobile-ui-spec §4.5,Step 5 Compose 重写):
 * 竖屏 16:9 播放器(MyVideoView + ComposeLiveController,AndroidView 包壳)
 * → 频道信息区(频道号/名称/直播或回看/线路/当前与下个节目)
 * → 频道分组折叠列表(点选换台,密码锁分组走旧 LivePasswordDialog)。
 * EPG 节目单与直播设置均为 bottom sheet;数字选台与全部 DPAD/MENU/INFO 逻辑随 TV 代码删除。
 * 全屏 = 点播放器进横屏沉浸(同详情页);左右快滑切上一/下一频道(§4.5)。
 * EPG 加载/解析(JSON+XML)、时移回看 URL 构建、自动换源状态机等业务逻辑自旧 Java 版 1:1 移植。
 */
class LivePlayActivity : BaseActivity() {

    companion object {
        private const val TAG = "LivePlayActivity"
        private const val EPG_LOAD_DELAY = 1200L
        private const val RESOLUTION_INFO_MAX_RETRY = 10
        private const val RESOLUTION_INFO_RETRY_DELAY = 300L
        private const val RESOLUTION_INFO_HIDE_DELAY = 3000L
        private const val OVERLAY_HIDE_DELAY = 6000L // 旧 postTimeout
        private const val CONNECT_TIMEOUT_SWITCH_DELAY = 3500L
        private const val DEFAULT_EPG_ADDRESS = "http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}"
        private val CATCHUP_TOKEN_PATTERN = Pattern.compile("(\\$?\\{[^}]*\\})")
        private val CATCHUP_TAG_PATTERN = Pattern.compile("\\{([^}]*)\\}")
        private val FORMAT_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val FORMAT_DATE1 = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    private enum class PageState { LOADING, EMPTY, READY }

    // ============================================================
    // Compose 状态
    // ============================================================

    private var pageState by mutableStateOf(PageState.LOADING)
    private var playState by mutableStateOf(VideoView.STATE_IDLE)
    private var snapshotVisible by mutableStateOf(false)
    private var snapshotBitmap by mutableStateOf<Bitmap?>(null)
    private var fullScreen by mutableStateOf(false)
    private var overlayVisible by mutableStateOf(false)
    private var isBackState by mutableStateOf(false) // 旧 isBack(回看中)
    private var epgSheetVisible by mutableStateOf(false)
    private var settingsSheetVisible by mutableStateOf(false)
    // 频道分组密码弹窗目标:(groupIndex, liveChannelIndex),null=隐藏(Compose 版,替代旧 LivePasswordDialog)
    private var passwordDialogTarget by mutableStateOf<Pair<Int, Int>?>(null)
    private var settingsVersion by mutableIntStateOf(0)
    private var channelVersion by mutableIntStateOf(0)
    private var epgVersion by mutableIntStateOf(0)
    private var scrollTick by mutableIntStateOf(0)
    private var resolutionText by mutableStateOf("")
    private var resolutionVisible by mutableStateOf(false)
    private var showTimeOn by mutableStateOf(false)
    private var showNetSpeedOn by mutableStateOf(false)
    private var timeText by mutableStateOf("")
    private var netSpeedText by mutableStateOf("")
    private var gestureHintText by mutableStateOf<String?>(null)
    private var tsPosition by mutableIntStateOf(0)
    private var tsDuration by mutableIntStateOf(0)
    private var channelInfoUi by mutableStateOf(ChannelInfoUi())
    private val expandedGroups = mutableStateListOf<Int>()

    private data class ChannelInfoUi(
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

    private var mVideoView: MyVideoView? = null
    private val mHandler = Handler(Looper.getMainLooper())
    private val liveChannelGroupList = ArrayList<LiveChannelGroup>()
    private var currentChannelGroupIndex = 0
    private var currentLiveChannelIndex = -1
    private var currentLiveLookBackIndex = -1
    private var currentLiveChangeSourceTimes = 0
    private var allowLiveSwitchPlayer = true
    private var currentLiveChannelItem: LiveChannelItem? = null
    private var pendingLiveRefreshChannelName: String? = null
    private var pendingLiveRefreshSourceIndex = -1
    private var refreshingLiveChannelList = false
    private var liveConfigRequestId = 0
    private val livePlayerManager = LivePlayerManager()
    private val channelGroupPasswordConfirmed = ArrayList<Int>()
    private var channelName: LiveChannelItem? = null // 旧 channel_Name
    private val hsEpg = Hashtable<String, ArrayList<Epginfo>>()
    private var epgdata = ArrayList<Epginfo>()
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
        enableEdgeToEdge()
        // 播放器与状态栏均为纯黑,状态栏图标强制白色(§3/Step 4 定稿⑤)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
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
        Hawk.put(HawkConfig.NOW_DATE, FORMAT_DATE.format(Date()))
        nowday = Date()
        epgDayPresented = FORMAT_DATE1.format(nowday)
        initVideoView()
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, true)
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
        exitingLivePlay = false
        mVideoView?.resume()
    }

    override fun onPause() {
        super.onPause()
        if (!exitingLivePlay) mVideoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        Hawk.put(HawkConfig.PLAYER_IS_LIVE, false)
        hideSwitchChannelSnapshot()
        mVideoView?.release()
        mVideoView = null
        mHandler.removeCallbacksAndMessages(null)
    }

    // ============================================================
    // 播放器与控制层
    // ============================================================

    private fun initVideoView() {
        val controller = ComposeLiveController(this)
        controller.setListener(liveControlListener)
        val view = MyVideoView(this)
        view.setVideoController(controller)
        view.setProgressManager(null)
        mVideoView = view
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
                    (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1) + 1) * 5000L,
                )
            }
            else -> LOG.i("echo-Unexpected live_play state: $state")
        }
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
            overlayVisible = true
            scheduleOverlayHide()
            super.hideSysBar()
        } else {
            overlayVisible = false
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = false
        }
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
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem?.channelName ?: "")
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
                videoView.release()
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
            val next = getNextChannel(if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)) -1 else 1)
            playChannel(next[0], next[1], false)
        } else {
            playNextSource()
        }
    }

    private fun switchLivePlayerAndReplay(): Boolean {
        val videoView = mVideoView
        // 取局部变量(2026-09-12 加固):currentLiveChannelItem 是 var,判空后无法 smart-cast,
        // 原写法在下面用 !! 取值;改为一次取值,后续不再依赖字段中途不变
        val item = currentLiveChannelItem ?: return false
        if (!allowLiveSwitchPlayer || videoView == null) {
            return false
        }
        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun)
        videoView.release()
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
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
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
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
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

    private fun onPasswordConfirmed(password: String) {
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

    private fun loadLiveConfigOnEnter() {
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
                    // (2026-09-12 修复竞态:loadLives 会对 ApiConfig.liveChannelGroupList 做 clear/add,
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
                    return response.body?.string() ?: ""
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
        val lastChannelName = pendingLiveRefreshChannelName ?: Hawk.get(HawkConfig.LIVE_CHANNEL, "")
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
        if (releasePlayer) mVideoView?.release()
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

    // ============================================================
    // 设置(旧 7 组:线路/画面比例/播放解码/超时换源/偏好/多源/配置)
    // ============================================================

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
        (liveSettingGroupList.getOrNull(0) as? LiveSettingGroup)?.liveSettingItems = items
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

    private fun openSettingsSheet() {
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
            3 -> Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)
            5 -> ApiConfig.getLiveGroupIndex()
            6 -> getCurrentLiveConfigIndex()
            else -> -1
        }
    }

    /**
     * 配置切换历史:长按删除(2026-09-12 方案 2,用户定稿)。
     * 删除订阅源不会联动清理直播配置历史(LIVE_API_HISTORY 独立持久化、只增不减),
     * 由用户手动管理;当前使用中的配置(LIVE_API_URL)不可删除。
     */
    fun removeLiveConfigHistory(itemIndex: Int) {
        val history = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        if (itemIndex < 0 || itemIndex >= history.size) return
        if (history[itemIndex] == Hawk.get(HawkConfig.LIVE_API_URL, "")) {
            Toast.makeText(this, "当前使用中的配置不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        history.removeAt(itemIndex)
        Hawk.put(HawkConfig.LIVE_API_HISTORY, history)
        ApiConfig.get().refreshLiveApiHistoryItems()
        settingsVersion++
        Toast.makeText(this, "已从历史中删除", Toast.LENGTH_SHORT).show()
    }

    fun settingChecked(position: Int): Boolean {
        return when (position) {
            0 -> Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)
            1 -> Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
            2 -> Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false)
            3 -> Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)
            else -> false
        }
    }

    /**
     * 直播设置「配置切换」组的选中项(2026-09-12 点播/直播拆分):
     * 第 0 项 = 合成的「跟随点播源」;其后为直播配置历史,历史第 i 项在该组里的 itemIndex = i + 1。
     */
    private fun getCurrentLiveConfigIndex(): Int {
        if (ApiConfig.isLiveFollowVod()) return 0
        val history = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
        val index = history.indexOf(Hawk.get(HawkConfig.LIVE_API_URL, ""))
        return if (index < 0) -1 else index + 1
    }

    private fun clickSettingItem(groupIndex: Int, position: Int) {
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
                videoView.release()
                livePlayerManager.changeLivePlayerType(videoView, position)
                currentLiveChannelItem?.let { videoView.setUrl(it.url, liveChannelHeader()) }
                videoView.start()
            }
            3 -> { // 超时换源
                if (position == Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 1)) return
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position)
            }
            4 -> { // 偏好设置
                when (position) {
                    0 -> Hawk.put(HawkConfig.LIVE_SHOW_TIME, !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)).also { showTime() }
                    1 -> Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)).also { showNetSpeed() }
                    2 -> Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                    3 -> Hawk.put(HawkConfig.LIVE_CROSS_GROUP, !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false))
                }
            }
            5 -> { // 多源切换
                if (position == ApiConfig.getLiveGroupIndex()) return
                val currentChannelName = getPreferredLiveRefreshChannelName()
                val currentSourceIndex = getPreferredLiveRefreshSourceIndex()
                val liveGroups = Hawk.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
                if (liveGroups == null || position >= liveGroups.size()) return
                liveConfigRequestId++
                val livesOBJ = liveGroups.get(position).asJsonObject
                ApiConfig.setLiveGroupIndex(position)
                ApiConfig.get().loadLiveApi(livesOBJ)
                if (ApiConfig.get().channelGroupList.isEmpty()) {
                    mVideoView?.release()
                    setEmptyLiveChannelList(false)
                    return
                }
                refreshLiveChannelListAndPlay(currentChannelName, currentSourceIndex)
            }
            6 -> { // 配置切换:第 0 项 =「跟随点播源」,其后为直播配置历史
                val history = Hawk.get(HawkConfig.LIVE_API_HISTORY, ArrayList<String>())
                val target: String
                if (position == 0) {
                    if (ApiConfig.isLiveFollowVod()) return
                    target = "" // 空 = 跟随当前点播源
                } else {
                    if (position - 1 >= history.size) return
                    target = history[position - 1]
                    if (target == Hawk.get(HawkConfig.LIVE_API_URL, "")) return
                }
                val configChannelName = getPreferredLiveRefreshChannelName()
                val configSourceIndex = getPreferredLiveRefreshSourceIndex()
                val requestId = ++liveConfigRequestId
                Hawk.put(HawkConfig.LIVE_API_URL, target)
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
                            mVideoView?.release()
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
        return Hawk.get(HawkConfig.LIVE_CHANNEL, "")
    }

    private fun getPreferredLiveRefreshSourceIndex(): Int {
        currentLiveChannelItem?.let { return it.sourceIndex }
        return -1
    }

    private fun liveWebHeader(): HashMap<String, String>? {
        return Hawk.get(HawkConfig.LIVE_WEB_HEADER)
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
        val liveGroups = Hawk.get(HawkConfig.LIVE_GROUP_LIST, JsonArray())
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

    private fun onEpgRowClicked(position: Int) {
        if (position == currentLiveLookBackIndex) return
        val selectedData = epgdata.getOrNull(position) ?: return
        if (selectedData.startdateTime == null || selectedData.enddateTime == null) return
        val now = Date()
        if (now.before(selectedData.startdateTime)) return
        if (now.after(selectedData.enddateTime) && !canCurrentChannelCatchup()) return
        currentLiveLookBackIndex = position
        if (!now.before(selectedData.startdateTime) && !now.after(selectedData.enddateTime)) {
            // 正在播出 → 回直播
            backToLiveFromEpg()
            epgSheetVisible = false
        } else if (canCurrentChannelCatchup()) {
            startCatchupReplay(selectedData)
            epgSheetVisible = false
        }
        epgVersion++
    }

    private fun startCatchupReplay(epg: Epginfo) {
        val item = currentLiveChannelItem ?: return
        val videoView = mVideoView ?: return
        videoView.release()
        isSHIYI = true
        val shiyiUrl = buildCatchupUrl(item.url, epg)
        if (TextUtils.isEmpty(shiyiUrl)) return
        LOG.i("echo-回看地址playUrl :$shiyiUrl")
        playUrl = shiyiUrl
        videoView.setUrl(playUrl, liveChannelHeader())
        videoView.start()
        shiyiTimeC = getCatchupDurationSeconds(epg)
        tsDuration = PlayerUtils.safeTimeMs(shiyiTimeC.toLong() * 1000)
        tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition.toLong())
        // 时移条每秒跟随播放前进(2026-09-13 修复:此前该 Runnable 从未被 post,
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
        videoView.release()
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
            tsPosition = PlayerUtils.safeTimeMs(videoView.currentPosition.toLong())
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
        showTimeOn = Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)
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
        showNetSpeedOn = Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)
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

    private fun getFirstPartBeforeSpace(str: String?): String? {
        if (str.isNullOrEmpty()) return str
        val spaceIndex = str.indexOf(' ')
        return if (spaceIndex == -1) str else str.substring(0, spaceIndex)
    }

    fun getEpg(date: Date) {
        val channel = channelName ?: return
        val channelNameStr = channel.channelName ?: return
        val channelNameReal = normalizeEpgChannelName(getFirstPartBeforeSpace(channelNameStr) ?: "")
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
        val epgQueryNames = buildEpgQueryNames(channelNameStr, channelNameReal, epgTagName)
        val url = buildEpgUrl(epgStringAddress, epgQueryNames[0], date, timeFormat)
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

    private fun buildEpgUrl(address: String, epgTagName: String, date: Date, timeFormat: SimpleDateFormat): String {
        return when {
            address.contains("{name}") || address.contains("{date}") ->
                address.replace("{name}", encodeEpgParam(epgTagName)).replace("{date}", timeFormat.format(date))
            isXmlEpgAddress(address) -> address
            else ->
                address + (if (address.contains("?")) "&" else "?") +
                        "ch=" + encodeEpgParam(epgTagName) + "&date=" + timeFormat.format(date)
        }
    }

    private fun encodeEpgParam(value: String?): String {
        return try {
            URLEncoder.encode(value ?: "", "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            value ?: ""
        }
    }

    private fun buildEpgQueryNames(channelName: String, channelNameReal: String, epgTagName: String): ArrayList<String> {
        val queryNames = ArrayList<String>()
        addEpgQueryName(queryNames, epgTagName)
        addEpgQueryName(queryNames, channelNameReal)
        addEpgQueryName(queryNames, normalizeEpgChannelName(getFirstPartBeforeSpace(channelName) ?: ""))
        addEpgQueryName(queryNames, getFirstPartBeforeSpace(channelName))
        addEpgQueryName(queryNames, channelName)
        if (queryNames.isEmpty()) queryNames.add("")
        return queryNames
    }

    private fun addEpgQueryName(queryNames: ArrayList<String>, name: String?) {
        if (name == null) return
        val trimName = name.trim { it <= ' ' }
        if (trimName.isEmpty() || queryNames.contains(trimName)) return
        queryNames.add(trimName)
    }

    private fun getConfiguredEpgAddress(): String {
        val userEpgAddress: String = Hawk.get(HawkConfig.EPG_URL, "")
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
                    response.body?.string() ?: ""
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
            if (isXmlEpgResponse(paramString)) {
                arrayList = parseXmlEpg(paramString, finalEpgTagName, date)
            } else if (paramString.contains("epg_data") || paramString.trim { it <= ' ' }.startsWith("{")) {
                arrayList = parseJsonEpg(paramString, date)
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
        val fallbackUrl = buildEpgUrl(DEFAULT_EPG_ADDRESS, epgQueryNames[0], date, timeFormat)
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
        if (!isTemplateEpgAddress(epgStringAddress) || queryIndex + 1 >= epgQueryNames.size) {
            return false
        }
        val nextIndex = queryIndex + 1
        val nextUrl = buildEpgUrl(epgStringAddress, epgQueryNames[nextIndex], date, timeFormat)
        LOG.i("echo-epg retry query name:" + epgQueryNames[nextIndex])
        requestEpg(nextUrl, date, channelNameReal, finalEpgTagName, savedEpgKey, epgQueryNames, timeFormat, nextIndex)
        return true
    }

    private fun isTemplateEpgAddress(address: String?): Boolean {
        return address != null && (address.contains("{name}") || address.contains("{date}"))
    }

    private fun isCurrentEpgRequest(savedEpgKey: String): Boolean {
        val channel = channelName ?: return false
        return savedEpgKey == channel.channelName + "_" + epgDayPresented
    }

    private fun isXmlEpgAddress(address: String?): Boolean {
        if (address == null) return false
        var lowerAddress = address.lowercase(Locale.ROOT)
        val queryIndex = lowerAddress.indexOf("?")
        if (queryIndex >= 0) {
            lowerAddress = lowerAddress.substring(0, queryIndex)
        }
        return lowerAddress.endsWith(".xml")
    }

    private fun isXmlEpgResponse(response: String?): Boolean {
        if (response == null) return false
        val trimResponse = response.trim { it <= ' ' }
        return trimResponse.startsWith("<?xml") || trimResponse.startsWith("<tv") || trimResponse.contains("<programme")
    }

    private fun parseJsonEpg(response: String, date: Date): ArrayList<Epginfo> {
        val epgList = ArrayList<Epginfo>()
        val jsonObject = JSONObject(response)
        val channelNameStr = jsonObject.optString("channel_name", jsonObject.optString("channel", ""))
        if (isUnavailableEpgText(channelNameStr)) {
            return epgList
        }
        val epgArray = findJsonEpgArray(jsonObject) ?: return epgList
        for (i in 0 until epgArray.length()) {
            val item = epgArray.optJSONObject(i) ?: continue
            val title = cleanEpgTitle(item.optString("title", item.optString("name", "")))
            if (TextUtils.isEmpty(title) || isUnavailableEpgText(title)) continue
            val startText = item.optString("start", item.optString("start_time", item.optString("starttime", "")))
            val endText = item.optString("end", item.optString("end_time", item.optString("endtime", "")))
            val startDate = parseJsonEpgDate(date, startText)
            val endDate = parseJsonEpgDate(date, endText)
            if (startDate == null || endDate == null) continue
            var fixedEnd = endDate
            if (!fixedEnd.after(startDate)) {
                fixedEnd = Date(fixedEnd.time + TimeUnit.DAYS.toMillis(1))
            }
            epgList.add(createXmlEpgInfo(date, title, startDate, fixedEnd, epgList.size))
        }
        return epgList
    }

    private fun findJsonEpgArray(jsonObject: JSONObject): JSONArray? {
        var epgArray = jsonObject.optJSONArray("epg_data")
        if (epgArray != null) return epgArray
        epgArray = jsonObject.optJSONArray("data")
        if (epgArray != null) return epgArray
        epgArray = jsonObject.optJSONArray("list")
        if (epgArray != null) return epgArray
        val dataObject = jsonObject.optJSONObject("data")
        if (dataObject != null) {
            epgArray = dataObject.optJSONArray("epg_data")
            if (epgArray != null) return epgArray
            epgArray = dataObject.optJSONArray("list")
        }
        return epgArray
    }

    private fun parseJsonEpgDate(date: Date, timeText: String?): Date? {
        if (timeText.isNullOrEmpty() || timeText.trim { it <= ' ' }.isEmpty()) return null
        val trimText = timeText.trim { it <= ' ' }
        for (pattern in arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try {
                val dateFormat = SimpleDateFormat(pattern, Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
                return dateFormat.parse(trimText)
            } catch (ignored: ParseException) {
            }
        }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        dayFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        val dayText = dayFormat.format(date)
        for (pattern in arrayOf("HH:mm:ss", "HH:mm")) {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd $pattern", Locale.getDefault())
                dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
                return dateFormat.parse("$dayText $trimText")
            } catch (ignored: ParseException) {
            }
        }
        return null
    }

    private fun cleanEpgTitle(title: String?): String {
        if (title == null) return ""
        return title.replace(" --免费使用", "").replace("--免费使用", "").trim { it <= ' ' }
    }

    private fun isUnavailableEpgText(text: String?): Boolean {
        return text != null && (text.contains("未提供") || text.contains("暂无"))
    }

    private fun normalizeEpgChannelName(channelName: String?): String {
        if (channelName == null) return ""
        val trimName = channelName.trim { it <= ' ' }
        val compactName = trimName.replace("-", "").replace(" ", "")
        val cctvMatcher = Pattern.compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|$)").matcher(compactName)
        if (cctvMatcher.matches()) {
            return cctvMatcher.group(1).uppercase(Locale.ROOT)
        }
        if (compactName.uppercase(Locale.ROOT).startsWith("CCTV")) {
            return compactName.uppercase(Locale.ROOT)
        }
        return trimName
    }

    private fun parseXmlEpg(xml: String, channelName: String, date: Date): ArrayList<Epginfo> {
        val epgList = ArrayList<Epginfo>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isIgnoringComments = true
            factory.isCoalescing = true
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            } catch (ignored: Exception) {
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document: Document = builder.parse(InputSource(StringReader(xml)))
            document.documentElement.normalize()

            val targetName = normalizeEpgChannelName(channelName)
            val channelIds = ArrayList<String>()
            val channelNodes = document.getElementsByTagName("channel")
            for (i in 0 until channelNodes.length) {
                val channelNode = channelNodes.item(i)
                if (channelNode.nodeType != Node.ELEMENT_NODE) continue
                val channelElement = channelNode as Element
                val channelId = channelElement.getAttribute("id")
                if (targetName == normalizeEpgChannelName(channelId)) {
                    channelIds.add(channelId)
                    continue
                }
                val displayNameNodes = channelElement.getElementsByTagName("display-name")
                for (j in 0 until displayNameNodes.length) {
                    val displayName = displayNameNodes.item(j).textContent
                    if (targetName == normalizeEpgChannelName(displayName)) {
                        channelIds.add(channelId)
                        break
                    }
                }
            }

            val dayStart = getDayStart(date)
            val dayEnd = Date(dayStart.time + TimeUnit.DAYS.toMillis(1))
            val programmeNodes = document.getElementsByTagName("programme")
            for (i in 0 until programmeNodes.length) {
                val programmeNode = programmeNodes.item(i)
                if (programmeNode.nodeType != Node.ELEMENT_NODE) continue
                val programmeElement = programmeNode as Element
                val programmeChannel = programmeElement.getAttribute("channel")
                if (!channelIds.contains(programmeChannel) && targetName != normalizeEpgChannelName(programmeChannel)) {
                    continue
                }
                val startDate = parseXmlTvDate(programmeElement.getAttribute("start"))
                val endDate = parseXmlTvDate(programmeElement.getAttribute("stop"))
                if (startDate == null || endDate == null || !endDate.after(startDate)) continue
                if (!startDate.before(dayEnd) || !endDate.after(dayStart)) continue
                var title = ""
                val titleNodes = programmeElement.getElementsByTagName("title")
                if (titleNodes.length > 0) {
                    title = titleNodes.item(0).textContent
                }
                epgList.add(createXmlEpgInfo(date, title, startDate, endDate, epgList.size))
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
        return epgList
    }

    private fun getDayStart(date: Date): Date {
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        dayFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
        return dayFormat.parse(dayFormat.format(date)) ?: date
    }

    private fun parseXmlTvDate(dateText: String?): Date? {
        if (dateText.isNullOrEmpty() || dateText.trim { it <= ' ' }.isEmpty()) return null
        val trimDate = dateText.trim { it <= ' ' }
        try {
            return SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault()).parse(trimDate)
        } catch (ignored: ParseException) {
        }
        try {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
            return dateFormat.parse(trimDate)
        } catch (ignored: ParseException) {
        }
        return null
    }

    private fun createXmlEpgInfo(epgDate: Date, title: String, startDate: Date, endDate: Date, index: Int): Epginfo {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val epgInfo = Epginfo(epgDate, title, epgDate, timeFormat.format(startDate), timeFormat.format(endDate), index)
        epgInfo.startdateTime = startDate
        epgInfo.enddateTime = endDate
        epgInfo.start = timeFormat.format(startDate)
        epgInfo.end = timeFormat.format(endDate)
        epgInfo.originStart = epgInfo.start
        epgInfo.originEnd = epgInfo.end
        epgInfo.datestart = epgInfo.start.replace(":", "").toInt()
        epgInfo.dateend = epgInfo.end.replace(":", "").toInt()
        return epgInfo
    }

    // ============================================================
    // 时移回看 URL 构建(1:1 移植)
    // ============================================================

    private fun currentChannelHasCatchup(): Boolean {
        return currentLiveChannelItem != null && hasCatchupSource(currentLiveChannelItem?.channelCatchup)
    }

    private fun currentCatchup(): JsonObject? {
        if (currentChannelHasCatchup()) return currentLiveChannelItem!!.channelCatchup
        return catchup
    }

    private fun getCatchupValue(catchupObj: JsonObject?, key: String): String {
        if (catchupObj == null || !catchupObj.has(key) || catchupObj.get(key).isJsonNull) return ""
        return try {
            catchupObj.get(key).asString
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun hasCatchupSource(catchupObj: JsonObject?): Boolean {
        return getCatchupValue(catchupObj, "source").isNotEmpty()
    }

    private fun canCurrentChannelCatchup(): Boolean {
        val item = currentLiveChannelItem ?: return false
        val url = item.url
        val catchupObj = currentCatchup()
        if (hasCatchupSource(catchupObj)) {
            val regex = getCatchupValue(catchupObj, "regex")
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
        if (hasCatchupSource(catchupObj)) {
            return formatCatchupUrl(url, catchupObj!!, epg)
        }
        if (!url.contains("/PLTV/")) return ""
        val source = "?playseek=" + formatCatchupTime(epg.startdateTime!!, "yyyyMMddHHmmss") +
                "-" + formatCatchupTime(epg.enddateTime!!, "yyyyMMddHHmmss")
        return appendCatchupUrl(url, "/PLTV/,/TVOD/", source)
    }

    private fun formatCatchupUrl(url: String, catchupObj: JsonObject, epg: Epginfo): String {
        val source = formatCatchupSource(getCatchupValue(catchupObj, "source"), epg)
        if ("default".equals(getCatchupValue(catchupObj, "type"), ignoreCase = true)) return source
        return appendCatchupUrl(url, getCatchupValue(catchupObj, "replace"), source)
    }

    private fun appendCatchupUrl(url: String, replace: String, source: String): String {
        var replayUrl = url
        var finalSource = source
        val parts = replace.split(",".toRegex(), 2).toTypedArray()
        if (parts.size == 2 && parts[0].isNotEmpty()) {
            try {
                replayUrl = replayUrl.replace(parts[0].toRegex(), parts[1])
            } catch (ignored: Throwable) {
            }
        }
        val queryIndex = replayUrl.indexOf('?')
        if (queryIndex >= 0 && queryIndex < replayUrl.length - 1) finalSource = finalSource.replace("?", "&")
        return replayUrl + finalSource
    }

    private fun formatCatchupSource(source: String, epg: Epginfo): String {
        val matcher = CATCHUP_TOKEN_PATTERN.matcher(source)
        val result = StringBuffer()
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(formatCatchupToken(matcher.group(1), epg)))
        }
        matcher.appendTail(result)
        return result.toString()
    }

    private fun formatCatchupToken(token: String, epg: Epginfo): String {
        val matcher = CATCHUP_TAG_PATTERN.matcher(token)
        if (!matcher.find()) return ""
        val tag = matcher.group(1)
        if (tag.startsWith("utcend:")) return (epg.enddateTime!!.time / 1000).toString()
        if (tag.startsWith("utc:")) return (epg.startdateTime!!.time / 1000).toString()
        val bracketIndex = tag.indexOf(')')
        if (tag.startsWith("(b") && bracketIndex >= 0) return formatCatchupTime(epg.startdateTime!!, tag.substring(bracketIndex + 1))
        if (tag.startsWith("(e") && bracketIndex >= 0) return formatCatchupTime(epg.enddateTime!!, tag.substring(bracketIndex + 1))
        return ""
    }

    private fun formatCatchupTime(time: Date, pattern: String): String {
        if ("timestamp" == pattern) return (time.time / 1000).toString()
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(time)
        } catch (ignored: IllegalArgumentException) {
            ""
        }
    }

    private fun getCatchupDurationSeconds(epg: Epginfo?): Int {
        if (epg == null || epg.startdateTime == null || epg.enddateTime == null) return 0
        val duration = max(0L, epg.enddateTime!!.time - epg.startdateTime!!.time) / 1000
        return if (duration > Int.MAX_VALUE) Int.MAX_VALUE else duration.toInt()
    }

    // ============================================================
    // 工具
    // ============================================================

    private fun durationToString(duration: Int): String {
        val dur = max(duration, 0) / 1000
        val hour = dur / 3600
        val min = dur / 60 % 60
        val sec = dur % 60
        return if (hour > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hour, min, sec)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }
    }

    // ============================================================
    // Compose UI
    // ============================================================

    @Composable
    private fun LiveScreen(activity: LivePlayActivity) {
        val background = if (activity.fullScreen) Color.Black else MaterialTheme.colorScheme.surfaceContainer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background),
        ) {
            when (activity.pageState) {
                PageState.LOADING -> LoadStateBox(
                    state = LoadState.Loading,
                    emptyText = "",
                    errorText = "",
                    retryText = "",
                    modifier = Modifier.fillMaxSize(),
                    // 播放器页:加载指示保持 48dp(页面级 64dp 定稿的例外,spec §6)
                    loadingContent = { ContainedLoadingIndicator(Modifier.size(48.dp)) },
                )

                PageState.EMPTY -> LoadStateBox(
                    state = LoadState.Error("暂无直播频道,请检查直播配置"),
                    emptyText = "",
                    errorText = "暂无直播频道,请检查直播配置",
                    retryText = "重试",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { activity.loadLiveConfigOnEnter() },
                )

                PageState.READY -> LiveReadyContent(activity)
            }
            if (activity.epgSheetVisible) EpgSheet(activity)
            if (activity.settingsSheetVisible) SettingsSheet(activity)
            activity.passwordDialogTarget?.let {
                LivePasswordDialog(
                    onConfirm = { activity.onPasswordConfirmed(it) },
                    onDismiss = { activity.passwordDialogTarget = null },
                )
            }
        }
    }

    /**
     * 频道分组密码弹窗(替代旧 View 版 LivePasswordDialog + dialog_live_password.xml):
     * 密码为空时确定按钮禁用,逻辑与旧版一致。
     */
    @Composable
    private fun LivePasswordDialog(
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("请输入密码") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("频道分组密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (password.isNotBlank()) onConfirm(password.trim()) },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotBlank(),
                    onClick = { onConfirm(password.trim()) },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    }

    @Composable
    private fun LiveReadyContent(activity: LivePlayActivity) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerArea(
                activity = activity,
                modifier = if (activity.fullScreen) {
                    Modifier.fillMaxSize()
                } else {
                    // 状态栏区域纯黑（背景画在 statusBarsPadding 外圈），播放器紧贴其下（对齐详情页补丁⑤）
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .statusBarsPadding()
                        .aspectRatio(16f / 9f)
                },
            )
            if (!activity.fullScreen) {
                ChannelInfoSection(activity)
                ChannelListSection(activity, Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun PlayerArea(activity: LivePlayActivity, modifier: Modifier) {
        val videoView = activity.mVideoView
        Box(modifier = modifier.background(Color.Black)) {
            if (videoView != null) {
                AndroidView(
                    factory = { videoView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 切台快照(旧 switchChannelSnapshotOverlay)
            if (activity.snapshotVisible) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    activity.snapshotBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                }
            }
            // 加载态
            if (!activity.snapshotVisible &&
                (activity.playState == VideoView.STATE_PREPARING || activity.playState == VideoView.STATE_BUFFERING)
            ) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
            }
            // 清晰度角标
            if (activity.resolutionVisible && activity.resolutionText.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = activity.resolutionText,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            // 亮度/音量指示
            activity.gestureHintText?.let { hint ->
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = hint,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            // 时移条(回看中)
            if (activity.isBackState && activity.overlayVisible) {
                TimeshiftBar(activity, Modifier.align(Alignment.BottomCenter))
            }
            // 竖屏:常驻角标入口(节目单/设置)
            if (!activity.fullScreen) {
                PlayerCornerButtons(activity, Modifier.align(Alignment.TopEnd))
            } else if (activity.overlayVisible) {
                // 全屏:返回按钮(浮层随交互显隐)
                IconButton(
                    onClick = { activity.applyFullscreen(false) },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "退出全屏",
                        tint = Color.White,
                    )
                }
                PlayerCornerButtons(activity, Modifier.align(Alignment.TopEnd))
            }
        }
    }

    @Composable
    private fun PlayerCornerButtons(activity: LivePlayActivity, modifier: Modifier) {
        Row(modifier = modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerCornerButton(
                icon = { Icon(Icons.Filled.Event, contentDescription = "节目单", tint = Color.White, modifier = Modifier.size(20.dp)) },
                onClick = { activity.epgSheetVisible = true },
            )
            PlayerCornerButton(
                icon = { Icon(Icons.Filled.Settings, contentDescription = "直播设置", tint = Color.White, modifier = Modifier.size(20.dp)) },
                onClick = { activity.openSettingsSheet() },
            )
        }
    }

    @Composable
    private fun PlayerCornerButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.4f),
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        ) {
            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon() }
        }
    }

    @Composable
    private fun TimeshiftBar(activity: LivePlayActivity, modifier: Modifier) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                // 2026-09-10 用户定稿:回看时移条去除半透明黑底,直接叠在画面上
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity.onTimeshiftTogglePlay() }) {
                Icon(
                    imageVector = if (activity.playState == VideoView.STATE_PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = "播放/暂停",
                    tint = Color.White,
                )
            }
            Slider(
                value = activity.tsPosition.toFloat().coerceIn(0f, max(activity.tsDuration, 1).toFloat()),
                onValueChange = { activity.onTimeshiftSeek(it) },
                valueRange = 0f..max(activity.tsDuration, 1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = activity.durationToString(activity.tsPosition) + " / " + activity.durationToString(activity.tsDuration),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    @Composable
    private fun ChannelInfoSection(activity: LivePlayActivity) {
        val info = activity.channelInfoUi
        if (info.name.isEmpty()) return
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = info.num.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = info.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (activity.isBackState) {
                    Text(text = "回看中", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Text(text = "直播中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (info.sourceText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = info.sourceText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = info.currentEpgTime + "  " + info.currentEpgTitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = info.nextEpgTime + "  " + info.nextEpgTitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (activity.showTimeOn || activity.showNetSpeedOn) {
                Spacer(modifier = Modifier.height(2.dp))
                val parts = ArrayList<String>()
                if (activity.showTimeOn && activity.timeText.isNotEmpty()) parts.add(activity.timeText)
                if (activity.showNetSpeedOn && activity.netSpeedText.isNotEmpty()) parts.add(activity.netSpeedText)
                Text(
                    text = parts.joinToString("  "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ============================================================
    // 频道分组折叠列表
    // ============================================================

    private class LiveListRow(
        val group: LiveChannelGroup?,
        val channel: LiveChannelItem?,
        val channelPos: Int,
        val key: String,
    )

    @Composable
    private fun ChannelListSection(activity: LivePlayActivity, modifier: Modifier) {
        val listState = rememberLazyListState()
        // 频道数据/展开变化时定位到当前频道
        LaunchedEffect(activity.scrollTick, activity.channelVersion) {
            val rows = activity.buildChannelRows()
            var target = -1
            for (i in rows.indices) {
                val row = rows[i]
                if (row.channel != null &&
                    row.group?.groupIndex == activity.currentChannelGroupIndex &&
                    row.channel.channelIndex == activity.currentLiveChannelIndex
                ) {
                    target = i
                    break
                }
            }
            if (target > 0) listState.animateScrollToItem(max(0, target - 2))
        }
        val rows = activity.buildChannelRows()
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                // 频道行同样持有所属 group 引用,必须以 channel 为准区分行类型,
                // 否则展开分组后频道行会全部被渲染成组头(表现为一排重复的分组名)
                val channel = row.channel
                if (channel == null) {
                    val group = row.group ?: return@itemsIndexed
                    GroupHeaderRow(activity, group)
                } else {
                    ChannelRow(activity, row, channel)
                }
            }
        }
    }

    private fun buildChannelRows(): List<LiveListRow> {
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

    @Composable
    private fun GroupHeaderRow(activity: LivePlayActivity, group: LiveChannelGroup) {
        val expanded = activity.expandedGroups.contains(group.groupIndex)
        val locked = group.groupPassword.isNotEmpty() && !activity.isPasswordConfirmedForUi(group.groupIndex)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { activity.toggleChannelGroup(group.groupIndex) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.groupName ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (locked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "需密码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
    }

    fun isPasswordConfirmedForUi(groupIndex: Int): Boolean = isPasswordConfirmed(groupIndex)

    @Composable
    private fun ChannelRow(
        activity: LivePlayActivity,
        row: LiveListRow,
        channel: LiveChannelItem,
    ) {
        val group = row.group ?: return
        val selected = group.groupIndex == activity.currentChannelGroupIndex &&
                channel.channelIndex == activity.currentLiveChannelIndex
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.cardContainer else Color.Transparent)
                .clickable { activity.selectChannel(group.groupIndex, row.channelPos) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.channelNum.toString(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
            Text(
                text = channel.channelName ?: "",
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // ============================================================
    // EPG 节目单 bottom sheet
    // ============================================================

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun EpgSheet(activity: LivePlayActivity) {
        activity.epgVersion // 读取以保证数据变化时刷新
        val channelNameStr = activity.channelName?.channelName ?: ""
        AVBoxBottomSheet(
            onDismissRequest = { activity.epgSheetVisible = false },
            title = if (channelNameStr.isEmpty()) "节目单" else "节目单 · $channelNameStr",
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // 内容自带 LazyColumn(heightIn 520dp),滚动交给它,避免与封装的内容区抢手势
            isScrollable = false,
        ) {
            val epgList = activity.epgdata
            if (epgList.isEmpty()) {
                Text(
                    text = "暂无节目单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
                return@AVBoxBottomSheet
            }
            val canCatchup = activity.canCurrentChannelCatchup()
            val now = Date()
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(bottom = 16.dp)) {
                itemsIndexed(epgList) { index, epg ->
                    val isNow = epg.startdateTime != null && epg.enddateTime != null &&
                            !now.before(epg.startdateTime) && !now.after(epg.enddateTime)
                    val clickable = epg.startdateTime != null && !now.before(epg.startdateTime) &&
                            (canCatchup || (epg.enddateTime != null && !now.after(epg.enddateTime)))
                    val selected = index == activity.currentLiveLookBackIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = clickable) { activity.onEpgRowClicked(index) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = epg.start + "-" + epg.end,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = epg.title,
                            fontSize = 14.sp,
                            fontWeight = if (selected || isNow) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                selected -> MaterialTheme.colorScheme.primary
                                isNow -> MaterialTheme.colorScheme.onSurface
                                clickable -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            selected -> Text(text = "回看中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            isNow -> Text(text = "正在播出", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    // ============================================================
    // 直播设置 bottom sheet
    // ============================================================

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsSheet(activity: LivePlayActivity) {
        activity.settingsVersion // 读取以保证数据变化时刷新
        val groups = activity.visibleSettingGroups()
        AVBoxBottomSheet(
            onDismissRequest = { activity.settingsSheetVisible = false },
            title = "直播设置",
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // 内容自带 LazyColumn(heightIn 560dp),滚动交给它
            isScrollable = false,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                groups.forEach { group ->
                    val items = group.liveSettingItems ?: return@forEach
                    item(key = "sg" + group.groupIndex) {
                        // 配置切换(组6)标注长按删除入口(2026-09-12 方案 2)
                        SettingsGroup(
                            title = if (group.groupIndex == 6) group.groupName + "（长按可删除）" else group.groupName,
                        ) {
                            // 与设置页一致:每项一张小卡,按卡位拼圆角(FIRST/MIDDLE/LAST)
                            items.forEachIndexed { index, item ->
                                val position = when {
                                    items.size == 1 -> SettingsCardPosition.SINGLE
                                    index == 0 -> SettingsCardPosition.FIRST
                                    index == items.size - 1 -> SettingsCardPosition.LAST
                                    else -> SettingsCardPosition.MIDDLE
                                }
                                SettingsCard(
                                    position = position,
                                    color = MaterialTheme.colorScheme.surfaceBright,
                                ) {
                                    if (group.groupIndex == 4) {
                                        SettingsSwitchRow(
                                            title = item.itemName,
                                            checked = activity.settingChecked(item.itemIndex),
                                            onCheckedChange = { activity.clickSettingItem(group.groupIndex, item.itemIndex) },
                                        )
                                    } else {
                                        SettingsOptionRow(
                                            title = item.itemName,
                                            selected = activity.settingSelectedIndex(group.groupIndex) == item.itemIndex,
                                            onClick = { activity.clickSettingItem(group.groupIndex, item.itemIndex) },
                                            // 配置切换历史:长按删除(当前使用中的配置拒绝删除);
                                            // 第 0 项是合成的「跟随点播源」,不参与删除,历史下标需 -1
                                            onLongClick = if (group.groupIndex == 6 && item.itemIndex > 0) {
                                                { activity.removeLiveConfigHistory(item.itemIndex - 1) }
                                            } else {
                                                null
                                            },
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
}
