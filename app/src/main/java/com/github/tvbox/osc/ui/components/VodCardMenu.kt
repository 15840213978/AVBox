package com.github.tvbox.osc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.page.jumpToSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus

/**
 * 卡片长按「收藏 / 搜索相似内容」菜单状态(2026-09-16,首页 / 搜索页 / 栏目二级页统一):
 * 长按卡片 → 后台查收藏状态 → 弹选项面板;收藏动作发 TYPE_COLLECT_REFRESH 联动收藏页刷新。
 */
@Stable
class VodCardMenuState internal constructor(private val scope: CoroutineScope) {

    /** 当前长按的卡片(空 = 无面板):video to 是否已收藏 */
    internal var menu by mutableStateOf<Pair<Movie.Video, Boolean>?>(null)

    /** 长按卡片入口:收藏状态需查库,查到才弹面板 */
    fun show(video: Movie.Video) {
        scope.launch {
            val collected = withContext(Dispatchers.IO) {
                RoomDataManger.isVodCollect(video.sourceKey, video.id)
            }
            menu = video to collected
        }
    }
}

/** 页面根部 remember 一次,卡片 onLongClick 调 [VodCardMenuState.show] */
@Composable
fun rememberVodCardMenuState(): VodCardMenuState {
    val scope = rememberCoroutineScope()
    return remember(scope) { VodCardMenuState(scope) }
}

/** 面板渲染:页面根部调用一次(需能覆盖全屏,同 [AVBoxBottomSheet] 宿主约定) */
@Composable
fun VodCardMenu(state: VodCardMenuState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    state.menu?.let { (video, collected) ->
        AVBoxOptionSheet(
            onDismissRequest = { state.menu = null },
            title = video.name,
            options = if (collected) listOf("取消收藏", "搜索相似内容") else listOf("加入收藏", "搜索相似内容"),
            selected = null,
            onSelect = { option ->
                when (option) {
                    "加入收藏" -> scope.launch(Dispatchers.IO) {
                        RoomDataManger.insertVodCollect(video.sourceKey, toVodInfo(video))
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
                    }
                    "取消收藏" -> scope.launch(Dispatchers.IO) {
                        RoomDataManger.deleteVodCollect(video.sourceKey, toVodInfo(video))
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH))
                    }
                    "搜索相似内容" -> context.jumpToSearch(video.name ?: "")
                }
                // 不在此处置空 menu:AVBoxOptionSheet 选中后会先播放滑出动画,
                // 动画结束才回调 onDismissRequest 清理;此处若直接置 null 会跳过动画
            },
        )
    }
}

/** 卡片 → 收藏表 VodInfo(仅收藏所需字段) */
internal fun toVodInfo(video: Movie.Video): VodInfo {
    val info = VodInfo()
    info.id = video.id
    info.name = video.name
    info.pic = video.pic
    info.sourceKey = video.sourceKey
    return info
}