package com.github.tvbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.SearchActivity

/** 旧 UI 与 Compose 页面共用的详情跳转逻辑 */
fun Context.jumpToDetail(id: String?, sourceKey: String?, title: String?, picture: String?, collect: Boolean = false) {
    // 源数据的 id/sourceKey 可能为 null(Java 平台类型),与旧行为一致兜底为空串
    // 历史/收藏入口不再兜底跳搜索:即使源缺失也直接进详情页(2026-09-10 用户定稿)
    val bundle = Bundle()
    bundle.putString("id", id.orEmpty())
    bundle.putString("sourceKey", sourceKey.orEmpty())
    bundle.putString("title", title)
    bundle.putString("picture", picture)
    bundle.putBoolean("collect", collect)
    startActivity(Intent(this, DetailActivity::class.java).putExtras(bundle))
}

/** 按标题搜索 */
fun Context.jumpToSearch(title: String) {
    val bundle = Bundle()
    bundle.putString("title", title)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}

