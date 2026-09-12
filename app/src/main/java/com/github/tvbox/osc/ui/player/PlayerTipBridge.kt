package com.github.tvbox.osc.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 播放器提示形态:loading = 取流中(spinner),err = 播放失败(错误图标);msg 为空时不渲染文字行 */
data class PlayerTipState(
    val msg: String = "",
    val loading: Boolean = false,
    val err: Boolean = false,
)

/**
 * 播放器提示桥(2026-09-11 替代 view_play_container.xml 的 play_loading/play_load_error/play_load_tip 三个 View):
 * 写侧 = [PlayContainer] 的 setTip/hideTip(任意线程,Compose snapshot state 支持后台线程写入,
 * 原 runOnUiThread 语义不再必要);读侧 = DetailScreen 播放器区域的 Compose 覆盖层。
 * 全局单例:[PlayContainer] 构造时调 [hide],清掉上一个页面实例的残留(如源站错误文案)。
 */
object PlayerTipBridge {
    var state by mutableStateOf(PlayerTipState())
        private set

    @JvmStatic
    fun setTip(msg: String, loading: Boolean, err: Boolean) {
        state = PlayerTipState(msg, loading, err)
    }

    @JvmStatic
    fun hide() {
        state = PlayerTipState()
    }
}
