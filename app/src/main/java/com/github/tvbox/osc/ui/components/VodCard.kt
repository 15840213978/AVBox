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

private val RATING_SCORE_REGEX = Regex("评分[:：]?\\s*(\\d+(?:\\.\\d+)?)")
private val RATING_SCORE_SUFFIX_REGEX = Regex("^(\\d+(?:\\.\\d+)?)\\s*分$")

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
