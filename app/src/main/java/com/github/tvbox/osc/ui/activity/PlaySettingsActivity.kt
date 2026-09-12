package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.PlaySettingsScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme

/**
 * 播放设置页(2026-09-12):设置 tab 进入,由主设置页拆出的播放器相关项
 * (播放内核/画面渲染/画面缩放/解码方式/IJK 缓存播放/隧道模式/AAC 优先)。
 */
class PlaySettingsActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PlaySettingsActivity::class.java))
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
                PlaySettingsScreen(onNavigateBack = { finish() })
            }
        }
    }
}
