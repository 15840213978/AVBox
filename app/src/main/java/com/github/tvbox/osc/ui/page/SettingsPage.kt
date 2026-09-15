@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.tvbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.AVBoxOptionSheet
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.activity.PlaySettingsActivity
import com.github.tvbox.osc.ui.activity.PreferenceSettingsActivity
import com.github.tvbox.osc.ui.activity.PreloadSettingsActivity
import com.github.tvbox.osc.ui.activity.ThemeSettingsActivity
import com.github.tvbox.osc.util.FileUtils
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.OkGoHelper
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置项状态:集中从 KV 读取,设置变更后整体刷新 */
data class SettingsState(
    val playType: Int,
    val playRender: Int,
    val playScale: Int,
    val ijkCodec: String,
    val ijkCachePlay: Boolean,
    val playTunnel: Boolean,
    val preferAac: Boolean,
    val autoSwitchLine: Boolean,
    val m3u8Purify: Boolean,
    /** 无痕模式:不记录搜索历史与观看历史(收藏正常) */
    val incognito: Boolean,
    /** 禁用手势控制:开启后播放器不再响应上下滑调节亮度/音量 */
    val gestureControlDisabled: Boolean,
    val danmuOpen: Boolean,
    val danmuApi: String,
    val defaultLoadLive: Boolean,
    val historyNumIndex: Int,
    val searchThreads: Int,
    val longPressSpeed: Int,
    val bufferTimes: Int,
    val preloadNextEpisode: Boolean,
    val preloadDuration: Int,
    val playCache: Boolean,
    val exoCacheSizeMb: Int,
    val apiUrl: String,
    val apiLines: List<String>,
    val dohIndex: Int,
    /** 缓存占用展示文本(目录扫描耗时,由 SettingsViewModel.refreshCacheSize 异步统计后回填) */
    val cacheSizeText: String = "",
) {
    val apiLineVisible: Boolean
        get() = HistoryHelper.isApiLineUrl(apiUrl)
}

class SettingsViewModel : ViewModel() {
    /**
     * 缓存占用文本:目录递归统计是耗时 IO,单独异步算,不放进 loadState 阻塞主线程。
     * ⚠️ 必须声明在 [_state] 之前 —— _state 的初始化器会调用 loadState(),
     * 而 Kotlin 属性按声明顺序初始化,声明在后面时这里读到的仍是 null(非空参数 → NPE 崩溃)。
     */
    private var cacheSizeText: String = ""

    private val _state = mutableStateOf(loadState())

    init {
        refresh()
    }

    val state: androidx.compose.runtime.State<SettingsState> = _state

    fun refresh() {
        _state.value = loadState()
        refreshCacheSize()
    }

    /** 后台统计缓存占用并回填;值未变化时不触发重组 */
    fun refreshCacheSize() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { FileUtils.formatCacheSize(FileUtils.getCacheSize()) }
            applyCacheSizeText(text)
        }
    }

    /**
     * 清除缓存(内部 + 外部,含 Exo 视频缓存)。
     * [onCleared] 在清理完成、占用文本刷新后于主线程回调(Tip 提示由 UI 层负责,ViewModel 不碰 UI)。
     */
    fun clearCache(onCleared: () -> Unit = {}) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                FileUtils.clearCache()
                FileUtils.formatCacheSize(FileUtils.getCacheSize())
            }
            applyCacheSizeText(text)
            onCleared()
        }
    }

    private fun applyCacheSizeText(text: String) {
        if (text != cacheSizeText) {
            cacheSizeText = text
            _state.value = _state.value.copy(cacheSizeText = text)
        }
    }

    private fun loadState(): SettingsState = SettingsState(
        playType = KV.get(HawkConfig.PLAY_TYPE, 2),
        // 默认 SurfaceView(2026-09-09 用户定稿)
        playRender = KV.get(HawkConfig.PLAY_RENDER, 1),
        playScale = KV.get(HawkConfig.PLAY_SCALE, 0),
        ijkCodec = KV.get(HawkConfig.IJK_CODEC, "硬解码"),
        ijkCachePlay = KV.get(HawkConfig.IJK_CACHE_PLAY, false),
        playTunnel = KV.get(HawkConfig.PLAY_TUNNEL, false),
        preferAac = KV.get(HawkConfig.PLAY_PREFER_AAC, false),
        autoSwitchLine = KV.get(HawkConfig.AUTO_SWITCH_LINE, false),
        m3u8Purify = KV.get(HawkConfig.M3U8_PURIFY, false),
        incognito = KV.get(HawkConfig.INCOGNITO, false),
        gestureControlDisabled = KV.get(HawkConfig.GESTURE_CONTROL_DISABLED, false),
        // 默认与 DanmuHelper.isOpen() 对齐(true),避免首装显示“关”但弹幕实际开着
        danmuOpen = KV.get(HawkConfig.DANMU_OPEN, true),
        danmuApi = KV.get(HawkConfig.DANMU_API, ""),
        defaultLoadLive = KV.get(HawkConfig.DEFAULT_LOAD_LIVE, false),
        historyNumIndex = KV.get(HawkConfig.HISTORY_NUM, 0),
        searchThreads = KV.get(HawkConfig.SEARCH_THREADS, HawkConfig.SEARCH_THREADS_DEFAULT),
        longPressSpeed = KV.get(HawkConfig.LONG_PRESS_SPEED, HawkConfig.LONG_PRESS_SPEED_DEFAULT),
        bufferTimes = KV.get(HawkConfig.BUFFER_TIMES, HawkConfig.BUFFER_TIMES_DEFAULT),
        preloadNextEpisode = KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false),
        preloadDuration = KV.get(HawkConfig.PRELOAD_DURATION, HawkConfig.PRELOAD_DURATION_DEFAULT),
        playCache = KV.get(HawkConfig.PLAY_CACHE, false),
        exoCacheSizeMb = KV.get(HawkConfig.EXO_CACHE_SIZE_MB, HawkConfig.EXO_CACHE_SIZE_MB_DEFAULT),
        apiUrl = KV.get(HawkConfig.API_URL, ""),
        apiLines = KV.get(HawkConfig.API_LINE_LIST, ArrayList()),
        dohIndex = KV.get(HawkConfig.DOH_URL, 0),
        cacheSizeText = cacheSizeText,
    )

    /** 通用写入口:写 KV 后刷新状态流 */
    fun <T> put(key: String, value: T) {
        KV.put(key, value)
        refresh()
    }
}

/** 单选选项 sheet 的 UI 状态(设置页与播放设置页共用) */
class OptionSheetState(
    val title: String,
    val options: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
)

@Composable
fun SettingsPage(vm: SettingsViewModel = viewModel(), bottomPadding: Dp = 0.dp) {
    val state by vm.state
    // 缓存占用会随播放持续增长,而 ViewModel 只在首帧(或改设置/清理后)算一次;
    // 播放发生在 Detail/LivePlay 等独立 Activity,返回本页必然走 ON_RESUME,故在此重算,
    // 否则会一直显示进播放页之前的旧值(实测:播完一分钟返回仍显示 0KB)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshCacheSize() }
    val context = LocalContext.current
    var optionSheet by remember { mutableStateOf<OptionSheetState?>(null) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    var aboutSheet by remember { mutableStateOf(false) }

    // 无边框顶栏(2026-09-11 晚照 `示例文件/android` 官方方案重做):Scaffold + M3 TopAppBar
    val listState = rememberScrollState()

    fun openOptions(title: String, options: List<String>, currentIndex: Int, onSelect: (Int) -> Unit) {
        optionSheet = OptionSheetState(title, options, currentIndex, onSelect)
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                // 液态玻璃模式:叠加悬浮栏遮挡高度(MainScreen 下发,M3 栏模式为 0)
                .padding(bottom = 8.dp + bottomPadding),
            verticalArrangement = Arrangement.spacedBy(28.dp), // 2026-09-09:分组卡片间距 28dp(用户定稿)
        ) {
            // 顶部占位 = 顶栏高度 - 20dp(2026-09-12 用户定稿:首卡与顶栏间距在 -12 基础上再缩小 8dp,约 13dp 视觉间距)
            Spacer(Modifier.height(topPad - 20.dp))

            // 应用信息卡(2026-09-12 用户定稿:AVBox 大字 + 介绍 + 版本胶囊 + 右侧图标,28dp 圆角渐变动态取色)
            AppInfoHeaderCard(versionName)

            // ---- 设置入口(2026-09-12 用户定稿:配置管理/主题设置/播放设置/偏好设置/预载设置 合并为一组) ----
            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = "配置管理",
                        subtitle = "导入或删除订阅源",
                        iconRes = R.drawable.ic_settings_api,
                        onClick = { ConfigManageActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "主题设置",
                        subtitle = "修改应用的配色",
                        iconRes = R.drawable.ic_settings_theme,
                        onClick = { ThemeSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "播放设置",
                        subtitle = "播放核心和解码方式",
                        iconRes = R.drawable.ic_settings_play,
                        onClick = { PlaySettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "偏好设置",
                        subtitle = "修改应用的使用偏好",
                        iconRes = R.drawable.ic_settings_preference,
                        onClick = { PreferenceSettingsActivity.start(context) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = "预载设置",
                        subtitle = "播放视频时预加载",
                        iconRes = R.drawable.ic_settings_preload,
                        onClick = { PreloadSettingsActivity.start(context) },
                    )
                }
            }

            // ---- 首页与网络(2026-09-12 用户定稿:默认启动页/历史记录上限/清除缓存/DOH 一组) ----
            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = "默认启动页",
                        subtitle = "首次打开应用的所在位置",
                        iconRes = R.drawable.ic_settings_start,
                        valueText = if (state.defaultLoadLive) "直播" else "点播",
                        onClick = {
                            openOptions(
                                "默认启动页",
                                listOf("点播", "直播"),
                                if (state.defaultLoadLive) 1 else 0,
                            ) { idx -> vm.put(HawkConfig.DEFAULT_LOAD_LIVE, idx == 1) }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "历史记录上限",
                        subtitle = "最多保留多少条记录",
                        iconRes = R.drawable.ic_settings_history,
                        valueText = HistoryHelper.getHistoryNumName(state.historyNumIndex),
                        onClick = {
                            openOptions(
                                "历史记录上限",
                                listOf(0, 1, 2).map { HistoryHelper.getHistoryNumName(it) },
                                state.historyNumIndex,
                            ) { idx -> vm.put(HawkConfig.HISTORY_NUM, idx) }
                        },
                    )
                }
                // 清除缓存(2026-09-12):口径 = 内部缓存 + 外部缓存(Exo 视频缓存 exo-video-cache 在外部缓存目录,
                // 只清内部缓存等于没清);点击直接清理并刷新占用显示,不做二次确认(缓存清理不丢用户数据)
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsRow(
                        title = "清除缓存",
                        subtitle = "清理应用的使用缓存",
                        iconRes = R.drawable.ic_delete,
                        valueText = state.cacheSizeText,
                        onClick = {
                            vm.clearCache {
                                Toast.makeText(context, "清理成功", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = "DOH",
                        subtitle = "安全DNS",
                        iconRes = R.drawable.ic_settings_doh,
                        valueText = OkGoHelper.dnsHttpsList.getOrNull(state.dohIndex) ?: "关闭",
                        onClick = {
                            openOptions(
                                "DOH",
                                OkGoHelper.dnsHttpsList,
                                state.dohIndex,
                            ) { idx -> vm.put(HawkConfig.DOH_URL, idx) }
                        },
                    )
                }
            }

            // ---- 接口线路(多线路订阅时显示;2026-09-12 配置管理/DOH 移出后独立成组) ----
            if (state.apiLineVisible) {
                SettingsGroup(title = null) {
                    SettingsCard(SettingsCardPosition.SINGLE) {
                        SettingsRow(
                            title = "接口线路",
                            valueText = currentLineName(state),
                            onClick = {
                                openOptions(
                                    "接口线路",
                                    state.apiLines.map { HistoryHelper.getApiLineName(it) },
                                    currentLineIndex(state),
                                ) { idx ->
                                    val newApi = HistoryHelper.getApiLineUrl(state.apiLines[idx])
                                    if (newApi.isNotEmpty()) {
                                        val oldApi = KV.get(HawkConfig.API_URL, "")
                                        // 2026-09-12 点播/直播拆分:只切点播;直播跟随态继续跟随新线路,
                                        // 独立直播源原样保留(旧实现双写会把独立直播源冲掉)
                                        val followLive = ApiConfig.isLiveFollowVod()
                                        KV.put(HawkConfig.API_URL, newApi)
                                        if (followLive) KV.put(HawkConfig.LIVE_API_URL, "")
                                        vm.refresh()
                                        if (oldApi != newApi) {
                                            // 作废旧配置 + 通知首页刷新 + 重载:失败时不会残留旧线路的内容
                                            AppBootstrap.onApiUrlChanged()
                                        } else {
                                            ApiConfig.get().invalidateLiveConfig()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ---- 关于 ----
            SettingsGroup(title = null) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsRow(
                        title = "关于",
                        subtitle = "查看详细信息",
                        iconRes = R.drawable.ic_settings_about,
                        onClick = { aboutSheet = true },
                    )
                }
            
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsRow(
                        title = "访问 GitHub 仓库",
                        subtitle = "访问项目源代码仓库",
                        iconRes = R.drawable.ic_settings_github,
                        onClick = { openExternalUrl(context, GITHUB_REPO_URL) },
                    )
                }
            }
        }
    }

    optionSheet?.let { sheet ->
        AVBoxOptionSheet(
            onDismissRequest = { optionSheet = null },
            title = sheet.title,
            options = sheet.options,
            selected = sheet.options.getOrNull(sheet.selectedIndex),
        ) { option ->
            sheet.onSelect(sheet.options.indexOf(option))
        }
    }

    if (aboutSheet) {
        AboutSheet(versionName = versionName, onDismiss = { aboutSheet = false })
    }

}

@Composable
private fun AppInfoHeaderCard(versionName: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(scheme.primaryContainer, scheme.tertiaryContainer),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AVBox",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onPrimaryContainer,
                )
                Text(
                    text = "TVBox手机版",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                // 版本号胶囊(深底浅字,与卡片背景形成对比)
                Surface(
                    shape = CircleShape,
                    color = scheme.onPrimaryContainer,
                    contentColor = scheme.primaryContainer,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        text = if (versionName.isEmpty()) "v-.-.-" else "v$versionName",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            // 图标:复用自适应图标前景矢量(不占位图,随主题 tint 着色,深浅模式共用一套资源);
            // 前景自带启动器安全边距(图形约占画布 48%),放大 1.7 倍以匹配原图标的视觉大小
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(84.dp)
                    .scale(1.7f),
            )
        }
    }
}

/** 关于(2026-09-09:由 View 版 AboutDialog 迁移为 bottom sheet;免责文案取自原 dialog_about.xml) */
@Composable
private fun AboutSheet(versionName: String, onDismiss: () -> Unit) {
    AVBoxBottomSheet(onDismissRequest = onDismiss, title = "关于") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            if (versionName.isNotEmpty()) {
                Text(
                    text = "版本 v" + versionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "本软件只提供聚合展示功能，所有资源均来自互联网，软件不参与任何内置、制作、上传、储存、下载等内容，也不接受任何捐赠、打赏、付费等谋利行为，软件仅供开源学习参考, 请于安装后24小时内删除。\n\n打包分发请保留出处\nhttps://github.com/CatVodTVOfficial/TVBoxOSC\nhttps://github.com/q215613905/TVBoxOS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun currentLineName(state: SettingsState): String {
    val current = HistoryHelper.getApiLineUrl(state.apiUrl)
    val line = state.apiLines.firstOrNull { HistoryHelper.getApiLineUrl(it) == current }
    return if (line == null) state.apiUrl else HistoryHelper.getApiLineName(line)
}

private fun currentLineIndex(state: SettingsState): Int {
    val current = HistoryHelper.getApiLineUrl(state.apiUrl)
    return state.apiLines.indexOfFirst { HistoryHelper.getApiLineUrl(it) == current }.coerceAtLeast(0)
}

/** 项目仓库地址(2026-09-12 用户提供):设置页「访问 GitHub 仓库」入口跳转目标 */
private const val GITHUB_REPO_URL = "https://github.com/XiaochangXu/AVBox"

private fun openExternalUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 文本输入对话框(Material3 AlertDialog,2026-09-11 由 bottom sheet 迁移):确认按钮固定右下角;设置页与偏好设置页共用 */
@Composable
fun TextEditDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("请输入 API 地址") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("确定") }
        },
    )
}
