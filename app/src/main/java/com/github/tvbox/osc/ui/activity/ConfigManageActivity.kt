package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.ConfigManageScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.handleLocalConfigResult
import com.github.tvbox.osc.util.startLocalConfig

/**
 * 配置管理页(2026-09-11):设置 tab →「配置管理」进入,管理订阅源(添加 / 开关切换 / 长按删除)。
 * 添加订阅 dialog 的「从本地选择」走系统 SAF(OpenDocument),结果回填链接输入框。
 */
class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    /** 系统文件选择器(SAF;2026-09-11 替代自绘 LocalFileActivity) */
    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleLocalConfigResult(this, uri)
        }

    /** Compose 入口调用:选择本地配置文件,结果经 onResult(clan:// 接口地址)回调 */
    fun launchLocalConfig(onResult: (api: String) -> Unit) {
        startLocalConfig(localConfigLauncher) { api -> onResult(api) }
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
                ConfigManageScreen(onNavigateBack = { finish() })
            }
        }
    }
}
