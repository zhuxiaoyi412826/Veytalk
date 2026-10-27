package com.im.nativeapp.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.ui.components.Avatar
import com.im.nativeapp.ui.theme.ThemeEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val settings: SettingsStore
) : ViewModel() {

    val user = auth.currentUser

    fun server(): String = session.currentServer()
    fun media(): String = session.mediaBase()
    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    fun themeMode(): String = settings.themeMode
    fun liveQuality(): String = settings.liveQuality
    fun notifySound(): Boolean = settings.notifySound

    fun setThemeMode(v: String) {
        viewModelScope.launch {
            settings.setThemeMode(v)
            ThemeEvents.notifyChanged()
        }
    }

    fun setLiveQuality(v: String) {
        viewModelScope.launch { settings.setLiveQuality(v) }
    }

    fun setNotifySound(v: Boolean) {
        viewModelScope.launch { settings.setNotifySound(v) }
    }

    fun setServer(raw: String, onDone: () -> Unit) {
        viewModelScope.launch {
            session.setServer(raw.ifBlank { null })
            onDone()
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            auth.logout()
            onDone()
        }
    }
}

/** 「我的」页：资料 + 编辑入口 + 设置（对齐 Web 端 /settings） + 服务器地址 + 登出 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    onLogout: () -> Unit,
    onOpenAi: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel()
) {
    val user by vm.user.collectAsStateWithLifecycle()
    var serverDialog by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf(vm.themeMode()) }
    var liveQuality by remember { mutableStateOf(vm.liveQuality()) }
    var notifySound by remember { mutableStateOf(vm.notifySound()) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("我的") },
            actions = {
                // 对齐 Settings.vue 头部「编辑个人信息」入口
                TextButton(onClick = onOpenProfile) { Text("编辑个人信息") }
            }
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(url = vm.resolveUrl(user.avatar), name = user.nickname.ifEmpty { user.username }, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(user.nickname.ifEmpty { user.username }, style = MaterialTheme.typography.titleLarge)
                        Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AI 面试助手", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "知识库问答 · 语音作答 · 回答播报",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onOpenAi) { Text("开始面试") }
                }
            }

            // ==================== 设置（对齐 Web /settings，仅当前设备生效） ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("设置", style = MaterialTheme.typography.titleSmall)

                    // 主题模式：浅色/深色/跟随系统
                    SettingRow("深色模式", "浅色 / 深色 / 跟随系统") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统").forEach { (v, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = themeMode == v,
                                    onClick = { themeMode = v; vm.setThemeMode(v) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    // 直播默认清晰度：进直播间默认档位
                    SettingRow("直播默认清晰度", "进直播间时的默认档位") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("auto" to "自动", "480" to "480p", "720" to "720p", "1080" to "1080p").forEach { (v, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = liveQuality == v,
                                    onClick = { liveQuality = v; vm.setLiveQuality(v) },
                                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    // 新消息提示音（仅本设备）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("消息提示音", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "新消息到一声短提示，仅当前设备生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = notifySound,
                            onCheckedChange = { notifySound = it; vm.setNotifySound(it) }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleSmall)
                    Text(vm.server(), style = MaterialTheme.typography.bodySmall)
                    Text("流媒体：${vm.media()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { serverDialog = true }) { Text("修改服务器地址") }
                }
            }

            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.logout(onLogout) }, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录")
            }
        }
    }

    if (serverDialog) {
        var input by remember { mutableStateOf(vm.server()) }
        AlertDialog(
            onDismissRequest = { serverDialog = false },
            title = { Text("服务器地址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("http(s)://host:port") },
                        singleLine = true
                    )
                    Text(
                        "清空后保存将恢复默认 ${SessionStore.DEFAULT_SERVER}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setServer(input) { serverDialog = false } }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { serverDialog = false }) { Text("取消") } }
        )
    }
}

/** 设置行：标题 + 说明在左，控制件在右（对齐 Settings.vue 的 settings__row） */
@Composable
private fun SettingRow(
    title: String,
    desc: String,
    control: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(6.dp))
        control()
    }
}
