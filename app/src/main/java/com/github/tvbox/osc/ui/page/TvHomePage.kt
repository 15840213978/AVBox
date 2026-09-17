package com.github.tvbox.osc.ui.page

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.LivePlayActivity
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.ui.activity.SearchActivity

/** 电视端首页：横向内容轨道 + 大图推荐 + 明确焦点态。 */
@Composable
fun TvHomePage(vm: HomeViewModel) {
    val context = LocalContext.current
    val currentSource by vm.currentSource.collectAsState()
    val sources by vm.sources.collectAsState()
    val rec by vm.rec.collectAsState()
    val partitions by vm.partitions.collectAsState()
    val pageLoading by vm.pageLoading.collectAsState()
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var heroVideo by remember { mutableStateOf<Movie.Video?>(null) }

    LaunchedEffect(rec.videos) {
        if (heroVideo == null || rec.videos.none { it.id == heroVideo?.id }) {
            heroVideo = rec.videos.firstOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 30.dp, top = 20.dp, end = 30.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "tv_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AVBox",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "电视模式",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Box {
                        TvActionButton(
                            text = currentSource?.name?.takeIf { !it.isNullOrBlank() } ?: "订阅源",
                            icon = Icons.Filled.SwapHoriz,
                            onClick = { sourceMenuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = sourceMenuExpanded,
                            onDismissRequest = { sourceMenuExpanded = false },
                        ) {
                            sources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.name ?: source.key ?: "未命名") },
                                    onClick = {
                                        sourceMenuExpanded = false
                                        if (source.key != currentSource?.key) vm.switchSource(source)
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    TvActionButton(
                        text = "搜索",
                        icon = Icons.Filled.Search,
                        onClick = { context.startActivity(Intent(context, SearchActivity::class.java)) },
                    )
                    Spacer(Modifier.width(10.dp))
                    TvActionButton(
                        text = "直播",
                        icon = Icons.Filled.LiveTv,
                        onClick = { context.startActivity(Intent(context, LivePlayActivity::class.java)) },
                    )
                }
            }

            if (pageLoading) {
                item(key = "tv_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContainedLoadingIndicator(Modifier.size(58.dp))
                    }
                }
            } else if (sources.isEmpty()) {
                item(key = "tv_no_source") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("尚未配置订阅接口，请到设置 → 配置管理添加")
                    }
                }
            } else {
                heroVideo?.let { video ->
                    item(key = "tv_hero") {
                        TvHero(
                            video = video,
                            onClick = {
                                context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
                            },
                        )
                    }
                }

                if (rec.videos.isNotEmpty()) {
                    item(key = "tv_rec") {
                        TvVideoRail(
                            title = "推荐",
                            videos = rec.videos,
                            onFocused = { heroVideo = it },
                            onClick = { video ->
                                context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
                            },
                        )
                    }
                }

                partitions.forEach { partition ->
                    item(key = "tv_partition_${partition.sort.id}") {
                        TvVideoRail(
                            title = partition.sort.name ?: "",
                            videos = partition.videos,
                            onFocused = { heroVideo = it },
                            onClick = { video ->
                                context.dispatchVodCardClick(video, onAction = { vm.handleAction(it) })
                            },
                            onOpenAll = {
                                PartitionListActivity.startForPartition(context, partition.sort)
                            },
                            onReachEnd = {
                                if (partition.hasMore && partition.state == HomeViewModel.PartitionState.Ready) {
                                    vm.loadMorePartition(partition)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHero(video: Movie.Video, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, label = "tvHeroScale")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp)
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable(),
    ) {
        AsyncImage(
            model = video.pic,
            contentDescription = video.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.82f),
                        0.48f to Color.Black.copy(alpha = 0.36f),
                        1f to Color.Transparent,
                    )
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.56f)
                .padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = video.name ?: "",
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (video.year > 0) append(video.year)
                if (!video.area.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(video.area)
                }
                if (!video.note.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(video.note)
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = Color.White.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!video.des.isNullOrBlank()) {
                Text(
                    text = video.des,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (focused) "按 OK 打开" else "热门推荐",
                color = Color.White,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.32f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun TvVideoRail(
    title: String,
    videos: List<Movie.Video>,
    onFocused: (Movie.Video) -> Unit,
    onClick: (Movie.Video) -> Unit,
    onOpenAll: (() -> Unit)? = null,
    onReachEnd: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onOpenAll != null) {
                TvTextButton(text = "查看全部  ›", onClick = onOpenAll)
            }
        }

        if (videos.isEmpty()) {
            Text(
                text = "暂无内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(videos, key = { index, video -> "${video.sourceKey}_${video.id}_$index" }) { index, video ->
                    TvPosterCard(
                        video = video,
                        onFocused = { onFocused(video) },
                        onClick = { onClick(video) },
                    )
                    if (index == videos.lastIndex && onReachEnd != null) {
                        LaunchedEffect(videos.size) { onReachEnd() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPosterCard(
    video: Movie.Video,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.085f else 1f, label = "tvPosterScale")
    val borderColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "tvPosterBorder",
    )

    Column(
        modifier = Modifier
            .width(154.dp)
            .scale(scale)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .border(3.dp, borderColor, RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = video.pic,
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (!video.note.isNullOrBlank()) {
                Text(
                    text = video.note,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(topStart = 8.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
        }
        Text(
            text = video.name ?: "",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.055f else 1f, label = "tvActionScale")
    val bg by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "tvActionBg",
    )
    Row(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(
                if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvTextButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = text,
        color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
