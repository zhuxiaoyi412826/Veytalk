package com.im.nativeapp.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.im.nativeapp.core.network.ConversationVO
import com.im.nativeapp.ui.components.Avatar
import com.im.nativeapp.ui.components.formatImTime

/**
 * 会话列表（对齐 ChatHome 的会话列）：未读数、置顶、免打扰、长按操作菜单。
 *
 * 顶部是收窄的标题栏（与微信一致）：居中「消息」+ 右侧加号，
 * 加号菜单里放发起群聊/添加朋友/扫一扫——扫一扫用于扫电脑网页的登录二维码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationTab(
    onOpenChat: (String) -> Unit,
    onOpenScan: () -> Unit = {},
    onOpenAddFriend: () -> Unit = {},
    onOpenCreateGroup: () -> Unit = {},
    vm: ConversationViewModel = hiltViewModel()
) {
    val list by vm.list.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 自绘窄标题栏：TopAppBar 固定 64dp，比微信高出一截，列表被迫整体下移
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧留出与右侧图标等宽的空位，让标题真正居中
            Spacer(Modifier.width(44.dp))
            Text(
                "消息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("发起群聊") },
                        onClick = { menuOpen = false; onOpenCreateGroup() }
                    )
                    DropdownMenuItem(
                        text = { Text("添加朋友") },
                        onClick = { menuOpen = false; onOpenAddFriend() }
                    )
                    DropdownMenuItem(
                        text = { Text("扫一扫") },
                        onClick = { menuOpen = false; onOpenScan() }
                    )
                }
            }
        }
        HorizontalDivider()
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            if (list.isEmpty() && !loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无会话，去「好友」页发起聊天吧", color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.conversationId ?: "" }) { item ->
                        ConversationRow(
                            item = item,
                            avatarUrl = vm.resolveUrl(item.avatar),
                            onClick = { item.conversationId?.let(onOpenChat) },
                            onToggleTop = { vm.toggleTop(item, !(item.top == true)) },
                            onToggleMute = { vm.toggleMute(item, !(item.muted == true)) },
                            onRemove = { vm.remove(item) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationVO,
    avatarUrl: String?,
    onClick: () -> Unit,
    onToggleTop: () -> Unit,
    onToggleMute: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (item.top == true) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surface
                )
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Avatar(url = avatarUrl, name = item.name, size = 46.dp)
                // 单聊在线绿点（对齐会话列表的 online 字段）
                if (item.type == 1 && item.online == true) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(2.dp)
                            .background(androidx.compose.ui.graphics.Color(0xFF67C23A), CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        formatImTime(item.lastMsgTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.atFlag == true) {
                        Text("[有人@我] ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        item.lastMsgContent ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        // 免打扰会话的未读降级成灰点（对齐 Web 端 muted 的视觉降噪）
                        if (item.muted == true) {
                            Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                        } else {
                            Badge { Text(if (item.unreadCount > 99) "99+" else item.unreadCount.toString()) }
                        }
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (item.top == true) "取消置顶" else "置顶") },
                onClick = { menuOpen = false; onToggleTop() }
            )
            DropdownMenuItem(
                text = { Text(if (item.muted == true) "取消免打扰" else "消息免打扰") },
                onClick = { menuOpen = false; onToggleMute() }
            )
            DropdownMenuItem(
                text = { Text("删除会话", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onRemove() }
            )
        }
    }
}
