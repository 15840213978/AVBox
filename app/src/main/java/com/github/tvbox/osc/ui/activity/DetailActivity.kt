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
import com.github.tvbox.osc.player.PageHost
import com.github.tvbox.osc.ui.player.PlayContainer
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.PermissionHelper
import kotlinx.coroutines.launch

private const val SYSBAR_APPEARANCE_REASSERT_DELAY_MS = 400L

class DetailActivity : BaseActivity(), PageHost {

    private val vm: DetailViewModel by lazy {
        ViewModelProvider(this)[DetailViewModel::class.java]
    }

    var playContainer: PlayContainer? = null
        private set
    private var fullScreen = false

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
        if (fullScreen) super.hideSysBar()
    }

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
            AVBoxTheme(manageStatusBarIcons = false) {
                DetailScreen(activity = this, vm = vm)
            }
        }
    }

    fun ensurePlayContainer(): PlayContainer {
        if (playContainer == null) {
            playContainer = PlayContainer(this).also {
                it.setPageHost(this)
                it.setPreviewMode(true)
            }
        }
        return playContainer!!
    }

    private fun releasePlayContainer() {
        playContainer?.hostDestroy()
        playContainer = null
    }

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

    fun playCurrent() {
        val container = playContainer ?: return
        val session = vm.preparePlaySession()
        if (session == null) {
            container.clearSourceSwitchTip()
            return
        }
        container.setData(session)
    }

    fun applyFullscreen(full: Boolean) {
        playContainer?.setAutoSwitchLineEnabled(!full)
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
            applyStatusBarAppearance()
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) applyStatusBarAppearance()
            }, SYSBAR_APPEARANCE_REASSERT_DELAY_MS)
        }
        syncFullBoxSideEffects()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vm.rotating.value = false
        syncFullBoxSideEffects()
    }

    fun isFullBox(): Boolean {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (vm.rotating.value) !landNow else fullScreen
    }

    private fun syncFullBoxSideEffects() {
        // 字幕字号随形态缩放(预览 0.6×)已收口到 PlayContainer.setPreviewMode,这里不再单独下发
        playContainer?.setPreviewMode(!isFullBox())
    }

    fun startDetailFallbackAfterLinesExhausted(): Boolean = vm.startFallbackAfterLinesExhausted()

    override fun onResume() {
        super.onResume()
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
