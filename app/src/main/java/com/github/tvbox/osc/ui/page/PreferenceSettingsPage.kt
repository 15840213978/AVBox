package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import kotlin.math.roundToInt

/**
 * 偏好设置页(2026-09-12 用户定稿):设置 tab 入口,收纳原设置页的通用偏好项。
 * 卡片顺序(用户指定):自动换线 → M3U8 净化 → 无痕模式 → 弹幕开关 → 弹幕 API → 长按倍速 → 缓冲时间 → 搜索线程。
 */
@Composable
fun PreferenceSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    // 滑块拖动中值(松手才落盘;key 绑定 state,落盘刷新后自动与持久值同步)
    var sliderSpeed by remember(state.longPressSpeed) { mutableStateOf(state.longPressSpeed) }
    var sliderBuffer by remember(state.bufferTimes) { mutableStateOf(state.bufferTimes) }
    var sliderThreads by remember(state.searchThreads) { mutableStateOf(state.searchThreads) }
    var danmuApiDialog by remember { mutableStateOf(false) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "偏好设置",
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
        ) {
            // 顶部占位 = 顶栏高度 + 8dp:首卡与顶栏间距与设置页一致(2026-09-12 用户定稿,原 -8+28=+20)
            Spacer(Modifier.height(topPad + 8.dp))

            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = "自动换线",
                        checked = state.autoSwitchLine,
                        onCheckedChange = { vm.put(HawkConfig.AUTO_SWITCH_LINE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "M3U8 净化",
                        checked = state.m3u8Purify,
                        onCheckedChange = { vm.put(HawkConfig.M3U8_PURIFY, it) },
                    )
                }
                // 无痕模式(2026-09-12):开启后搜索历史与观看历史都不再写入,手动收藏照常
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "无痕模式",
                        checked = state.incognito,
                        onCheckedChange = { vm.put(HawkConfig.INCOGNITO, it) },
                    )
                }
                // 禁用手势控制(2026-09-13):开启后播放器不再响应上下滑调亮度/音量(单击/双击/横滑进度不受影响)
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "禁用手势控制",
                        subtitle = "开启后将禁用手势控制亮度和音量",
                        checked = state.gestureControlDisabled,
                        onCheckedChange = { vm.put(HawkConfig.GESTURE_CONTROL_DISABLED, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "弹幕开关",
                        checked = state.danmuOpen,
                        onCheckedChange = { vm.put(HawkConfig.DANMU_OPEN, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "弹幕 API",
                        valueText = state.danmuApi.ifEmpty { "未设置" },
                        onClick = { danmuApiDialog = true },
                    )
                }
                // 长按倍速(2026-09-12):长按画面临时提速倍率,2x~10x 步长 1(9 档);松手落盘,长按触发时实时读 KV
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = "长按倍速",
                        value = sliderSpeed.toFloat(),
                        valueText = "${sliderSpeed}x",
                        valueRange = 2f..10f,
                        steps = 7,
                        onValueChange = { sliderSpeed = (it - 2).roundToInt() + 2 },
                        onValueChangeFinished = {
                            if (sliderSpeed != state.longPressSpeed) {
                                vm.put(HawkConfig.LONG_PRESS_SPEED, sliderSpeed)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = "缓冲时间",
                        value = sliderBuffer.toFloat(),
                        valueText = "${sliderBuffer}x",
                        valueRange = 1f..10f,
                        steps = 8,
                        onValueChange = { sliderBuffer = (it - 1).roundToInt() + 1 },
                        onValueChangeFinished = {
                            if (sliderBuffer != state.bufferTimes) {
                                vm.put(HawkConfig.BUFFER_TIMES, sliderBuffer)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = "搜索线程",
                        value = sliderThreads.toFloat(),
                        valueText = "$sliderThreads",
                        valueRange = 16f..64f,
                        steps = 2,
                        onValueChange = { sliderThreads = ((it - 16) / 16).roundToInt() * 16 + 16 },
                        onValueChangeFinished = {
                            if (sliderThreads != state.searchThreads) {
                                vm.put(HawkConfig.SEARCH_THREADS, sliderThreads)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    if (danmuApiDialog) {
        TextEditDialog(
            title = "弹幕 API",
            initialText = state.danmuApi,
            onDismiss = { danmuApiDialog = false },
            onConfirm = { text ->
                vm.put(HawkConfig.DANMU_API, text)
                danmuApiDialog = false
            },
        )
    }
}
