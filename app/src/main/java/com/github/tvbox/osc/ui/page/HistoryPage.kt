@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.tvbox.osc.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class HistoryViewModel : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodInfo>>(emptyList())

    init {
        EventBus.getDefault().register(this)
        refresh()
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    /** 回顶信号:数据刷新后页面滚动回顶部(自增版本,页面层 collect) */
    val scrollSignal = MutableStateFlow(0)

    /**
     * 补位动画开关(2026-09-12):删除时启用 placement 位移动画(补位只差一行,短距离
     * 不穿顶栏);观看刷新回顶场景关闭(长距离跳到顶部的滑行会穿过透明顶栏,观感怪异)。
     */
    val placementAnim = MutableStateFlow(false)

    /**
     * [scrollToTop]=true 用于"观看行为"触发的刷新(播放器落库后发事件):刚看的影片会
     * 插入到列表顶部(index 0),若保持旧滚动偏移,新顶部的卡片会顶进状态栏区被顶栏遮住
     * (2026-09-12 用户截图报障);删除操作不改变排序语义,保持原位不回滚。
     */
    fun refresh(scrollToTop: Boolean = false) {
        // 仅首屏(列表为空)显示全屏 loading;删除/事件刷新原位更新列表,避免整页转圈闪烁
        if (items.value.isEmpty()) loading.value = true
        if (scrollToTop) placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val limit = HistoryHelper.getHisNum(KV.get(HawkConfig.HISTORY_NUM, 0))
            val list = RoomDataManger.getAllVodRecord(limit)
            list.forEach { if (!it.playNote.isNullOrEmpty()) it.note = "上次看到" + it.playNote }
            items.value = list
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) refresh(scrollToTop = true)
    }

    /** 删除单条(长按卡片,2026-09-12 用户定稿交互):启用补位动画(短距离) */
    fun deleteOne(item: VodInfo) {
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodRecord(item.sourceKey, item)
            refresh()
        }
    }

    /** 清空全部(右上角删除控件,确认弹窗后执行) */
    fun deleteAll() {
        placementAnim.value = false // 全部淡出,无补位可言
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodRecordAll()
            refresh()
        }
    }

    companion object {
        fun key(item: VodInfo): String = item.sourceKey + "|" + item.id
    }
}

/**
 * 历史页(2026-09-12 用户定稿交互改造):
 * - 右上角删除控件**常驻**:点击弹确认窗,确认后清空全部观看历史(全选机制删除);
 * - **长按卡片**弹确认窗,确认后删除该条记录;
 * - 卡片增删/重排带 animateItem 动画(与配置管理页一致)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(vm: HistoryViewModel = viewModel(), bottomPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val placementAnim by vm.placementAnim.collectAsState()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VodInfo?>(null) }

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar
    val listState = rememberLazyListState()

    // 观看行为刷新后平滑滚回顶部(刚看的影片在 index 0,避免保持旧偏移时新卡顶进状态栏区)
    LaunchedEffect(vm) {
        vm.scrollSignal.collect {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "历史",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {
            // 删除控件常驻(2026-09-12 用户定稿):确认弹窗后清空全部;单条走长按卡片
            ManageActionIcon(
                iconRes = R.drawable.ic_delete,
                contentDescription = "清空历史",
                onClick = { showDeleteAllDialog = true },
            )
        },
    ) { topPad, _ ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
            }

            items.isEmpty() -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = "暂无观看历史",
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_empty_record,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 顶部 = 顶栏高度 + 8dp:首卡与顶栏间距与设置页一致(2026-09-12 用户定稿,原 -8+28=+20);
                // 内容可延伸到状态栏下,滚动时从顶栏区域穿过并被顶部遮罩渐隐
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topPad + 8.dp,
                    // 液态玻璃模式:叠加悬浮栏遮挡高度(MainScreen 下发,M3 栏模式为 0)
                    bottom = 8.dp + bottomPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp), // 卡片间距 12dp(2026-09-09 用户定稿,原 8dp)
            ) {
                items(items, key = { HistoryViewModel.key(it) }) { item ->
                    HistoryRow(
                        item = item,
                        // 淡入淡出动画保留;位移动画禁用(placementSpec=null):观看后记录会从
                        // 列表中部跳到顶部,长距离滑行会穿过顶部透明顶栏/状态栏区域,观感怪异
                        // (2026-09-12 用户反馈"卡片往前顶时变形")
                        modifier = Modifier.animateItem(
                            fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            // 补位动画按场景开关:删除=开启(短距离补位);观看刷新回顶=关闭(瞬时)
                            placementSpec = if (placementAnim) {
                                spring(stiffness = Spring.StiffnessMediumLow)
                            } else {
                                null
                            },
                            fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        ),
                        onClick = {
                            context.jumpToDetail(item.id, item.sourceKey, item.name, item.pic)
                        },
                        onLongClick = { deleteTarget = item },
                    )
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = "清空历史",
            text = "将删除全部观看历史记录，此操作不可恢复",
            onConfirm = { vm.deleteAll() },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = "删除记录",
            text = "删除「${target.name ?: "未命名"}」的观看记录？",
            onConfirm = { vm.deleteOne(target) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    item: VodInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 影视源显示名(2026-09-09:卡片下方第三段;无名称时回退 sourceKey)
    val sourceName = remember(item.sourceKey) {
        val key = item.sourceKey
        if (key.isNullOrEmpty()) "" else ApiConfig.get().getSource(key)?.name ?: key
    }
    // 16dp 圆角卡片容器(2026-09-11 用户定稿,与收藏页海报卡一致);Surface 提供底色,内层 clip 保证 ripple 按圆角裁剪
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .height(IntrinsicSize.Min), // 使右侧文字列与海报等高,三段垂直分布
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.pic,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 三段垂直分布:名称(上)/集数(中)/影视源(下),2026-09-09 用户定稿(原两行居中拥挤)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = item.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.note ?: "", // 集数/播放进度("上次看到第X集")
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 历史/收藏页圆形图标按钮(共用):40dp 圆形容器(surfaceBright 底),图标 22dp(onSurface)。
 * 2026-09-12 交互改造后常驻显示(历史/收藏页右上角 = 清空全部入口)。
 */
@Composable
internal fun ManageActionIcon(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 历史/收藏删除确认窗(共用,2026-09-12 用户定稿):
 * 清空全部与单条删除均需二次确认,防误触。
 */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
