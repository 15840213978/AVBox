package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.page.ConfigManageScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.PermissionHelper
import com.github.tvbox.osc.util.handleLocalConfigResult
import com.github.tvbox.osc.util.startLocalConfig

class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleLocalConfigResult(this, uri)
        }

    fun launchLocalConfig(onResult: (api: String) -> Unit) {
        if (PermissionHelper.isStorageGranted(this)) {
            startLocalConfig(localConfigLauncher) { api -> onResult(api) }
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            // 拒绝则取消本次导入(复制分支下同目录引用会静默失效),不替用户弹出选择器
            if (!granted.isNullOrEmpty()) {
                startLocalConfig(localConfigLauncher) { api -> onResult(api) }
            } else {
                Toast.makeText(this, "未开启「所有文件访问」,已取消导入(可再点一次重试)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                ConfigManageScreen(onNavigateBack = { finish() })
            }
        }
    }
}
