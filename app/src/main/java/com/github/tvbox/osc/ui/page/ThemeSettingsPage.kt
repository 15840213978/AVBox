@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.ThemeColorPickerSheet
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.theme.AppThemeState
import com.github.tvbox.osc.ui.theme.LiquidGlassState
import com.github.tvbox.osc.ui.theme.PaletteStyles
import com.github.tvbox.osc.ui.theme.PresetSeeds
import com.github.tvbox.osc.ui.theme.ThemeMode
import com.github.tvbox.osc.ui.theme.ThemeSource
import com.materialkolor.PaletteStyle
import kotlin.math.roundToInt

/** 非自定义模式下不可用行的整体透明度(与示例项目一致) */
private const val DisabledAlpha = 0.45f

/**
 * 主题设置页(2026-09-11,照搬 `示例文件/android` 的主题设置页):
 * 自定义主题开关 / 深浅模式 / 预设色卡 / 自定义种子色(取色器)/ 配色风格,
 * 配置读写走 [AppThemeState](Hawk 持久化 + 全局可观察),改动即时全局生效。
 *
 * 未走 ViewModel:主题是进程级单例状态,页面只做"读状态 + 下发 intent",
 * 加一层 VM 只是转发(同 [MainScreen] 直接读 AppBootstrap 的既有风格)。
 */
@Composable
fun ThemeSettingsScreen(onNavigateBack: () -> Unit) {
    val config = AppThemeState.config
    val isCustom = config.source == ThemeSource.CUSTOM
    var seedPickerOpen by remember { mutableStateOf(false) }
    // 液态玻璃滑条:拖动中走本地 state,松手才落盘(与全局 SettingsSliderRow 约定一致);
    // remember 键绑 config 值,外部变更(恢复默认)即时回显
    val glassConfig = LiquidGlassState.config
    var blurValue by remember(glassConfig.blurDp) { mutableStateOf(glassConfig.blurDp) }
    var distortionValue by remember(glassConfig.distortionDp) { mutableStateOf(glassConfig.distortionDp) }

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar
    val listState = rememberScrollState()

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "主题设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, "返回", onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            // 分组间距 28dp,与设置页同规格(2026-09-13 用户定稿:此前漏配,两个分组贴死,分组结构不可见)
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // 顶部占位 = 顶栏高度 - 20dp:spacedBy(28) 已含 28dp,净间距仍为 topPad+8dp(与设置页同公式)
            Spacer(Modifier.height(topPad - 20.dp))

            SettingsGroup(title = null) {
                ThemeCard(SettingsCardPosition.FIRST) {
                    HeaderRow("主题颜色")
                }
                ThemeCard(SettingsCardPosition.MIDDLE) {
                    CustomThemeSwitchRow(
                        checked = isCustom,
                        onCheckedChange = { checked ->
                            AppThemeState.setSource(
                                if (checked) ThemeSource.CUSTOM else ThemeSource.SYSTEM,
                            )
                        },
                    )
                }
                // 主题模式与取色来源无关,始终可用
                ThemeCard(SettingsCardPosition.MIDDLE) {
                    ThemeModeRow(
                        currentMode = config.mode,
                        onModeSelected = { AppThemeState.setMode(it) },
                    )
                }
                // 预设色卡 / 自定义色 / 配色风格仅在自定义模式下可用
                ThemeCard(SettingsCardPosition.MIDDLE, enabled = isCustom) {
                    PresetSeedsRow(
                        currentSeed = config.seedArgb,
                        style = config.style,
                        enabled = isCustom,
                        onSeedSelected = { AppThemeState.setSeed(it) },
                    )
                }
                ThemeCard(SettingsCardPosition.MIDDLE, enabled = isCustom) {
                    CustomSeedRow(
                        seedArgb = config.seedArgb,
                        enabled = isCustom,
                        onClick = { seedPickerOpen = true },
                    )
                }
                ThemeCard(SettingsCardPosition.LAST, enabled = isCustom) {
                    VariantSelectorRow(
                        currentStyle = config.style,
                        enabled = isCustom,
                        onStyleSelected = { AppThemeState.setStyle(it) },
                    )
                }
            }

            // 液态玻璃导航栏组(2026-09-13,用户定稿:布局照搬示例 NavStyleScreen 设计稿——
            // 头部卡(标题+重置,无 icon)、开关卡、两张滑条卡;重置按钮不带容器底,其余与设计稿一致)
            SettingsGroup(title = null) {
                // 头部卡:标题 + 重置(纯文字),下行为版本支持说明(2026-09-13 用户定稿:去掉 icon,只保留标题)
                SettingsCard(SettingsCardPosition.FIRST) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "导航栏效果",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { LiquidGlassState.restoreDefaults() }) {
                                Text("重置")
                            }
                        }
                        Text(
                            text = "Android 13 及以上支持液态玻璃",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "开启液态玻璃效果",
                        checked = glassConfig.enabled,
                        onCheckedChange = { LiquidGlassState.setEnabled(it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    GlassSliderRow(
                        title = "模糊效果",
                        value = blurValue,
                        valueRange = LiquidGlassState.BLUR_RANGE,
                        onValueChange = { blurValue = it },
                        onValueChangeFinished = { LiquidGlassState.setBlurDp(blurValue) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    GlassSliderRow(
                        title = "扭曲效果",
                        value = distortionValue,
                        valueRange = LiquidGlassState.DISTORTION_RANGE,
                        onValueChange = { distortionValue = it },
                        onValueChangeFinished = { LiquidGlassState.setDistortionDp(distortionValue) },
                    )
                }
            }

            // 底部收尾:28dp 已由 spacedBy 提供,补 36dp 保持总收尾 64dp 不变
            Spacer(Modifier.height(36.dp))
        }
    }

    if (seedPickerOpen) {
        ThemeColorPickerSheet(
            title = "自定义颜色",
            initialColor = config.seedArgb,
            onConfirm = { argb ->
                AppThemeState.setSeed(argb)
                // 关闭由 ThemeColorPickerSheet 内部带动画处理(2026-09-13),
                // 滑出结束经 onDismissRequest → onDismiss 置 seedPickerOpen = false
            },
            onDismiss = { seedPickerOpen = false },
        )
    }
}

/**
 * 主题页设置卡:复用全局 [SettingsCard](卡位圆角 + cardContainer 底色),
 * 内部按示例项目主题页规格(minHeight 64dp / 水平 16dp / 垂直 12dp 且内容垂直居中);
 * [enabled] 为 false 时整卡降透明度表示不可用。
 */
@Composable
private fun ThemeCard(
    position: SettingsCardPosition,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    SettingsCard(
        position = position,
        modifier = Modifier.then(if (enabled) Modifier else Modifier.alpha(DisabledAlpha)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

/** 分组标题行(如"主题颜色") */
@Composable
private fun HeaderRow(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 自定义主题开关行(卡内自带 16dp 内边距,故不套用全局 SettingsSwitchRow) */
@Composable
private fun CustomThemeSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "自定义主题",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 液态玻璃滑条行(2026-09-13 照搬示例 NavStyleScreen 的 NavSliderRow):
 * 标题 + 右侧数值角标(surfaceVariant 小圆角块) + Slider(显式配色与全局滑块一致);
 * 拖动中走本地 state,松手经 [onValueChangeFinished] 落盘。
 */
@Composable
private fun GlassSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = value.roundToInt().toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = 0,
            // 显式配色:原生 Slider 默认 inactiveTrack 走 surfaceContainerHighest 色阶,与全站滑块观感不一
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledThumbColor = MaterialTheme.colorScheme.outline,
                disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

/** 深浅模式选择行:跟随系统 / 浅色 / 深色 */
@Composable
private fun ThemeModeRow(currentMode: Int, onModeSelected: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "主题模式",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        CapsuleSegmentedButton(
            options = listOf(
                SegmentOption(
                    label = "跟随系统",
                    value = ThemeMode.FOLLOW_SYSTEM,
                    iconPainter = painterResource(R.drawable.ic_brightness_auto),
                ),
                SegmentOption(
                    label = "浅色",
                    value = ThemeMode.LIGHT,
                    iconPainter = painterResource(R.drawable.ic_light_mode),
                ),
                SegmentOption(
                    label = "深色",
                    value = ThemeMode.DARK,
                    iconPainter = painterResource(R.drawable.ic_dark_mode),
                ),
            ),
            selectedValue = currentMode,
            onOptionSelected = onModeSelected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 自定义种子色入口行:点击打开取色器 */
@Composable
private fun CustomSeedRow(seedArgb: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "自定义颜色",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        val seedShape = MaterialTheme.shapes.small
        Surface(
            modifier = Modifier
                .size(40.dp)
                .clip(seedShape)
                .clickable(enabled = enabled, onClick = onClick),
            shape = seedShape,
            color = Color(seedArgb),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {}
    }
}

/** 配色风格选择行:横向滚动 FilterChip */
@Composable
private fun VariantSelectorRow(
    currentStyle: PaletteStyle,
    enabled: Boolean,
    onStyleSelected: (PaletteStyle) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "配色风格",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaletteStyles.forEach { (style, label) ->
                FilterChip(
                    selected = currentStyle == style,
                    onClick = { onStyleSelected(style) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }
    }
}

/** 预设色卡网格(4 列 × 2 行);色卡配色由 [AppThemeState.previewScheme] 计算并缓存 */
@Composable
private fun PresetSeedsRow(
    currentSeed: Int,
    style: PaletteStyle,
    enabled: Boolean,
    onSeedSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "预设色卡",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        PresetSeeds.chunked(4).forEachIndexed { rowIndex, rowItems ->
            if (rowIndex > 0) Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { (name, argb) ->
                    // key 含 style:换风格时色卡重建,produceState 才会按新风格重算预览
                    key(argb, style) {
                        PresetSeedCard(
                            name = name,
                            seedArgb = argb,
                            selected = currentSeed == argb,
                            style = style,
                            enabled = enabled,
                            onClick = { onSeedSelected(argb) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 单个预设色卡:动态配色预览(主色条 + 次色/第三色块)+ 选中态(勾选圈) */
@Composable
private fun PresetSeedCard(
    name: String,
    seedArgb: Int,
    selected: Boolean,
    style: PaletteStyle,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewScheme by produceState(
        initialValue = MaterialTheme.colorScheme,
        key1 = seedArgb,
        key2 = style,
    ) {
        value = AppThemeState.previewScheme(seedArgb, style)
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    // 圆角 16dp(2026-09-11 用户定稿;曾为 shapes.medium 12dp → 28dp → 16dp)
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(cardShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = cardShape,
        color = previewScheme.surfaceContainer,
        border = BorderStroke(2.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.6f)
                    .height(10.dp)
                    .background(previewScheme.primary, RoundedCornerShape(5.dp)),
            )
            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(previewScheme.secondary, RoundedCornerShape(3.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(previewScheme.tertiary, RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = previewScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
