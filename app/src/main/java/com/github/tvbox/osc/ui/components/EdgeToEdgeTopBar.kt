package com.github.tvbox.osc.ui.components

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 页面顶栏壳(2026-09-11 晚,照 `示例文件/android` 的官方方案整体重做):
 * `Scaffold + M3 TopAppBar(TopAppBarDefaults.exitUntilCollapsedScrollBehavior)`,
 * 滚动记账完全由官方 behavior 管理,彻底消除自研增量记账与列表位置失联的两类装机 bug
 * (标题残留状态栏区 / 标题叠在滚过的内容上)。
 *
 * 结构逐字对齐示例(SettingsScreen / ThemeSettingsScreen):
 * - `nestedScroll(scrollBehavior.nestedScrollConnection)` 挂 Scaffold;
 * - `contentWindowInsets = 0`,内容延伸到状态栏下,由 [TopScrim] 渐隐穿过顶栏区域的内容;
 * - `TopAppBar` 自身 `windowInsets = 0`,外层补 statusBars padding,透明底;
 * - 内容留白用 content 回调里的 `padding.calculateTopPadding()`(= 顶栏实测总高)计算。
 *
 * 2026-09-16 追加:内容层额外录一份到玻璃采样图层,顶栏内的控件(经 [glassTopBarSurface])据此
 * 做真模糊 —— 与底部悬浮导航栏同一套液态玻璃。顶栏与 [TopScrim] 都画在采样层之外。
 *
 * @param titleContent 顶栏标题区(纯文字大标题,或首页这类复杂行);
 *                     M3 TopAppBar 标准高 64dp,标题垂直居中,随滚动整体滚出
 * @param navigationIcon 左侧槽(二级页放 [TopBarActionBox] 返回钮)
 * @param actions 右侧槽(管理/添加等控件)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBarScaffold(
    titleContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    collapseEnabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.BoxScope.(topPadding: androidx.compose.ui.unit.Dp, bottomPadding: androidx.compose.ui.unit.Dp) -> Unit,
) {
    // collapseEnabled=false 用 pinned:顶栏常驻不折叠(2026-09-12 首页/搜索页启用——
    // exitUntilCollapsed 与 material3 pullToRefresh 的 nested scroll 冲突:收指示器的向上
    // 手势剩余量会把顶栏折死且无法自动恢复;且这两页内容可透过透明顶栏,折叠无收益)
    val scrollBehavior = if (collapseEnabled) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }
    // 顶栏玻璃采样层(2026-09-16):内容录进独立图层,顶栏控件经 [LocalTopBarGlassBackdrop]
    // 用它 drawBackdrop 做真模糊;顶栏自身在图层之外(否则自采样)。开关关闭/低版本不挂
    // layerBackdrop(图层不录制,零开销),顶栏控件回退原实心容器
    // 门控 = 「应用控件」开关 × API(与底部导航各自独立,无总开关)
    val glassConfig = LiquidGlassState.config
    val glassEnabled = glassConfig.controlsEnabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val glassOnDraw: ContentDrawScope.() -> Unit = remember(containerColor) {
        { drawRect(containerColor); drawContent() }
    }
    val glassBackdrop = rememberLayerBackdrop(onDraw = glassOnDraw)
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = containerColor,
        topBar = {
            // 采样层只在顶栏槽内下发:内容区的同名控件(搜索页历史卡删除钮等)不玻璃化
            CompositionLocalProvider(
                LocalTopBarGlassBackdrop provides glassBackdrop.takeIf { glassEnabled }
            ) {
                TopAppBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = titleContent,
                    // 槽内边距补齐:M3 槽自身 start/end 各 4dp(TopAppBarHorizontalPadding),
                    // 再加 12dp 使 40dp 圆钮外缘距屏 16dp —— 与全站卡片距屏 16dp 规范及迁移前自研顶栏一致
                    navigationIcon = {
                        if (navigationIcon != null) {
                            Box(modifier = Modifier.padding(start = 12.dp)) { navigationIcon() }
                        }
                    },
                    actions = {
                        Row(modifier = Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            actions()
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glassEnabled) Modifier.layerBackdrop(glassBackdrop) else Modifier),
            ) {
                content(
                    padding.calculateTopPadding(),
                    padding.calculateBottomPadding(),
                )
            }
            // 顶部渐变遮罩画在采样层之外:否则顶栏玻璃会把遮罩自身也模糊进来(近乎纯色,看不出玻璃)
            TopScrim(height = padding.calculateTopPadding())
        }
    }
}

/**
 * 顶部渐变遮罩:默认高度 = 状态栏高 × 1.2;`AppTopBarScaffold` 传入顶栏实测总高
 * (= 状态栏 + 64dp,2026-09-12 用户定稿延伸到"搜索框/胶囊行"区域),
 * 从页面背景色 0.95 alpha 渐至透明,滚动内容穿过整个顶栏行时渐隐淡出,过渡更平滑
 * (视觉近似"渐变模糊",同示例项目 StatusBarScrim);无 pointerInput,不拦截触摸。
 */
@Composable
fun TopScrim(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    height: Dp = with(androidx.compose.ui.platform.LocalDensity.current) {
        (WindowInsets.statusBars.getTop(this) * 1.2f).toDp()
    },
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0f to color.copy(alpha = 0.95f),
                    0.5f to color.copy(alpha = 0.7f),
                    1f to Color.Transparent,
                )
            ),
    )
}

/**
 * 顶栏操作按钮:40dp 圆形容器 + 22dp 图标(surfaceBright 底;2026-09-11);
 * 带返回键的二级页复用,避免各页各写一份。
 * 2026-09-16 起容器改走 [glassTopBarSurface]:液态玻璃开启时为玻璃圆钮(跟随底部导航栏),
 * 关闭/低版本仍是原实心 surfaceBright 圆底。
 */
@Composable
fun TopBarActionBox(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .glassTopBarSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
