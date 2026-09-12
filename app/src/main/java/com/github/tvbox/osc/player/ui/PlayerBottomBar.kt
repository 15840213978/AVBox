package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import xyz.doikki.videoplayer.util.PlayerUtils.stringForTime

/** SeekBar max 照搬旧布局 android:max="1000" */
private const val SEEK_MAX = 1000

/**
 * 底部菜单（图二布局：进度行在上、菜单行在下）：
 * - 菜单用 FlowRow 自动铺开（SpaceBetween），不再横向滚动；已裁剪 下一集/上一集/重置/屏显
 *   （上/下一集移至中央控制组，重置经片头/片尾长按可达）；
 * - 全控件距屏边缘 ≥16dp；
 * - 按钮可见性全部由 PlayerUiState 衍生规则驱动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    actions: PlayerActions,
    focus: PlayerFocusTargets,
    modifier: Modifier = Modifier,
) {
    if (!state.controlsVisible) return
    Column(
        modifier
            .fillMaxWidth()
            // 轻量化：实底面板改为自下而上的渐变 scrim
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                )
            )
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)
    ) {
        // —— 进度行（时间 - 进度条 - 总时长，横竖屏同款） ——
        // 预览态右侧预留 44dp 给详情页右下角全屏入口图标，进度行与其融合不重叠
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = if (state.previewMode) 44.dp else 0.dp),
        ) {
            // 时间按内容自适应完整显示（照搬哔哩哔哩），进度条 weight 占据剩余宽度
            Text(
                text = stringForTime(state.seekPreviewOrPosition),
                color = Color.White,
                fontSize = playerTextSize(R.dimen.ts_20),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 48.dp),
            )
            PlayerSeekRow(
                state = state,
                actions = actions,
                focus = focus,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                text = stringForTime(state.duration),
                color = Color.White,
                fontSize = playerTextSize(R.dimen.ts_20),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 48.dp),
            )
        }

        // —— 菜单行（FlowRow 自动铺开）；预览态不显示，避免抬高进度条 ——
        if (!state.previewMode) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayerMenuButton(
                "重播",
                onClick = actions::onRetryClicked,
                // 播放按钮已移除，showBottom 默认焦点改落在首个底栏按钮（替代 mNextBtn.requestFocus()）
                focusRequester = focus.nextBtn,
            )
            if (state.landscape) {
                PlayerMenuButton("刷新", onClick = actions::onRefreshClicked)
            }
            if (state.landscape) {
                PlayerMenuButton(
                    state.scaleBtnText,
                    onClick = actions::onScaleClicked,
                    onLongClick = actions::onScaleLongClicked,
                )
            }
            if (state.landscape && state.liveButtonsVisible) {
                PlayerMenuButton(
                    state.speedBtnText,
                    onClick = actions::onSpeedClicked,
                    onLongClick = actions::onSpeedLongClicked,
                )
            }
            PlayerMenuButton(
                state.playerBtnText,
                onClick = actions::onPlayerClicked,
                onLongClick = actions::onPlayerLongClicked,
            )
            if (state.ijkBtnVisible) {
                PlayerMenuButton(state.ijkBtnText, onClick = actions::onIjkClicked)
            }
            if (state.landscape && state.liveButtonsVisible) {
                PlayerMenuButton(
                    state.timeStartText,
                    onClick = actions::onTimeStartClicked,
                    onLongClick = actions::onTimeStartLongClicked,
                )
                PlayerMenuButton(
                    state.timeEndText,
                    onClick = actions::onTimeEndClicked,
                    onLongClick = actions::onTimeEndLongClicked,
                )
            }
            if (state.castBtnVisible) {
                PlayerMenuButton("投屏", onClick = actions::onCastClicked)
            }
            if (state.landscape) {
                PlayerMenuButton(
                    "字幕",
                    onClick = actions::onSubtitleClicked,
                    onLongClick = actions::onSubtitleLongClicked,
                )
            }
            if (state.trackBtnVisible) {
                PlayerMenuButton("音轨", onClick = actions::onAudioTrackClicked)
            }
            if (state.trackBtnVisible) {
                PlayerMenuButton("视轨", onClick = actions::onVideoTrackClicked)
            }
            if (state.danmuBtnVisible) {
                PlayerMenuButton(
                    "弹幕",
                    onClick = actions::onDanmuSettingClicked,
                    onLongClick = actions::onDanmuSettingLongClicked,
                )
            }
            if (state.danmuSearchBtnVisible) {
                PlayerMenuButton(
                    "搜弹幕",
                    onClick = actions::onDanmuSearchClicked,
                    onLongClick = actions::onDanmuSearchLongClicked,
                )
            }
            if (state.landscapePortraitVisible) {
                PlayerMenuButton(state.landscapePortraitText, onClick = actions::onLandscapePortraitClicked)
            }
        }
        }

        // —— 解析行（旧 parse_root + mGridParseView） ——
        if (state.showParseRow && state.landscape) {
            val parseList = remember(state.parseListVersion) { ApiConfig.get().parseBeanList }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "解析",
                    color = Color.White,
                    fontSize = playerTextSize(R.dimen.ts_20),
                    maxLines = 1,
                    modifier = Modifier.padding(end = playerDim(R.dimen.vs_10)),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                    items(parseList.size) { index ->
                        val item = parseList[index]
                        PlayerMenuButton(
                            item.name,
                            onClick = { actions.onParseSelected(index) },
                            focusRequester = if (index == 0) focus.parseFirst else null,
                            textColor = if (item.isDefault) Color(0xFF02F8E1) else Color.White,
                            textSizeId = R.dimen.ts_20,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自绘进度条：视觉照搬 shape_player_control_vod_seek（轨道 #4DFFFFFF / 缓冲 #66FFFFFF /
 * 进度 #FF4081，圆角 2dp）与 CircleThumbDrawable（12dp 白圆 + #FF4081 2dp 描边，激活 16dp）。
 * 交互：触摸拖拽/点按、TV 方向键步进、鼠标滚轮步进（旧 SeekBar 三种方式等价）。
 */
@Composable
private fun PlayerSeekRow(
    state: PlayerUiState,
    actions: PlayerActions,
    focus: PlayerFocusTargets,
    modifier: Modifier,
) {
    val enabled = state.duration > 0
    var focused by remember { mutableStateOf(false) }
    var draggingLocal by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    // 拖拽/按键步进中显示预览位置（两条路径都写入 seekPreviewPositionMs）
    val progress: Float = when {
        state.dragging && state.duration > 0 ->
            state.seekPreviewPositionMs.toFloat() / state.duration * SEEK_MAX
        state.duration > 0 -> state.position.toFloat() / state.duration * SEEK_MAX
        else -> 0f
    }
    val buffered: Float =
        if (state.duration > 0) state.bufferedPercent / 100f * SEEK_MAX else 0f
    val thumbActive = focused || draggingLocal || state.dragging

    var seekModifier = modifier
        .height(playerDim(R.dimen.vs_30))
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) actions.keepControlsAlive()
        }
        .focusProperties {
            up = if (state.showParseRow && state.landscape) focus.parseFirst else focus.nextBtn
        }
        .focusable(enabled = enabled)
        .onKeyEvent { event ->
            if (!enabled) return@onKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                when (event.key) {
                    Key.DirectionLeft -> {
                        actions.onSeekStep(-1); true
                    }
                    Key.DirectionRight -> {
                        actions.onSeekStep(1); true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        .pointerInput(Unit) {
            // 鼠标滚轮步进（旧 onGenericMotionEvent ACTION_SCROLL）
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Scroll && state.duration > 0) {
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                        val dir = when {
                            delta.y != 0f -> if (delta.y > 0) 1 else -1
                            delta.x != 0f -> if (delta.x > 0) 1 else -1
                            else -> continue
                        }
                        actions.onSeekStep(dir)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            // 点按跳转（旧 SeekBar 点按：start → change → stop）
            detectTapGestures { offset ->
                if (state.duration <= 0) return@detectTapGestures
                val target = (offset.x / size.width * SEEK_MAX).toInt().coerceIn(0, SEEK_MAX)
                actions.onSeekStarted()
                actions.onSeekPreview(target)
                actions.onSeekFinished(target)
            }
        }
        .pointerInput(Unit) {
            // 横向拖拽（旧 onStartTrackingTouch/onProgressChanged/onStopTrackingTouch）
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    if (state.duration > 0) {
                        actions.onSeekStarted()
                        draggingLocal = true
                        dragProgress = (offset.x / size.width * SEEK_MAX).coerceIn(0f, SEEK_MAX.toFloat())
                        actions.onSeekPreview(dragProgress.toInt())
                    }
                },
                onDragEnd = {
                    if (draggingLocal) {
                        actions.onSeekFinished(dragProgress.toInt())
                    }
                    draggingLocal = false
                },
                onDragCancel = {
                    if (draggingLocal) {
                        actions.onSeekCancelled()
                    }
                    draggingLocal = false
                },
            ) { change, dragAmount ->
                if (draggingLocal) {
                    dragProgress = (dragProgress + dragAmount / size.width * SEEK_MAX)
                        .coerceIn(0f, SEEK_MAX.toFloat())
                    actions.onSeekPreview(dragProgress.toInt())
                    change.consume()
                }
            }
        }

    Canvas(seekModifier) {
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2
        val corner = CornerRadius(2.dp.toPx())
        // 背景
        drawRoundRect(
            color = Color(0x4DFFFFFF),
            topLeft = Offset(0f, centerY - trackHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = corner,
        )
        // 缓冲
        if (buffered > 0f) {
            drawRoundRect(
                color = Color(0x66FFFFFF),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (buffered / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        // 进度
        if (progress > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(0f, centerY - trackHeight / 2),
                size = androidx.compose.ui.geometry.Size(size.width * (progress / SEEK_MAX), trackHeight),
                cornerRadius = corner,
            )
        }
        // 圆形 thumb（激活时放大；轻量化配色：白圆 + 半透明白描边）
        val thumbRadius = (if (thumbActive) 8.dp else 6.dp).toPx()
        val thumbCenter = Offset(size.width * (progress / SEEK_MAX), centerY)
        drawCircle(Color.White, radius = thumbRadius, center = thumbCenter)
        drawCircle(
            Color.White.copy(alpha = 0.55f),
            radius = thumbRadius,
            center = thumbCenter,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
