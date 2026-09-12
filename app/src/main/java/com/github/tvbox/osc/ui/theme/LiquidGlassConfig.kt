package com.github.tvbox.osc.ui.theme

/**
 * 液态玻璃导航栏配置(2026-09-13,照搬 `示例文件/android` 的 LiquidGlassConfig)。
 * blurDp 仅 API 31+ 生效,distortionDp 仅 API 33+ 生效(低版本库内静默 no-op,读取侧据此门控回退)。
 */
data class LiquidGlassConfig(
    val enabled: Boolean,
    val blurDp: Float,
    val distortionDp: Float,
)
