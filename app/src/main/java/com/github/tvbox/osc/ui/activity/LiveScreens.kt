@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.tvbox.osc.bean.LiveChannelGroup
import com.github.tvbox.osc.bean.LiveChannelItem
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.ui.activity.LivePlayActivity.PageState
import xyz.doikki.videoplayer.player.VideoView
import java.util.ArrayList
import java.util.Date
import kotlin.math.max

/**
 * 直播页 Compose UI(自 LivePlayActivity 拆出,同包)。
 * 只做渲染与交互回调;页面状态、播放控制与业务逻辑仍在 LivePlayActivity,
 * 经 `activity` 参数读取(相关成员为 internal)。
 */

// ============================================================
// Compose UI
// ============================================================

@Composable
internal fun LiveScreen(activity: LivePlayActivity) {
    // 背景色跟随实际形态:过渡期不当帧变黑/变浅,避免半新半旧
    val background = if (activity.isFullBox()) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        when (activity.pageState) {
            PageState.LOADING -> LoadStateBox(
                state = LoadState.Loading,
                emptyText = "",
                errorText = "",
                retryText = "",
                modifier = Modifier.fillMaxSize(),
                // 播放器页:加载指示保持 48dp(页面级 64dp 定稿的例外,spec §6)
                loadingContent = { ContainedLoadingIndicator(Modifier.size(48.dp)) },
            )

            PageState.EMPTY -> LoadStateBox(
                state = LoadState.Error("暂无直播频道,请检查直播配置"),
                emptyText = "",
                errorText = "暂无直播频道,请检查直播配置",
                retryText = "重试",
                modifier = Modifier.fillMaxSize(),
                onRetry = { activity.loadLiveConfigOnEnter() },
            )

            PageState.READY -> LiveReadyContent(activity)
        }
        if (activity.epgSheetVisible) EpgSheet(activity)
        if (activity.settingsSheetVisible) SettingsSheet(activity)
        activity.passwordDialogTarget?.let {
            LivePasswordDialog(
                onConfirm = { activity.onPasswordConfirmed(it) },
                onDismiss = { activity.passwordDialogTarget = null },
            )
        }
    }
}

/**
 * 频道分组密码弹窗:密码为空时确定按钮禁用。
 */
@Composable
private fun LivePasswordDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请输入密码") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = { Text("频道分组密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (password.isNotBlank()) onConfirm(password.trim()) },
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank(),
                onClick = { onConfirm(password.trim()) },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LiveReadyContent(activity: LivePlayActivity) {
    // 播放区形态 = activity.isFullBox()(过渡期跟随实际方向);
    // 竖屏高度 = 短边 × 16:9,钳制在 [150dp, 长边/2](与详情页同一套算法)
    val configuration = LocalConfiguration.current
    val shortEdge = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    val previewHeight = (shortEdge * 9f / 16f)
        .coerceAtLeast(150.dp)
        .coerceAtMost(maxOf(150.dp, longEdge / 2))
    Column(modifier = Modifier.fillMaxSize()) {
        PlayerArea(
            activity = activity,
            modifier = if (activity.isFullBox()) {
                Modifier.fillMaxSize()
            } else {
                // 状态栏区域纯黑（背景画在 statusBarsPadding 外圈），播放器紧贴其下（对齐详情页补丁⑤）
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .height(previewHeight)
            },
        )
        if (!activity.isFullBox()) {
            ChannelInfoSection(activity)
            ChannelListSection(activity, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerArea(activity: LivePlayActivity, modifier: Modifier) {
    val videoView = activity.mVideoView
    Box(modifier = modifier.background(Color.Black)) {
        if (videoView != null) {
            AndroidView(
                factory = { videoView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 切台快照
        if (activity.snapshotVisible) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                activity.snapshotBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            }
        }
        // 加载态
        if (!activity.snapshotVisible &&
            (activity.playState == VideoView.STATE_PREPARING || activity.playState == VideoView.STATE_BUFFERING)
        ) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
        }
        // 清晰度角标
        if (activity.resolutionVisible && activity.resolutionText.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = activity.resolutionText,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        // 亮度/音量指示
        activity.gestureHintText?.let { hint ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        // 时移条(回看中)
        if (activity.isBackState && activity.overlayVisible) {
            TimeshiftBar(activity, Modifier.align(Alignment.BottomCenter))
        }
        // 竖屏:常驻角标入口(节目单/设置);形态判定走 isFullBox(过渡期跟随实际方向)
        if (!activity.isFullBox()) {
            PlayerCornerButtons(activity, Modifier.align(Alignment.TopEnd))
        } else if (activity.overlayVisible) {
            // 全屏:返回按钮(浮层随交互显隐)
            IconButton(
                onClick = { activity.applyFullscreen(false) },
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出全屏",
                    tint = Color.White,
                )
            }
            PlayerCornerButtons(activity, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun PlayerCornerButtons(activity: LivePlayActivity, modifier: Modifier) {
    Row(modifier = modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerCornerButton(
            icon = { Icon(Icons.Filled.Event, contentDescription = "节目单", tint = Color.White, modifier = Modifier.size(20.dp)) },
            onClick = { activity.epgSheetVisible = true },
        )
        PlayerCornerButton(
            icon = { Icon(Icons.Filled.Settings, contentDescription = "直播设置", tint = Color.White, modifier = Modifier.size(20.dp)) },
            onClick = { activity.openSettingsSheet() },
        )
    }
}

@Composable
private fun PlayerCornerButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun TimeshiftBar(activity: LivePlayActivity, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 回看时移条不加半透明黑底,直接叠在画面上
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { activity.onTimeshiftTogglePlay() }) {
            Icon(
                imageVector = if (activity.playState == VideoView.STATE_PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = "播放/暂停",
                tint = Color.White,
            )
        }
        Slider(
            value = activity.tsPosition.toFloat().coerceIn(0f, max(activity.tsDuration, 1).toFloat()),
            onValueChange = { activity.onTimeshiftSeek(it) },
            valueRange = 0f..max(activity.tsDuration, 1).toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = LiveEpgParser.durationToString(activity.tsPosition) + " / " + LiveEpgParser.durationToString(activity.tsDuration),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ChannelInfoSection(activity: LivePlayActivity) {
    val info = activity.channelInfoUi
    if (info.name.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = info.num.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = info.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (activity.isBackState) {
                Text(text = "回看中", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(text = "直播中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (info.sourceText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = info.sourceText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = info.currentEpgTime + "  " + info.currentEpgTitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = info.nextEpgTime + "  " + info.nextEpgTitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (activity.showTimeOn || activity.showNetSpeedOn) {
            Spacer(modifier = Modifier.height(2.dp))
            val parts = ArrayList<String>()
            if (activity.showTimeOn && activity.timeText.isNotEmpty()) parts.add(activity.timeText)
            if (activity.showNetSpeedOn && activity.netSpeedText.isNotEmpty()) parts.add(activity.netSpeedText)
            Text(
                text = parts.joinToString("  "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================
// 频道分组折叠列表
// ============================================================

@Composable
private fun ChannelListSection(activity: LivePlayActivity, modifier: Modifier) {
    val listState = rememberLazyListState()
    // 频道数据/展开变化时定位到当前频道
    LaunchedEffect(activity.scrollTick, activity.channelVersion) {
        val rows = activity.buildChannelRows()
        var target = -1
        for (i in rows.indices) {
            val row = rows[i]
            if (row.channel != null &&
                row.group?.groupIndex == activity.currentChannelGroupIndex &&
                row.channel.channelIndex == activity.currentLiveChannelIndex
            ) {
                target = i
                break
            }
        }
        if (target > 0) listState.animateScrollToItem(max(0, target - 2))
    }
    val rows = activity.buildChannelRows()
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = navBarInset + 24.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            val channel = row.channel
            if (channel == null) {
                val group = row.group ?: return@itemsIndexed
                GroupHeaderRow(activity, group)
            } else {
                ChannelRow(activity, row, channel)
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(activity: LivePlayActivity, group: LiveChannelGroup) {
    val expanded = activity.expandedGroups.contains(group.groupIndex)
    val locked = group.groupPassword.isNotEmpty() && !activity.isPasswordConfirmedForUi(group.groupIndex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { activity.toggleChannelGroup(group.groupIndex) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.groupName ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "需密码",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 90f else 0f),
        )
    }
}

@Composable
private fun ChannelRow(
    activity: LivePlayActivity,
    row: LiveListRow,
    channel: LiveChannelItem,
) {
    val group = row.group ?: return
    val selected = group.groupIndex == activity.currentChannelGroupIndex &&
            channel.channelIndex == activity.currentLiveChannelIndex
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.cardContainer else Color.Transparent)
            .clickable { activity.selectChannel(group.groupIndex, row.channelPos) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.channelNum.toString(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = channel.channelName ?: "",
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EpgSheet(activity: LivePlayActivity) {
    activity.epgVersion // 读取以保证数据变化时刷新
    val channelNameStr = activity.channelName?.channelName ?: ""
    AVBoxBottomSheet(
        onDismissRequest = { activity.epgSheetVisible = false },
        title = if (channelNameStr.isEmpty()) "节目单" else "节目单 · $channelNameStr",
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // 内容自带 LazyColumn(heightIn 520dp),滚动交给它,避免与封装的内容区抢手势
        isScrollable = false,
    ) {
        // 切换成功才关闭节目单:走 LocalSheetDismiss 滑出动画;
        // 须在 SheetOverlay 的 provider 作用域内读取
        val dismissAnimated = LocalSheetDismiss.current
        val epgList = activity.epgdata
        if (epgList.isEmpty()) {
            Text(
                text = "暂无节目单",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
            return@AVBoxBottomSheet
        }
        val canCatchup = activity.canCurrentChannelCatchup()
        val now = Date()
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(bottom = 16.dp)) {
            itemsIndexed(epgList) { index, epg ->
                val isNow = epg.startdateTime != null && epg.enddateTime != null &&
                        !now.before(epg.startdateTime) && !now.after(epg.enddateTime)
                val clickable = epg.startdateTime != null && !now.before(epg.startdateTime) &&
                        (canCatchup || (epg.enddateTime != null && !now.after(epg.enddateTime)))
                val selected = index == activity.currentLiveLookBackIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = clickable) {
                            // 仅在真正切换播放(回直播/开始回看)时关闭节目单,与原行为一致
                            if (activity.onEpgRowClicked(index)) dismissAnimated()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = epg.start + "-" + epg.end,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = epg.title,
                        fontSize = 14.sp,
                        fontWeight = if (selected || isNow) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            selected -> MaterialTheme.colorScheme.primary
                            isNow -> MaterialTheme.colorScheme.onSurface
                            clickable -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        selected -> Text(text = "回看中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        isNow -> Text(text = "正在播出", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ============================================================
// 直播设置 bottom sheet
// ============================================================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(activity: LivePlayActivity) {
    activity.settingsVersion // 读取以保证数据变化时刷新
    val groups = activity.visibleSettingGroups()
    AVBoxBottomSheet(
        onDismissRequest = { activity.settingsSheetVisible = false },
        title = "直播设置",
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // 内容自带 LazyColumn(heightIn 560dp),滚动交给它
        isScrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            groups.forEach { group ->
                val items = group.liveSettingItems ?: return@forEach
                item(key = "sg" + group.groupIndex) {
                    // 配置切换(组6)标注长按删除入口
                    SettingsGroup(
                        title = if (group.groupIndex == 6) group.groupName + "（长按可删除）" else group.groupName,
                    ) {
                        // 与设置页一致:每项一张小卡,按卡位拼圆角(FIRST/MIDDLE/LAST)
                        items.forEachIndexed { index, item ->
                            val position = when {
                                items.size == 1 -> SettingsCardPosition.SINGLE
                                index == 0 -> SettingsCardPosition.FIRST
                                index == items.size - 1 -> SettingsCardPosition.LAST
                                else -> SettingsCardPosition.MIDDLE
                            }
                            SettingsCard(
                                position = position,
                                color = MaterialTheme.colorScheme.surfaceBright,
                            ) {
                                if (group.groupIndex == 4) {
                                    SettingsSwitchRow(
                                        title = item.itemName,
                                        checked = activity.settingChecked(item.itemIndex),
                                        onCheckedChange = { activity.clickSettingItem(group.groupIndex, item.itemIndex) },
                                    )
                                } else {
                                    SettingsOptionRow(
                                        title = item.itemName,
                                        selected = activity.settingSelectedIndex(group.groupIndex) == item.itemIndex,
                                        onClick = { activity.clickSettingItem(group.groupIndex, item.itemIndex) },
                                        // 配置切换历史:长按删除(当前使用中的配置拒绝删除);
                                        // 第 0 项是合成的「跟随点播源」,不参与删除,历史下标需 -1
                                        onLongClick = if (group.groupIndex == 6 && item.itemIndex > 0) {
                                            { activity.removeLiveConfigHistory(item.itemIndex - 1) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
