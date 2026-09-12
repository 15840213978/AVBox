package com.github.tvbox.osc.player.controller

import com.github.tvbox.osc.bean.ParseBean

import java.util.HashMap

/**
 * 播放器控制层对外回调契约（Compose 化改造 §4.1：从 VodController 嵌套接口提取为顶层，
 * 阶段 8 删除 VodController 后由 [ComposeVideoController] 与 PlayContainer 继续使用）。
 * 方法签名与旧 VodController.VodControlListener 完全一致。
 */
interface VodControlListener {
    fun playNext(rmProgress: Boolean)

    fun playPre()

    fun showEpisodeDialog()

    fun prepared()

    fun changeParse(pb: ParseBean)

    fun updatePlayerCfg()

    fun replay(replay: Boolean)

    fun errReplay()

    fun selectSubtitle()

    fun selectAudioTrack()

    fun selectVideoTrack()

    fun showDanmuSetting()

    fun toggleDanmu(): Boolean

    fun searchDanmuUi(longClick: Boolean)

    fun startPlayUrl(url: String, headers: HashMap<String, String>?)

    fun onM3u8ProxyUrl(proxyUrl: String, sourceUrl: String)

    fun clickCast()

    fun setAllowSwitchPlayer(isAllow: Boolean)
}
