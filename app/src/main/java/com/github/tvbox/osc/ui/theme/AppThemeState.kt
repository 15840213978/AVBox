package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.github.tvbox.osc.util.HawkConfig
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用主题状态(全局单例,2026-09-11 照搬 `示例文件/android` 的 ThemeState + 两个配色缓存)。
 *
 * 用单例而非 ViewModel/Hilt:主题是进程级状态(已组合的页面都要同步重组),
 * 且本项目无 DI 框架,与 [com.github.tvbox.osc.ui.page.AppBootstrap] 同风格。
 * 持久化走 KV —— 设置页写、全 App 读。
 */
object AppThemeState {

    private var current by mutableStateOf(load())

    /** Compose 侧读该属性即为可观察状态 */
    val config: ThemeConfig get() = current

    private fun load(): ThemeConfig = ThemeConfig(
        source = KV.get(HawkConfig.THEME_SOURCE, ThemeSource.SYSTEM),
        mode = KV.get(HawkConfig.THEME_MODE, ThemeMode.FOLLOW_SYSTEM),
        seedArgb = KV.get(HawkConfig.THEME_SEED, DefaultSeedArgb),
        // 枚举名被持久化,历史值可能失效(升级/改名),解析失败回默认风格
        style = runCatching { PaletteStyle.valueOf(KV.get(HawkConfig.THEME_PALETTE_STYLE, "")) }
            .getOrDefault(DefaultPaletteStyle),
    )

    fun setSource(source: Int) {
        KV.put(HawkConfig.THEME_SOURCE, source)
        current = current.copy(source = source)
    }

    fun setMode(mode: Int) {
        KV.put(HawkConfig.THEME_MODE, mode)
        current = current.copy(mode = mode)
    }

    fun setSeed(argb: Int) {
        KV.put(HawkConfig.THEME_SEED, argb)
        current = current.copy(seedArgb = argb)
    }

    fun setStyle(style: PaletteStyle) {
        KV.put(HawkConfig.THEME_PALETTE_STYLE, style.name)
        current = current.copy(style = style)
    }

    /** 解析最终深浅色:浅色/深色模式覆盖系统,跟随系统模式用系统值 */
    fun isDark(systemDark: Boolean): Boolean = when (current.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    /** 由种子色生成的整套配色方案缓存:同 seed/明暗/风格只做一次 HCT 转换(避免切主题闪屏) */
    private val schemeCache = ConcurrentHashMap<Triple<Int, Boolean, PaletteStyle>, ColorScheme>()

    fun customScheme(seedArgb: Int, isDark: Boolean, style: PaletteStyle): ColorScheme =
        schemeCache.getOrPut(Triple(seedArgb, isDark, style)) {
            dynamicColorScheme(seedColor = Color(seedArgb), isDark = isDark, style = style)
        }

    /**
     * 预设色卡预览配色:恒按浅色生成(色卡只做"像不像"的观感预览),
     * 计算放后台线程,避免 8 张色卡同时算 HCT 卡住首帧。
     */
    private val previewCache = ConcurrentHashMap<Pair<Int, PaletteStyle>, ColorScheme>()

    suspend fun previewScheme(seedArgb: Int, style: PaletteStyle): ColorScheme {
        previewCache[seedArgb to style]?.let { return it }
        return withContext(Dispatchers.Default) {
            previewCache.getOrPut(seedArgb to style) {
                dynamicColorScheme(seedColor = Color(seedArgb), isDark = false, style = style)
            }
        }
    }
}
