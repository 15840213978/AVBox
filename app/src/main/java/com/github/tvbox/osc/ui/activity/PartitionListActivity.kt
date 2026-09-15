@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.ui.components.FilterSheet
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.VodCardMenu
import com.github.tvbox.osc.ui.components.rememberVodCardMenuState
import com.github.tvbox.osc.ui.page.PartitionListVM
import com.github.tvbox.osc.ui.page.dispatchVodCardClick
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 栏目资源二级页(2026-09-09):首页分区/搜索结果分区右侧「全部 >」进入。
 * partition 模式:携带 SortData JSON,重新经 SourceViewModel.getList 全量分页加载(可筛选);
 * folder 模式(2026-09-11):网盘目录下钻,以目录 id 当分类 id 走同一条加载链,可逐级递归;
 * search 模式:直接携带该源全部搜索结果 JSON,纯网格展示。
 */
class PartitionListActivity : BaseActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SORT = "sort_json"
        private const val EXTRA_VIDEOS = "videos_json"
        const val MODE_PARTITION = "partition"
        const val MODE_SEARCH = "search"
        const val MODE_FOLDER = "folder"

        /** 首页分区入口:携带分类/筛选数据,二级页全量分页加载 */
        fun startForPartition(context: Context, sort: MovieSort.SortData) {
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_PARTITION)
                putExtra(EXTRA_TITLE, sort.name ?: "")
                putExtra(EXTRA_SORT, Gson().toJson(sort))
            })
        }

        /** 网盘目录入口:folderId 即上一层目录条目的 id(与上游 openFolder 同语义) */
        fun startForFolder(context: Context, folderId: String, folderName: String) {
            val sort = MovieSort.SortData(folderId, folderName)
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_FOLDER)
                putExtra(EXTRA_TITLE, folderName)
                putExtra(EXTRA_SORT, Gson().toJson(sort))
            })
        }

        /** 搜索结果源分区入口:直接携带该源全部搜索结果 */
        fun startForSearch(context: Context, videos: List<Movie.Video>, title: String) {
            context.startActivity(Intent(context, PartitionListActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SEARCH)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_VIDEOS, Gson().toJson(videos))
            })
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
        // 手机端保留系统栏(§3)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                PartitionListScreen(
                    mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_PARTITION,
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                    sortJson = intent.getStringExtra(EXTRA_SORT),
                    videosJson = intent.getStringExtra(EXTRA_VIDEOS),
                )
            }
        }
    }
}

@Composable
private fun PartitionListScreen(mode: String, title: String, sortJson: String?, videosJson: String?) {
    val context = LocalContext.current
    val vm: PartitionListVM = viewModel()
    var filterOpen by remember { mutableStateOf(false) }
    val ui by vm.ui.collectAsState()
    // 长按卡片菜单(收藏/搜索相似内容):与首页共用同一组件(2026-09-16)
    val vodMenu = rememberVodCardMenuState()

    // partition / folder 模式:反序列化分类并首次加载(幂等,重建后 VM 存活则跳过)
    LaunchedEffect(sortJson) {
        if (mode != PartitionListActivity.MODE_SEARCH && sortJson != null) {
            vm.initIfNeed(Gson().fromJson(sortJson, MovieSort.SortData::class.java))
        }
    }
    // action 卡(网盘配置卡等)结果提示;列表刷新由 VM 在 action 回调里自行触发
    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    // search 模式:一次性反序列化结果列表
    val searchVideos = remember(videosJson) {
        if (videosJson == null) {
            emptyList()
        } else {
            val type = object : TypeToken<List<Movie.Video>>() {}.type
            Gson().fromJson<List<Movie.Video>>(videosJson, type)
        }
    }

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar

    AppTopBarScaffold(
        // 需遮住 BaseActivity.onResume 设置的旧版 app_bg 窗口背景
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall, // 24sp/700(2026-09-11 与其他页面大标题统一)
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            BarActionBox(R.drawable.ic_arrow_left, "返回") {
                (context as? Activity)?.finish()
            }
        },
        actions = {
            if (mode == PartitionListActivity.MODE_PARTITION) {
                val selectedCount = vm.sort?.filterSelectCount() ?: 0
                BarActionBox(
                    R.drawable.ic_filter,
                    "筛选",
                    // 选中筛选条件时图标高亮 primary
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ) { filterOpen = true }
            }
        },
    ) { topPad, _ ->
        // 网格列表(3 列海报卡);顶部留白 = 顶栏高度(内容可延伸到状态栏下)
        when {
            mode == PartitionListActivity.MODE_SEARCH -> VideoGrid(
                videos = searchVideos,
                // search 模式:走搜索链路入口(只特判目录卡,其余进详情)
                onCardClick = { video -> context.openVodCardOrDetail(video) },
                onCardLongClick = { video -> vodMenu.show(video) },
                onLoadMore = {},
                // 顶栏高度 - 20dp(+网格内部 28dp = 首卡距顶栏 8dp,与设置页一致;2026-09-12 用户定稿)
                topPadding = topPad - 20.dp,
            )

            ui.state == PartitionListVM.State.Loading -> LoadStateBox(
                state = LoadState.Loading,
                emptyText = "",
                errorText = "",
                retryText = "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            ui.state == PartitionListVM.State.Empty -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = "暂无内容",
                errorText = "",
                retryText = "",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            else -> VideoGrid(
                videos = ui.videos,
                // 2026-09-11:与首页共用统一分发(action > 网盘目录下钻 > 源级策略 搜索/详情)
                onCardClick = { video -> context.dispatchVodCardClick(video, onAction = { vm.runAction(it) }) },
                onCardLongClick = { video -> vodMenu.show(video) },
                onLoadMore = { vm.loadMore() },
                enableLoadMore = true,
                // 顶栏高度 - 20dp(+网格内部 28dp = 首卡距顶栏 8dp,与设置页一致;2026-09-12 用户定稿)
                topPadding = topPad - 20.dp,
            )
        }
    }

    // 长按卡片:收藏/操作菜单(页面根部渲染,覆盖全屏)
    VodCardMenu(vodMenu)

    if (filterOpen) {
        vm.sort?.let { sort ->
            FilterSheet(
                sort = sort,
                onDismiss = { filterOpen = false },
                onConfirm = { selection ->
                    // 关闭由 FilterSheet 内部带动画处理(2026-09-13),这里只应用筛选
                    vm.applyFilter(selection)
                },
            )
        }
    }
}

/**
 * 顶栏操作按钮:40dp 圆角容器(surfaceBright 底),图标 22dp(2026-09-11,
 * 替代裸 IconButton;返回/筛选共用)。
 */
@Composable
private fun BarActionBox(
    iconRes: Int,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun VideoGrid(
    videos: List<Movie.Video>,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onLoadMore: () -> Unit = {},
    enableLoadMore: Boolean = false,
    topPadding: Dp = 0.dp,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = rememberLazyGridState(),
        // 顶部 = topPadding + 28dp:topPadding 由调用方按「与设置页首卡间距一致」口径传入(2026-09-12)
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding + 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // key 拼入索引:同分区 sourceKey 恒同,id/name 可能为空或重复,纯字段拼接会撞 key 崩溃
        itemsIndexed(videos, key = { i, video -> "${i}_${video.id}_${video.name}" }) { _, video ->
            VodCard(
                video = video,
                onClick = { onCardClick(video) },
                onLongClick = { onCardLongClick(video) },
            )
        }
        if (enableLoadMore) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                // 尾部哨兵:进入组合即触发加载更多(与首页分区同策略)
                LaunchedEffect(videos.size) { onLoadMore() }
            }
        }
    }
}
