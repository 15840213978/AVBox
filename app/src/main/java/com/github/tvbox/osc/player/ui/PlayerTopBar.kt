package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

/**
 * 顶部应用栏（照搬旧 tv_top_l_container / tv_top_r_container 布局与显隐规则）。
 * 阶段 3 直接修复：补 scrim 渐变，亮画面下白字不再糊在视频上。
 *
 * 显隐规则（§4.3，逐条逆向自旧 msg 1002/1003）：
 * - 左块（片名+分辨率）= 底栏可见 OR 竖屏切集临时标题(3s)；暂停时强制隐藏
 * - 右块（网速/进度/系统时间）= 屏显开关 OR 底栏可见（一旦显示过就保持可见）
 */
@Composable
fun PlayerTopBar(state: PlayerUiState, actions: PlayerActions) {
    val anyVisible = state.topLeftVisible || state.topRightVisible
    // 左右边距按窗口宽度分档（竖屏预览 16dp / 横屏全屏与平板 24dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    Box(Modifier.fillMaxWidth()) {
        if (anyVisible) {
            // scrim 渐变（黑 55% → 透明），替代旧实现"无背景白字压画面"
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = edge,
                    end = edge,
                    top = 12.dp,
                    bottom = playerDim(R.dimen.vs_5),
                )
        ) {
            // —— 左块：返回箭头 + 片名 + 分辨率 ——
            if (state.topLeftVisible) {
                Row(Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
                    // 返回箭头：点击等价于遥控器返回键（onBackClicked）
                    Box(
                        Modifier
                            .size(36.dp)
                            .focusable()
                            .tvConfirmKey(actions::onBackClicked, null)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { actions.onBackClicked() })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.player_ic_back),
                            contentDescription = "返回",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column {
                        Text(
                            text = state.title,
                            color = Color.White,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = playerDim(R.dimen.vs_10),
                                top = playerDim(R.dimen.vs_5),
                            )
                        )
                        Text(
                            text = state.videoSize,
                            color = Color.White,
                            fontSize = playerTextSize(R.dimen.ts_20),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = playerDim(R.dimen.vs_10),
                                top = playerDim(R.dimen.vs_5),
                            )
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(3f))
            }
            // —— 右块：网速/进度时间/系统时间 ——
            if (state.topRightVisible) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.netSpeedSideVisible) {
                            TopBarText(state.netSpeedTopRight)
                        }
                        if (state.seekTimeVisible) {
                            TopBarText(state.seekTimeText)
                        }
                        if (state.sysTimeVisible) {
                            TopBarText(state.sysTime)
                        }
                    }
                    if (state.netSpeedTopRightVisible) {
                        TopBarText(state.netSpeedTopRight)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TopBarText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            end = playerDim(R.dimen.vs_10),
            top = playerDim(R.dimen.vs_5),
        )
    )
}
