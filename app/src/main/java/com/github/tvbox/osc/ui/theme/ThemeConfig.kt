package com.github.tvbox.osc.ui.theme

import com.materialkolor.PaletteStyle

object ThemeSource {
    const val SYSTEM = 0

    const val CUSTOM = 1
}

object ThemeMode {
    const val FOLLOW_SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

data class ThemeConfig(
    val source: Int,
    val mode: Int,
    val seedArgb: Int,
    val style: PaletteStyle,
)

val DefaultSeedArgb: Int = 0xFF1B6EF3.toInt()

val DefaultPaletteStyle: PaletteStyle = PaletteStyle.TonalSpot

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
