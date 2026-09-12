package com.github.tvbox.osc.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * BottomSheet 统一封装(avbox-mobile-ui-spec §4.3):固定顶部 28dp 圆角、默认完全展开。
 *
 * 2026-09-11 用户定稿(方案 B):**不再用 M3 ModalBottomSheet 的独立 dialog window**,
 * 改为应用窗口内的覆盖层(scrim + 滑入面板 + 拖拽/返回关闭)。原因:sheet 弹在独立窗口时
 * 状态栏图标外观会随"外观归属窗口"切换被系统重设,弹出/关闭时图标闪烁两次(所有 sheet 稳定复现);
 * 覆盖层与页面同窗口,状态栏外观不再变化,也顺带修掉"主页开 sheet 时图标被写成白色"的隐患。
 *
 * 宿主要求:调用方所在子树须覆盖全屏(页面根部)。页面若位于被裁剪/带底栏的容器内
 * (如 MainScreen 的 pager tab),由上层窗口根部提供 [SheetHost] 槽位即可,调用方零改动。
 *
 * 2026-09-11 补丁(装机反馈:筛选 sheet 的「确定」够不到):手写覆盖层丢掉了 M3 ModalBottomSheet
 * 自带的"面板限高 + 内容滚动",长内容(如电影/电视筛选 20+ 个分组)会被裁在屏幕外且滚不动,
 * 排在内容末尾的操作行(清除/确定)永远不可见。现由封装统一兜底:
 * ①面板限高为窗口的 [SheetMaxHeightFraction];②把手与标题固定,内容区在超高时自动可滚;
 * ③下滑关闭手势只挂在把手/标题区,避免抢走内容区的纵向滚动(原先挂在整个面板上)。
 * 调用方自带滚动容器(LazyColumn/LazyVerticalGrid)时传 [isScrollable] = false,避免与其抢手势。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVBoxBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    // 为 null 时使用 M3 默认容器色;页面可传 surfaceContainer 等覆盖
    containerColor: Color? = null,
    // 内容区是否由本封装提供纵向滚动:内容自带滚动容器(LazyColumn/LazyVerticalGrid 等)时传 false
    isScrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalSheetHost.current
    if (host == null) {
        SheetOverlay(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            containerColor = containerColor,
            isScrollable = isScrollable,
            content = content,
        )
    } else {
        // 提交到窗口根部槽位渲染:页面被 pager/底栏裁剪时也能覆盖全屏
        val id = remember { Any() }
        SideEffect {
            host.submit(
                SheetRequest(id, onDismissRequest, modifier, title, containerColor, isScrollable, content),
            )
        }
        DisposableEffect(id) {
            onDispose { host.clear(id) }
        }
    }
}

/**
 * bottom sheet 单选列表(avbox-mobile-ui-spec §4.3:点开单选)。
 * 2026-09-12 用户定稿:视觉对齐首页「订阅源」弹窗 —— 面板底色 `surfaceContainer`,
 * 选项收成一组分组卡片(左右距屏 16dp、卡色 `surfaceBright`、卡间距 2dp 由 [SettingsGroup] 统一)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AVBoxOptionSheet(
    onDismissRequest: () -> Unit,
    title: String?,
    options: List<String>,
    selected: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SettingsCard(
                    position = optionCardPosition(index, options.size),
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = option,
                        selected = option == selected,
                        onClick = {
                            onSelect(option)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

/** 选项卡位:与首页订阅源弹窗同规则,按首/中/末卡拼圆角 */
private fun optionCardPosition(index: Int, size: Int): SettingsCardPosition = when {
    size <= 1 -> SettingsCardPosition.SINGLE
    index == 0 -> SettingsCardPosition.FIRST
    index == size - 1 -> SettingsCardPosition.LAST
    else -> SettingsCardPosition.MIDDLE
}

/** sheet 面板最大宽度(M3 ModalBottomSheet sheetMaxWidth 默认值) */
private val SheetMaxWidth = 640.dp

/** 面板最大高度占窗口高度的比例(对齐 M3 ModalBottomSheet 默认:留出顶部余量,不顶到状态栏) */
private const val SheetMaxHeightFraction = 0.9f

/** 面板滑入/滑出时长(对齐 M3 bottom sheet 动画时长) */
private const val SHEET_SLIDE_DURATION_MS = 280

/** 收起比例超过该值即视为拖拽关闭(对齐 M3 手势判定) */
private const val SHEET_DRAG_DISMISS_FRACTION = 0.25f/** 收起比例很小但快速下滑也视为关闭(px/s) */
private const val SHEET_DRAG_DISMISS_VELOCITY = 1400f

/** sheet 请求:页面内 AVBoxBottomSheet 提交,由窗口根部的 [SheetHost] 渲染 */
internal class SheetRequest(
    val id: Any,
    val onDismissRequest: () -> Unit,
    val modifier: Modifier,
    val title: String?,
    val containerColor: Color?,
    val isScrollable: Boolean,
    val content: @Composable ColumnScope.() -> Unit,
)

/** 窗口根部 sheet 槽位状态(见 [LocalSheetHost]) */
@Stable
class SheetHostState {
    internal var request by mutableStateOf<SheetRequest?>(null)
        private set

    internal fun submit(newRequest: SheetRequest) {
        request = newRequest
    }

    internal fun clear(id: Any) {
        if (request?.id === id) request = null
    }
}

/** 页面被 pager/底栏等裁剪时,由上层在窗口根部提供槽位,sheet 改在该处渲染(覆盖全屏) */
val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/** 窗口根部槽位宿主(通常放在 MainScreen 内容最外层,需能覆盖底栏与系统栏) */
@Composable
fun SheetHost(state: SheetHostState) {
    state.request?.let { req ->
        SheetOverlay(
            onDismissRequest = req.onDismissRequest,
            modifier = req.modifier,
            title = req.title,
            containerColor = req.containerColor,
            isScrollable = req.isScrollable,
            content = req.content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetOverlay(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    containerColor: Color? = null,
    isScrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 面板收起比例:1 = 完全移出屏幕下方,0 = 完全展开。
    // 用"比例"而非"像素":面板高度由 graphicsLayer 的 size 在绘制期提供,不依赖
    // onGloballyPositioned 的测量时序(此前测量回调若缺失,面板会永远停在屏外)
    val collapse = remember { Animatable(1f) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var entered by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        collapse.animateTo(0f, tween(SHEET_SLIDE_DURATION_MS))
        entered = true
    }

    /** 用户主动关闭(scrim/返回/拖拽):先滑出再通知宿主,避免瞬间消失 */
    fun dismissWithAnimation() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            collapse.animateTo(1f, tween(SHEET_SLIDE_DURATION_MS))
            onDismissRequest()
        }
    }

    // 入场动画期间不响应返回,避免与"滑出"动画叠在一起
    BackHandler(enabled = entered) { dismissWithAnimation() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 遮罩:随收起比例淡出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - collapse.value }
                .background(BottomSheetDefaults.ScrimColor)
                .clickable(
                    // 入场完成前不拦截触摸:即使面板因异常停在屏外,页面也不会被"隐形遮罩"锁死
                    enabled = entered,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { dismissWithAnimation() },
                ),
        )
        // 面板限高(2026-09-11):此前仅受父级 fillMaxSize 约束,超长内容会被裁在屏幕外且无法滚动
        val panelMaxHeight = (LocalConfiguration.current.screenHeightDp * SheetMaxHeightFraction).dp
        Surface(
            modifier = Modifier
                .then(modifier)
                .widthIn(max = SheetMaxWidth)
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight)
                .graphicsLayer { translationY = collapse.value * size.height }
                .onGloballyPositioned { panelHeightPx = it.size.height },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = containerColor ?: BottomSheetDefaults.ContainerColor,
        ) {
            // 内容区不加导航栏 inset(2026-09-11 用户定稿):列表可滑到手势条下面,手势条浮在内容上(沉浸);
            // 面板底色本来就铺到屏幕最底,各 sheet 内容自带 16dp 底部 padding 保证收尾间距
            Column {
                // 把手 + 标题固定不滚动,并独占下滑关闭手势(2026-09-11):
                // 手势原先挂在整个面板上,会与内容区的纵向滚动抢夺触摸,内层列表滚不动
                Column(
                    modifier = Modifier.draggable(
                        state = sheetDragState(collapse, entered, panelHeightPx),
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val dismiss = collapse.value > SHEET_DRAG_DISMISS_FRACTION ||
                                    velocity > SHEET_DRAG_DISMISS_VELOCITY
                            if (dismiss) {
                                dismissWithAnimation()
                            } else {
                                scope.launch { collapse.animateTo(0f, tween(SHEET_SLIDE_DURATION_MS)) }
                            }
                        },
                    ),
                ) {
                    // 把手居中:M3 的 DragHandle 自身仅 32dp 宽(内部靠 align(Center) 定位),
                    // 需外层拉满宽度再居中,否则会贴到面板左边
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BottomSheetDefaults.DragHandle()
                    }
                    title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (isScrollable) {
                    // 内容区高度交给 weight,超高时把面板顶到限高并在区内滚动;
                    // 内容自带滚动容器时传 isScrollable = false,让内层自己滚
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        content = content,
                    )
                } else {
                    Column(content = content)
                }
            }
        }
    }
}

/**
 * 面板下滑的拖拽状态(把手/标题区专用):把纵向位移按面板高度换算成收起比例。
 * 面板高度为 0(尚未测量)或入场动画未结束时不响应,避免出现"拖不动"或与入场动画打架。
 */
@Composable
private fun sheetDragState(
    collapse: Animatable<Float, AnimationVector1D>,
    entered: Boolean,
    panelHeightPx: Int,
): DraggableState {
    val scope = rememberCoroutineScope()
    return rememberDraggableState { delta ->
        if (entered && panelHeightPx > 0) {
            scope.launch {
                collapse.snapTo(
                    (collapse.value + delta / panelHeightPx).coerceIn(0f, 1f),
                )
            }
        }
    }
}

