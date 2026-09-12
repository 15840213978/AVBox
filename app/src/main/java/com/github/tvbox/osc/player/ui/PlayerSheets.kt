package com.github.tvbox.osc.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.DanmakuApi
import com.github.tvbox.osc.bean.DanmuSearchResult
import com.github.tvbox.osc.bean.Subtitle
import com.github.tvbox.osc.dlna.CastDevice
import com.github.tvbox.osc.dlna.DLNACastManager
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.state.CastSheetState
import com.github.tvbox.osc.player.state.DanmuSearchSheetState
import com.github.tvbox.osc.player.state.DanmuSettingSheetState
import com.github.tvbox.osc.player.state.EpisodeSheetState
import com.github.tvbox.osc.player.state.SubtitleSearchSheetState
import com.github.tvbox.osc.player.state.SubtitleSheetState
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.viewmodel.SubtitleViewModel
import kotlinx.coroutines.delay
import org.greenrobot.eventbus.EventBus

/**
 * 播放器对话框 Compose 化(Step 6 方案 A:替代 View 版 DanmuSetting/SearchDanmu/
 * Subtitle/SearchSubtitle/CastDevice/Episode Dialog)。
 *
 * 全部沿用阶段 7 PlayerSelectDialog 的模式:androidx.compose.ui.window.Dialog 独立窗口
 * (等价旧 View Dialog 的全屏窗口 + 居中面板),视觉照搬旧 XML(480~960mm 面板、
 * dialog_panel_bg 底 + 描边、vs_50 高圆角描边按钮、选中 #02F8E1)。
 * TV 确认键经 tvConfirmKey 兼容;触屏点按、输入法搜索键提交同步支持。
 */

// ---------------------------------------------------------------------------
// 公共骨架组件
// ---------------------------------------------------------------------------

/** 对话框面板(视觉照搬 shape_danmu_setting_bg,同 PlayerSelectDialog 资源) */
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

/**
 * 面板按钮(照搬 item_button/button_danmu_setting):vs_50 高、圆角描边、
 * 选中项 #02F8E1 加粗;TV 确认键 + 触摸点按双通道。
 */
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

/** 左标签行(照搬 dialog_danmu_setting.xml:120mm 右对齐标签 + 右侧 50mm 高控件区) */
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

/** 横排单选 chips(替代旧 TvRecyclerView + ButtonAdapter;间距照搬 vs_10) */
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

/** 步进行(减 - 值 + 加;值居中 ts_26,同旧布局字号行/行数行/透明行) */
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

/** 面板输入框(照搬 input_search 视觉:描边圆角 + hint 6CFFFFFF;IME 搜索键提交) */
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

/** 对话框内加载指示(替代 anim_loading ProgressBar) */
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

private fun Context.findActivityOrNull(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

// ---------------------------------------------------------------------------
// 弹幕设置(替代 View 版 DanmuSettingDialog + dialog_danmu_setting.xml)
// ---------------------------------------------------------------------------

private val DANMU_SPEEDS = listOf(2.4f, 1.8f, 1.5f, 1.0f)

@Composable
fun DanmuSettingSheet(sheet: DanmuSettingSheetState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(width = playerDim(R.dimen.vs_520)) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
                SheetTitle("弹幕设置")
                Spacer(Modifier.height(playerDim(R.dimen.vs_12)))
                // TYPE_SET_DANMU_SETTINGS 第二参数:旧实现颜色行传 true、其余 false(照搬)
                val postSettings: (Boolean) -> Unit = { forColor ->
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SET_DANMU_SETTINGS, forColor))
                }
                var colorIdx by remember { mutableIntStateOf(if (DanmuHelper.useRandomColor()) 1 else 0) }
                var speedIdx by remember {
                    mutableIntStateOf(DANMU_SPEEDS.indexOf(DanmuHelper.getSpeed()).coerceAtLeast(0))
                }
                var size by remember { mutableIntStateOf(Math.round(DanmuHelper.getSizeScale() * 10)) }
                var line by remember { mutableIntStateOf(DanmuHelper.getMaxLine()) }
                var alpha by remember { mutableIntStateOf(Math.round(DanmuHelper.getAlpha() * 100)) }
                val searchFocus = remember { FocusRequester() }

                SheetLabelRow("在线弹幕") {
                    SheetButton(
                        text = "搜索",
                        onClick = {
                            onDismiss()
                            sheet.onOpenSearch()
                        },
                        modifier = Modifier.weight(1f),
                        focusRequester = searchFocus,
                        autoFocus = true,
                    )
                }
                SheetLabelRow("弹幕颜色") {
                    SheetChipRow(listOf("默认", "随机"), colorIdx, onSelect = { idx ->
                        colorIdx = idx
                        DanmuHelper.setRandomColor(idx == 1)
                        postSettings(true)
                    })
                }
                SheetLabelRow("弹幕速度") {
                    SheetChipRow(listOf("超慢", "慢", "适中", "快"), speedIdx, onSelect = { idx ->
                        speedIdx = idx
                        DanmuHelper.setSpeed(DANMU_SPEEDS[idx])
                        postSettings(false)
                    })
                }
                SheetLabelRow("弹幕大小") {
                    SheetStepper(
                        "$size 档",
                        onMinus = {
                            if (size > 6) {
                                size--
                                DanmuHelper.setSizeScale(size / 10f)
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (size < 20) {
                                size++
                                DanmuHelper.setSizeScale(size / 10f)
                                postSettings(false)
                            }
                        },
                    )
                }
                SheetLabelRow("弹幕行数") {
                    SheetStepper(
                        "$line 行",
                        onMinus = {
                            if (line > 1) {
                                line--
                                DanmuHelper.setMaxLine(line)
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (line < 15) {
                                line++
                                DanmuHelper.setMaxLine(line)
                                postSettings(false)
                            }
                        },
                    )
                }
                SheetLabelRow("弹幕透明") {
                    SheetStepper(
                        "$alpha%",
                        onMinus = {
                            if (alpha > 10) {
                                alpha -= 10
                                DanmuHelper.setAlpha(alpha / 100f)
                                postSettings(false)
                            }
                        },
                        onPlus = {
                            if (alpha < 100) {
                                alpha += 10
                                DanmuHelper.setAlpha(alpha / 100f)
                                postSettings(false)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 弹幕搜索(替代 View 版 SearchDanmuDialog + dialog_search_danmu.xml)
// ---------------------------------------------------------------------------

@Composable
fun DanmuSearchSheet(sheet: DanmuSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var word by remember { mutableStateOf(sheet.searchWord) }
    var results by remember { mutableStateOf(emptyList<DanmuSearchResult>()) }
    var loading by remember { mutableStateOf(false) }

    val search: (String) -> Unit = { raw ->
        val w = raw.trim()
        if (w.isEmpty()) {
            Toast.makeText(context, "输入内容不能为空", Toast.LENGTH_SHORT).show()
        } else {
            loading = true
            results = emptyList()
            DanmakuApi.searchList(w, sheet.episode, object : DanmakuApi.SearchListCallback {
                override fun onSuccess(list: List<DanmuSearchResult>?) {
                    mainHandler.post {
                        loading = false
                        results = list ?: emptyList()
                        if (list.isNullOrEmpty()) {
                            Toast.makeText(context, "未查询到匹配弹幕", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(message: String?) {
                    mainHandler.post {
                        loading = false
                        results = emptyList()
                        Toast.makeText(context, message ?: "", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    // 进入即按初始词搜索(旧 setSearchWord);离开时取消在途请求(旧 onBackPressed)
    LaunchedEffect(Unit) {
        if (sheet.searchWord.isNotBlank()) search(sheet.searchWord)
    }
    DisposableEffect(Unit) {
        onDispose { DanmakuApi.cancel() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(
                width = playerDim(R.dimen.vs_960),
                modifier = Modifier.height(playerDim(R.dimen.vs_480)),
            ) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
                Row(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .height(playerDim(R.dimen.vs_50)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetInput(
                        value = word,
                        onValueChange = { word = it },
                        hint = "请输入弹幕名称",
                        modifier = Modifier.weight(1f),
                        onSubmit = { search(word) },
                    )
                    Spacer(Modifier.width(playerDim(R.dimen.vs_5)))
                    SheetButton(text = "搜索", onClick = { search(word) })
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = playerDim(R.dimen.vs_30)),
                ) {
                    if (loading) {
                        SheetLoading(size = playerDim(R.dimen.vs_50))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5))) {
                            itemsIndexed(results) { _, item ->
                                SheetButton(text = item.name, onClick = {
                                    loading = true
                                    DanmakuApi.loadSearchResult(item, object : DanmakuApi.SearchResultCallback {
                                        override fun onSuccess(danmu: String?) {
                                            mainHandler.post {
                                                onDismiss()
                                                if (danmu != null) sheet.onLoad(danmu)
                                            }
                                        }

                                        override fun onError(message: String?) {
                                            mainHandler.post {
                                                loading = false
                                                Toast.makeText(context, message ?: "", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    })
                                })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 字幕设置(替代 View 版 SubtitleDialog + dialog_subtitle.xml)
// ---------------------------------------------------------------------------

@Composable
fun SubtitleSheet(sheet: SubtitleSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exo = sheet.exoInternal
    var sizeText by remember {
        mutableStateOf(if (exo) SubtitleHelper.getExoSubtitleScale().toString() + "%"
        else SubtitleHelper.getTextSize(context.findActivityOrNull()).toString())
    }
    var posText by remember {
        mutableStateOf(if (exo) {
            val p = SubtitleHelper.getExoSubtitlePosition()
            if (p == 0.0f) "0" else "$p%"
        } else "")
    }
    var delayText by remember {
        val d = SubtitleHelper.getTimeDelay()
        mutableStateOf(if (d == 0) "0" else (d / 1000.0).toString())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(width = playerDim(R.dimen.vs_640)) {
                Column(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(playerDim(R.dimen.vs_480))
                        .padding(vertical = playerDim(R.dimen.vs_30)),
                ) {
                    if (sheet.hasInternal) {
                        SheetButton("选择内置字幕", onClick = {
                            onDismiss()
                            sheet.onSelectInternal()
                        }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    }
                    SheetButton("选择本地字幕", onClick = {
                        onDismiss()
                        sheet.onSelectLocal()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    SheetButton("在线搜索字幕", onClick = {
                        onDismiss()
                        sheet.onSelectRemote()
                    }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 字号行:exo 模式百分比 50~200 步 5;外挂字号 12~60 步 2(照搬)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton("字号减小", onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.getExoSubtitleScale() - 5).coerceAtLeast(50)
                                sizeText = "$scale%"
                                SubtitleHelper.setExoSubtitleScale(scale)
                            } else {
                                val cur = (sizeText.toIntOrNull() ?: 16) - 2
                                val next = cur.coerceAtLeast(12)
                                sizeText = next.toString()
                                SubtitleHelper.setTextSize(next)
                            }
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                        Text(
                            text = sizeText,
                            color = colorResource(R.color.dialog_text_primary),
                            fontSize = playerTextSize(R.dimen.ts_26),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton("字号增大", onClick = {
                            if (exo) {
                                val scale = (SubtitleHelper.getExoSubtitleScale() + 5).coerceAtMost(200)
                                sizeText = "$scale%"
                                SubtitleHelper.setExoSubtitleScale(scale)
                            } else {
                                val cur = (sizeText.toIntOrNull() ?: 16) + 2
                                val next = cur.coerceAtMost(60)
                                sizeText = next.toString()
                                SubtitleHelper.setTextSize(next)
                            }
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 样式行:exo = 上移/位置/下移;外挂 = 样式一/样式二(照搬)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton(
                            if (exo) "字幕上移" else "字幕样式一",
                            onClick = {
                                if (exo) {
                                    val position = (SubtitleHelper.getExoSubtitlePosition() + 0.5f).coerceAtMost(80.0f)
                                    SubtitleHelper.setExoSubtitlePosition(position)
                                    posText = if (position == 0.0f) "0" else "$position%"
                                } else {
                                    // 样式一 = 外挂字幕白色(2026-09-12 补回丢失的应用逻辑)
                                    sheet.onSelectStyle(0)
                                    onDismiss()
                                    Toast.makeText(context, "设置样式成功", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(playerDim(R.dimen.vs_140)),
                        )
                        Text(
                            text = posText,
                            color = colorResource(R.color.dialog_text_primary),
                            fontSize = playerTextSize(R.dimen.ts_26),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton(
                            if (exo) "字幕下移" else "字幕样式二",
                            onClick = {
                                if (exo) {
                                    val position = (SubtitleHelper.getExoSubtitlePosition() - 0.5f).coerceAtLeast(-80.0f)
                                    SubtitleHelper.setExoSubtitlePosition(position)
                                    posText = if (position == 0.0f) "0" else "$position%"
                                } else {
                                    // 样式二 = 外挂字幕粉色 #FFB6C1(2026-09-12 补回丢失的应用逻辑)
                                    sheet.onSelectStyle(1)
                                    onDismiss()
                                    Toast.makeText(context, "设置样式成功", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.width(playerDim(R.dimen.vs_140)),
                        )
                    }
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    Text(
                        text = if (exo) "字幕延时对内置字幕有效" else "字幕延时仅对外挂字幕有效",
                        color = colorResource(R.color.dialog_text_hint),
                        fontSize = playerTextSize(R.dimen.ts_20),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                    // 延时行:±0.5s 步进;±0.5*1000ms 增量回调(照搬旧 mseconds 语义)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(playerDim(R.dimen.vs_60)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetButton("字幕提前", onClick = {
                            var time = (delayText.toDoubleOrNull() ?: 0.0) - 0.5
                            SubtitleHelper.setTimeDelay((time * 1000).toInt())
                            delayText = if (time == 0.0) "0" else time.toString()
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                        Text(
                            text = delayText,
                            color = colorResource(R.color.dialog_text_primary),
                            fontSize = playerTextSize(R.dimen.ts_26),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = playerDim(R.dimen.vs_10)),
                        )
                        SheetButton("字幕推迟", onClick = {
                            var time = (delayText.toDoubleOrNull() ?: 0.0) + 0.5
                            SubtitleHelper.setTimeDelay((time * 1000).toInt())
                            delayText = if (time == 0.0) "0" else time.toString()
                        }, modifier = Modifier.width(playerDim(R.dimen.vs_140)))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 字幕搜索(替代 View 版 SearchSubtitleDialog + dialog_search_subtitle.xml;
// 数据链沿用 SubtitleViewModel:assrt 搜索/zip 展开/分页)
// ---------------------------------------------------------------------------

@Composable
fun SubtitleSearchSheet(sheet: SubtitleSearchSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val viewModel: SubtitleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var word by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(emptyList<Subtitle>()) }
    var loading by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("search") } // search | zipfiles
    var page by remember { mutableIntStateOf(1) }
    var canLoadMore by remember { mutableStateOf(false) }
    val zipCache = remember { mutableListOf<Subtitle>() }
    val maxPage = 5
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val search: (String) -> Unit = { raw ->
        val w = raw.trim()
        if (w.isEmpty()) {
            Toast.makeText(context, "输入内容不能为空", Toast.LENGTH_SHORT).show()
        } else {
            mode = "search"
            items = emptyList()
            loading = true
            word = w
            page = 1
            viewModel.searchResult(w, 1)
        }
    }

    // 观察 SubtitleViewModel.searchResult(逻辑照搬旧 initViewModel observer)
    DisposableEffect(viewModel) {
        val observer = androidx.lifecycle.Observer<com.github.tvbox.osc.bean.SubtitleData> { data ->
            mainHandler.post {
                loading = false
                val list = data?.subtitleList
                if (list == null) {
                    Toast.makeText(context, "未查询到匹配字幕", Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (list.isNotEmpty()) {
                    if (data.isZip) {
                        if (data.isNew) {
                            items = list
                            zipCache.clear()
                            zipCache.addAll(list)
                        } else {
                            items = items + list
                            zipCache.addAll(list)
                        }
                        page++
                        if (page > maxPage) {
                            canLoadMore = false
                        } else {
                            canLoadMore = true
                        }
                    } else {
                        items = list
                        canLoadMore = false
                    }
                } else {
                    canLoadMore = false
                }
            }
        }
        viewModel.searchResult.observe(lifecycleOwner, observer)
        onDispose { viewModel.searchResult.removeObserver(observer) }
    }

    // 进入即清洗片名并自动搜索(旧 setSearchWord 的清洗链)
    LaunchedEffect(Unit) {
        var wd = sheet.searchWord
        wd = wd.replace(Regex("(?:（|\\(|\\[|【|\\.mp4|\\.mkv|\\.avi|\\.MP4|\\.MKV|\\.AVI)"), "")
        wd = wd.replace(Regex("(?:：|\\:|）|\\)|\\]|】|\\.)"), " ")
        wd = wd.take(36).trim()
        word = wd
        if (wd.isNotEmpty()) search(wd)
    }

    // zip 展开态按返回回搜索列表(旧 onBackPressed)
    BackHandler(enabled = mode == "zipfiles") {
        mode = "search"
        items = zipCache.toList()
        canLoadMore = page < maxPage
        loading = false
    }

    // 触底加载更多(zip 搜索态)
    val listState = rememberLazyListState()
    LaunchedEffect(listState, canLoadMore, mode, items) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (canLoadMore && mode == "search" && items.isNotEmpty() &&
                    items.firstOrNull()?.isZip == true && last != null && last >= items.size - 3
                ) {
                    canLoadMore = false
                    viewModel.searchResult(word, page)
                }
            }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(
                width = playerDim(R.dimen.vs_960),
                modifier = Modifier.height(playerDim(R.dimen.vs_480)),
            ) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
                Row(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .height(playerDim(R.dimen.vs_50)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SheetInput(
                        value = word,
                        onValueChange = { word = it },
                        hint = "请输入字幕名称",
                        modifier = Modifier.weight(1f),
                        onSubmit = { search(word) },
                    )
                    Spacer(Modifier.width(playerDim(R.dimen.vs_5)))
                    SheetButton(text = "搜索", onClick = { search(word) })
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_10)))
                Box(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = playerDim(R.dimen.vs_30)),
                ) {
                    if (loading) {
                        SheetLoading(size = playerDim(R.dimen.vs_50))
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_5)),
                        ) {
                            itemsIndexed(items) { _, item ->
                                SheetButton(
                                    text = "${item.name}(${if (item.isZip) "压缩包" else "文件"})",
                                    onClick = {
                                        if (item.isZip) {
                                            mode = "zipfiles"
                                            loading = true
                                            viewModel.getSearchResultSubtitleUrls(item)
                                        } else {
                                            // 旧行为:发起直链解析后立即收起,回调在容器侧落地
                                            viewModel.getSubtitleUrl(item) { subtitle ->
                                                mainHandler.post {
                                                    if (subtitle.url != null) sheet.onLoadSubtitle(subtitle)
                                                }
                                            }
                                            onDismiss()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_30)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 投屏(替代 View 版 CastDeviceDialog + dialog_cast.xml;扫描/投送逻辑 1:1 迁移)
// ---------------------------------------------------------------------------

@Composable
fun CastSheet(sheet: CastSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val devices = remember { LinkedHashMap<String, CastDevice>() }
    var deviceList by remember { mutableStateOf(emptyList<CastDevice>()) }
    var searchFinished by remember { mutableStateOf(false) }
    var scanToken by remember { mutableIntStateOf(1) }

    val addDevice: (CastDevice) -> Unit = { device ->
        devices[device.type.toString() + ":" + device.id] = device
        deviceList = devices.values.toList()
    }

    DisposableEffect(scanToken) {
        searchFinished = false
        // TVBox 局域网扫描(旧 searchTvBoxDevices;回调线程切主线程)
        Thread {
            RemoteTVBox.searchAvalible(object : RemoteTVBox.Callback() {
                override fun found(viewHost: String?, end: Boolean) {
                    mainHandler.post {
                        // 记住扫描到的 TVBox 地址(2026-09-13 修复):PlayerHelper 13 号
                        // 「RemoteTVBox 播放器」与 RemoteTVBox.run() 都依赖 HawkConfig.REMOTE_TVBOX,
                        // 而这里曾是它唯一的写入时机 —— Compose 迁移后漏掉了,导致该播放器永远不可用。
                        // 仅当尚未记住时写入,避免多台设备时覆盖用户显式选择。
                        if (!viewHost.isNullOrEmpty() && RemoteTVBox.getAvalible() == null) {
                            RemoteTVBox.setAvalible(viewHost)
                            PlayerHelper.invalidatePlayersExistInfo()
                        }
                        addDevice(CastDevice.tvbox(viewHost))
                    }
                }

                override fun fail(all: Boolean, end: Boolean) {
                    // 旧实现 end 时仅刷新状态,无需处理
                }
            })
        }.start()
        // DLNA 扫描(旧 searchDlnaDevices)
        DLNACastManager.get().setDeviceListener(object : DLNACastManager.DeviceListener {
            override fun onDeviceChanged() {
                mainHandler.post {
                    for (device in DLNACastManager.get().devices) addDevice(device)
                }
            }
        })
        DLNACastManager.get().init(context)
        mainHandler.postDelayed({ DLNACastManager.get().search() }, 1000)
        mainHandler.postDelayed({ searchFinished = true }, 15000)
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            DLNACastManager.get().setDeviceListener(null)
            DLNACastManager.get().release(context)
        }
    }

    val castToDevice: (CastDevice) -> Unit = { device ->
        if (device.type == CastDevice.TYPE_TVBOX) {
            try {
                val headers = sheet.video.headers
                val url = if (headers == null || headers.isEmpty()) sheet.video.url
                else sheet.video.url + "@Headers=" +
                        java.net.URLEncoder.encode(org.json.JSONObject(headers).toString(), "UTF-8") + "@"
                val params = HashMap<String, String>()
                params["do"] = "push"
                params["url"] = url
                RemoteTVBox.post("http://" + device.id + "/action", params, object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        mainHandler.post {
                            Toast.makeText(context, "TVBox投屏失败", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val ok = try {
                            response.body?.string() == "ok"
                        } finally {
                            response.close()
                        }
                        mainHandler.post {
                            if (ok) {
                                // 投屏成功 = 用户显式选中该设备 → 以它为准记住地址(覆盖扫描时的兜底值)
                                RemoteTVBox.setAvalible(device.id)
                                PlayerHelper.invalidatePlayersExistInfo()
                                Toast.makeText(context, "投屏成功", Toast.LENGTH_SHORT).show()
                                sheet.onCastSuccess()
                                onDismiss()
                            } else {
                                Toast.makeText(context, "TVBox投屏失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(context, "TVBox投屏失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            DLNACastManager.get().cast(device, sheet.video, object : DLNACastManager.CastCallback {
                override fun onResult(success: Boolean, msg: String?) {
                    mainHandler.post {
                        if (success) {
                            Toast.makeText(context, "投屏成功", Toast.LENGTH_SHORT).show()
                            sheet.onCastSuccess()
                            onDismiss()
                        } else {
                            Toast.makeText(context, if (msg.isNullOrEmpty()) "投屏失败" else msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SheetPanel(width = playerDim(R.dimen.vs_520)) {
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
                SheetTitle("投屏到设备")
                Spacer(Modifier.height(playerDim(R.dimen.vs_15)))
                Text(
                    text = "确认电视/盒子已安装AVBox或已打开DLNA投屏\n电视/盒子与手机连接在同一Wi-Fi下",
                    color = colorResource(R.color.dialog_text_hint),
                    fontSize = playerTextSize(R.dimen.ts_18),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = playerDim(R.dimen.vs_30)),
                )
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                Box(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_200)),
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10))) {
                        itemsIndexed(deviceList) { _, device ->
                            SheetButton(
                                text = "${device.name}  ${if (device.type == CastDevice.TYPE_DLNA) "DLNA  " else "TVBox  "}${device.id}",
                                onClick = { castToDevice(device) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (deviceList.isEmpty() && !searchFinished) {
                        SheetLoading(size = playerDim(R.dimen.vs_40))
                        Text(
                            text = "正在搜索设备...",
                            color = colorResource(R.color.dialog_text_hint),
                            fontSize = playerTextSize(R.dimen.ts_20),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(top = playerDim(R.dimen.vs_60)),
                        )
                    }
                    if (deviceList.isEmpty() && searchFinished) {
                        Text(
                            text = "未找到可用设备",
                            color = colorResource(R.color.dialog_text_hint),
                            fontSize = playerTextSize(R.dimen.ts_20),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                Row(
                    Modifier
                        .padding(horizontal = playerDim(R.dimen.vs_30))
                        .fillMaxWidth()
                        .height(playerDim(R.dimen.vs_50)),
                    horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_20)),
                ) {
                    SheetButton(text = "刷新", onClick = {
                        devices.clear()
                        deviceList = emptyList()
                        scanToken++
                    }, modifier = Modifier.weight(1f))
                    SheetButton(text = "取消", onClick = { onDismiss() }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(playerDim(R.dimen.vs_24)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 选集(替代 View 版 EpisodeDialog + dialog_episode.xml:右半屏面板 + 左半屏关闭区;
// 列数按最长集名估宽 1~4,照搬旧 getSpanCount)
// ---------------------------------------------------------------------------

@Composable
fun EpisodeSheet(sheet: EpisodeSheetState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(Modifier.fillMaxSize()) {
            // 左半屏:点击收起(旧 episode_dismiss)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(colorResource(R.color.dialog_panel_bg))
                    .padding(start = playerDim(R.dimen.vs_30), top = playerDim(R.dimen.vs_24),
                        end = playerDim(R.dimen.vs_30), bottom = playerDim(R.dimen.vs_24)),
            ) {
                Text(
                    text = sheet.title,
                    color = colorResource(R.color.dialog_text_primary),
                    fontSize = playerTextSize(R.dimen.ts_26),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(playerDim(R.dimen.vs_50)),
                )
                Spacer(Modifier.height(playerDim(R.dimen.vs_20)))
                val gridState = rememberLazyGridState()
                var widthPx by remember { mutableStateOf(0) }
                val spanCount = remember(sheet.episodes, widthPx) {
                    if (widthPx <= 0) 2 else {
                        val res = context.resources
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        paint.textSize = res.getDimension(R.dimen.ts_20)
                        val bounds = Rect()
                        var maxTextWidth = 1
                        for (episode in sheet.episodes) {
                            val name = episode?.name ?: ""
                            if (name.isEmpty()) continue
                            paint.getTextBounds(name, 0, name.length, bounds)
                            if (bounds.width() > maxTextWidth) maxTextWidth = bounds.width()
                        }
                        val itemPadding = res.getDimensionPixelSize(R.dimen.vs_10) * 2
                        val itemMargin = res.getDimensionPixelSize(R.dimen.vs_5) * 2
                        val itemWidth = maxTextWidth + itemPadding + itemMargin
                        (widthPx / itemWidth.coerceAtLeast(1)).coerceIn(1, 4)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(spanCount),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
                    horizontalArrangement = Arrangement.spacedBy(playerDim(R.dimen.vs_10)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { widthPx = it.width },
                ) {
                    itemsIndexed(sheet.episodes) { idx, episode ->
                        SheetButton(
                            text = episode?.name ?: "",
                            selected = idx == sheet.currentIndex,
                            onClick = {
                                onDismiss()
                                sheet.onSelect(idx)
                            },
                        )
                    }
                }
                // 定位当前集(旧 setSelectionWithSmooth)
                LaunchedEffect(sheet.currentIndex) {
                    runCatching { gridState.scrollToItem(sheet.currentIndex) }
                }
            }
        }
    }
}
