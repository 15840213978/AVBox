package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 卡片容器色(avbox-mobile-ui-spec §3):
 * 2026-09-09 用户定稿:不论深浅一律 surfaceBright。
 * (原逻辑为浅色 surfaceContainerHigh/深色 surfaceBright,已废弃)
 */
val ColorScheme.cardContainer: Color
    get() = surfaceBright
