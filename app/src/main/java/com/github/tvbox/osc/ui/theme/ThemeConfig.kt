package com.github.tvbox.osc.ui.theme

import com.materialkolor.PaletteStyle

/**
 * 主题设置模型(2026-09-11,照搬 `示例文件/android` 主题设置页):
 * 取色来源 / 深浅模式 / 自定义种子色 / 配色风格四元组,持久化见 [AppThemeState]。
 */
object ThemeSource {
    /** 跟随系统取色(Android 12+ Material You;低版本回退品牌色板) */
    const val SYSTEM = 0

    /** 自定义种子色(配合 [ThemeConfig.style] 生成整套配色) */
    const val CUSTOM = 1
}

object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** [seedArgb] 与 [style] 仅在 [ThemeSource.CUSTOM] 下生效 */
data class ThemeConfig(
    val source: Int,
    val mode: Int,
    val seedArgb: Int,
    val style: PaletteStyle,
)

/** 默认种子色 = 预设色卡中的蓝色(与示例项目一致) */
val DefaultSeedArgb: Int = 0xFF1B6EF3.toInt()

/** 默认配色风格 */
val DefaultPaletteStyle: PaletteStyle = PaletteStyle.TonalSpot

/** 预设种子色色卡:名称 + ARGB(色值照搬示例项目) */
val PresetSeeds: List<Pair<String, Int>> = listOf(
    "红色" to 0xFFD02020.toInt(),
    "橙色" to 0xFFE07A00.toInt(),
    "黄色" to 0xFFB08000.toInt(),
    "绿色" to 0xFF208040.toInt(),
    "青色" to 0xFF008080.toInt(),
    "蓝色" to 0xFF1B6EF3.toInt(),
    "紫色" to 0xFF6750A4.toInt(),
    "粉色" to 0xFFB04080.toInt(),
)

/** 配色风格选项(MaterialKolor PaletteStyle;顺序与示例项目一致) */
val PaletteStyles: List<Pair<PaletteStyle, String>> = listOf(
    PaletteStyle.TonalSpot to "柔和",
    PaletteStyle.Vibrant to "鲜艳",
    PaletteStyle.Expressive to "表现力",
    PaletteStyle.Neutral to "中性",
    PaletteStyle.Monochrome to "单色",
    PaletteStyle.Fidelity to "保真",
    PaletteStyle.Content to "内容",
    PaletteStyle.Rainbow to "彩虹",
    PaletteStyle.FruitSalad to "水果沙拉",
)
