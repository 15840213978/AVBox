package com.github.tvbox.osc.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 分段外观:
 * - [Connected] = **连接胶囊**(2026-09-11 原样式):段间 `ConnectedSpaceBetween` 细缝 + connectedShapes,
 *   除形状外全部走 M3 `ToggleButton` 默认(选中 primary 实心、未选中 surfaceContainer),主题设置页在用;
 * - [Track] = **胶囊轨道 + 浮动选中胶囊**(2026-09-12):外层全圆角轨道(surfaceContainerHighest)+ 4dp 内缩,
 *   未选中段容器透明(与轨道融合),尺寸更紧凑,配置管理页在用。
 */
enum class SegmentStyle { Connected, Track }

/** 轨道圆角(全圆角胶囊,与内部选中胶囊同心) */
private val TrackShape = RoundedCornerShape(percent = 50)

/** 轨道内缩:让选中胶囊"浮"在轨道里,而不是撑满轨道 */
private val TrackPadding = 4.dp

/** 轨道内相邻段间距(不再用 ConnectedSpaceBetween —— 段之间靠轨道色分隔即可) */
private val TrackSegmentSpacing = 4.dp

/** 轨道内段内边距:纵向 2dp + 两行文字 36dp = 40dp,正好落在 [ToggleButtonDefaults.MinHeight] 上 */
private val TrackContentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)

/**
 * 分段选项:[icon]/[iconPainter] 二选一,均空则只显示文字。
 * [badge](2026-09-12):可选的第二行摘要(如配置管理页「点播/直播」两段各显示当前所选源),
 * 为 null 时渲染结果与单行版完全一致。
 */
data class SegmentOption<T>(
    val label: String,
    val value: T,
    val icon: ImageVector? = null,
    val iconPainter: Painter? = null,
    val badge: String? = null,
)

/**
 * 胶囊分段选择器(2026-09-11 引入,2026-09-12 加 [SegmentStyle]),整行均分宽度,
 * 按下带弹簧回弹动效(0.94 → 1);选项图标 / 文字由 [SegmentOption] 描述。
 *
 * 段高由 M3 `ToggleButton` 自带的 40dp 下限控制(内容更高时随内容),不需要调用方传高度。
 *
 * @param style 外观,默认 [SegmentStyle.Connected](不传即保持引入时的原样式)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> CapsuleSegmentedButton(
    options: List<SegmentOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    style: SegmentStyle = SegmentStyle.Connected,
) {
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)

    if (style == SegmentStyle.Track) {
        Surface(
            modifier = modifier,
            shape = TrackShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(TrackPadding),
                horizontalArrangement = Arrangement.spacedBy(TrackSegmentSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, option ->
                    CapsuleToggleButton(
                        option = option,
                        checked = selectedIndex == index,
                        onCheckedChange = { onOptionSelected(option.value) },
                        index = index,
                        count = options.size,
                        style = style,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            CapsuleToggleButton(
                option = option,
                checked = selectedIndex == index,
                onCheckedChange = { onOptionSelected(option.value) },
                index = index,
                count = options.size,
                style = style,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> CapsuleToggleButton(
    option: SegmentOption<T>,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
    style: SegmentStyle,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 弹簧缩放:按下 → 0.94f,松开 → 1f(dampingRatio 0.45 产生回弹)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "capsuleScale",
    )

    val segmentModifier = modifier
        .semantics { role = Role.RadioButton }
        .scale(scale)

    if (style == SegmentStyle.Track) {
        ToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = segmentModifier,
            // 三段形状统一为全圆角:与轨道同心,同时关掉 M3 默认的按压形变(分段选中态不宜变形)
            // (material3 1.5.0-alpha28:工厂 ToggleButtonDefaults.shapes 移除,改为直接构造 ToggleButtonShapes)
            shapes = ToggleButtonShapes(
                shape = TrackShape,
                pressedShape = TrackShape,
                checkedShape = TrackShape,
            ),
            // 未选中 → 透明(与轨道融合);选中/文字/禁用色沿用 M3 默认
            // (material3 1.5.0-alpha28:toggleButtonColors 更名为 colors)
            colors = ToggleButtonDefaults.colors(containerColor = Color.Transparent),
            // 透明容器上保留默认阴影会留一圈灰边,故关掉
            elevation = null,
            contentPadding = TrackContentPadding,
            interactionSource = interactionSource,
        ) {
            SegmentContent(option, MaterialTheme.typography.labelSmall)
        }
    } else {
        // 原样式:除连接形状外全部走 M3 默认,保证与引入时逐像素一致
        ToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = segmentModifier,
            shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            },
            interactionSource = interactionSource,
        ) {
            SegmentContent(option, MaterialTheme.typography.bodySmall)
        }
    }
}

/** 段内容:可选图标 + 标题(+可选徽标第二行),两行居中 */
@Composable
private fun <T> SegmentContent(option: SegmentOption<T>, badgeStyle: TextStyle) {
    if (option.icon != null) {
        Icon(imageVector = option.icon, contentDescription = null)
        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
    } else if (option.iconPainter != null) {
        Icon(painter = option.iconPainter, contentDescription = null)
        Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = option.label, overflow = TextOverflow.Ellipsis, maxLines = 1)
        option.badge?.let { badge ->
            Text(
                text = badge,
                style = badgeStyle,
                // 继承按钮的 contentColor(选中/未选中各自的对比色),降透明度区分主次
                color = LocalContentColor.current.copy(alpha = 0.75f),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}
