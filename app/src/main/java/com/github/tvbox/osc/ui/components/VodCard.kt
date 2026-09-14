package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.bean.Movie

/** 海报卡片(§4.1 最终版):PressableCard + 2:3 海报 + 底部 scrim + 16sp 标题 + 14sp 年份 */
@Composable
fun VodCard(
    video: Movie.Video,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PressableCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.aspectRatio(2f / 3f)) {
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
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f),
                        )
                    ),
            )
            // 右上角角标(2026-09-10 揭秘日风格):「评分： 8.7」/「8.2 分」提取纯数字,
            // 非评分备注(共40集/HD)原样展示,空或评分为 0 不显示
            val badge = ratingBadgeText(video.note)
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 名称下方元信息:年份 / 地区(2026-09-10 揭秘日风格,字段缺失时整行隐藏)
                val meta = buildString {
                    if (video.year > 0) append(video.year)
                    if (!video.area.isNullOrBlank()) {
                        if (isNotEmpty()) append(" / ")
                        append(video.area)
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 评分提取正则(顶层常量):ratingBadgeText 在首页横排/相关推荐/HeroCarousel 每卡组合时调用,
 * 提出函数体避免每次调用重新编译(2026-09-14 性能优化) */
private val RATING_SCORE_REGEX = Regex("评分[:：]?\\s*(\\d+(?:\\.\\d+)?)")
private val RATING_SCORE_SUFFIX_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*分$")

/**
 * note → 角标文本:「评分： 8.7」/「评分:8.7」/「8.2 分」→ 纯数字;
 * 评分为 0 或 note 为空返回 null(隐藏角标);其余备注(共40集/HD)原样返回。
 */
internal fun ratingBadgeText(note: String?): String? {
    val n = note?.trim().orEmpty()
    if (n.isEmpty()) return null
    val m = RATING_SCORE_REGEX.find(n)
        ?: RATING_SCORE_SUFFIX_REGEX.find(n)
    if (m != null) {
        val num = m.groupValues[1]
        return if (num.toFloatOrNull() == 0f) null else num
    }
    return n
}
