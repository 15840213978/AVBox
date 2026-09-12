package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.components.CapsuleSegmentedButton
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.SegmentOption
import com.github.tvbox.osc.ui.components.SegmentStyle
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsIconBadge
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.orhanobut.hawk.Hawk

/** 订阅源分隔符(参照 HistoryHelper 的 api 线路约定,名字与链接用 \t 拼接存储) */
private const val SubscribeSplit = "\t"

/** 订阅源:名字 + 链接 */
private data class SubscribeSource(val name: String, val url: String)

/** 配置管理页分段:点播源 / 独立直播源(2026-09-12 点播/直播拆分) */
private enum class ConfigMode { Vod, Live }

/** 分段对应的订阅列表存储键:两个角色各自独立,同一链接不必重复录入(直播可保持"跟随") */
private fun subscribeKeyOf(mode: ConfigMode): String = when (mode) {
    ConfigMode.Vod -> HawkConfig.SUBSCRIBE_LIST
    ConfigMode.Live -> HawkConfig.LIVE_SUBSCRIBE_LIST
}

/** 读取某角色的全部订阅源(Hawk: ArrayList<String>,每项 = "名字\t链接") */
private fun loadSubscribes(mode: ConfigMode): List<String> =
    Hawk.get(subscribeKeyOf(mode), ArrayList<String>()).toList()

private fun parseSubscribe(value: String): SubscribeSource {
    val index = value.indexOf(SubscribeSplit)
    return if (index < 0) {
        SubscribeSource(value.trim(), value.trim())
    } else {
        SubscribeSource(
            value.substring(0, index).trim(),
            value.substring(index + SubscribeSplit.length).trim(),
        )
    }
}

/** 保存订阅:同链接视为更新(保留新名字,位置不变),否则追加到列表末尾(2026-09-11:后加的在下方) */
private fun saveSubscribe(mode: ConfigMode, name: String, url: String): List<String> {
    val value = (name.ifEmpty { url }) + SubscribeSplit + url
    val list = ArrayList(loadSubscribes(mode))
    val existIndex = list.indexOfFirst { parseSubscribe(it).url == url }
    if (existIndex >= 0) list[existIndex] = value else list.add(value)
    Hawk.put(subscribeKeyOf(mode), list)
    return list
}

/**
 * 编辑订阅(2026-09-12):按**原链接**定位并原地更新(名称与链接都可改,列表位置不变)。
 * 若把链接改成与另一项相同,则去掉被撞的那一项 —— 保留刚编辑的这项,避免出现重复项。
 */
private fun updateSubscribe(mode: ConfigMode, original: SubscribeSource, name: String, url: String): List<String> {
    val value = (name.ifEmpty { url }) + SubscribeSplit + url
    val list = ArrayList(loadSubscribes(mode))
    val index = list.indexOfFirst { parseSubscribe(it).url == original.url }
    if (index < 0) return list
    list[index] = value
    val dupIndex = list.indexOfFirst { it != value && parseSubscribe(it).url == url }
    if (dupIndex >= 0) list.removeAt(dupIndex)
    Hawk.put(subscribeKeyOf(mode), list)
    return list
}

/**
 * 分段徽标文本:优先订阅名;名字取不到(如线路切换后接口地址不在订阅列表里)时退回主机名,
 * 避免整条 url 把胶囊撑坏;都没有则显示「未配置」。
 */
private fun badgeText(name: String, url: String): String = when {
    name.isNotEmpty() -> name
    url.isEmpty() -> "未配置"
    else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

// ============================================================
// 写入侧(2026-09-12 点播/直播拆分:两个角色互不覆盖)
// ============================================================

/**
 * 切换点播源:只写点播侧。直播若处于跟随态,归一化为「空 = 跟随」并继续跟随新的点播源;
 * 直播若为独立源则一个字都不改(修复旧 applySubscribe 双写把独立直播源冲掉的问题)。
 * @return 切换后直播是否处于跟随态
 */
private fun applyVodSource(item: SubscribeSource): Boolean {
    val followLive = ApiConfig.isLiveFollowVod() // 必须在改写 API_URL 之前判定
    val oldApi = Hawk.get(HawkConfig.API_URL, "")
    HistoryHelper.setApiHistory(item.url)
    Hawk.put(HawkConfig.API_URL, item.url)
    if (followLive) Hawk.put(HawkConfig.LIVE_API_URL, "")
    if (!HistoryHelper.isApiLineHistory(item.url)) HistoryHelper.clearApiLineList()
    if (oldApi == item.url) {
        // 地址没变(重新启用同一个源):不必作废内存配置,也不必整页重载
        ApiConfig.get().invalidateLiveConfig()
        return followLive
    }
    // 地址变了:作废旧配置 + 通知首页立即刷新 + 重新拉取。
    // 关键是第一步 —— 新源若拉取失败,首页会落到空态/引导态,而不是继续显示旧源内容
    AppBootstrap.onApiUrlChanged()
    return followLive
}

/** 切换独立直播源:只写直播侧,不触碰点播配置,也不触发点播整页重载 */
private fun applyLiveSource(item: SubscribeSource) {
    HistoryHelper.setLiveApiHistory(item.url)
    Hawk.put(HawkConfig.LIVE_API_URL, item.url)
    ApiConfig.get().invalidateLiveConfig()
}

/** 回到「跟随点播源」:清空独立直播源(LIVE_API_URL 空 = 跟随当前点播源) */
private fun applyLiveFollowVod() {
    Hawk.put(HawkConfig.LIVE_API_URL, "")
    ApiConfig.get().invalidateLiveConfig()
}

/**
 * 配置管理页(2026-09-11,用户多轮迭代定稿;2026-09-12 点播/直播拆分):
 * 全 App 唯一的源添加/管理入口 —— 右上角「添加订阅」圆钮(40dp surfaceBright 圆底 +
 * `.tubiao/添加订阅.svg`)→ Material3 dialog(名字 / 链接两行输入 + 标题右上角「从本地选择」+ 右下角保存);
 * 已保存订阅源以 28dp 圆角卡片展示(距屏幕边缘 16dp),卡片右侧开关 = 切换当前接口(单选);
 * **已开启的源置顶**,其余按添加顺序排列(后加的在下);长按卡片进入管理模式(卡片转勾选),
 * 右上角出现删除控件;**正在使用的源不可删除**(勾选框禁用 + 长按/点选 Toast 提示)。
 *
 * 2026-09-12 起点播/直播分段:LIVE_API_URL 与 API_URL 分离,直播段首项固定为「跟随点播源」
 * (= 直播未单独配置时的默认来源,始终复用当前点播源),独立直播源优先级更高且点播不受影响。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigManageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(ConfigMode.Vod) }
    // 两个角色的列表都常驻:徽标要显示"另一个角色当前选的是哪个源",不能只持有当前段的数据
    var vodItems by remember { mutableStateOf(loadSubscribes(ConfigMode.Vod)) }
    var liveItems by remember { mutableStateOf(loadSubscribes(ConfigMode.Live)) }
    var activeUrl by remember { mutableStateOf(Hawk.get(HawkConfig.API_URL, "")) }
    var liveActiveUrl by remember { mutableStateOf(Hawk.get(HawkConfig.LIVE_API_URL, "")) }
    var liveFollow by remember { mutableStateOf(ApiConfig.isLiveFollowVod()) }
    var addDialogOpen by remember { mutableStateOf(false) }
    /** 编辑目标:非空即处于「编辑订阅」态(与 [addDialogOpen] 共用同一个 dialog) */
    var editTarget by remember { mutableStateOf<SubscribeSource?>(null) }
    var manageMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val isVod = mode == ConfigMode.Vod
    // 命名避开 LazyListScope.items DSL 函数,防止后续在 LazyColumn 内容里误引用
    val currentItems = if (isVod) vodItems else liveItems
    val listState = rememberLazyListState()

    // 管理模式下取消全部选中即自动退出(删除控件随之隐藏);列表清空同理
    LaunchedEffect(selected, currentItems) {
        if (manageMode && selected.isEmpty()) manageMode = false
    }

    // 切分段:退出管理模式并清空勾选(否则会把另一角色勾中的源当成当前角色的删除目标)+ 列表回顶
    LaunchedEffect(mode) {
        manageMode = false
        selected = emptySet()
        editTarget = null
        listState.scrollToItem(0)
    }

    /** 退出管理模式:取消勾选并关闭编辑弹窗(右上角控件随之回到「添加」) */
    fun exitManageMode() {
        manageMode = false
        selected = emptySet()
        editTarget = null
    }

    // 返回:管理模式下先退回普通态,不直接离开页面(2026-09-12);
    // 非管理模式不拦截,交给 Activity 默认返回(= finish)
    BackHandler(enabled = manageMode) { exitManageMode() }

    // 当前角色选中的源(直播跟随态下没有选中项,由「跟随点播源」卡承担)
    fun isInUse(url: String): Boolean =
        if (isVod) url == activeUrl else !liveFollow && url == liveActiveUrl

    fun switchToVod(item: SubscribeSource) {
        if (activeUrl == item.url) return
        val followLive = applyVodSource(item)
        activeUrl = item.url
        if (followLive) {
            liveActiveUrl = ""
            liveFollow = true
        }
        Toast.makeText(context, "已切换到:" + item.name, Toast.LENGTH_SHORT).show()
    }

    fun switchToLive(item: SubscribeSource) {
        if (!liveFollow && liveActiveUrl == item.url) return
        applyLiveSource(item)
        liveActiveUrl = item.url
        liveFollow = false
        Toast.makeText(context, "已切换到:" + item.name, Toast.LENGTH_SHORT).show()
    }

    fun followLiveNow() {
        applyLiveFollowVod()
        liveActiveUrl = ""
        liveFollow = true
        Toast.makeText(context, "直播已跟随点播源", Toast.LENGTH_SHORT).show()
    }

    fun deleteSelected() {
        // 正在使用的源不可删(长按/点选已拦截,这里再兜一层)
        val target = selected.filterNot { isInUse(parseSubscribe(it).url) }
        val remaining = currentItems.filterNot { it in target }
        Hawk.put(subscribeKeyOf(mode), ArrayList(remaining))
        if (isVod) {
            vodItems = remaining
            if (remaining.isEmpty()) {
                // 点播列表被删空(激活源不在列表中的边界情形)→ 清空点播配置回引导态;
                // 独立直播源不受影响(旧的 clearConfig 会连坐清掉,2026-09-12 起改走 clearVodConfig)
                ApiConfig.get().clearVodConfig()
                activeUrl = ""
                AppBootstrap.retry()
            }
        } else {
            liveItems = remaining
            if (remaining.isEmpty()) {
                // 直播源被删空:自动回落到「跟随点播源」,点播侧完全不受影响
                applyLiveFollowVod()
                liveActiveUrl = ""
                liveFollow = true
            }
        }
        selected = emptySet()
    }

    /** 新增订阅:列表首个订阅源添加后直接启用(新装/清空后省一步开关操作) */
    fun commitAdd(name: String, url: String) {
        val newItems = saveSubscribe(mode, name, url)
        if (isVod) vodItems = newItems else liveItems = newItems
        addDialogOpen = false
        if (newItems.size == 1) {
            val item = parseSubscribe(newItems.first())
            if (isVod) switchToVod(item) else switchToLive(item)
        }
    }

    /**
     * 编辑选中订阅(2026-09-12):原地更新名称/链接。
     * 改的若是**当前正在使用**的源且地址变了,按新地址重新生效(点播走整页重载、直播只换直播侧);
     * 仅名称变化不需要重新生效,列表状态更新即可。
     */
    fun commitEdit(target: SubscribeSource, name: String, url: String) {
        if (url.isEmpty()) return
        val newValue = (name.ifEmpty { url }) + SubscribeSplit + url
        val oldValue = selected.firstOrNull { parseSubscribe(it).url == target.url }
        val updated = updateSubscribe(mode, target, name, url)
        if (isVod) vodItems = updated else liveItems = updated
        // 条目字符串随名称/链接变化,同步替换勾选值,保持该项仍处于勾选态
        if (oldValue != null) selected = selected - oldValue + newValue
        editTarget = null
        val item = parseSubscribe(newValue)
        if (isVod) {
            if (target.url == activeUrl && url != activeUrl) switchToVod(item)
        } else if (!liveFollow && target.url == liveActiveUrl && url != liveActiveUrl) {
            switchToLive(item)
        }
    }

    // 当前角色正在使用的源置顶,其余保持添加顺序(2026-09-11 用户要求:后加的源在下方)
    val orderedItems = remember(currentItems, activeUrl, liveActiveUrl, liveFollow, isVod) {
        currentItems.sortedByDescending {
            val url = parseSubscribe(it).url
            if (isVod) url == activeUrl else !liveFollow && url == liveActiveUrl
        }
    }

    // 分段徽标:让用户不切分段也能看到两个角色各自的当前选择
    val vodBadge = remember(vodItems, activeUrl) {
        badgeText(vodItems.firstOrNull { parseSubscribe(it).url == activeUrl }?.let { parseSubscribe(it).name }.orEmpty(), activeUrl)
    }
    val liveBadge = remember(liveItems, liveActiveUrl, liveFollow) {
        if (liveFollow) {
            ApiConfig.LIVE_FOLLOW_ITEM_NAME
        } else {
            badgeText(liveItems.firstOrNull { parseSubscribe(it).url == liveActiveUrl }?.let { parseSubscribe(it).name }.orEmpty(), liveActiveUrl)
        }
    }

    AppTopBarScaffold(
        // 分段行常驻内容区顶部(与首页/搜索页同款 pinned 顶栏):分段不被列表滚走,topPad 恒定
        collapseEnabled = false,
        titleContent = {
            Text(
                text = "配置管理",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                "返回",
                // 管理模式下箭头与系统返回同为「先退出管理模式」,避免误触丢勾选
                onClick = { if (manageMode) exitManageMode() else onNavigateBack() },
            )
        },
        actions = {
            // 管理模式 ↔ 添加按钮:缩放+淡入淡出过渡(2026-09-12 用户要求加动画)
            AnimatedContent(
                targetState = manageMode && currentItems.isNotEmpty(),
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configTopAction",
            ) { managing ->
                if (managing) {
                    // 管理模式:右上角 =「编辑」+「删除」(删除控件左侧为编辑,2026-09-12 用户要求);
                    // 编辑只对"选中的那一个源"生效,故多选时禁用
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_edit,
                            contentDescription = "编辑",
                            enabled = selected.size == 1,
                            onClick = { editTarget = selected.firstOrNull()?.let { parseSubscribe(it) } },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = "删除",
                            enabled = selected.isNotEmpty(),
                            onClick = { deleteSelected() },
                        )
                    }
                } else {
                    // 添加订阅:40dp 圆形控件(surfaceBright 圆底)+ .tubiao/添加订阅.svg
                    TopBarActionBox(
                        iconRes = R.drawable.ic_subscribe_add,
                        contentDescription = if (isVod) "添加订阅" else "添加直播源",
                        onClick = { addDialogOpen = true },
                    )
                }
            }
        },
    ) { topPad, _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            // 点播 / 直播分段(2026-09-12):两行版(标题 + 当前源徽标),Track 外观 = 胶囊轨道包裹
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(label = "点播", value = ConfigMode.Vod, badge = vodBadge),
                    SegmentOption(label = "直播", value = ConfigMode.Live, badge = liveBadge),
                ),
                selectedValue = mode,
                onOptionSelected = { mode = it },
                style = SegmentStyle.Track,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = topPad + 8.dp),
            )
            if (isVod && currentItems.isEmpty()) {
                // 点播段空态 = 全 App 未配置订阅接口的引导态
                LoadStateBox(
                    state = LoadState.Empty,
                    emptyText = "暂无订阅",
                    errorText = "",
                    retryText = "",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 顶栏留白已由上面的分段行承担,这里只留分段与首卡的 12dp 间距(与卡间距一致)
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp), // 卡片间距 12dp(与历史页一致)
                ) {
                    if (!isVod) {
                        // 直播段首项:合成的「跟随点播源」(非订阅项,不参与长按删除)
                        item(key = "Live#follow") {
                            FollowVodCard(
                                checked = liveFollow,
                                subtitle = if (activeUrl.isEmpty()) "未配置点播源" else "当前点播源:$vodBadge",
                                onFollow = { followLiveNow() },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    items(orderedItems, key = { "${mode.name}#$it" }) { value ->
                        val item = parseSubscribe(value)
                        val inUse = isInUse(item.url)
                        SubscribeCard(
                            // 切源时激活卡片置顶重排:animateItem 让卡片平滑滑动到新位置(2026-09-12 用户要求)
                            modifier = Modifier.animateItem(),
                            item = item,
                            active = inUse,
                            deletable = !inUse,
                            manageMode = manageMode,
                            selected = value in selected,
                            onClick = {
                                if (manageMode) {
                                    // 正在使用的源不可删(勾选框禁用,点击给提示)
                                    if (inUse) {
                                        Toast.makeText(context, "正在使用的源不能删除", Toast.LENGTH_SHORT).show()
                                    } else {
                                        selected = if (value in selected) selected - value else selected + value
                                    }
                                } else if (isVod) {
                                    switchToVod(item)
                                } else {
                                    switchToLive(item)
                                }
                            },
                            onLongClick = {
                                // 长按进入管理模式并选中该卡(右上角出现删除控件);正在使用的源不可删
                                if (inUse) {
                                    Toast.makeText(context, "正在使用的源不能删除", Toast.LENGTH_SHORT).show()
                                } else {
                                    manageMode = true
                                    selected = setOf(value)
                                }
                            },
                            onCheckedChange = { checked ->
                                // 点播:关闭不动作(必须有一个点播源);直播:关闭 = 回到「跟随点播源」
                                if (checked) {
                                    if (isVod) switchToVod(item) else switchToLive(item)
                                } else if (!isVod) {
                                    followLiveNow()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 新增 / 编辑共用一个 dialog(编辑态由 editTarget 非空决定,字段预填当前值)
    val editing = editTarget
    if (addDialogOpen || editing != null) {
        AddSubscribeDialog(
            title = if (editing != null) {
                if (isVod) "编辑订阅" else "编辑直播源"
            } else {
                if (isVod) "添加订阅" else "添加直播源"
            },
            // 直播源的链接形态比点播宽:接口 JSON / 纯直播 JSON / m3u / txt 都能直接填
            urlSupportingText = if (isVod) "" else "支持配置 JSON / m3u / txt 直播源",
            initialName = editing?.name.orEmpty(),
            initialUrl = editing?.url.orEmpty(),
            onDismiss = {
                addDialogOpen = false
                editTarget = null
            },
            onSave = { name, url ->
                if (editing != null) commitEdit(editing, name, url) else commitAdd(name, url)
            },
            onPickFile = { onPicked ->
                // 系统 SAF 文件选择器(2026-09-11 起替代自绘 LocalFileActivity,自带读取授权、无需存储权限)
                (context as? ConfigManageActivity)?.launchLocalConfig { api -> onPicked(api) }
            },
        )
    }
}

/**
 * 直播段的「跟随点播源」合成卡(2026-09-12):
 * 直播未单独配置时的默认来源 —— 直接复用当前点播源配置里的 lives,点播换源时自动跟随。
 * 开关不可关闭(直播至少要有一个来源),点击 = 从独立直播源切回跟随;不参与长按删除。
 * 副标题(当前点播源名)走 subtitle 而非 valueText:后者在 Row 里不受 weight 约束,
 * 源名过长会把右侧开关挤出卡片。
 */
@Composable
private fun FollowVodCard(
    checked: Boolean,
    subtitle: String,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(position = SettingsCardPosition.SINGLE, modifier = modifier) {
        SettingsSwitchRow(
            title = "跟随点播源",
            subtitle = subtitle,
            checked = checked,
            // 关闭不动作:直播至少要有跟随点播源这个兜底来源
            onCheckedChange = { next -> if (next) onFollow() },
        )
    }
}

/**
 * 订阅源卡片:28dp 圆角卡片容器(cardContainer),距屏幕边缘 16dp 由列表 contentPadding 保证;
 * 左侧 = 40dp 圆形源图标(`SettingsIconBadge` + `.tubiao/配置管理的订阅源卡片icon图标.svg`),
 * 右侧开关 = 是否当前接口(单选,开关切换);管理模式下开关转勾选框,整卡点击 = 切换选中;
 * 正在使用的源不可删除([deletable] = false 时勾选框禁用)。
 */
@Composable
private fun SubscribeCard(
    item: SubscribeSource,
    active: Boolean,
    deletable: Boolean,
    manageMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 源图标:40dp 圆形 primaryContainer 容器 + 22dp 图标(来自 .tubiao 的订阅源图标);
            // 放在 Column 外,使名称与链接同处一条基线、整体与图标垂直居中(卡高不变)
            SettingsIconBadge(iconRes = R.drawable.ic_subscribe_source)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            // 管理模式勾选框 ↔ 开关:缩放+淡入过渡(2026-09-12 用户要求加动画)
            AnimatedContent(
                targetState = manageMode,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.7f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configRowControl",
            ) { managing ->
                if (managing) {
                    // 正在使用的源不可删:勾选框禁用(长按/点选也会给提示)
                    Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = deletable)
                } else {
                    Switch(checked = active, onCheckedChange = onCheckedChange)
                }
            }
        }
    }
}

/**
 * 添加 / 编辑订阅对话框(Material3 AlertDialog):两行输入(名字 / 链接)+ 右下角保存;
 * 标题右上角「从本地选择」控件(40dp 圆形 surfaceBright 圆底 + `.tubiao/文件选择.svg`),
 * 打开系统 SAF 选择器,选中后把 clan:// 接口地址回填到链接输入框。
 * [initialName] / [initialUrl] 用于编辑态预填(新增态传空串)。
 */
@Composable
private fun AddSubscribeDialog(
    title: String,
    urlSupportingText: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onPickFile: (onPicked: (String) -> Unit) -> Unit,
) {
    // 对话框按需组合:每次打开都是新实例,直接以入参为初值即可
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    // 链接框下方提示(M3 supportingText 槽):显式标注可组合函数类型,
    // 避免 `?.let { { Text(it) } }` 推断成普通 ()->Unit 而与非可组合类型不匹配
    val urlHint: (@Composable () -> Unit)? = if (urlSupportingText.isEmpty()) {
        null
    } else {
        {
            Text(
                text = urlSupportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceBright)
                        .clickable { onPickFile { picked -> url = picked } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = "从本地选择",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名字") },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("链接") },
                    supportingText = urlHint,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("保存") }
        },
    )
}
