package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.withFrameNanos
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.SelectDialogState

/**
 * 播放器选择弹窗（阶段 7，替代 View 版 SelectDialog，视觉照搬 dialog_select.xml +
 * item_dialog_select.xml + shape_danmu_setting_bg / button_danmu_setting）：
 * 480mm 宽面板、标题、竖向列表（最高 vs_410）、条目圆角描边按钮、选中项高亮 #02F8E1 加粗。
 * 行为照搬：点击已选中项不响应；点击其他项回调后收起；初始聚焦默认选中项。
 */
@Composable
fun PlayerSelectDialog(
    dialogState: SelectDialogState,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(playerDim(R.dimen.vs_480))
                .background(
                    colorResource(R.color.dialog_panel_bg),
                    RoundedCornerShape(playerDim(R.dimen.vs_10)),
                )
                .border(
                    playerDim(R.dimen.vs_1),
                    colorResource(R.color.dialog_panel_stroke),
                    RoundedCornerShape(playerDim(R.dimen.vs_10)),
                )
        ) {
            Text(
                text = dialogState.tip,
                color = colorResource(R.color.dialog_text_primary),
                fontSize = playerTextSize(R.dimen.ts_24),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(playerDim(R.dimen.vs_20)),
            )
            LazyColumn(
                Modifier
                    .padding(horizontal = playerDim(R.dimen.vs_30))
                    .heightIn(max = playerDim(R.dimen.vs_410)),
            ) {
                items(dialogState.items.size) { index ->
                    SelectDialogItem(
                        text = dialogState.items[index],
                        selected = index == dialogState.defaultIndex,
                        onClick = {
                            if (index != dialogState.defaultIndex) {
                                dialogState.onSelected(index)
                                onDismiss()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
        }
    }
}

@Composable
private fun SelectDialogItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(vertical = playerDim(R.dimen.vs_5))
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(
                if (focused) colorResource(R.color.dialog_control_bg_focused)
                else colorResource(R.color.dialog_control_bg),
                RoundedCornerShape(playerDim(R.dimen.vs_6)),
            )
            .border(
                playerDim(R.dimen.vs_1),
                if (focused) colorResource(R.color.dialog_control_stroke_focused)
                else colorResource(R.color.dialog_control_stroke),
                RoundedCornerShape(playerDim(R.dimen.vs_6)),
            )
            .tvConfirmKey(onClick, null)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            }
            .defaultMinSize(minHeight = playerDim(R.dimen.vs_50))
            .padding(playerDim(R.dimen.vs_10)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF02F8E1) else colorResource(R.color.dialog_text_primary),
            fontSize = playerTextSize(R.dimen.ts_22),
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
            else androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
    // 初始聚焦默认选中项（旧 TvRecyclerView.setSelection(select)）
    LaunchedEffect(Unit) {
        if (selected) {
            withFrameNanos { _ -> }
            runCatching { focusRequester.requestFocus() }
        }
    }
}
