package com.im.nativeapp.ui.friend

import android.widget.Toast
import com.im.nativeapp.ui.components.Avatar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.network.FriendRequestVO
import com.im.nativeapp.core.network.FriendVO
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

// ============================== 好友 Tab（微信通讯录式布局） ==============================

/** 通讯录分组：灰底首字母 + 该字母下的好友；startIndex 供右侧字母索引条跳转 */
private data class FriendSection(val letter: Char, val startIndex: Int, val friends: List<FriendVO>)

/**
 * 取排序/分组用的首字母。
 * 汉字用 Android 自带 ICU 的 Han-Latin 转写拿拼音首字母，不引入第三方拼音库；
 * 数字/表情等非字母开头统一归到 '#'，按通讯录惯例排在所有字母之后。
 */
private val hanLatinTransliterator by lazy {
    listOf("Han-Latin; Latin-ASCII", "Han-Latin").firstNotNullOfOrNull { id ->
        runCatching { android.icu.text.Transliterator.getInstance(id) }.getOrNull()
    }
}

private fun initialOf(name: String): Char {
    val first = name.trim().firstOrNull() ?: return '#'
    if (first.code < 128 && first.isLetter()) return first.uppercaseChar()
    val latin = hanLatinTransliterator?.transliterate(first.toString())?.trim().orEmpty()
    val letter = latin.firstOrNull { it.code < 128 && it.isLetter() } ?: return '#'
    return letter.uppercaseChar()
}

private fun displayNameOf(friend: FriendVO): String = friend.displayName.ifEmpty { friend.nickname }

/** 「新的朋友」「群聊」两个固定入口占 LazyColumn 前两项，字母分组下标从 2 起算 */
private const val CONTACT_ENTRY_COUNT = 2

private fun buildSections(friends: List<FriendVO>): List<FriendSection> {
    if (friends.isEmpty()) return emptyList()
    val sorted = friends.sortedWith(
        compareBy<FriendVO>(
            { if (initialOf(displayNameOf(it)) == '#') 1 else 0 },
            { initialOf(displayNameOf(it)) },
            { displayNameOf(it) }
        )
    )
    var index = CONTACT_ENTRY_COUNT
    return sorted.groupBy { initialOf(displayNameOf(it)) }.map { (letter, list) ->
        FriendSection(letter, index, list).also { index += 1 + list.size }
    }
}

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
    /** false=好友通讯录；true=群聊列表（对齐微信：群聊是通讯录里的一个入口，不占独立 tab） */
    var showGroups by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

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
        // 与消息页同款窄标题栏：44dp、标题居中
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showGroups) {
                IconButton(onClick = { showGroups = false; groupKeyword = "" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回好友")
                }
            } else {
                // 左侧留出与右侧图标等宽的空位，让标题居中
                Spacer(Modifier.width(44.dp))
            }
            Text(
                if (showGroups) "群聊" else "好友",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            if (showGroups) {
                IconButton(onClick = onOpenCreateGroup) {
                    Icon(Icons.Default.GroupAdd, contentDescription = "创建群聊")
                }
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("添加朋友") },
                            onClick = { menuOpen = false; onOpenAddFriend() }
                        )
                        DropdownMenuItem(
                            text = { Text("发起群聊") },
                            onClick = { menuOpen = false; onOpenCreateGroup() }
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        if (!showGroups) {
            ContactSearchField(keyword, "搜索好友") { keyword = it }
            val filtered = if (keyword.isBlank()) friends
            else friends.filter { displayNameOf(it).contains(keyword.trim(), ignoreCase = true) }
            val sections = buildSections(filtered)
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "__requests") {
                        ContactEntryRow(
                            icon = Icons.Default.PersonAdd,
                            iconBg = Color(0xFFFA9D3B),
                            label = "新的朋友",
                            badge = pendingCount,
                            onClick = onOpenRequests
                        )
                    }
                    item(key = "__groups") {
                        ContactEntryRow(
                            icon = Icons.Default.Group,
                            iconBg = Color(0xFF07C160),
                            label = "群聊",
                            subtitle = "${groups.size} 个群聊",
                            onClick = { showGroups = true }
                        )
                    }
                    sections.forEach { section ->
                        item(key = "sec-${section.letter}") { LetterHeader(section.letter) }
                        items(section.friends, key = { "f-" + (it.friendId ?: it.username) }) { friend ->
                            FriendRow(
                                friend = friend,
                                avatarUrl = vm.resolveUrl(friend.avatar),
                                onClick = { friend.friendId?.let { vm.openChatWith(it, onOpenChat) } },
                                onLongPress = { deleteTarget = friend.friendId }
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "__empty") {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (keyword.isBlank()) "还没有好友，点右上角加号添加吧" else "没有匹配的好友",
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
                // 右侧字母索引条：只列出现过的字母，点一下跳到对应分组
                if (keyword.isBlank() && sections.size >= 3) {
                    Column(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        sections.forEach { section ->
                            Text(
                                section.letter.toString(),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .clickable { scope.launch { listState.animateScrollToItem(section.startIndex) } }
                                    .padding(vertical = 1.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // ---------- 群聊列表（保留搜索 / 创建 / 邀请码加群） ----------
            ContactSearchField(groupKeyword, "搜索群名称") { groupKeyword = it }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onOpenCreateGroup, modifier = Modifier.weight(1f)) { Text("创建群聊") }
                OutlinedButton(onClick = { joinDialog = true }, modifier = Modifier.weight(1f)) { Text("邀请码加群") }
            }

            val filtered = if (groupKeyword.isBlank()) groups
            else groups.filter { it.name.contains(groupKeyword.trim(), ignoreCase = true) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.groupId ?: "" }) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { group.conversationId?.let(onOpenChat) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(url = vm.resolveUrl(group.avatar), name = group.name, size = 42.dp)
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

/** 灰底圆角搜索框（对齐微信通讯录搜索条） */
@Composable
private fun ContactSearchField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
    )
}

/** 通讯录功能入口（新的朋友 / 群聊）：彩色圆角图标 + 标题，未读用红点 */
@Composable
private fun ContactEntryRow(
    icon: ImageVector,
    iconBg: Color,
    label: String,
    subtitle: String? = null,
    badge: Int = 0,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).background(iconBg, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        if (badge > 0) {
            Box(
                modifier = Modifier.size(20.dp).background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** 字母分组头：灰底通栏 */
@Composable
private fun LetterHeader(letter: Char) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 3.dp)
    ) {
        Text(
            letter.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

/** 好友行：点进聊天，长按弹删除（不再每行挂红色删除按钮，贴近微信通讯录） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(
    friend: FriendVO,
    avatarUrl: String?,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Avatar(url = avatarUrl, name = displayNameOf(friend), size = 42.dp)
            if (friend.online == true) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(2.dp)
                        .background(Color(0xFF67C23A), CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayNameOf(friend),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
