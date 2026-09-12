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
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import kotlin.math.roundToInt

/**
 * 预载设置页(2026-09-12 用户定稿):设置 tab 入口,收纳预载与磁盘缓存相关项。
 * 卡片顺序(用户指定):下一集预载 → 预载时长 → 边播边缓存 → 缓存容量。
 */
@Composable
fun PreloadSettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    // 滑块拖动中值(松手才落盘;key 绑定 state,落盘刷新后自动与持久值同步)
    var sliderPreloadDuration by remember(state.preloadDuration) { mutableStateOf(state.preloadDuration) }
    var sliderCacheSize by remember(state.exoCacheSizeMb) { mutableStateOf(state.exoCacheSizeMb) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "预载设置",
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
                        title = "下一集预载",
                        subtitle = "仅EXO播放器和部分源支持",
                        checked = state.preloadNextEpisode,
                        onCheckedChange = { vm.put(HawkConfig.PRELOAD_NEXT_EPISODE, it) },
                    )
                }
                // 预载时长(第二期参数化):20~120s 步长 10,控制下一集预载的数据范围(内存缓冲 + 磁盘写盘)
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = "预载时长",
                        value = sliderPreloadDuration.toFloat(),
                        valueText = "${sliderPreloadDuration}s",
                        valueRange = 20f..120f,
                        steps = 9,
                        onValueChange = { sliderPreloadDuration = ((it - 20) / 10).roundToInt() * 10 + 20 },
                        onValueChangeFinished = {
                            if (sliderPreloadDuration != state.preloadDuration) {
                                vm.put(HawkConfig.PRELOAD_DURATION, sliderPreloadDuration)
                            }
                        },
                    )
                }
                // 边播边缓存(第二期扩展):点播全程走磁盘缓存数据源(直播页不启用);与预载共用同一缓存实例
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = "边播边缓存",
                        checked = state.playCache,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_CACHE, it) },
                    )
                }
                // 缓存容量(第二期扩展):128MB~4GB 步长 128,SimpleCache 创建时固定 → 改动重启 App 生效
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = "缓存容量",
                        value = sliderCacheSize.toFloat(),
                        valueText = if (sliderCacheSize >= 1024) "%.1fGB".format(sliderCacheSize / 1024f) else "${sliderCacheSize}MB",
                        valueRange = 128f..4096f,
                        steps = 30,
                        onValueChange = { sliderCacheSize = ((it - 128) / 128).roundToInt() * 128 + 128 },
                        onValueChangeFinished = {
                            if (sliderCacheSize != state.exoCacheSizeMb) {
                                vm.put(HawkConfig.EXO_CACHE_SIZE_MB, sliderCacheSize)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }
}
