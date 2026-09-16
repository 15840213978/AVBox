package com.github.tvbox.osc.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
