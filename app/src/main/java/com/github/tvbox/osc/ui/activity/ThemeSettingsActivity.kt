package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.ThemeSettingsScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

/**
 * 主题设置页(2026-09-11,照搬 `示例文件/android` 的主题设置页):设置 tab 进入。
 * 主题配置改动即时全局生效([com.github.tvbox.osc.ui.theme.AppThemeState] 广播)。
 */
class ThemeSettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ThemeSettingsActivity::class.java))
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 手机端保留系统栏(§3)
    }

    override fun init() {
        enableEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                ThemeSettingsScreen(onNavigateBack = { finish() })
            }
        }
    }
}
