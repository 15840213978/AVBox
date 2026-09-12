package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号阶梯 18/14/12sp(avbox-mobile-ui-spec §6)+ 设置行 16sp/组标题 13sp(§4.3)
 * + 页面左上角大标题 24sp/700(§4.2/§4.3,2026-09-09 用户定稿);
 * 未列出的角色保持 Material 3 默认。
 */
val AVBoxTypography = Typography(
    // 分区标题(海报卡片标题 2026-09-09 起就地覆盖为 16sp,不走此角色)
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    // 页面左上角大标题(设置/历史/收藏;用户定稿 24sp/700,区别于分区标题 titleLarge 18sp)
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
    ),
    // 设置行标题
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // 元信息行 / 设置当前值
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // 设置组标题
    labelMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
)
