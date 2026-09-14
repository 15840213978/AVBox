package com.github.tvbox.osc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.tvbox.osc.bean.Movie
import kotlin.math.abs

/** 每组海报复制成的循环页数:10 万页/组,初始定位正中 → 任意方向实际都滑不到头 */
private const val HERO_PAGES_PER_SET = 100_000

/**
 * Hero 大卡轮播(2026-09-10 揭秘日风格;2026-09-12 用户定稿:无限循环轮播):
 * - 用官方 [HorizontalPager](foundation 1.9 stable,天然吸附落定):
 *   - 无限循环:页数 = 海报数 × [HERO_PAGES_PER_SET],初始定位在最中央,
 *     任意方向都滑不到头,内容按 `page % n` 取模循环出现;
 *   - 居中 peek:两侧 contentPadding = (100%-64%)/2 = 18% 屏宽(页宽 = 视口 - padding
 *     = 64% 屏宽,2026-09-12 用户定稿缩小,两侧露出更多相邻卡),页对齐 padding start
 *     吸附 → 落定当前卡必居中、左右等距 peek 相邻卡;
 * - 海报全彩铺底 + 底部黑色渐变承托白字,中央胶囊标签;
 *   滑动时按页偏移缩放/淡出:偏移在 graphicsLayer 块内绘制期读 state,
 *   逐帧更新只触发重绘,不重组可见页(2026-09-14 BugFix,原实现误在组合期求值)。
 */
@Composable
fun HeroCarousel(
    videos: List<Movie.Video>,
    onCardClick: (Movie.Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (videos.isEmpty()) return
    val n = videos.size
    val sidePad = LocalConfiguration.current.screenWidthDp.dp * 0.18f
    val pagerState = rememberPagerState(
        initialPage = n * (HERO_PAGES_PER_SET / 2),
        pageCount = { n * HERO_PAGES_PER_SET },
    )

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = sidePad),
        pageSpacing = 12.dp,
    ) { page ->
        val video = videos[page % n]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .graphicsLayer {
                    // 页偏移:当前页 0,相邻页 ±1(2026-09-14 BugFix:计算移入块内,
                    // 绘制期读 state,滚动时只触发重绘;若在组合期求值,滑动期间
                    // 2~3 个可见页会每帧重组)
                    val pageOffset =
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val d = abs(pageOffset).coerceIn(0f, 1f)
                    scaleX = 1f - 0.18f * d
                    scaleY = 1f - 0.18f * d
                    alpha = 1f - 0.25f * d
                }
                .clip(RoundedCornerShape(24.dp))
                .clickable { onCardClick(video) },
        ) {
            AsyncImage(
                model = video.pic,
                contentDescription = video.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 底部渐变承托白字(不再整面叠浅色遮罩,保持海报原色)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.6f),
                        )
                    ),
            )
            Text(
                text = "热门推荐",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = video.name ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副标题:纯数字评分加「评分」前缀(如「评分 8.7」),其余备注原样,空/0 隐藏
                val sub = ratingBadgeText(video.note)
                if (!sub.isNullOrBlank()) {
                    Text(
                        text = if (sub != video.note?.trim()) "评分 $sub" else sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
