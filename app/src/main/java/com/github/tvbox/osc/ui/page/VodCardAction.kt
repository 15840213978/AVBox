package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

/**
 * 源级卡片点击策略(2026-09-11 用户定稿):
 * SEARCH = 带标题跳搜索页聚合搜索(默认,兼容 2026-09-10 定稿「点卡片不再直接进详情」);
 * DETAIL = 直接进详情页播放(音乐源 / 网盘源这类"点开即播"的源)。
 * 音乐与影视在数据里没有区分字段,只能按源定策略;按 sourceKey 存 KV,订阅源 sheet 行内切换。
 */
enum class SourceCardPolicy(val label: String) {
    SEARCH("搜索"),
    DETAIL("详情");

    fun toggled(): SourceCardPolicy = if (this == SEARCH) DETAIL else SEARCH
}

/**
 * 卡片点击目标(判定优先级:action > folder > 源级策略 > 搜索),
 * 与上游 fongmi `TypeFragment.onItemClick` 的分支一一对应。
 */
sealed interface VodCardTarget {
    /** 自定义操作卡(action 非空,如云盘配置卡「登入/清除」):由页面执行 action 并提示结果 */
    data class Action(val video: Movie.Video) : VodCardTarget

    /** 目录卡(vod_tag=folder / 带 cate):递归下钻该目录 */
    data class Folder(val video: Movie.Video) : VodCardTarget

    data class Search(val title: String) : VodCardTarget

    data class Detail(val video: Movie.Video) : VodCardTarget
}

/** 源级策略读写:只登记 DETAIL 的源,缺省即 SEARCH(避免在 KV 里堆一份全量空表) */
object VodCardPolicy {
    private const val VALUE_DETAIL = "detail"

    private fun readMap(): HashMap<String, String> =
        KV.get(HawkConfig.SOURCE_CARD_POLICY, HashMap<String, String>())

    fun policyOf(sourceKey: String?): SourceCardPolicy {
        if (sourceKey.isNullOrEmpty()) return SourceCardPolicy.SEARCH
        return if (readMap()[sourceKey] == VALUE_DETAIL) SourceCardPolicy.DETAIL else SourceCardPolicy.SEARCH
    }

    fun setPolicy(sourceKey: String?, policy: SourceCardPolicy) {
        if (sourceKey.isNullOrEmpty()) return
        val map = readMap()
        if (policy == SourceCardPolicy.DETAIL) map[sourceKey] = VALUE_DETAIL else map.remove(sourceKey)
        KV.put(HawkConfig.SOURCE_CARD_POLICY, map)
    }
}

/** 目录卡判定:上游 `Vod.isFolder()` 的等价物(AbsJson 已把 cate 归一到 tag) */
internal fun Movie.Video.isFolderCard(): Boolean = tag == "folder"

/** 卡片点击目标判定(首页/栏目页分发用;读源级策略 KV,纯 JVM 下不可测) */
fun resolveVodCardTarget(video: Movie.Video): VodCardTarget = when {
    !video.action.isNullOrEmpty() -> VodCardTarget.Action(video)
    video.isFolderCard() -> VodCardTarget.Folder(video)
    VodCardPolicy.policyOf(video.sourceKey) == SourceCardPolicy.DETAIL -> VodCardTarget.Detail(video)
    else -> VodCardTarget.Search(video.name.orEmpty())
}

/**
 * 统一卡片点击分发(首页 Hero/推荐/分区、栏目二级页、网盘目录页共用)。
 * [onAction] 由调用页面提供:action 卡需要页面的配置模型(HomeViewModel / PartitionListVM)执行并刷新。
 */
fun Context.dispatchVodCardClick(video: Movie.Video, onAction: (Movie.Video) -> Unit = {}) {
    when (val target = resolveVodCardTarget(video)) {
        is VodCardTarget.Action -> onAction(video)
        is VodCardTarget.Folder -> openVodFolder(target.video)
        is VodCardTarget.Search -> jumpToSearch(target.title)
        is VodCardTarget.Detail -> jumpToDetail(
            target.video.id,
            target.video.sourceKey,
            target.video.name,
            target.video.pic,
        )
    }
}

/**
 * 搜索链路(搜索页结果/源级结果页/相关推荐)入口:**只特判目录卡**,其余照旧进详情页。
 * 不复用 [dispatchVodCardClick]:其源级策略的 SEARCH 分支在这些场景会把点击变成"再跳一次搜索页"(成环)。
 */
fun Context.openVodCardOrDetail(video: Movie.Video) {
    if (video.isFolderCard()) {
        openVodFolder(video)
        return
    }
    jumpToDetail(video.id, video.sourceKey, video.name, video.pic)
}

/** 目录下钻:复用栏目二级页(folderId 直接当分类 id 传给 spider.categoryContent,可逐级递归) */
private fun Context.openVodFolder(video: Movie.Video) {
    val folderId = video.id.orEmpty()
    if (folderId.isEmpty()) {
        Toast.makeText(this, "目录数据缺失,无法打开", Toast.LENGTH_SHORT).show()
        return
    }
    PartitionListActivity.startForFolder(this, folderId, video.name.orEmpty())
}
