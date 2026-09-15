package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.SubtitleHelper
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

/** 退出全屏后系统栏过渡(旋转 + 系统栏滑入)耗时,过渡结束后补一次状态栏图标外观断言 */
private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

/**
 * 详情/播放页:顶部 16:9 内嵌播放器,向下依次为标题/简介/清晰度·线路/选集/换源/相关推荐。
 * 全屏 = 横屏沉浸,返回退回竖屏预览。
 */
class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? = null
        private set
    private var fullScreen = false

    /**
     * 本地字幕选择(SAF 系统文件选择器):零权限、Android 13+ 全兼容
     * (旧 ChooserDialog 自检 WRITE_EXTERNAL_STORAGE,该系统上被静默拒绝)。
     * mime 必须通配:字幕扩展名在 SAF 中常被标为 octet-stream,严格过滤会筛掉字幕。
     */
    private val localSubtitlePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) playContainer?.onLocalSubtitlePicked(uri)
    }

    override fun launchLocalSubtitlePicker() {
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
     * 竖屏状态栏区域为纯黑 → 图标恒白。系统 ROM(vivo OriginOS、小米 HyperOS 同类)会在
     * 沉浸退出、横竖屏过渡、回前台时按主题重设图标外观,故这些时机反复断言一次。
     * 导航键图标按应用主题断言:本页 manageStatusBarIcons=false 主题不接管,
     * 而 light() 导航栏样式让 EdgeToEdge 恒设深色图标,深色主题下会压深色内容。
     */
    private fun applyStatusBarAppearance() {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !AppThemeState.isDark(systemDark)
        }
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        applyStatusBarAppearance()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val container = playContainer
                if (fullScreen) {
                    // 菜单展开时第一次返回先收菜单(由控制器消费),菜单收起后再滑才退出全屏
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
                it.setPageHost(this)
                // 首次进页面即为预览态,否则首呼覆盖层会带出全屏的菜单行
                it.setPreviewMode(true)
            }
        }
        return playContainer!!
    }

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

    // ==================== PageHost ====================
    // 播放层经此接口回调本地字幕选择器 / 线路耗尽后的换源兜底,不再依赖具体页面类。

    override fun context(): Context = this

    override fun isPageAlive(): Boolean = !isFinishing && !isDestroyed

    override fun runOnUi(action: Runnable) {
        if (isPageAlive()) runOnUiThread(action)
    }

    override fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun requestNotificationPermission() {
        PermissionHelper.requestNotificationIfNeeded(this)
    }

    override fun onPlaybackLinesExhausted(): Boolean = startDetailFallbackAfterLinesExhausted()

    /** 把当前选中的集投给播放容器(对应旧 jumpToPlay 的下半段) */
    fun playCurrent() {
        val container = playContainer ?: return
        val session = vm.preparePlaySession()
        if (session == null) {
            // 组装不出播放数据:别把"正在切换片源"提示留在播放器上
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
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
            // 同步断言会被系统过渡结束态覆盖,等过渡结束后再兜底一次
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        val container = playContainer
        if (container != null) {
            container.setAutoSwitchLineEnabled(!full)
        }
        // 覆盖层/字幕字号跟随"实际形态",旋转落地后再同步,避免半新半旧
        syncFullBoxSideEffects()
    }

    /** 旋转落地:清过渡态并同步随形态联动的项 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.rotating.value = false
        syncFullBoxSideEffects()
    }

    /**
     * 当前布局形态是否为「全屏铺满」——必须与 [DetailScreen] 的 `fullBox` 同一判定。
     * 过渡期(rotating)跟随当前方向,落地后才切到目标态 [fullScreen];否则横屏窗口里会去算
     * 竖屏的预览盒(高度超屏 → 视频缩放跳动)。系统没下发 onConfigurationChanged 时退化为
     * 当前方向的自然形态,不会卡死。
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

    /** 预览态字幕按 0.6 倍缩放 */
    private fun applySubtitleTextSize(preview: Boolean) {
        var size = SubtitleHelper.getTextSize(this)
        if (preview) size = (size * 0.6).toInt()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, size))
    }

    /** 线路耗尽后由播放容器回调 */
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
