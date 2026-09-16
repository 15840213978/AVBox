package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.activity.PartitionListActivity
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV

enum class SourceCardPolicy(val label: String) {
    SEARCH("搜索"),
    DETAIL("详情");

    fun toggled(): SourceCardPolicy = if (this == SEARCH) DETAIL else SEARCH
}

sealed interface VodCardTarget {
    data class Action(val video: Movie.Video) : VodCardTarget

    data class Folder(val video: Movie.Video) : VodCardTarget

    data class Search(val title: String) : VodCardTarget

    data class Detail(val video: Movie.Video) : VodCardTarget
}

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

internal fun Movie.Video.isFolderCard(): Boolean = tag == "folder"

fun resolveVodCardTarget(video: Movie.Video): VodCardTarget = when {
    !video.action.isNullOrEmpty() -> VodCardTarget.Action(video)
    video.isFolderCard() -> VodCardTarget.Folder(video)
    VodCardPolicy.policyOf(video.sourceKey) == SourceCardPolicy.DETAIL -> VodCardTarget.Detail(video)
    else -> VodCardTarget.Search(video.name.orEmpty())
}

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

fun Context.openVodCardOrDetail(video: Movie.Video) {
    if (video.isFolderCard()) {
        openVodFolder(video)
        return
    }
    jumpToDetail(video.id, video.sourceKey, video.name, video.pic)
}

private fun Context.openVodFolder(video: Movie.Video) {
    val folderId = video.id.orEmpty()
    if (folderId.isEmpty()) {
        Toast.makeText(this, "目录数据缺失,无法打开", Toast.LENGTH_SHORT).show()
        return
    }
    PartitionListActivity.startForFolder(this, folderId, video.name.orEmpty())
}
