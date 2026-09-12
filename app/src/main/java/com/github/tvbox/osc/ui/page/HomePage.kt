@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.github.tvbox.osc.ui.page

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.activity.SearchActivity
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.HeroCarousel
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.PressableCard
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SkeletonBox
import com.github.tvbox.osc.ui.theme.cardContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/**
 * [bottomPadding]:液态玻璃模式下悬浮导航栏的遮挡高度(MainScreen 统一下发,M3 栏模式传 0 走布局避让),
 * 叠加到列表 contentPadding 与 FAB 底部偏移,末尾内容不被悬浮栏遮住。
 */
@Composable
fun HomePage(vm: HomeViewModel, bottomPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsState()
    val sources by vm.sources.collectAsState()
    val rec by vm.rec.collectAsState()
    val partitions by vm.partitions.collectAsState()
    val scope = rememberCoroutineScope()

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar
    val listState = rememberLazyListState()

    // 下拉刷新(2026-09-12 用户要求):下拉出现圆形加载指示器(48dp),松手整页重载
    val pullState = rememberPullToRefreshState()

    // 整页加载(2026-09-12 用户定稿:进 App/切源/下拉刷新不显示占位卡片,页面中心
    // 圆形加载指示器,加载完成后消失;开始/结束判定均由 VM.pageLoading 管理)
    val pageLoading by vm.pageLoading.collectAsState()
    // 整页/分区加载失败(看门狗 20s 超时,携带文案)Toast 提示(2026-09-12 用户要求)
    LaunchedEffect(vm) {
        vm.pageErrorEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // BugReview #14:action 卡片结果(旧 GridFragment:Toast + forceRefresh),
    // 补回 Compose 版丢失的观察链路,避免点击后静默死交互
    LaunchedEffect(vm) {
        vm.actionMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.refreshPartitions()
        }
    }

    var collectMenu by remember { mutableStateOf<Pair<Movie.Video, Boolean>?>(null) }

    // 订阅源切换 sheet 开关(2026-09-10:源 chips 行收敛为左上角胶囊入口)
    var showSourceSheet by remember { mutableStateOf(false) }
    // 源级卡片点击策略变更计数:驱动 sheet 内策略标记重组(策略本体存 Hawk)
    var policyTick by remember { mutableStateOf(0) }

    AppTopBarScaffold(
        // 顶栏不折叠(2026-09-12):exitUntilCollapsed 与下拉刷新手势冲突,顶栏会被折死不恢复
        collapseEnabled = false,
        titleContent = {
            // 顶部区:左=订阅源胶囊(点击弹源切换 sheet)(§4.1);右=搜索图标卡片走 actions 槽
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 订阅源胶囊:宽度随源名自适应、上限 220dp(2026-09-12 用户要求"不需要那么长"),
                // 不再占满顶栏剩余宽度;源名超长在 220dp 内省略号截断
                Row(
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.cardContainer)
                        .heightIn(min = 40.dp)
                        .clickable { showSourceSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 胶囊头像:站点 icon 优先,其次接口配置顶层 logo(2026-09-10),均无则回退 Tune 图标
                    val capsuleLogo = currentSource?.icon?.takeIf { it.isNotEmpty() }
                        ?: ApiConfig.get().configLogo.takeIf { it.isNotEmpty() }
                    if (!capsuleLogo.isNullOrEmpty()) {
                        AsyncImage(
                            model = capsuleLogo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(50)),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = currentSource?.name?.takeIf { it.isNotEmpty() } ?: "订阅源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            // 搜索入口:仅图标,surfaceBright 圆形容器,高 40dp(2026-09-11 改为正圆)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceBright)
                    .clickable {
                        context.startActivity(Intent(context, SearchActivity::class.java))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { topPad, _ ->
        when {
            // 整页加载优先(2026-09-12):进 App 后配置/jar 在后台加载,首页页心转圈
            // 覆盖"配置+数据"两段,未配置接口的引导态等加载结束(pageLoading=false)再显示,
            // 避免初始 sources 为空时闪一下引导态。
            // BugFix(2026-09-12 用户反馈"转圈突然下移一下"):不用 padding(top = topPad) 居中——
            // 冷启动首帧状态栏 inset 未派发,topPad 先 64dp 后变"状态栏+64dp",容器中心随之下跳;
            // 改整屏(fillMaxSize)居中,不依赖 inset,首帧即稳定
            pageLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(64.dp))
                }
            }
            sources.isEmpty() -> {
                // 未配置接口:引导态(§4.1 无配置时不进入内容流)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPad),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "尚未配置订阅接口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { ConfigManageActivity.start(context) }) {
                            Text("添加订阅")
                        }
                    }
                }
            }
            else -> {
            // 内容流:推荐 + 全部分类分区(§4.1);顶部留白 = 顶栏高度,内容可延伸到状态栏后被遮罩渐隐
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // 下拉刷新:仅在列表顶部下拉时消费手势,松手触发整页重载(vm.reload 会清运行期缓存)。
                    // 刷新反馈由页心 ContainedLoadingIndicator 承担(vm.pageLoading),
                    // 顶部指示器只做下拉手势形变反馈,松手即收回
                    .pullToRefresh(
                        isRefreshing = false,
                        state = pullState,
                        onRefresh = { vm.reload() },
                    ),
                // 底部留 FAB 悬浮空间,末尾卡片不被遮挡;液态玻璃模式再叠加悬浮栏遮挡高度
                contentPadding = PaddingValues(top = topPad + 8.dp, bottom = 88.dp + bottomPadding),
            ) {
                // Hero 大卡轮播:推荐前 5 部(2026-09-10 揭秘日风格)。
                // BugFix:LazyColumn 以首可见项 key 锚定滚动位置,若 Hero 数据到位后才插入首项,
                // 锚点仍停在原首项(推荐分区),Hero 会被顶到视口上方需手动上滑;
                // 因此 Loading 态就用骨架占位首项,数据到位为原位替换,不再产生插入位移
                item(key = "hero") {
                    if (rec.state == HomeViewModel.PartitionState.Ready && rec.videos.isNotEmpty()) {
                        HeroCarousel(
                            videos = rec.videos.take(5),
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                        )
                    } else if (rec.state == HomeViewModel.PartitionState.Loading) {
                        SkeletonBox(
                            modifier = Modifier
                                .fillParentMaxWidth(0.78f)
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(24.dp)),
                            shape = RoundedCornerShape(24.dp),
                        )
                    } else if (rec.state == HomeViewModel.PartitionState.Error) {
                        // 2026-09-11:看门狗超时错误态——原位替换骨架,提供整页重试入口
                        Box(
                            modifier = Modifier
                                .fillParentMaxWidth(0.78f)
                                .aspectRatio(1.5f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceBright),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadStateBox(
                                state = LoadState.Error(),
                                emptyText = "",
                                errorText = "首页加载超时，请检查网络后重试",
                                retryText = "重试",
                                onRetry = { vm.loadHome() },
                            )
                        }
                    }
                }
                // 推荐分区与 Hero 错位去重:跳过 Hero 已展示的前 5 部
                if (rec.state != HomeViewModel.PartitionState.Empty &&
                    (rec.state == HomeViewModel.PartitionState.Loading || rec.videos.size > 5)
                ) {
                    item(key = "rec") {
                        PartitionSection(
                            title = "推荐",
                            state = rec.state,
                            videos = rec.videos.drop(5),
                            onLoadMore = {},
                            onCardClick = { video -> handleCardClick(vm, video, context) },
                            onCardLongClick = { video ->
                                scope.launch {
                                    val collected = withContext(Dispatchers.IO) {
                                        RoomDataManger.isVodCollect(video.sourceKey, video.id)
                                    }
                                    collectMenu = video to collected
                                }
                            },
                            cardWidth = 140.dp,
                        )
                    }
                }
                items(partitions, key = { it.sort.id }) { p ->
                    PartitionSection(
                        title = p.sort.name ?: "",
                        state = p.state,
                        videos = p.videos,
                        onLoadMore = { vm.loadMorePartition(p) },
                        onCardClick = { video -> handleCardClick(vm, video, context) },
                        onCardLongClick = { video ->
                            scope.launch {
                                val collected = withContext(Dispatchers.IO) {
                                    RoomDataManger.isVodCollect(video.sourceKey, video.id)
                                }
                                collectMenu = video to collected
                            }
                        },
                        onOpenAll = {
                            // 2026-09-09:筛选控件删除,改「全部 >」进栏目二级页(全量分页+筛选)
                            PartitionListActivity.startForPartition(context, p.sort)
                        },
                        onRetry = { vm.retryPartition(p) },
                    )
                }
            }
        }
        }

        // 下拉刷新指示器(2026-09-12):仅内容流态显示(未配置接口的引导态无可刷内容);
        // 刷新转圈反馈已移交页心指示器(vm.pageLoading),此处恒为下拉形变态、松手即收回
        if (sources.isNotEmpty()) {
            HomePullRefreshIndicator(
                state = pullState,
                isRefreshing = false,
                topPadding = topPad,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // 直播入口:右下角图标 FAB(2026-09-10 去文字,仅保留图标;图标换成 .tubiao/直播fab.svg)
        FloatingActionButton(
            onClick = { context.startActivity(Intent(context, LivePlayActivity::class.java)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                // 液态玻璃模式:悬浮栏盖住屏幕底部,FAB 随 bottomPadding 抬到栏上方
                .padding(bottom = bottomPadding),
        ) {
            Icon(painter = painterResource(R.drawable.ic_live_fab), contentDescription = "直播")
        }
    }

    // 订阅源切换 sheet(§4.3 bottom sheet;2026-09-10 改为设置页同款分组卡片风格,末组保留接口配置入口)
    if (showSourceSheet) {
        AVBoxBottomSheet(
            onDismissRequest = { showSourceSheet = false },
            title = "订阅源",
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // 内容自带 LazyColumn(heightIn 420dp),滚动交给它
            isScrollable = false,
        ) {
            // 行内点击改走「带动画关闭」(2026-09-13):先执行动作,面板滑出后再移除,
            // 替代原先直接置 false 的瞬间消失。此处读取发生在 SheetOverlay 的 provider 作用域内
            val dismissAnimated = LocalSheetDismiss.current
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                item {
                    // 源列表:每项一张小卡,按卡位拼圆角(FIRST/MIDDLE/LAST),与设置页一致
                    SettingsGroup(title = "卡片点击状态:进入搜索 / 进入详情(点右侧标记切换)") {
                        sources.forEachIndexed { index, bean ->
                            val selected = bean.key == currentSource?.key
                            val policy = remember(bean.key, policyTick) { VodCardPolicy.policyOf(bean.key) }
                            val position = when {
                                sources.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == sources.size - 1 -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            }
                            SettingsCard(
                                position = position,
                                color = MaterialTheme.colorScheme.surfaceBright,
                            ) {
                                SettingsOptionRow(
                                    title = bean.name ?: bean.key,
                                    selected = selected,
                                    onClick = {
                                        if (!selected) {
                                            vm.switchSource(bean)
                                        }
                                        dismissAnimated()
                                    },
                                    trailing = {
                                        CardPolicyPill(
                                            policy = policy,
                                            modifier = Modifier.padding(end = 6.dp),
                                        ) {
                                            VodCardPolicy.setPolicy(bean.key, policy.toggled())
                                            policyTick++
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    SettingsGroup(title = null) {
                        SettingsCard(
                            position = SettingsCardPosition.SINGLE,
                            color = MaterialTheme.colorScheme.surfaceBright,
                        ) {
                            SettingsRow(
                                title = "配置管理",
                                onClick = {
                                    // 面板滑出与新 Activity 转场同时进行,返回时 sheet 已关闭
                                    dismissAnimated()
                                    ConfigManageActivity.start(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 长按卡片:收藏/操作菜单(§4.1)
    collectMenu?.let { (video, collected) ->
        val options = if (collected) listOf("取消收藏", "搜索相似内容") else listOf("加入收藏", "搜索相似内容")
        com.github.tvbox.osc.ui.components.AVBoxOptionSheet(
            onDismissRequest = { collectMenu = null },
            title = video.name,
            options = options,
            selected = null,
            onSelect = { option ->
                if (option == "加入收藏") {
                    scope.launch(Dispatchers.IO) {
                        RoomDataManger.insertVodCollect(video.sourceKey, toVodInfo(video))
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
                    }
                } else if (option == "取消收藏") {
                    scope.launch(Dispatchers.IO) {
                        RoomDataManger.deleteVodCollect(video.sourceKey, toVodInfo(video))
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
                    }
                } else if (option == "搜索相似内容") {
                    context.jumpToSearch(video.name ?: "")
                }
                // 不在此处置空 collectMenu:AVBoxOptionSheet 选中后会先播放滑出动画,
                // 动画结束才回调 onDismissRequest 清理;此处若直接置 null 会跳过动画
            },
        )
    }
}

private fun toVodInfo(video: Movie.Video): com.github.tvbox.osc.bean.VodInfo {
    val info = com.github.tvbox.osc.bean.VodInfo()
    info.id = video.id
    info.name = video.name
    info.pic = video.pic
    info.sourceKey = video.sourceKey
    return info
}

/**
 * 首页下拉刷新指示器(2026-09-12,用户定稿:进 App 引导页同款 M3 expressive
 * [ContainedLoadingIndicator],md3e 风格 48dp;除播放器页面外全项目圆形加载指示器统一用它)。
 * 滑入/隐藏/裁剪机制复用 [PullToRefreshDefaults.IndicatorBox],但 shape=RectangleShape +
 * 透明底 + 无阴影 → 不产生圆底徽章,观感与引导页加载器一致;
 * 下拉过程按 [PullToRefreshState.distanceFraction] 形变(>1 时整体旋转,同 M3 官方
 * `PullToRefreshDefaults.LoadingIndicator` 的实现),松手刷新中转不定态转圈。
 * 顶栏是透明覆盖层且位于内容之上,整体下移 [topPadding],使指示器从顶栏下沿滑出,
 * 不被左上角订阅源胶囊遮挡(指示器无手势,不拦截列表触摸)。
 */
@Composable
private fun HomePullRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .offset(y = topPadding)
            .size(48.dp),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        elevation = 0.dp,
    ) {
        Crossfade(targetState = isRefreshing) { refreshing ->
            if (refreshing) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            } else {
                ContainedLoadingIndicator(
                    progress = { state.distanceFraction },
                    modifier = Modifier
                        .size(48.dp)
                        .drawWithContent {
                            val progress = state.distanceFraction
                            if (progress > 1f) {
                                rotate(degrees = -(progress - 1f) * 180f) {
                                    this@drawWithContent.drawContent()
                                }
                            } else {
                                drawContent()
                            }
                        },
                )
            }
        }
    }
}

/**
 * 卡片点击:统一分发(2026-09-11 用户定稿)
 * 优先级 = action 卡 > 网盘目录卡(递归下钻) > 源级策略(搜索 / 详情,默认搜索)。
 */
private fun handleCardClick(vm: HomeViewModel, video: Movie.Video, context: android.content.Context) {
    context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
}

/** 源级卡片点击策略标记:点一下在「搜索 / 详情」间切换(详情态高亮 primary) */
@Composable
private fun CardPolicyPill(policy: SourceCardPolicy, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = policy.label,
        style = MaterialTheme.typography.labelMedium,
        color = if (policy == SourceCardPolicy.DETAIL) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PartitionSection(
    title: String,
    state: HomeViewModel.PartitionState,
    videos: List<Movie.Video>,
    onLoadMore: () -> Unit,
    onCardClick: (Movie.Video) -> Unit,
    onCardLongClick: (Movie.Video) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    cardWidth: Dp = 110.dp,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                // 2026-09-09:筛选控件删除,改「全部 >」进栏目二级页
                // 2026-09-11:加半透明 surface 容器背景(同 VodCard 评分条样式)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable(onClick = onOpenAll)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "全部",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        when (state) {
            HomeViewModel.PartitionState.Loading -> {
                // 横排灰卡骨架 shimmer(§4.1 三态)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) {
                        SkeletonBox(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            HomeViewModel.PartitionState.Empty -> Text(
                text = "暂无内容",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HomeViewModel.PartitionState.Error -> {
                // 2026-09-11:看门狗超时错误行 + 单分区重试
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "加载失败，请检查网络",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRetry?.invoke() }) {
                        Text(text = "重试")
                    }
                }
            }

            HomeViewModel.PartitionState.Ready -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(videos) { index, video ->
                    com.github.tvbox.osc.ui.components.VodCard(
                        video = video,
                        onClick = { onCardClick(video) },
                        onLongClick = { onCardLongClick(video) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
                item(key = "more_$title") {
                    LaunchedEffect(videos.size) { onLoadMore() }
                }
            }
        }
    }
}
