package com.github.tvbox.osc.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import com.github.tvbox.osc.R

/**
 * 播放器面板公共骨架:面板容器/标题/按钮/标签行/chips/步进/输入框/加载指示。
 * 具体面板见 DanmuSheets / SubtitleSheets / CastSheet / EpisodeSheet,同为播放器 Dialog 形态。
 * 视觉规格:vs_50 高圆角描边按钮、选中色 #02F8E1、TV 确认键经 tvConfirmKey 兼容。
 */

// ---------------------------------------------------------------------------
// 公共骨架组件
// ---------------------------------------------------------------------------

/** 对话框面板:vs_10 圆角,dialog_panel_bg 底 + dialog_panel_stroke 描边 */
@Composable
internal fun SheetPanel(
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(width)
            .background(
                colorResource(R.color.dialog_panel_bg),
                RoundedCornerShape(playerDim(R.dimen.vs_10)),
            )
            .border(
                playerDim(R.dimen.vs_1),
                colorResource(R.color.dialog_panel_stroke),
                RoundedCornerShape(playerDim(R.dimen.vs_10)),
            ),
    ) { content() }
}

/** 对话框标题(弹幕设置/投屏居中,选集左对齐) */
@Composable
internal fun SheetTitle(text: String, alignStart: Boolean = false) {
    Text(
        text = text,
        color = colorResource(R.color.dialog_text_primary),
        fontSize = playerTextSize(R.dimen.ts_26),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignStart) TextAlign.Start else TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = playerDim(R.dimen.vs_30)),
    )
}

/** 面板按钮:vs_50 高、圆角描边,选中项 #02F8E1 加粗;TV 确认键 + 触摸点按双通道。*/
@Composable
internal fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    autoFocus: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val m = modifier
        .onFocusChanged { focused = it.isFocused }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
        .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
        .height(playerDim(R.dimen.vs_50))
    Box(modifier = m, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (selected) Color(0xFF02F8E1) else colorResource(R.color.dialog_text_primary),
            fontSize = playerTextSize(R.dimen.ts_20),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { _ -> }
            runCatching { focusRequester?.requestFocus() }
        }
    }
}

/** 左标签行:120mm 右对齐标签 + 右侧 50mm 高控件区 */
@Composable
internal fun SheetLabelRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = playerDim(R.dimen.vs_5), horizontal = playerDim(R.dimen.vs_30)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colorResource(R.color.dialog_text_primary),
            fontSize = playerTextSize(R.dimen.ts_20),
            textAlign = TextAlign.End,
            modifier = Modifier.width(playerDim(R.dimen.vs_120)),
        )
        Row(
            Modifier
                .weight(1f)
                .padding(start = playerDim(R.dimen.vs_20))
                .height(playerDim(R.dimen.vs_50)),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** 横排单选 chips,间距 vs_10 */
@Composable
internal fun SheetChipRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10))) {
        options.forEachIndexed { idx, label ->
            SheetButton(
                text = label,
                selected = idx == selected,
                onClick = { onSelect(idx) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 步进行(减 / 值 / 加;值居中 ts_26) */
@Composable
internal fun SheetStepper(
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SheetButton("-", onClick = onMinus, modifier = Modifier.size(playerDim(R.dimen.vs_50)))
        Text(
            text = valueText,
            color = colorResource(R.color.dialog_text_primary),
            fontSize = playerTextSize(R.dimen.ts_20),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        SheetButton("+", onClick = onPlus, modifier = Modifier.size(playerDim(R.dimen.vs_50)))
    }
}

/** 面板输入框:描边圆角 + hint 6CFFFFFF;IME 搜索键提交 */
@Composable
internal fun SheetInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .background(
                colorResource(R.color.dialog_control_bg),
                RoundedCornerShape(playerDim(R.dimen.vs_6)),
            )
            .border(
                playerDim(R.dimen.vs_1),
                colorResource(R.color.dialog_control_stroke),
                RoundedCornerShape(playerDim(R.dimen.vs_6)),
            )
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_10)),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colorResource(R.color.dialog_text_primary),
                fontSize = playerTextSize(R.dimen.ts_26),
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            color = Color(0x6CFFFFFF),
                            fontSize = playerTextSize(R.dimen.ts_26),
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** 对话框内加载指示 */
@Composable
internal fun SheetLoading(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            color = Color.White,
            strokeWidth = playerDim(R.dimen.vs_2),
        )
    }
}

internal fun Context.findActivityOrNull(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
