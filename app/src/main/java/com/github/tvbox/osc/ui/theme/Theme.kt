package com.github.tvbox.osc.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle

/**
 * 应用主题(avbox-mobile-ui-spec §3,2026-09-11 接入主题设置页):
 * 取色来源三路径 —— ①自定义种子色(MaterialKolor 按风格生成整套配色);
 * ②跟随系统取色(Android 12+ Material You);③低版本品牌色板占位(§9)。
 * 深浅模式由 [ThemeConfig.mode] 决定,浅色/深色可覆盖系统。
 *
 * 配置默认取 [AppThemeState](进程级可观察状态),故主题设置页改配置后
 * 所有已组合页面同步重组;⚠️ 不能把 [AppThemeState.config] 读进 `remember` 的 key 之外。
 *
 * @param manageStatusBarIcons 纯黑状态栏页面(详情页/直播页)恒为白色图标、由各自 Activity
 *   自行断言,这些页面传 false 关闭主题对状态栏的接管(避免浅色主题下被写成深色图标)。
 */
@Composable
fun AVBoxTheme(
    config: ThemeConfig = AppThemeState.config,
    manageStatusBarIcons: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (config.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val colorScheme = when {
        // 自定义种子色:同步计算 + 进程级缓存(同 seed/明暗/风格只算一次,避免旧实现蓝色闪屏)
        config.source == ThemeSource.CUSTOM -> remember(config.seedArgb, darkTheme, config.style) {
            AppThemeState.customScheme(config.seedArgb, darkTheme, config.style)
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    if (manageStatusBarIcons) {
        ApplyAppThemeBars(isDark = darkTheme)
    }

    CompositionLocalProvider(LocalRippleConfiguration provides rememberRippleConfiguration()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AVBoxTypography,
            content = content,
        )
    }
}

/**
 * 涟漪(点击/长按的激活反馈)透明度 = M3 默认的 2 倍,全局生效(2026-09-13,照搬 `示例文件/android`
 * 的 `Theme.kt`)。M3 默认 pressed 仅 10% —— 首页海报卡上是深色图片 + 底部黑色渐变 scrim,
 * 这点透明度几乎看不出来"点到了",2 倍后才形成明确的按下反馈。
 *
 * 走 [LocalRippleConfiguration] 而不是给每张卡各传一份 `indication`:全 App 的
 * `clickable`/`combinedClickable`/`Surface(onClick)`/`ToggleButton` 一次覆盖,风格天然统一。
 *
 * ⚠️ 反直觉但必要:`RippleConfiguration` 已标注 deprecated,而官方**未提供替代入口**
 * (新的 `RippleConfiguration` 覆盖不到 rippleAlpha),所以只能 `@Suppress("DEPRECATION")`。
 */
@Suppress("DEPRECATION")
@Composable
private fun rememberRippleConfiguration(): RippleConfiguration = remember {
    // 括号内为原始 M3 默认值:0.08 / 0.10 / 0.10 / 0.16,统一乘 2
    RippleConfiguration(
        rippleAlpha = RippleAlpha(
            hoveredAlpha = 2f * 0.08f,
            focusedAlpha = 2f * 0.10f,
            pressedAlpha = 2f * 0.10f,
            draggedAlpha = 2f * 0.16f,
        ),
    )
}

/**
 * 状态栏/导航栏图标外观:按解析后的应用主题取反(浅色主题深色图标、深色主题白色图标)。
 * 放在主题包装里统一断言,深浅模式覆盖系统时也不会出现"深色图标压在深色栏上"。
 */
@Composable
private fun ApplyAppThemeBars(isDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

/** 沿 ContextWrapper 链找 Activity;非 Activity context(如挂在 Service 上的 ComposeView)返回 null */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(name = "浅色", showBackground = true)
@Composable
private fun AVBoxThemeLightPreview() {
    AVBoxTheme(
        config = ThemeConfig(ThemeSource.CUSTOM, ThemeMode.LIGHT, DefaultSeedArgb, PaletteStyle.TonalSpot),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Text("AVBox")
        }
    }
}

@Preview(name = "深色", showBackground = true)
@Composable
private fun AVBoxThemeDarkPreview() {
    AVBoxTheme(
        config = ThemeConfig(ThemeSource.CUSTOM, ThemeMode.DARK, DefaultSeedArgb, PaletteStyle.TonalSpot),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Text("AVBox")
        }
    }
}
