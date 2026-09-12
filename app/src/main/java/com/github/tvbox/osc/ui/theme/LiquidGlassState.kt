package com.github.tvbox.osc.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.util.HawkConfig
import com.orhanobut.hawk.Hawk

/**
 * 液态玻璃导航栏状态(全局单例,2026-09-13 照搬 `示例文件/android` 的 LiquidGlassState):
 * 与 [AppThemeState] 同风格 —— 进程级单例 + Compose 可观察状态,Hawk 持久化。
 * 必须单例:MainScreen(玻璃分支门控)与主题设置页共享同一实例,各自持有一份会导致开关失效(示例踩坑)。
 */
object LiquidGlassState {

    // 2026-09-13 用户定稿:模糊默认 20dp(示例为 5dp),折射默认 30dp 与示例一致
    const val DEFAULT_BLUR_DP = 20f
    const val DEFAULT_DISTORTION_DP = 30f
    val BLUR_RANGE: ClosedFloatingPointRange<Float> = 0f..40f
    val DISTORTION_RANGE: ClosedFloatingPointRange<Float> = 0f..30f

    private var current by mutableStateOf(load())

    /** Compose 侧读该属性即为可观察状态 */
    val config: LiquidGlassConfig get() = current

    private fun load(): LiquidGlassConfig = LiquidGlassConfig(
        enabled = Hawk.get(HawkConfig.LIQUID_GLASS_ENABLED, true),
        blurDp = Hawk.get(HawkConfig.LIQUID_GLASS_BLUR, DEFAULT_BLUR_DP),
        distortionDp = Hawk.get(HawkConfig.LIQUID_GLASS_DISTORTION, DEFAULT_DISTORTION_DP),
    )

    fun setEnabled(enabled: Boolean) {
        Hawk.put(HawkConfig.LIQUID_GLASS_ENABLED, enabled)
        current = current.copy(enabled = enabled)
    }

    fun setBlurDp(dp: Float) {
        Hawk.put(HawkConfig.LIQUID_GLASS_BLUR, dp)
        current = current.copy(blurDp = dp)
    }

    fun setDistortionDp(dp: Float) {
        Hawk.put(HawkConfig.LIQUID_GLASS_DISTORTION, dp)
        current = current.copy(distortionDp = dp)
    }

    fun restoreDefaults() {
        setBlurDp(DEFAULT_BLUR_DP)
        setDistortionDp(DEFAULT_DISTORTION_DP)
    }
}
