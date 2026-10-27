package com.im.nativeapp.ui.friend

import android.widget.Toast
import com.im.nativeapp.ui.components.Avatar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.FriendRequestVO
import com.im.nativeapp.data.ConversationRepository
import com.im.nativeapp.data.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendViewModel @Inject constructor(
    private val repo: FriendRepository,
    private val conversations: ConversationRepository,
    private val session: SessionStore
) : ViewModel() {

    val friends = repo.friends
    val received = repo.received
    val sent = repo.sent
    val pendingCount = repo.pendingCount

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    init {
        viewModelScope.launch {
            runCatching { repo.fetchFriends() }
            runCatching { repo.refreshPendingCount() }
        }
    }

    fun search(keyword: String) {
        viewModelScope.launch { runCatching { repo.fetchFriends(keyword.trim().ifEmpty { null }) } }
    }

    fun refreshRequests() {
        viewModelScope.launch { runCatching { repo.fetchRequests() } }
    }

    fun apply(account: String, verifyMessage: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repo.apply(targetAccount = account.trim(), targetUserId = null, verifyMessage = verifyMessage.trim().ifEmpty { null })
                .onSuccess { onDone(true) }
                .onFailure { _error.value = it.message ?: "申请失败"; onDone(false) }
        }
    }

    fun accept(id: String, onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            repo.accept(id)
                .onSuccess { cid ->
                    // 接受后刷新会话列表：后端已建好单聊会话
                    runCatching { conversations.fetchList() }
                    cid?.let(onOpenChat)
                }
                .onFailure { _error.value = it.message ?: "操作失败" }
        }
    }

    fun reject(id: String) {
        viewModelScope.launch { repo.reject(id).onFailure { _error.value = it.message ?: "操作失败" } }
    }

    fun removeFriend(friendId: String) {
        viewModelScope.launch { repo.removeFriend(friendId).onFailure { _error.value = it.message ?: "删除失败" } }
    }

    /** 发起聊天：后端幂等，已有会话直接返回原 ID */
    fun openChatWith(friendId: String, onOpenChat: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { conversations.openWith(friendId) }
                .onSuccess(onOpenChat)
                .onFailure { _error.value = it.message ?: "打开会话失败" }
        }
    }

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    fun clearError() { _error.value = "" }
}

// ============================== 好友 Tab（好友|群聊 双板块，对齐 Web 端 FriendList） ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendTab(
    onOpenChat: (String) -> Unit,
    onOpenRequests: () -> Unit,
    onOpenAddFriend: () -> Unit,
    onOpenCreateGroup: () -> Unit = {},
    vm: FriendViewModel = hiltViewModel(),
    groupVm: com.im.nativeapp.ui.group.GroupViewModel = hiltViewModel()
) {
    val friends by vm.friends.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val groups by groupVm.myGroups.collectAsStateWithLifecycle()
    val groupError by groupVm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var keyword by remember { mutableStateOf("") }
    var groupKeyword by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var joinDialog by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(error) {
        if (error.isNotEmpty()) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }
    LaunchedEffect(groupError) {
        if (groupError.isNotEmpty()) {
            Toast.makeText(context, groupError, Toast.LENGTH_SHORT).show()
            groupVm.clearError()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("好友") },
            actions = {
                if (tab == 0) {
                    IconButton(onClick = onOpenAddFriend) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "添加好友")
                    }
                } else {
                    IconButton(onClick = onOpenCreateGroup) {
                        Icon(Icons.Default.GroupAdd, contentDescription = "创建群聊")
                    }
                }
            }
        )

        // 双板块切换：好友 | 群聊（对齐 Web FriendList 顶部 el-tabs）
        androidx.compose.material3.TabRow(selectedTabIndex = tab) {
            androidx.compose.material3.Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("好友 ${friends.size}") }
            )
            androidx.compose.material3.Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("群聊 ${groups.size}") }
            )
        }

        if (tab == 0) {
            // ---------- 好友板块 ----------
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it; vm.search(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索好友") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))

            // 「新的朋友」入口（待处理数红点，对齐 Web 端导航红点）
            ListItem(
                headlineContent = { Text("新的朋友") },
                leadingContent = { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    if (pendingCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(pendingCount.toString(), color = MaterialTheme.colorScheme.onError, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                modifier = Modifier.clickable(onClick = onOpenRequests)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(friends, key = { it.friendId ?: it.username }) { friend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { friend.friendId?.let { vm.openChatWith(it, onOpenChat) } }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Avatar(url = vm.resolveUrl(friend.avatar), name = friend.displayName.ifEmpty { friend.nickname }, size = 44.dp)
                            if (friend.online == true) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(10.dp)
                                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                                        .padding(2.dp)
                                        .background(androidx.compose.ui.graphics.Color(0xFF67C23A), CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(friend.displayName.ifEmpty { friend.nickname }, style = MaterialTheme.typography.titleMedium)
                            if (!friend.signature.isNullOrEmpty()) {
                                Text(
                                    friend.signature!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        TextButton(onClick = { deleteTarget = friend.friendId }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            // ---------- 群聊板块 ----------
            OutlinedTextField(
                value = groupKeyword,
                onValueChange = { groupKeyword = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索群名称") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onOpenCreateGroup, modifier = Modifier.weight(1f)) { Text("创建群聊") }
                OutlinedButton(onClick = { joinDialog = true }, modifier = Modifier.weight(1f)) { Text("邀请码加群") }
            }
            Spacer(Modifier.height(8.dp))

            val filtered = if (groupKeyword.isBlank()) groups else groups.filter { it.name.contains(groupKeyword.trim(), ignoreCase = true) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.groupId ?: "" }) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { group.conversationId?.let(onOpenChat) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(url = vm.resolveUrl(group.avatar), name = group.name, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${group.memberCount ?: 0} 人 · ${group.myRoleDesc ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "暂无群聊",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除好友") },
            text = { Text("删除后双方将解除好友关系，聊天记录保留。") },
            confirmButton = {
                TextButton(onClick = { vm.removeFriend(id); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    if (joinDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { joinDialog = false },
            title = { Text("邀请码加群") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("群邀请码") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        groupVm.joinByCode(code) { ok ->
                            if (ok) {
                                joinDialog = false
                                Toast.makeText(context, "已提交", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = code.isNotBlank()
                ) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { joinDialog = false }) { Text("取消") } }
        )
    }
}

// ============================== 好友申请页 ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendRequestsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    vm: FriendViewModel = hiltViewModel()
) {
    val received by vm.received.collectAsStateWithLifecycle()
    val sent by vm.sent.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshRequests() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新的朋友") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("收到的申请") }
            if (received.isEmpty()) item { EmptyHint("暂无收到的申请") }
            items(received, key = { "r" + (it.id ?: "") }) { req ->
                RequestRow(
                    req = req,
                    vm = vm,
                    actions = {
                        if (req.actionable == true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { req.id?.let { vm.accept(it, onOpenChat) } }) { Text("同意") }
                                OutlinedButton(onClick = { req.id?.let(vm::reject) }) { Text("拒绝") }
                            }
                        } else {
                            Text(req.statusDesc ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                )
            }
            item { SectionHeader("我发出的申请") }
            if (sent.isEmpty()) item { EmptyHint("暂无发出的申请") }
            items(sent, key = { "s" + (it.id ?: "") }) { req ->
                RequestRow(req = req, vm = vm, actions = {
                    Text(req.statusDesc ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary
    )
}

@Composable
private fun RequestRow(req: FriendRequestVO, vm: FriendViewModel, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(url = vm.resolveUrl(req.peerAvatar), name = req.peerNickname ?: req.peerUsername ?: "?", size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(req.peerNickname ?: req.peerUsername ?: "", style = MaterialTheme.typography.titleMedium)
            if (!req.verifyMessage.isNullOrEmpty()) {
                Text(
                    req.verifyMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

// ============================== 添加好友页 ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendScreen(
    onBack: () -> Unit,
    vm: FriendViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var account by remember { mutableStateOf("") }
    var verifyMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加好友") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("对方账号") },
                singleLine = true
            )
            OutlinedTextField(
                value = verifyMessage,
                onValueChange = { verifyMessage = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("验证消息（可选）") },
                maxLines = 3
            )
            Button(
                onClick = {
                    vm.apply(account, verifyMessage) { ok ->
                        if (ok) {
                            Toast.makeText(context, "申请已发送", Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = account.isNotBlank()
            ) {
                Text("发送申请")
            }
        }
    }
}
