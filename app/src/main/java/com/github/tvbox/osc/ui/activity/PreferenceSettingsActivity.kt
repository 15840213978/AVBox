package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.PreferenceSettingsScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

/**
 * 偏好设置页(2026-09-12):设置 tab 进入,收纳原设置页通用偏好项
 * (自动换线/M3U8 净化/弹幕开关/弹幕 API/长按倍速/缓冲时间/搜索线程)。
 */
class PreferenceSettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PreferenceSettingsActivity::class.java))
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 手机端保留系统栏(§3)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                PreferenceSettingsScreen(onNavigateBack = { finish() })
            }
        }
    }
}
