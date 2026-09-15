@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.player.ui.playerDim
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.page.openVodCardOrDetail
import com.github.tvbox.osc.ui.player.PlayerTipBridge

// ================= UI =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activity: DetailActivity, vm: DetailViewModel) {
    val pageState by vm.pageState.collectAsState()
    val full by vm.fullScreen.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val revision by vm.revision.collectAsState()
    val playSignal by vm.playSignal.collectAsState()
    val toast by vm.toastEvent.collectAsState()
    val finish by vm.finishEvent.collectAsState()

    // 播放器区形态(必须与 DetailActivity.isFullBox() 同一判定):过渡期跟随实际方向,
    // 落地后才切目标态 —— 否则会在横屏窗口里算出竖屏的 16:9 盒(高度超屏 → 视频缩放跳动)
    val configuration = LocalConfiguration.current
    val isLandscapeNow = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullBox = if (rotating) isLandscapeNow else full
    // 预览态播放区高度:短边 × 16:9,钳制在 [150dp, 长边/2] —— 与窗口方向无关,
    // 即使形态被切也是合法小矩形(不会"宽推高 → 超出屏幕")
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewBoxHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))

    // 容器随首次组合创建;Activity 重建时 remember 重置,自动重建并补播
    val container = remember { activity.ensurePlayContainer().also { vm.playContainerRef = it } }

    LaunchedEffect(container, playSignal) {
        if (playSignal > 0) activity.playCurrent()
    }

    LaunchedEffect(full) {
        activity.applyFullscreen(full)
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(activity, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(finish) {
        if (finish) {
            vm.consumeFinish()
            activity.finish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        // 顶部 16:9 播放器:全屏占满整屏,预览态高度由 previewBoxHeight 显式给出
        Box(
            modifier = if (fullBox) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewBoxHeight)
                    .background(Color.Black)
            },
        ) {
            AndroidView(
                factory = { container },
                modifier = Modifier.fillMaxSize(),
            )
            if (pageState is DetailViewModel.PageState.Loading && !fullBox) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    // 纯黑底上默认配色过亮,统一用低透明白
                    ContainedLoadingIndicator(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        indicatorColor = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            // 取流 loading / 播放错误覆盖层:无 pointer 处理,触摸穿透到控制器;
            // 声明在全屏入口之前,不遮挡其点击
            PlayerTipOverlay()
            // 预览态不要盖透明点击层:会拦掉下方控制器全部触摸,单击显隐由控制器手势处理
            if (!fullBox) {
                // 右下角全屏入口。bottom 与预览态进度条水平线对齐:16dp + 进度条高(vs_30)/2 - 图标盒 40dp/2,
                // 改播放页底栏边距时此处要同步(DetailActivity 全屏入口同式)
                Icon(
                    painter = painterResource(R.drawable.ic_player_expand),
                    contentDescription = "全屏播放",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp + playerDim(R.dimen.vs_30) / 2 - 20.dp)
                        .size(40.dp)
                        .clickable { vm.setFullScreen(true) }
                        .padding(9.dp),
                )
            }
        }

        if (!fullBox) {
            when (val state = pageState) {
                is DetailViewModel.PageState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ContainedLoadingIndicator()
                    }
                }

                is DetailViewModel.PageState.Empty -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = state.msg ?: "暂无片源,可尝试换源或搜索",
                            errorText = "",
                            retryText = "",
                            modifier = Modifier.weight(1f),
                        )
                        SourceSection(vm, currentSourceName = null, revision = revision)
                    }
                }

                is DetailViewModel.PageState.Ready -> {
                    DetailContent(activity, vm, revision)
                }
            }
        }
    }

    EpisodeSheet(vm, revision)
}

@Composable
private fun DetailContent(activity: DetailActivity, vm: DetailViewModel, revision: Int) {
    val info = vm.vodInfo ?: return
    // revision 仅用于触发重组(vodInfo 为可变 bean)
    @Suppress("UNUSED_EXPRESSION") revision

    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex
    val qualityOptions by vm.qualityOptions.collectAsState()
    val qualitySelected by vm.qualitySelected.collectAsState()
    val collected by vm.collected.collectAsState()
    var descExpanded by rememberSaveable { mutableStateOf(false) }

    val currentSource = ApiConfig.get().getSource(vm.firstsourceKey)
    val displaySourceName = currentSource?.name ?: vm.firstsourceKey

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // ---- 标题 / 来源 / 简介：surfaceBright 圆角卡片(距屏 16dp) ----
        item(key = "header") {
            // 必须 remember(info.des):removeHtmlTag 含正则编译 + Html.fromHtml,
            // 而 revision 会随切集/换源等频繁 bump。须在 item 内(LazyListScope 非 Composable 上下文)
            val desc = remember(info.des) { removeHtmlTag(info.des) }
            Column(
                modifier = Modifier
                    .padding(start = 6.dp, end = 6.dp, top = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 标题 + 投屏 + 收藏
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.name ?: "TVBox",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 投屏:复用播放器「投屏」面板(showCast → CastSheet),DLNA/TVBox 扫描投送同一条链路
                    IconButton(onClick = { activity.playContainer?.showCast() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_detail_cast),
                            contentDescription = "投屏",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    IconButton(onClick = { vm.toggleCollect() }) {
                        // 未收藏＝描边星，已收藏＝实心星＋主题色（描边 tint 对空心图标不直观，用户反馈）
                        AnimatedContent(
                            targetState = collected,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith
                                        (scaleOut(targetScale = 0.6f) + fadeOut())
                            },
                            label = "collectIcon",
                        ) { isCollected ->
                            Icon(
                                painter = painterResource(
                                    if (isCollected) R.drawable.ic_tab_collect_filled else R.drawable.ic_tab_collect
                                ),
                                contentDescription = if (isCollected) "取消收藏" else "加入收藏",
                                tint = if (isCollected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                // 来源 pill(surfaceContainer) + 其余元信息
                val metaParts = listOfNotNull(
                    if (info.year > 0) info.year.toString() else null,
                    info.area?.takeIf { it.isNotBlank() },
                    info.type?.takeIf { it.isNotBlank() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "来源：$displaySourceName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        )
                    }
                }
                // 简介：surfaceContainer 圆角块 + 右下角 展开/收起(向下箭头)
                if (desc.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { descExpanded = !descExpanded },
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { descExpanded = !descExpanded },
                        ) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (descExpanded) "收起" else "展开",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (descExpanded) 180f else 0f),
                            )
                        }
                    }
                }
            }
        }

        // ---- 清晰度(仅多清晰度时显示) ----
        if (qualityOptions.size > 1) {
            item(key = "quality") {
                ChipRow(title = "清晰度") {
                    itemsIndexed(qualityOptions) { index, option ->
                        FilterChip(
                            selected = index == qualitySelected,
                            onClick = { vm.onQualityClick(index) },
                            label = { Text(option) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
        }

        // ---- 线路 ----
        if (flags.size > 1) {
            item(key = "flags") {
                ChipRow(title = "线路") {
                    // key 拼入索引:线路名可能为空或重复,纯 name 拼接会撞 key 崩溃
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            label = { Text(flag.name ?: "") },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            }
        }

        // ---- 选集横向行 ----
        if (episodes.isNotEmpty()) {
            item(key = "episodes") {
                EpisodeRow(vm, info, episodes, playIndex, currentFlag)
            }
        }

        // ---- 换源行 ----
        item(key = "sources") {
            SourceSection(vm, currentSourceName = displaySourceName, revision = revision)
        }

        // ---- 相关推荐 ----
        item(key = "related") {
            RelatedSection(activity, vm)
        }
    }
}

@Composable
private fun EpisodeRow(
    vm: DetailViewModel,
    info: VodInfo,
    episodes: List<VodInfo.VodSeries>,
    playIndex: Int,
    currentFlag: String?,
) {
    // surfaceBright 圆角卡片(圆角 16dp,距屏 6dp)
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选集",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            PillAction(
                iconRes = R.drawable.ic_episode_reverse,
                text = if (info.reverseSort) "正序" else "倒序",
                onClick = { vm.toggleReverse() },
            )
            Spacer(Modifier.width(8.dp))
            PillAction(
                iconRes = R.drawable.ic_episode_grid_all,
                text = "全部",
                onClick = { vm.showEpisodeSheet() },
            )
        }
        val listState = rememberLazyListState()
        // 倒序/正序切换后回到新顺序顶部(主流 app 行为:倒序让最新集立即可见,当前集仅保持高亮);
        // 其余场景(进页/切线路/切集)仍定位到当前集
        var prevReverseSort by remember { mutableStateOf(info.reverseSort) }
        LaunchedEffect(playIndex, currentFlag, episodes.size, info.reverseSort) {
            if (episodes.isEmpty()) return@LaunchedEffect
            val reverseChanged = info.reverseSort != prevReverseSort
            prevReverseSort = info.reverseSort
            if (reverseChanged) {
                listState.scrollToItem(0)
            } else if (playIndex >= 0) {
                listState.scrollToItem(minOf(playIndex, episodes.size - 1))
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(episodes) { index, ep ->
                FilterChip(
                    selected = index == playIndex,
                    onClick = { vm.onEpisodeClick(index) },
                    label = {
                        Text(
                            text = ep.name ?: (index + 1).toString(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

/** surfaceContainer 药丸按钮：左 icon + 右文字（选集卡片 倒序/全部） */
@Composable
private fun PillAction(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SourceSection(vm: DetailViewModel, currentSourceName: String?, revision: Int) {
    // revision 仅用于触发重组
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsState()
    val sourcesSearching by vm.sourcesSearching.collectAsState()
    if (!sourcesSearching && sourceChips.isEmpty()) return
    val listState = rememberLazyListState()
    // 换源后把行首的当前源 chip 滚回视野(行滚动位置会跨数据更新保留)
    LaunchedEffect(currentSourceName) {
        if (currentSourceName != null) listState.scrollToItem(0)
    }
    // surfaceBright 圆角卡片(圆角 16dp,距屏 6dp)
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "换源",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (sourcesSearching) {
                Text(
                    text = "寻找片源中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (currentSourceName != null) {
                item(key = "current") {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(currentSourceName) },
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
            itemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                FilterChip(
                    selected = false,
                    onClick = { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } },
                    label = { Text(chip.name) },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RelatedSection(activity: DetailActivity, vm: DetailViewModel) {
    val relatedVideos by vm.relatedVideos.collectAsState()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = "相关推荐",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.openVodCardOrDetail(video) },
                    onLongClick = {},
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

/**
 * chips 分区行:surfaceBright 圆角卡片(同选集/换源卡)+ 标题 + LazyRow。
 * Column 与 LazyRow 都必须显式 fillMaxWidth —— 两者默认 wrapContent,不给宽度时卡片会缩成
 * 标题/chips 的宽度(线路只有两条时比「选集」卡窄一截的根因),且横向手势区也只覆盖 chips 那几个字。
 */
@Composable
private fun ChipRow(title: String, content: LazyListScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

private fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EpisodeSheet(vm: DetailViewModel, revision: Int) {
    // revision 仅用于触发重组
    @Suppress("UNUSED_EXPRESSION") revision
    val show by vm.episodeSheet.collectAsState()
    if (!show) return
    val info = vm.vodInfo ?: return
    val flags = info.seriesFlags.orEmpty()
    val currentFlag = info.playFlag
    val episodes = info.seriesMap?.get(currentFlag).orEmpty()
    val playIndex = info.playIndex

    val groupCount = when {
        episodes.size > 400 -> 120
        episodes.size > 100 -> 60
        else -> 20
    }
    val groups = if (episodes.size > groupCount) {
        val result = ArrayList<String>()
        var i = 0
        while (i < episodes.size) {
            val end = minOf(i + groupCount, episodes.size)
            result.add("${i + 1} - $end")
            i += groupCount
        }
        result
    } else {
        emptyList()
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var selectedGroup by rememberSaveable { mutableStateOf(0) }

    // 打开时定位到当前集所在分组;之后点分组跳转
    LaunchedEffect(show, currentFlag, playIndex) {
        if (show && playIndex >= 0) {
            val target = (playIndex / groupCount) * groupCount
            selectedGroup = playIndex / groupCount
            if (target in episodes.indices) gridState.scrollToItem(target)
        }
    }
    LaunchedEffect(selectedGroup) {
        if (selectedGroup > 0) {
            val target = selectedGroup * groupCount
            if (target in episodes.indices) gridState.scrollToItem(target)
        }
    }

    // 自适应多列网格:短集名一行多列,长集名单列全宽;仍 lazy,保留 scrollToItem 定位当前集

    AVBoxBottomSheet(
        onDismissRequest = { vm.dismissEpisodeSheet() },
        title = if (info.name.isNullOrEmpty()) "选集" else "${info.name} 选集",
        // 内容自带横向 LazyRow 与 LazyVerticalGrid(height 自适应 + 560dp 上限),滚动交给它们
        isScrollable = false,
    ) {
        // 集卡点击走「带动画关闭」:先切集播放,面板滑出后再移除;须在 SheetOverlay 的 provider 作用域内取
        val dismissAnimated = LocalSheetDismiss.current
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            if (flags.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // key 拼入索引:线路名可能为空或重复,纯 name 拼接会撞 key 崩溃
                    itemsIndexed(flags, key = { i, f -> "${i}_${f.name}" }) { _, flag ->
                        FilterChip(
                            selected = flag.name == currentFlag,
                            onClick = { vm.onFlagClick(flag.name ?: "") },
                            label = { Text(flag.name ?: "") },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (groups.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(groups) { index, label ->
                        FilterChip(
                            selected = index == selectedGroup,
                            onClick = { selectedGroup = index },
                            label = { Text(label) },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            // 列数随集名长度自适应:短集名 4 列,中等 2 列,长文件名单列全宽
            val maxNameLength = episodes.maxOfOrNull { it.name?.length ?: 0 } ?: 0
            val gridColumnCount = when {
                maxNameLength <= 4 -> 4
                maxNameLength <= 12 -> 2
                else -> 1
            }
            // 高度随集数收缩并封顶 560dp(否则短列表也会撑满全屏)
            val rowCount = if (episodes.isEmpty()) 0 else (episodes.size + gridColumnCount - 1) / gridColumnCount
            val gridContentHeight = (rowCount * 40).dp + (((rowCount - 1).coerceAtLeast(0)) * 8).dp
            val gridHeight = minOf(560.dp, gridContentHeight)
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = gridState,
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumnCount),
                modifier = Modifier.fillMaxWidth().height(gridHeight),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(episodes) { index, ep ->
                    FilterChip(
                        selected = index == playIndex,
                        onClick = {
                            vm.onEpisodeClick(index)
                            dismissAnimated()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        label = {
                            Text(
                                text = ep.name ?: (index + 1).toString(),
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                // 4 列下每格约 76dp,两位集数(14sp)实测需 41~42dp,仅差约 3dp 即溢出 ——
                                // 故字号改 13sp + 左右内边距 6dp(余量约 9dp),并去掉跑马灯改用省略号。
                                fontSize = 13.sp,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        // 收窄 chip 水平内边距(默认 FilterChipDefaults.ContentPadding = 8dp),换取文字可用宽度
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 播放器提示覆盖层(状态读 [PlayerTipBridge]):loading = 白色系指示器,err = 错误图标 + 文案。
 * 纯黑底铺满播放器区;无 pointer 处理 → 触摸穿透,不拦控制器与全屏入口。
 */
@Composable
private fun PlayerTipOverlay() {
    val tip = PlayerTipBridge.state
    if (!tip.loading && !tip.err) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (tip.loading) {
                // 纯黑底白色系,同竖屏玩家区 Loading(默认 secondaryContainer 在黑底上过亮)
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_error),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (tip.msg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = tip.msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}
