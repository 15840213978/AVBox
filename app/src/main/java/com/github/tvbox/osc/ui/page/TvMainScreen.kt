package com.github.tvbox.osc.ui.page

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.util.AppManager
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.delay

private enum class TvTab(val label: String, @DrawableRes val icon: Int) {
    HOME("首页", R.drawable.ic_tab_home),
    HISTORY("历史", R.drawable.ic_tab_history),
    COLLECT("收藏", R.drawable.ic_tab_collect),
    SETTINGS("设置", R.drawable.ic_tab_settings),
}

/**
 * Android TV 专用主界面。
 *
 * 手机继续使用 MainContent() 的底部导航；TV 使用左侧遥控器导航栏，避免把手机版 UI
 * 强行拉宽到 16:9。详情、搜索、直播等业务层继续复用原有 Activity 和 ViewModel。
 */
@Composable
fun TvMainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val homeViewModel: HomeViewModel = viewModel()
    var selectedTab by remember { mutableIntStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (!homeViewModel.defaultLiveLaunched) {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready && !homeViewModel.defaultLiveLaunched) {
                    homeViewModel.defaultLiveLaunched = true
                    if (KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false)) {
                        context.startActivity(Intent(context, LivePlayActivity::class.java))
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // 等第一帧布局完成再把遥控器焦点交给首页入口。
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }

    BackHandler {
        if (selectedTab != 0) {
            selectedTab = 0
        } else {
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
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        TvNavigationRail(
            selectedTab = selectedTab,
            onSelect = { selectedTab = it },
            onLive = { context.startActivity(Intent(context, LivePlayActivity::class.java)) },
            firstFocus = firstFocus,
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f),
        ) {
            when (TvTab.entries[selectedTab]) {
                TvTab.HOME -> TvHomePage(homeViewModel)
                TvTab.HISTORY -> HistoryPage(bottomPadding = 0.dp)
                TvTab.COLLECT -> CollectPage(bottomPadding = 0.dp)
                TvTab.SETTINGS -> SettingsPage(bottomPadding = 0.dp)
            }
        }
    }
}

@Composable
private fun TvNavigationRail(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    onLive: () -> Unit,
    firstFocus: FocusRequester,
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "AVBox",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.size(4.dp))

        TvTab.entries.forEachIndexed { index, tab ->
            TvRailButton(
                label = tab.label,
                icon = tab.icon,
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }

        TvRailButton(
            label = "直播",
            icon = R.drawable.ic_live_fab,
            selected = false,
            onClick = onLive,
        )

        Spacer(Modifier.weight(1f))
        Text(
            text = "遥控器版",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun TvRailButton(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "tvRailScale")
    val bg by animateColorAsState(
        when {
            focused -> MaterialTheme.colorScheme.primaryContainer
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> Color.Transparent
        },
        label = "tvRailBg",
    )
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimaryContainer
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, style = MaterialTheme.typography.titleSmall)
    }
}
