package com.im.nativeapp.ui.profile

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.im.nativeapp.core.config.SessionStore
import com.im.nativeapp.core.config.SettingsStore
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.CancelAccountRequest
import com.im.nativeapp.core.network.ChangePasswordRequest
import com.im.nativeapp.core.network.UserApi
import com.im.nativeapp.core.network.unwrapUnit
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.ui.components.Avatar
import com.im.nativeapp.ui.theme.ThemeEvents
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val settings: SettingsStore,
    private val apiClient: ApiClient
) : ViewModel() {

    val user = auth.currentUser

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast

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

    /**
     * 修改密码：成功后退出全部终端（后端 logoutAll 默认 true），本端也要清登录态回登录页。
     * oldPassword 允许留空：验证码登录自动建号、从未设过密码的账号首次设置密码时后端接受空值。
     */
    fun changePassword(oldPassword: String, newPassword: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                apiClient.service(UserApi::class.java)
                    .changePassword(
                        ChangePasswordRequest(
                            oldPassword = oldPassword.ifBlank { null },
                            newPassword = newPassword,
                            logoutAll = true
                        )
                    )
                    .unwrapUnit()
                auth.logout()
                _toast.tryEmit("密码已修改，请用新密码重新登录")
                onDone()
            } catch (e: Exception) {
                _toast.tryEmit(errorText(e, "修改密码失败"))
            }
        }
    }

    /** 账号注销：不可逆，成功后清本地登录态回登录页 */
    fun cancelAccount(password: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                apiClient.service(UserApi::class.java)
                    .cancelAccount(CancelAccountRequest(password))
                    .unwrapUnit()
                auth.logout()
                _toast.tryEmit("账号已注销")
                onDone()
            } catch (e: Exception) {
                _toast.tryEmit(errorText(e, "注销失败"))
            }
        }
    }

    private fun errorText(e: Exception, fallback: String): String =
        if (e is ApiError) e.message ?: fallback else "$fallback：${e.message ?: ""}"
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
    var pwdDialog by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
    var cancelDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 改密/注销结果提示（后端失败原因直接在 Toast 里展示）
    LaunchedEffect(Unit) {
        vm.toast.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 不设顶部标题栏：内容整体上移，资料卡本身就是「编辑个人信息」入口（同微信「我」）
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProfile)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(url = vm.resolveUrl(user.avatar), name = user.nickname.ifEmpty { user.username }, size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(user.nickname.ifEmpty { user.username }, style = MaterialTheme.typography.titleLarge)
                        Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text("编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
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

            // ==================== 账号与安全：编辑资料 / 修改密码 / 退出 / 注销 ====================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "账号与安全",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    AccountRow("编辑资料", onClick = onOpenProfile)
                    HorizontalDivider()
                    AccountRow("修改密码", onClick = { pwdDialog = true })
                    HorizontalDivider()
                    AccountRow("退出账号", onClick = { logoutDialog = true })
                    HorizontalDivider()
                    AccountRow("注销账号", danger = true, onClick = { cancelDialog = true })
                }
            }

            Spacer(Modifier.weight(1f))
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

    if (pwdDialog) {
        var oldPwd by remember { mutableStateOf("") }
        var newPwd by remember { mutableStateOf("") }
        var confirmPwd by remember { mutableStateOf("") }
        val ready = newPwd.length >= 6 && newPwd == confirmPwd
        AlertDialog(
            onDismissRequest = { pwdDialog = false },
            title = { Text("修改密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PwdField(oldPwd, { oldPwd = it }, "原密码（从未设过密码可留空）")
                    PwdField(newPwd, { newPwd = it }, "新密码（6-32 位）")
                    PwdField(confirmPwd, { confirmPwd = it }, "确认新密码")
                    if (newPwd.isNotEmpty() && newPwd.length < 6) {
                        Text(
                            "新密码至少 6 位",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (confirmPwd.isNotEmpty() && newPwd != confirmPwd) {
                        Text(
                            "两次输入的新密码不一致",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "修改成功后所有设备都要用新密码重新登录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pwdDialog = false
                        vm.changePassword(oldPwd, newPwd, onLogout)
                    },
                    enabled = ready
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pwdDialog = false }) { Text("取消") } }
        )
    }

    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text("退出账号") },
            text = { Text("退出后需要重新登录，聊天记录仍保留在服务器。") },
            confirmButton = {
                TextButton(onClick = {
                    logoutDialog = false
                    vm.logout(onLogout)
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("取消") } }
        )
    }

    if (cancelDialog) {
        var pwd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { cancelDialog = false },
            title = { Text("注销账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "注销后账号将被永久删除：好友关系、群组、会话与消息都会清除，且无法恢复。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    PwdField(pwd, { pwd = it }, "登录密码")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelDialog = false
                        vm.cancelAccount(pwd, onLogout)
                    },
                    enabled = pwd.isNotBlank()
                ) { Text("确认注销", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text("取消") } }
        )
    }
}

/** 账号操作行：左标题，右侧「›」指示；注销用危险色 */
@Composable
private fun AccountRow(title: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/** 密码输入框：掩码 + 密码键盘 */
@Composable
private fun PwdField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    )
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
