package com.github.tvbox.osc.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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

internal fun ComponentActivity.enableTransparentEdgeToEdge() {
    // 注意:navigationBarStyle 不能用 SystemBarStyle.auto —— auto 的 nightMode 是
    // MODE_NIGHT_AUTO,EdgeToEdgeApi29/35.setUp 会据此把 isNavigationBarContrastEnforced
    // 设回 true,覆盖 BaseActivity 的关闭调用,导致三键导航区域被系统画上半透明 scrim。
    // light() 的 nightMode=MODE_NIGHT_NO,contrastEnforced 为 false,三键区才能真透明。
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
        navigationBarStyle = SystemBarStyle.light(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
    )
}

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
        // 窗口栏颜色直接断言为透明(API 35+ 弃用但仍被部分 ROM 用于三键导航背景,照 示例文件/android Theme.kt)
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // 兜底:三键导航的系统对比度遮罩(scrim)必须关掉,否则导航键区域蒙一层半透明长方形;
        // 在 enableEdgeToEdge 之后反复断言,防止 config change 或其他调用把它重开
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
            activity.window.isStatusBarContrastEnforced = false
        }
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
