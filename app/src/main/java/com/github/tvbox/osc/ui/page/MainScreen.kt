@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.LocalSheetHost
import com.github.tvbox.osc.ui.components.SheetHost
import com.github.tvbox.osc.ui.components.SheetHostState
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.HawkConfig
import com.orhanobut.hawk.Hawk
import kotlinx.coroutines.launch

// 图标来自 .tubiao/*.svg 转换的 VectorDrawable(2026-09-09);选中/未选中态由 NavigationBarItem 自动着色
private enum class AppTab(val label: String, @DrawableRes val icon: Int) {
    HOME("首页", R.drawable.ic_tab_home),
    HISTORY("历史", R.drawable.ic_tab_history),
    COLLECT("收藏", R.drawable.ic_tab_collect),
    SETTINGS("设置", R.drawable.ic_tab_settings),
}

@Composable
fun MainScreen() {
    // 启动引导(幂等,进程内仅执行一次)
    LaunchedEffect(Unit) { AppBootstrap.start() }
    val boot by AppBootstrap.state.collectAsState()
    // 2026-09-12 用户定稿:不再渲染全屏 BootLoading,直接进主界面(首页)——配置/jar 在
    // 后台继续加载,由首页页心圆形指示器统一表达"配置+数据"两段加载
    // (HomeViewModel.pageLoading 初始 true,Boot.Ready 后才 loadHome);
    // 配置加载失败时在主界面上叠错误对话框(重试/离线继续)
    Box(modifier = Modifier.fillMaxSize()) {
        MainContent()
        if (boot is AppBootstrap.Boot.Error) {
            BootErrorDialog((boot as AppBootstrap.Boot.Error).msg)
        }
    }
}

@Composable
private fun BootErrorDialog(msg: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("配置加载失败") },
        text = { Text(msg) },
        confirmButton = {
            TextButton(onClick = { AppBootstrap.retry() }) { Text("重试") }
        },
        dismissButton = {
            TextButton(onClick = { AppBootstrap.continueOffline() }) { Text("取消") }
        },
    )
}

@Composable
private fun MainContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { AppTab.entries.size })
    val homeViewModel: HomeViewModel = viewModel()

    // 默认启动页=直播(§4.3 首页与搜索组设置)。
    // 2026-09-12:MainContent 提前到 Boot.Loading 就进入组合(用户定稿删全屏 BootLoading),
    // 跳直播必须仍等 Boot.Ready(直播页数据依赖已加载的配置);一次性标记防重放
    LaunchedEffect(Unit) {
        if (homeViewModel.defaultLiveLaunched) return@LaunchedEffect
        AppBootstrap.state.collect { boot ->
            if (boot is AppBootstrap.Boot.Ready && !homeViewModel.defaultLiveLaunched) {
                homeViewModel.defaultLiveLaunched = true
                if (Hawk.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                    context.startActivity(Intent(context, LivePlayActivity::class.java))
                }
            }
        }
    }

    // 双击返回退出(§3 返回行为);计时存 ViewModel 防组合重建重置
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - homeViewModel.lastBackTime < 2000) {
            AppManager.getInstance().finishAllActivity()
            ControlManager.get().stopServer()
            (context as? Activity)?.finishAffinity()
        } else {
            homeViewModel.lastBackTime = now
            Toast.makeText(context, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
        }
    }

    // 页面内 sheet(首页/设置)提交到窗口根部槽位渲染:不被 pager 裁剪、可覆盖底栏(§2 决策 2026-09-11)
    val sheetHost = remember { SheetHostState() }
    CompositionLocalProvider(LocalSheetHost provides sheetHost) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                // 内容延伸到状态栏:各 tab 页顶栏改无边框并自行处理 statusBars inset
                // + 顶部渐变遮罩(2026-09-11 用户定稿,照搬示例项目)
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.height(80.dp), // 2026-09-10:72→80dp(用户定稿,M3 默认)
                    ) {
                        AppTab.entries.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                icon = { Icon(painterResource(tab.icon), contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                HorizontalPager(
                    state = pagerState,
                    // 2026-09-10:预组合全部 4 个 tab(beyondViewportPageCount=3),切 tab 无首次构建开销
                    beyondViewportPageCount = 3,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) { page ->
                    when (AppTab.entries[page]) {
                        AppTab.HOME -> HomePage(homeViewModel)
                        AppTab.HISTORY -> HistoryPage()
                        AppTab.COLLECT -> CollectPage()
                        AppTab.SETTINGS -> SettingsPage()
                    }
                }
            }
            // sheet 槽位:在 Scaffold 之上渲染,覆盖底栏与系统栏(§2 决策 2026-09-11)
            SheetHost(sheetHost)
        }
    }
}
