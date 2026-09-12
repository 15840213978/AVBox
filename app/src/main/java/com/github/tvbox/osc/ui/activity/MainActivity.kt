package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.MainScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.util.PermissionHelper

/**
 * 手机版入口:Compose 重写的主界面(avbox-mobile-ui-spec §3)。
 * 继承 BaseActivity 以复用 AutoSize 密度适配,使旧 XML 对话框在 Compose 界面上渲染正常;
 * 系统 UI 由 Compose edge-to-edge 接管,不再走 TV 沉浸式隐藏。
 */
class MainActivity : BaseActivity() {

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 手机端保留系统栏;播放页全屏横屏沉浸由播放器内部处理(§3)
    }

    /**
     * 状态栏图标外观:顶栏区域现为页面内容 + 渐变遮罩(2026-09-11 无边框顶栏改造),
     * 图标按主题深浅取反 —— 浅色主题深色图标、深色主题白色图标;
     * 深浅以主题设置页的解析结果为准([AppThemeState],浅色/深色模式可覆盖系统);
     * 系统会在回前台时按主题重设外观,故 init / onResume 反复断言。
     */
    private fun applyStatusBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = !AppThemeState.isDark(night)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarAppearance()
    }

    override fun init() {
        enableEdgeToEdge()
        applyStatusBarAppearance()
        // 仅 Android 10- 在启动时弹窗授权;11+ 的"所有文件访问"为懒加载
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !PermissionHelper.isStorageGranted(this)) {
            PermissionHelper.requestStorage(this) { _, _ -> }
        }
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                MainScreen()
            }
        }
    }
}
