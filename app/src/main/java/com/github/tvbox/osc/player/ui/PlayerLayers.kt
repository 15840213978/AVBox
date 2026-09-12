package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

/**
 * 浮层组（照搬旧 tv_pause_container / tv_slide_progress_text / tv_progress_container /
 * loading / tv_play_load_net_speed / tv_back / tv_lock / play_speed_3_container）。
 * 视觉：圆角药丸背景照搬 shape_user_focus（#6C3D3D3D + 白描边）。
 */

private val PillBg = Color(0x6C3D3D3D)
private val PillShape = RoundedCornerShape(50)

@Composable
private fun HintPill(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            // M3 surface 样式：surfaceContainer 90% 透明度 + 轻投影,无描边
            .shadow(4.dp, PillShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
                PillShape
            )
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_10)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * 暂停浮层：左上标题(3行) + 中央播放图标。
 * 中央图标与控制菜单中央组同尺寸同素材（60dp / player_ic_play），位置严格同心；
 * 点按直接恢复播放（免二次点击），点图标以外区域仍由控制器手势唤出菜单。
 */
@Composable
fun PlayerPauseLayer(state: PlayerUiState, actions: PlayerActions) {
    if (!state.pauseOverlayVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = state.pauseTitle,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = playerDim(R.dimen.vs_20) * 2,
                    top = playerDim(R.dimen.vs_20) + playerDim(R.dimen.vs_10),
                )
        )
        // 中央：半透明圆底 + 播放图标（固定 60dp，与中央控制组同款）；点按即播放
        Box(
            Modifier
                .align(Alignment.Center)
                .size(60.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { actions.onPlayPauseClicked() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.player_ic_play),
                contentDescription = "播放",
                modifier = Modifier.size(60.dp * 0.55f),
            )
        }
    }
}

/** 亮度/音量提示（中央 200x100 药丸，替代旧 msg 100/101 + tv_slide_progress_text） */
@Composable
fun PlayerSlideHint(state: PlayerUiState) {
    if (!state.slideHintVisible) return
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(playerDim(R.dimen.vs_200))
                .height(playerDim(R.dimen.vs_100))
                .background(PillBg, PillShape)
                .border(2.dp, Color.White, PillShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.slideHintText,
                color = Color.White,
                fontSize = playerTextSize(R.dimen.ts_30),
            )
        }
    }
}

/** seek 提示（顶部居中 60mm，快进/快退图标 + 时间，替代 msg 1000/1001） */
@Composable
fun PlayerSeekHint(state: PlayerUiState) {
    if (!state.seekHintVisible) return
    Box(Modifier.fillMaxSize()) {
        HintPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = playerDim(R.dimen.vs_60))
        ) {
            Image(
                painter = painterResource(
                    if (state.seekHintForward) R.drawable.exo_icon_fastforward else R.drawable.exo_icon_rewind
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(playerDim(R.dimen.vs_40)),
            )
            Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
            Text(
                text = state.seekHintText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = playerTextSize(R.dimen.ts_30),
            )
        }
    }
}

/** loading（PREPARING/BUFFERING 显示，替代旧 vod_control_loading ProgressBar）；
 *  指示器下方实时网速（2026-09-12 用户需求）：复用 1s 轮询刷新的 netSpeedTopRight，
 *  拖动进度条/缓冲时用户可直观看到取流速度 */
@Composable
fun PlayerLoadingLayer(state: PlayerUiState) {
    if (!state.loadingVisible) return
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(playerDim(R.dimen.vs_50)),
            color = Color.White,
        )
        Text(
            text = state.netSpeedTopRight,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_50) / 2 + playerDim(R.dimen.vs_10)),
        )
    }
}

/** 中央网速（旧 tv_play_load_net_speed：center + marginTop 40mm，仅 IDLE 可见） */
@Composable
fun PlayerNetSpeedCenter(state: PlayerUiState) {
    if (!state.netSpeedCenterVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = state.netSpeedCenter,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_40)),
        )
    }
}

/** 锁屏按钮（右中；三态照搬 showLockView：横屏非 TV 才出现，锁定 3s 后隐藏）。UI v3：尺寸缩至 24dp */
@Composable
fun PlayerLockButton(state: PlayerUiState, actions: PlayerActions) {
    when (state.lockState) {
        LockVisibility.GONE -> return
        LockVisibility.HIDDEN, LockVisibility.SHOWN -> {
            val shown = state.lockState == LockVisibility.SHOWN
            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(
                        if (state.locked) R.drawable.icon_lock else R.drawable.icon_unlock
                    ),
                    contentDescription = "锁屏",
                    alpha = if (shown) 1f else 0f,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp, bottom = playerDim(R.dimen.vs_30))
                        .size(24.dp)
                        .then(
                            if (shown) {
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(onTap = { actions.onLockClicked() })
                                }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

/** 长按倍速浮层（替代 play_speed_3_container / fromLongPress;倍率设置页可调 2x~10x） */
@Composable
fun PlayerSpeedBoostHint(state: PlayerUiState) {
    if (!state.speedBoostVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = "%.1f X".format(state.speedBoostValue),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0x66000000), RoundedCornerShape(12.dp))
                .padding(8.dp),
        )
    }
}
