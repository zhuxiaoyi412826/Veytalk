package com.im.nativeapp.ui.group

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.data.FriendRepository
import com.im.nativeapp.data.GroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupViewModel @Inject constructor(
    private val repo: GroupRepository,
    private val friends: FriendRepository,
    private val session: SessionStore
) : ViewModel() {

    val myGroups = repo.myGroups
    val friendList = friends.friends

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    init {
        viewModelScope.launch {
            runCatching { repo.fetchMyGroups() }
            runCatching { friends.fetchFriends() }
        }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { repo.fetchMyGroups() } }
    }

    fun create(name: String, memberIds: List<String>, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            repo.create(name.trim(), memberIds)
                .onSuccess { onDone(it.conversationId) }
                .onFailure { _error.value = it.message ?: "创建失败"; onDone(null) }
        }
    }

    fun joinByCode(code: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repo.joinByCode(code.trim())
                .onSuccess { onDone(true) }
                .onFailure { _error.value = it.message ?: "加入失败"; onDone(false) }
        }
    }

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)
    fun clearError() { _error.value = "" }
}

// ============================== 创建群聊页 ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    vm: GroupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val friends by vm.friendList.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建群聊") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("群名称") },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Text("选择成员（${selected.size}）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(friends, key = { it.friendId ?: it.username }) { friend ->
                    val fid = friend.friendId ?: return@items
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (selected.contains(fid)) selected - fid else selected + fid
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.contains(fid),
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + fid else selected - fid
                            }
                        )
                        com.im.nativeapp.ui.components.Avatar(
                            url = vm.resolveUrl(friend.avatar),
                            name = friend.displayName.ifEmpty { friend.nickname },
                            size = 36.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(friend.displayName.ifEmpty { friend.nickname })
                    }
                }
            }
            Button(
                onClick = {
                    vm.create(name, selected.toList()) { cid ->
                        if (cid != null) {
                            Toast.makeText(context, "群聊已创建", Toast.LENGTH_SHORT).show()
                            onOpenChat(cid)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank()
            ) {
                Text("创建")
            }
        }
    }
}
