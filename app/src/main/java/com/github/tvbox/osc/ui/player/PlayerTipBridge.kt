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
