@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.tvbox.osc.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class CollectViewModel : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodCollect>>(emptyList())

    init {
        EventBus.getDefault().register(this)
        refresh()
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    /** 回顶信号:数据刷新后页面滚动回顶部(自增版本,页面层 collect) */
    val scrollSignal = MutableStateFlow(0)

    /** 补位动画开关:删除时启用(短距离补位),观看/收藏刷新回顶场景关闭(避免长滑行穿顶栏) */
    val placementAnim = MutableStateFlow(false)

    /**
     * [scrollToTop]=true 用于"收藏行为"触发的刷新(落库后发事件):刚收藏的影片会插入到
     * 列表顶部,若保持旧滚动偏移,新顶部的卡片会顶进状态栏区被顶栏遮住(2026-09-12);
     * 删除操作不改变排序语义,保持原位不回滚。
     */
    fun refresh(scrollToTop: Boolean = false) {
        // 仅首屏(列表为空)显示全屏 loading;取消收藏/事件刷新原位更新列表,避免整页转圈闪烁
        if (items.value.isEmpty()) loading.value = true
        if (scrollToTop) placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            items.value = RoomDataManger.getAllVodCollect()
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_COLLECT_REFRESH) refresh(scrollToTop = true)
    }

    /** 取消收藏单条(长按卡片,2026-09-12 用户定稿交互):启用补位动画(短距离) */
    fun deleteOne(item: VodCollect) {
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodCollect(item.id)
            refresh()
        }
    }

    /** 清空全部收藏(右上角删除控件,确认弹窗后执行) */
    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodCollectAll()
            refresh()
        }
    }
}

/**
 * 收藏页(2026-09-12 用户定稿交互改造,与历史页一致):
 * - 右上角删除控件**常驻**:点击弹确认窗,确认后清空全部收藏(全选机制删除);
 * - **长按海报卡**弹确认窗,确认后取消收藏该条;
 * - 卡片增删/重排带 animateItem 动画(与配置管理页一致)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectPage(vm: CollectViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val placementAnim by vm.placementAnim.collectAsState()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VodCollect?>(null) }

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar
    val listState = rememberLazyGridState()

    // 收藏行为刷新后平滑滚回顶部(刚收藏的影片在 index 0,避免保持旧偏移时新卡顶进状态栏区)
    LaunchedEffect(vm) {
        vm.scrollSignal.collect {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "收藏",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        actions = {
            // 删除控件常驻(2026-09-12 用户定稿):确认弹窗后清空全部;单条走长按卡片
            ManageActionIcon(
                iconRes = R.drawable.ic_delete,
                contentDescription = "清空收藏",
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
                state = com.github.tvbox.osc.ui.components.LoadState.Empty,
                emptyText = "暂无收藏",
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_empty_record,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                // 顶部 = 顶栏高度 + 8dp:首卡与顶栏间距与设置页一致(2026-09-12 用户定稿,原 -8+28=+20);
                // 内容可延伸到状态栏下,滚动时从顶栏区域穿过并被顶部遮罩渐隐
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topPad + 8.dp,
                    bottom = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    CollectCard(
                        item = item,
                        // 补位动画按场景开关:删除=开启(短距离补位);收藏刷新回顶=关闭(瞬时)
                        modifier = Modifier.animateItem(
                            fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            placementSpec = if (placementAnim) {
                                spring(stiffness = Spring.StiffnessMediumLow)
                            } else {
                                null
                            },
                            fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        ),
                        onClick = {
                            context.jumpToDetail(item.vodId, item.sourceKey, item.name, item.pic, collect = true)
                        },
                        onLongClick = { deleteTarget = item },
                    )
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = "清空收藏",
            text = "将删除全部收藏，此操作不可恢复",
            onConfirm = { vm.deleteAll() },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = "取消收藏",
            text = "取消收藏「${target.name ?: "未命名"}」？",
            onConfirm = { vm.deleteOne(target) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectCard(
    item: VodCollect,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = item.pic,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f),
                    )
                ),
        )
        Text(
            text = item.name ?: "",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        )
    }
}
