package com.github.tvbox.osc.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.ui.theme.cardContainer

/** 卡位:决定当前卡片的圆角(avbox-mobile-ui-spec §4.3) */
enum class SettingsCardPosition {
    SINGLE,
    FIRST,
    MIDDLE,
    LAST,
}

private fun shapeFor(position: SettingsCardPosition): Shape = when (position) {
    SettingsCardPosition.SINGLE -> RoundedCornerShape(28.dp)
    SettingsCardPosition.FIRST -> RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 4.dp, bottomStart = 4.dp)
    SettingsCardPosition.MIDDLE -> RoundedCornerShape(4.dp)
    SettingsCardPosition.LAST -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomEnd = 28.dp, bottomStart = 28.dp)
}

/**
 * 设置分组卡片:按卡位自动圆角,Surface 裁剪保证 ripple 按卡圆角裁剪(avbox-mobile-ui-spec §4.3)。
 * 一个可视分组拆多张卡时,各卡间距由 [SettingsGroup] 统一为 2dp。
 */
@Composable
fun SettingsCard(
    position: SettingsCardPosition,
    modifier: Modifier = Modifier,
    // 为 null 时使用全局 cardContainer 色;页面可传 surfaceBright 等覆盖
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapeFor(position),
        color = color ?: MaterialTheme.colorScheme.cardContainer,
    ) {
        Column(content = content)
    }
}

/**
 * 设置分组:组标题(13sp onSurfaceVariant)+ 若干张卡,卡间距 2dp;组间距由页面侧统一控制。
 * 分组拆多张卡时仅首卡传 title,各卡 position 依次 FIRST/MIDDLE/LAST。
 */
@Composable
fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp), // 2026-09-09:卡间距 4→2dp(用户定稿)
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        content()
    }
}

/**
 * 右侧当前值 + chevron 的设置行;无 [onClick] 时不显示 chevron;
 * 可带左侧圆形角标图标(iconRes)与标题下方小标题(subtitle,2026-09-12)。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueText: String? = null,
    enabled: Boolean = true,
    iconRes: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            SettingsIconBadge(iconRes, title)
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title = title, enabled = enabled)
            if (subtitle != null) {
                RowSubtitle(text = subtitle, enabled = enabled)
            }
        }
        if (valueText != null) {
            RowValue(text = valueText, enabled = enabled)
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 设置行左侧圆形角标图标(2026-09-12 用户定稿):40dp 圆形容器 primaryContainer 底(动态取色),
 * 图标 22dp onPrimaryContainer;规格与搜索页卡片角标(SearchActivity.SectionIconBadge)一致。
 */
@Composable
fun SettingsIconBadge(@DrawableRes iconRes: Int, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 滑块设置行:标题 + 右侧当前值 + M3 Slider(动态取色,自动读 colorScheme.primary/surfaceVariant)。
 * 拖动中仅回调 [onValueChange](调用方更新本地 state),松手才回调 [onValueChangeFinished] 落盘。
 */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowTitle(title = title, enabled = true, modifier = Modifier.weight(1f))
            if (valueText != null) {
                Spacer(Modifier.width(16.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

/**
 * 开关设置行:Switch 不直接接收点击,由整行 clickable 接管,避免双响应。
 * 可带标题下方小标题(subtitle,2026-09-12),样式与 [SettingsRow] 一致。
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueText: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(enabled = enabled, onClick = { onCheckedChange(!checked) })
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            RowTitle(title = title, enabled = enabled)
            if (subtitle != null) {
                RowSubtitle(text = subtitle, enabled = enabled)
            }
        }
        if (valueText != null) {
            RowValue(text = valueText, enabled = enabled, modifier = Modifier.padding(end = 8.dp))
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * bottom sheet 单选项(avbox-mobile-ui-spec §4.3:选项列表与设置行统一视觉)。
 * [trailing]:标题与单选圈之间的可选插槽(如订阅源 sheet 的「搜索/详情」策略标记)。
 * [onLongClick]:可选长按动作(如直播设置「配置切换」历史的长按删除,2026-09-12 方案 2)。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun RowTitle(title: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = modifier,
    )
}

/** 标题下方小标题(副标题):bodySmall + onSurfaceVariant,不可用行同透明度规则 */
@Composable
private fun RowSubtitle(text: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = modifier.padding(top = 4.dp),
    )
}

@Composable
private fun RowValue(text: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        modifier = modifier,
    )
}
