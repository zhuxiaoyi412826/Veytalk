package com.im.nativeapp.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.im.nativeapp.core.network.ApiClient
import com.im.nativeapp.core.network.ApiError
import com.im.nativeapp.core.network.FileApi
import com.im.nativeapp.core.network.FileVO
import com.im.nativeapp.core.network.UserApi
import com.im.nativeapp.core.network.UserInfoVO
import com.im.nativeapp.core.network.unwrap
import com.im.nativeapp.data.AuthRepository
import com.im.nativeapp.ui.components.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val auth: AuthRepository,
    private val session: SessionStore,
    @ApplicationContext private val context: android.content.Context
) : ViewModel() {

    val user = auth.currentUser

    var saving by mutableStateOf(false)
        private set

    private val _error = kotlinx.coroutines.flow.MutableStateFlow("")
    val error: kotlinx.coroutines.flow.StateFlow<String> = _error

    fun resolveUrl(path: String?): String? = session.resolveUrl(path)

    /** 上传头像拿 fileId（bizType=avatar），返回受控下载地址（头像存的是 url 不是 fileId） */
    private suspend fun uploadAvatar(uri: android.net.Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ApiError(-1, "无法读取所选图片")
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val part = MultipartBody.Part.createFormData(
            "file", "avatar.jpg",
            bytes.toRequestBody(mime.toMediaTypeOrNull())
        )
        val vo: FileVO = apiClient.service(FileApi::class.java)
            .upload(part, "avatar", null).unwrap(apiClient.json)
        // 头像字段存的是可展示地址；受控下载地址 /api/file/download/{fileId}
        return vo.url ?: ("/api/file/download/" + (vo.fileId ?: throw ApiError(-1, "上传未返回 fileId")))
    }

    /** 保存昵称 +（可选）新头像；成功后刷新本地用户资料让「我的」页立即变 */
    fun save(nickname: String, avatarUri: android.net.Uri?, onDone: () -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val body = mutableMapOf<String, String>()
                if (nickname.isNotBlank()) body["nickname"] = nickname.trim()
                if (avatarUri != null) body["avatar"] = uploadAvatar(avatarUri)
                if (body.isNotEmpty()) {
                    val envelope = apiClient.service(UserApi::class.java).updateProfile(body)
                    if (envelope.code != 200) throw ApiError(envelope.code, envelope.message ?: "保存失败")
                }
                // 回读最新资料：updateProfile 不一定回传完整用户对象，直接 fetchMe 最稳
                runCatching { auth.fetchMe() }
                onDone()
            } catch (e: Exception) {
                _error.value = if (e is ApiError) e.message ?: "保存失败" else "保存失败"
            } finally {
                saving = false
            }
        }
    }

    fun clearError() { _error.value = "" }
}

/** 编辑个人信息（对齐 Web 端 Settings.vue 头部「编辑个人信息」入口，落地 /user/profile） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
    onBack: () -> Unit,
    vm: ProfileEditViewModel = hiltViewModel()
) {
    val user by vm.user.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var nickname by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var nicknameInit by remember { mutableStateOf(false) }
    val saving = vm.saving

    androidx.compose.runtime.LaunchedEffect(user) {
        if (!nicknameInit && user.nickname.isNotEmpty()) {
            nickname = user.nickname
            nicknameInit = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(vm.error) {
        // 用一次性 toast 展示错误
    }
    val err by vm.error.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(err) {
        if (err.isNotEmpty()) {
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }

    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) avatarUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑个人信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像：点了换图；选了新图先本地预览，保存时才上传
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(
                    url = avatarUri?.toString() ?: vm.resolveUrl(user.avatar),
                    name = nickname.ifEmpty { user.nickname.ifEmpty { user.username } },
                    size = 88.dp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { avatarPicker.launch("image/*") }) {
                    Text(if (avatarUri != null) "已选新头像 ✓" else "更换头像")
                }
            }

            OutlinedTextField(
                value = nickname,
                onValueChange = { if (it.length <= 30) nickname = it },
                label = { Text("昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = "@${user.username}",
                onValueChange = {},
                label = { Text("账号") },
                enabled = false,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save(nickname, avatarUri) { onBack() } },
                enabled = !saving && nickname.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (saving) "保存中…" else "保存")
            }
        }
    }
}
