package com.im.nativeapp.ui.login

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.im.nativeapp.core.config.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 登录 / 注册 / 服务器地址设置（对齐 im-ui Login.vue 的布局与交互） */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    vm: LoginViewModel = hiltViewModel()
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.loginSuccess.collect { onLoginSuccess() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 对齐 Web 登录页渐变：135deg, #3A7BD5 → #2E3238
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3A7BD5), Color(0xFF2E3238))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Brand()

                // 服务器地址：显示当前值 + 设置入口（原生端唯一的换地址入口，热点换网段免重打包）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = ui.serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.openServerDialog() }) {
                        Icon(Icons.Default.Settings, contentDescription = "服务器地址设置")
                    }
                }

                Spacer(Modifier.height(4.dp))

                val tabIndex = if (ui.tab == "sms") 1 else 0
                TabRow(selectedTabIndex = tabIndex) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { vm.update { it.copy(tab = "account") } },
                        text = { Text("账号登录") }
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = {
                            vm.update { it.copy(tab = "sms") }
                            // 验证码 5 分钟有效，切过来时若没加载出来才补（对齐 Login.vue 的 watch）
                            if (ui.captchaImage.isEmpty()) vm.loadCaptcha()
                        },
                        text = { Text("短信登录") }
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (tabIndex == 0) {
                    AccountForm(ui, vm)
                } else {
                    SmsForm(ui, vm)
                }

                if (ui.error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(ui.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("未登录请 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        "注册",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.update { it.copy(showRegister = true, registerError = "") } }
                    )
                }
            }
        }
    }

    if (ui.showServerDialog) {
        ServerDialog(ui, vm)
    }
    if (ui.showRegister) {
        RegisterDialog(ui, vm)
    }
}

@Composable
private fun Brand() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("IM", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        Text("IM 即时通讯", style = MaterialTheme.typography.titleLarge)
        Text(
            "单聊 · 消息回执 · 多端同步",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AccountForm(ui: LoginUiState, vm: LoginViewModel) {
    OutlinedTextField(
        value = ui.account,
        onValueChange = { v -> vm.update { it.copy(account = v) } },
        label = { Text("账号") },
        placeholder = { Text("用户名或手机号") },
        leadingIcon = { Icon(Icons.Default.Person, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ui.password,
        onValueChange = { v -> vm.update { it.copy(password = v) } },
        label = { Text("密码") },
        placeholder = { Text("请输入密码") },
        leadingIcon = { Icon(Icons.Default.Lock, null) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    SubmitButton(
        text = "登录",
        loading = ui.submitting,
        onClick = { vm.submitAccount() }
    )
}

@Composable
private fun SmsForm(ui: LoginUiState, vm: LoginViewModel) {
    OutlinedTextField(
        value = ui.phone,
        onValueChange = { v -> vm.update { it.copy(phone = v.take(11)) } },
        label = { Text("手机号") },
        placeholder = { Text("11 位手机号") },
        leadingIcon = { Icon(Icons.Default.Phone, null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))

    // 第一步：图形验证码，作为发送短信的闸门。点图片换一张。
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ui.captchaCode,
            onValueChange = { v -> vm.update { it.copy(captchaCode = v.take(8)) } },
            label = { Text("图形验证码") },
            placeholder = { Text("先完成图形验证") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        CaptchaImage(
            dataUri = ui.captchaImage,
            loading = ui.captchaLoading,
            onRefresh = { vm.loadCaptcha() }
        )
    }
    Spacer(Modifier.height(8.dp))

    // 第二步：短信验证码
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = ui.smsCode,
            onValueChange = { v -> vm.update { it.copy(smsCode = v.filter(Char::isDigit).take(6)) } },
            label = { Text("短信验证码") },
            placeholder = { Text("6 位数字") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { vm.sendSmsCode() },
            enabled = ui.smsCountdown <= 0 && !ui.smsSending
        ) {
            Text(if (ui.smsCountdown > 0) "${ui.smsCountdown} 秒后重发" else "获取验证码")
        }
    }
    if (ui.smsDebugCode.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "开发环境回显短信验证码：${ui.smsDebugCode}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE6A23C)
        )
    }
    Spacer(Modifier.height(12.dp))
    SubmitButton(
        text = "登录",
        loading = ui.submitting,
        onClick = { vm.submitSms() }
    )
}

/** 图形验证码图片：后端下发 Data URI（data:image/png;base64,...），本地解码展示 */
@Composable
private fun CaptchaImage(dataUri: String, loading: Boolean, onRefresh: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = dataUri) {
        value = withContext(Dispatchers.Default) { decodeDataUri(dataUri) }
    }
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(40.dp)
            .background(Color(0xFFF0F0F0), RoundedCornerShape(6.dp))
            .clickable(onClick = onRefresh),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = "点击刷新验证码",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            else -> Icon(Icons.Default.Refresh, contentDescription = "加载验证码")
        }
    }
}

private fun decodeDataUri(dataUri: String): ImageBitmap? {
    val base64 = dataUri.substringAfter(",", "")
    if (base64.isEmpty()) return null
    return try {
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun SubmitButton(text: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

// ------------------------------ 服务器地址设置 ------------------------------

@Composable
private fun ServerDialog(ui: LoginUiState, vm: LoginViewModel) {
    AlertDialog(
        onDismissRequest = { vm.update { it.copy(showServerDialog = false) } },
        title = { Text("服务器地址") },
        text = {
            Column {
                OutlinedTextField(
                    value = ui.serverInput,
                    onValueChange = { v -> vm.update { it.copy(serverInput = v) } },
                    label = { Text("后端地址") },
                    placeholder = { Text(SessionStore.DEFAULT_SERVER) },
                    supportingText = { Text("留空保存 = 恢复默认 ${SessionStore.DEFAULT_SERVER}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { vm.saveServer() }) { Text("保存并生效") }
        },
        dismissButton = {
            TextButton(onClick = { vm.update { it.copy(showServerDialog = false) } }) { Text("取消") }
        }
    )
}

// ------------------------------ 注册弹窗 ------------------------------

@Composable
private fun RegisterDialog(ui: LoginUiState, vm: LoginViewModel) {
    AlertDialog(
        onDismissRequest = { vm.update { it.copy(showRegister = false) } },
        title = { Text("注册账号") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = ui.regUsername,
                    onValueChange = { v -> vm.update { it.copy(regUsername = v) } },
                    label = { Text("账号") },
                    placeholder = { Text("字母开头，4-32 位字母数字下划线") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.regNickname,
                    onValueChange = { v -> vm.update { it.copy(regNickname = v.take(32)) } },
                    label = { Text("昵称") },
                    placeholder = { Text("留空则与账号相同") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.regPassword,
                    onValueChange = { v -> vm.update { it.copy(regPassword = v) } },
                    label = { Text("密码") },
                    placeholder = { Text("6-32 位") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.regConfirm,
                    onValueChange = { v -> vm.update { it.copy(regConfirm = v) } },
                    label = { Text("确认密码") },
                    placeholder = { Text("再输入一次") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.regPhone,
                    onValueChange = { v -> vm.update { it.copy(regPhone = v.take(11)) } },
                    label = { Text("手机号（选填）") },
                    placeholder = { Text("用于短信登录") },
                    leadingIcon = { Icon(Icons.Default.Phone, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ui.regEmail,
                    onValueChange = { v -> vm.update { it.copy(regEmail = v) } },
                    label = { Text("邮箱（选填）") },
                    placeholder = { Text("用于邮箱登录") },
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (ui.registerError.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ui.registerError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.submitRegister() },
                enabled = !ui.submitting
            ) { Text("注册并登录") }
        },
        dismissButton = {
            TextButton(onClick = { vm.update { it.copy(showRegister = false) } }) { Text("取消") }
        }
    )
}
