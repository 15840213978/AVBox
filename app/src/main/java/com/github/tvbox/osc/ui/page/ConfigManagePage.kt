package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.github.tvbox.osc.ui.components.glassSurface
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.KV

private const val SubscribeSplit = "\t"

private data class SubscribeSource(val name: String, val url: String)

private enum class ConfigMode { Vod, Live }

private fun subscribeKeyOf(mode: ConfigMode): String = when (mode) {
    ConfigMode.Vod -> HawkConfig.SUBSCRIBE_LIST
    ConfigMode.Live -> HawkConfig.LIVE_SUBSCRIBE_LIST
}

private fun loadSubscribes(mode: ConfigMode): List<String> =
    KV.get(subscribeKeyOf(mode), ArrayList<String>()).toList()

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

private fun saveSubscribe(mode: ConfigMode, name: String, url: String): List<String> {
    val value = (name.ifEmpty { url }) + SubscribeSplit + url
    val list = ArrayList(loadSubscribes(mode))
    val existIndex = list.indexOfFirst { parseSubscribe(it).url == url }
    if (existIndex >= 0) list[existIndex] = value else list.add(value)
    KV.put(subscribeKeyOf(mode), list)
    return list
}

private fun updateSubscribe(mode: ConfigMode, original: SubscribeSource, name: String, url: String): List<String> {
    val value = (name.ifEmpty { url }) + SubscribeSplit + url
    val list = ArrayList(loadSubscribes(mode))
    val index = list.indexOfFirst { parseSubscribe(it).url == original.url }
    if (index < 0) return list
    list[index] = value
    val dupIndex = list.indexOfFirst { it != value && parseSubscribe(it).url == url }
    if (dupIndex >= 0) list.removeAt(dupIndex)
    KV.put(subscribeKeyOf(mode), list)
    return list
}

private fun badgeText(name: String, url: String): String = when {
    name.isNotEmpty() -> name
    url.isEmpty() -> "未配置"
    else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

private fun applyVodSource(item: SubscribeSource): Boolean {
    val followLive = ApiConfig.isLiveFollowVod()
    val oldApi = KV.get(HawkConfig.API_URL, "")
    HistoryHelper.setApiHistory(item.url)
    KV.put(HawkConfig.API_URL, item.url)
    if (followLive) KV.put(HawkConfig.LIVE_API_URL, "")
    if (!HistoryHelper.isApiLineHistory(item.url)) HistoryHelper.clearApiLineList()
    if (oldApi == item.url) {
        ApiConfig.get().invalidateLiveConfig()
        return followLive
    }
    AppBootstrap.onApiUrlChanged()
    return followLive
}

private fun applyLiveSource(item: SubscribeSource) {
    HistoryHelper.setLiveApiHistory(item.url)
    KV.put(HawkConfig.LIVE_API_URL, item.url)
    ApiConfig.get().invalidateLiveConfig()
}

private fun applyLiveFollowVod() {
    KV.put(HawkConfig.LIVE_API_URL, "")
    ApiConfig.get().invalidateLiveConfig()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigManageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(ConfigMode.Vod) }
    var vodItems by remember { mutableStateOf(loadSubscribes(ConfigMode.Vod)) }
    var liveItems by remember { mutableStateOf(loadSubscribes(ConfigMode.Live)) }
    var activeUrl by remember { mutableStateOf(KV.get(HawkConfig.API_URL, "")) }
    var liveActiveUrl by remember { mutableStateOf(KV.get(HawkConfig.LIVE_API_URL, "")) }
    var liveFollow by remember { mutableStateOf(ApiConfig.isLiveFollowVod()) }
    var addDialogOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SubscribeSource?>(null) }
    var manageMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val isVod = mode == ConfigMode.Vod
    val currentItems = if (isVod) vodItems else liveItems

    LaunchedEffect(selected, currentItems) {
        if (manageMode && selected.isEmpty()) manageMode = false
    }

    LaunchedEffect(mode) {
        manageMode = false
        selected = emptySet()
        editTarget = null
    }

    fun exitManageMode() {
        manageMode = false
        selected = emptySet()
        editTarget = null
    }

    BackHandler(enabled = manageMode) { exitManageMode() }

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
        val target = selected.filterNot { isInUse(parseSubscribe(it).url) }
        if (target.size != selected.size) {
            Toast.makeText(context, "正在使用的源不能删除", Toast.LENGTH_SHORT).show()
        }
        val remaining = currentItems.filterNot { it in target }
        KV.put(subscribeKeyOf(mode), ArrayList(remaining))
        if (isVod) {
            vodItems = remaining
            if (remaining.isEmpty()) {
                ApiConfig.get().clearVodConfig()
                activeUrl = ""
                AppBootstrap.retry()
            }
        } else {
            liveItems = remaining
            if (remaining.isEmpty()) {
                applyLiveFollowVod()
                liveActiveUrl = ""
                liveFollow = true
            }
        }
        selected = emptySet()
    }

    fun commitAdd(name: String, url: String) {
        val newItems = saveSubscribe(mode, name, url)
        if (isVod) vodItems = newItems else liveItems = newItems
        addDialogOpen = false
        if (newItems.size == 1) {
            val item = parseSubscribe(newItems.first())
            if (isVod) switchToVod(item) else switchToLive(item)
        }
    }

    fun commitEdit(target: SubscribeSource, name: String, url: String) {
        if (url.isEmpty()) return
        val newValue = (name.ifEmpty { url }) + SubscribeSplit + url
        val oldValue = selected.firstOrNull { parseSubscribe(it).url == target.url }
        val updated = updateSubscribe(mode, target, name, url)
        if (isVod) vodItems = updated else liveItems = updated
        if (oldValue != null) selected = selected - oldValue + newValue
        editTarget = null
        val item = parseSubscribe(newValue)
        if (isVod) {
            if (target.url == activeUrl && url != activeUrl) switchToVod(item)
        } else if (!liveFollow && target.url == liveActiveUrl && url != liveActiveUrl) {
            switchToLive(item)
        }
    }

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
                onClick = { if (manageMode) exitManageMode() else onNavigateBack() },
            )
        },
        actions = {
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
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val toRight = targetState == ConfigMode.Live
                    (
                        slideInHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) full / 4 else -full / 4
                        } + fadeIn(spring(stiffness = Spring.StiffnessMedium))
                        ).togetherWith(
                        slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) -full / 4 else full / 4
                        } + fadeOut(spring(stiffness = Spring.StiffnessMedium))
                    )
                },
                label = "configSegment",
            ) { m ->
                val mIsVod = m == ConfigMode.Vod
                val mItems = if (mIsVod) vodItems else liveItems
                if (mIsVod && mItems.isEmpty()) {
                    LoadStateBox(
                        state = LoadState.Empty,
                        emptyText = "暂无订阅",
                        errorText = "",
                        retryText = "",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val mOrdered = remember(mItems, activeUrl, liveActiveUrl, liveFollow, mIsVod) {
                        mItems.sortedByDescending {
                            val url = parseSubscribe(it).url
                            if (mIsVod) url == activeUrl else !liveFollow && url == liveActiveUrl
                        }
                    }
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!mIsVod) {
                            item(key = "Live#follow") {
                                FollowVodCard(
                                    checked = liveFollow,
                                    subtitle = if (activeUrl.isEmpty()) "未配置点播源" else "当前点播源:$vodBadge",
                                    onFollow = { followLiveNow() },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        items(mOrdered, key = { "${m.name}#$it" }) { value ->
                            val item = parseSubscribe(value)
                            val inUse = if (mIsVod) item.url == activeUrl else !liveFollow && item.url == liveActiveUrl
                            SubscribeCard(
                                modifier = Modifier.animateItem(),
                                item = item,
                                active = inUse,
                                manageMode = manageMode,
                                selected = value in selected,
                                onClick = {
                                    if (manageMode) {
                                        selected = if (value in selected) selected - value else selected + value
                                    } else if (mIsVod) {
                                        switchToVod(item)
                                    } else {
                                        switchToLive(item)
                                    }
                                },
                                onLongClick = {
                                    manageMode = true
                                    selected = setOf(value)
                                },
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (mIsVod) switchToVod(item) else switchToLive(item)
                                    } else if (!mIsVod) {
                                        followLiveNow()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val editing = editTarget
    if (addDialogOpen || editing != null) {
        AddSubscribeDialog(
            title = if (editing != null) {
                if (isVod) "编辑订阅" else "编辑直播源"
            } else {
                if (isVod) "添加订阅" else "添加直播源"
            },
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
                (context as? ConfigManageActivity)?.launchLocalConfig { api -> onPicked(api) }
            },
        )
    }
}

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
            onCheckedChange = { next -> if (next) onFollow() },
        )
    }
}

@Composable
private fun SubscribeCard(
    item: SubscribeSource,
    active: Boolean,
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
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    Switch(checked = active, onCheckedChange = onCheckedChange)
                }
            }
        }
    }
}

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
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
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
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
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
