package com.github.tvbox.osc.ui.page

import android.widget.Toast
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.SearchViewModel
import com.github.tvbox.osc.util.HawkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.greenrobot.eventbus.EventBus
import kotlin.coroutines.resume

/**
 * 应用启动引导:移植自旧 HomeActivity.initData 链(配置加载 → jar 加载 → 就绪),
 * 使用单例而非 ViewModel,避免持有 Activity 引用。
 */
object AppBootstrap {

    sealed interface Boot {
        data object Loading : Boot
        data object Ready : Boot
        data class Error(val msg: String) : Boot
    }

    private val _state = MutableStateFlow<Boot>(Boot.Loading)
    val state: StateFlow<Boot> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var dataInitOk = false
    private var jarInitOk = false

    fun start() {
        if (started) return
        started = true
        ControlManager.get().startServer()
        startInit()
    }

    /** 配置加载失败后重试 */
    fun retry() {
        dataInitOk = false
        jarInitOk = false
        _state.value = Boot.Loading
        startInit()
    }

    /** 用户选择忽略错误,离线继续 */
    fun continueOffline() {
        dataInitOk = true
        jarInitOk = true
        _state.value = Boot.Loading
        startInit()
    }

    /**
     * 点播源地址已变更后的统一收尾(2026-09-13)。**必须在改写 KV `API_URL` 之后调用**。
     *
     * 四步缺一不可:
     * ① `invalidateVodConfig()` 先作废内存里的旧配置 —— 新源拉取失败时不会残留旧源数据
     *    (失败的 loadConfig 不会走 parseJson,旧的 sourceBeanList/mHomeSource 会原样留着);
     * ② 丢弃搜索页的会话级"勾选搜索源"缓存 —— 那份缓存按**源 key** 记,而源 key 属于旧源集合,
     *    留着会让换源后的搜索被悄悄窄化到"新旧源共有的那几个源"(2026-09-13 修的 bug:只搜得到玩偶4k);
     * ③ 广播 `TYPE_API_URL_CHANGE` 让首页**立刻**按新状态刷新,而不是把旧源内容继续摆在屏幕上等结果;
     * ④ `retry()` 重新拉配置。
     */
    fun onApiUrlChanged() {
        ApiConfig.get().invalidateVodConfig()
        SearchViewModel.clearCheckedSources()
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE))
        retry()
    }

    private fun startInit() {
        scope.launch {
            if (!dataInitOk) {
                val err = awaitLoadConfig()
                if (err != null) {
                    // "-1" 为旧约定:取消等待,离线继续
                    if (err == "-1") {
                        dataInitOk = true
                        jarInitOk = true
                    } else {
                        _state.value = Boot.Error(err)
                        return@launch
                    }
                } else {
                    dataInitOk = true
                    if (ApiConfig.get().getSpider().isEmpty()) jarInitOk = true
                }
            }
            if (dataInitOk && !jarInitOk) {
                val err = awaitLoadJar()
                jarInitOk = true
                if (err != null) toast(err + " jar load err")
            }
            if (dataInitOk && jarInitOk) {
                ApiConfig.get().warmSearchSpiders()
                _state.value = Boot.Ready
            }
        }
    }

    private suspend fun awaitLoadConfig(): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadConfig(false, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                if (cont.isActive) cont.resume(null)
            }

            override fun error(msg: String?) {
                if (cont.isActive) cont.resume(msg ?: "-1")
            }

            override fun notice(msg: String?) {
                toast(msg)
            }
        }, null)
    }

    private suspend fun awaitLoadJar(): String? = suspendCancellableCoroutine { cont ->
        ApiConfig.get().loadJar(false, ApiConfig.get().getSpider(), object : ApiConfig.LoadConfigCallback {
            override fun success() {
                if (cont.isActive) cont.resume(null)
            }

            override fun error(msg: String?) {
                if (cont.isActive) cont.resume(msg ?: "")
            }

            override fun notice(msg: String?) {
                toast(msg)
            }
        })
    }

    private fun toast(msg: String?) {
        if (msg.isNullOrEmpty()) return
        val context = com.github.tvbox.osc.base.App.getInstance()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
