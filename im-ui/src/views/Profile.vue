<template>
  <div class="profile im-scroll">
    <div class="profile__inner">
      <!-- ==================== 头像与基本身份 ==================== -->
      <section class="profile__card">
        <div class="profile__avatar-row">
          <div class="profile__avatar" title="点击更换头像" @click="pickAvatar">
            <UserAvatar :src="auth.avatarRaw" :name="auth.nickname" :size="88" />
            <div class="profile__avatar-mask">
              <el-icon v-if="avatarUploading" class="is-loading"><Loading /></el-icon>
              <el-icon v-else><Camera /></el-icon>
              <span>更换</span>
            </div>
          </div>
          <input ref="avatarInputRef" type="file" accept="image/*" class="profile__file-input" @change="onAvatarPicked" />

          <div class="profile__identity">
            <div class="profile__nickname">{{ auth.nickname || '未设置昵称' }}</div>
            <div class="profile__account">@{{ auth.username }}</div>
            <div class="profile__meta">
              <el-tag v-for="role in auth.roles" :key="role" size="small" effect="plain">{{ role }}</el-tag>
              <el-tag size="small" type="info" effect="plain">{{ auth.permissions.length }} 项权限</el-tag>
            </div>
          </div>
        </div>

        <el-descriptions :column="2" border size="small" class="profile__facts">
          <el-descriptions-item label="用户 ID">{{ auth.userId }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ maskPhone(auth.phone) || '未绑定' }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ formatDateTime(userInfo.createTime) || '—' }}</el-descriptions-item>
          <el-descriptions-item label="上次登录">{{ formatDateTime(userInfo.lastLoginTime) || '—' }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <!-- ==================== 资料编辑 ==================== -->
      <section class="profile__card">
        <div class="profile__card-title">编辑资料</div>
        <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" class="profile__form">
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model.trim="form.nickname" maxlength="32" show-word-limit placeholder="留空将回退为账号名" />
          </el-form-item>
          <el-form-item label="性别" prop="gender">
            <el-radio-group v-model="form.gender">
              <el-radio :value="0">保密</el-radio>
              <el-radio :value="1">男</el-radio>
              <el-radio :value="2">女</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="签名" prop="signature">
            <el-input
              v-model.trim="form.signature"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="写一句个性签名"
            />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model.trim="form.email" maxlength="64" clearable placeholder="选填" />
          </el-form-item>
          <!--
            手机号只展示不可改：UpdateProfileRequest 里没有 phone 字段，
            换绑手机要走短信验证流程，放进这个表单会造成「改了但没生效」的假象。
          -->
          <el-form-item label="手机号">
            <span class="profile__readonly">{{ maskPhone(auth.phone) || '未绑定' }}</span>
          </el-form-item>
          <el-form-item label="账号">
            <span class="profile__readonly">{{ auth.username }}</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" :disabled="!dirty" @click="submit">保存修改</el-button>
            <el-button :disabled="!dirty" @click="resetForm">撤销</el-button>
          </el-form-item>
        </el-form>
      </section>

      <!-- ==================== 账号安全 ==================== -->
      <section class="profile__card">
        <div class="profile__card-title">账号安全</div>
        <div class="profile__actions">
          <el-button :icon="Lock" @click="openPasswordDialog">修改密码</el-button>
          <el-button type="danger" plain :icon="SwitchButton" @click="onLogout">退出登录</el-button>
        </div>
      </section>
    </div>

    <!-- ==================== 修改密码 ==================== -->
    <el-dialog v-model="passwordDialog.visible" title="修改密码" width="420px" @closed="resetPasswordForm">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="90px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="6-32 位" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            placeholder="再输入一次"
            @keyup.enter="submitPassword"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="passwordForm.logoutAll">修改后退出所有设备（包括当前）</el-checkbox>
          <div class="profile__hint">
            后端默认就是这个行为：不勾选才会保留当前登录态。改了密码还留着旧设备的登录，
            等于没改。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="passwordDialog.saving" @click="submitPassword">确定修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Camera, Loading, Lock, SwitchButton } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { uploadAvatar } from '@/api/file'
import { useAuthStore } from '@/stores/auth'
import { signOut } from '@/stores'
import { formatFileSize, formatDateTime, maskPhone } from '@/utils/format'

defineOptions({ name: 'Profile' })

const router = useRouter()
const auth = useAuthStore()

/** 与 im.file.max-avatar-size 一致，头像走的是独立的更小限额 */
const MAX_AVATAR_BYTES = 2 * 1024 * 1024

const userInfo = computed(() => auth.userInfo || {})

/* ------------------------------ 资料编辑 ------------------------------ */

const formRef = ref(null)
const saving = ref(false)
const form = reactive({ nickname: '', gender: 0, signature: '', email: '' })

/** 上次成功保存的快照，用来判断「有没有改动」，没改就把保存按钮置灰 */
const snapshot = ref('')

function fillForm() {
  form.nickname = auth.userInfo?.nickname || ''
  form.gender = Number(auth.userInfo?.gender ?? 0)
  form.signature = auth.userInfo?.signature || ''
  form.email = auth.userInfo?.email || ''
  snapshot.value = JSON.stringify(form)
}

const dirty = computed(() => JSON.stringify(form) !== snapshot.value)

const rules = {
  nickname: [{ max: 32, message: '昵称最多 32 个字符', trigger: 'blur' }],
  gender: [{ type: 'number', min: 0, max: 2, message: '性别取值只能为 0/1/2', trigger: 'change' }],
  signature: [{ max: 255, message: '签名最多 255 个字符', trigger: 'blur' }],
  email: [
    { max: 64, message: '邮箱最多 64 个字符', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ]
}

function resetForm() {
  fillForm()
  formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    // 只发后端 UpdateProfileRequest 认得的字段：多传 phone 之类的会被静默忽略，
    // 但会让读代码的人误以为这里能改手机号
    await auth.updateProfile({
      nickname: form.nickname,
      gender: form.gender,
      signature: form.signature,
      email: form.email
    })
    fillForm()
    ElMessage.success('资料已更新')
  } catch {
    // 提示已由 request.js 弹出
  } finally {
    saving.value = false
  }
}

/* -------------------------------- 头像 -------------------------------- */

const avatarInputRef = ref(null)
const avatarUploading = ref(false)

function pickAvatar() {
  if (avatarUploading.value) {
    return
  }
  avatarInputRef.value?.click()
}

async function onAvatarPicked(event) {
  const input = event.target
  const file = input.files && input.files[0]
  // 连续两次选同一个文件时 value 没变则不触发 change，必须清掉
  input.value = ''
  if (!file) {
    return
  }
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }
  if (file.size > MAX_AVATAR_BYTES) {
    ElMessage.error(`头像不能超过 ${formatFileSize(MAX_AVATAR_BYTES)}，当前 ${formatFileSize(file.size)}`)
    return
  }
  avatarUploading.value = true
  try {
    const vo = await uploadAvatar(file)
    // /api/file/avatar 服务端已经把地址写进了当前用户资料，
    // 这里只需刷新本地缓存，不必再调一次修改资料接口
    auth.applyAvatar(vo)
    ElMessage.success('头像已更新')
  } catch {
    // 提示已弹出
  } finally {
    avatarUploading.value = false
  }
}

/* ------------------------------ 修改密码 ------------------------------ */

const passwordFormRef = ref(null)
const passwordDialog = reactive({ visible: false, saving: false })
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '', logoutAll: true })

const passwordRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function openPasswordDialog() {
  passwordDialog.visible = true
}

function resetPasswordForm() {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordForm.logoutAll = true
  passwordFormRef.value?.clearValidate()
}

async function submitPassword() {
  const valid = await passwordFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  passwordDialog.saving = true
  try {
    const stayed = await auth.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
      logoutAll: passwordForm.logoutAll
    })
    passwordDialog.visible = false
    if (stayed) {
      ElMessage.success('密码已修改')
      return
    }
    // 勾选了「退出所有设备」时 auth.changePassword 已经清掉本地登录态，
    // 界面必须跟着回到登录页，否则会停在一个所有请求都返回未登录的空壳上
    ElMessage.success('密码已修改，请重新登录')
    router.replace({ name: 'login' })
  } catch {
    // 原密码错误等提示已弹出，保留对话框让用户重试
  } finally {
    passwordDialog.saving = false
  }
}

/* ------------------------------ 退出登录 ------------------------------ */

async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新登录才能收发消息，确定退出？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await signOut()
  ElMessage.success('已退出登录')
  router.replace({ name: 'login' })
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  // 刷新页面直接落在 /profile 时 store 可能还没恢复完，这里补一次
  if (!auth.userInfo) {
    await auth.loadCurrentUser()
  }
  fillForm()
})
</script>

<style scoped>
.profile {
  height: 100%;
  overflow-y: auto;
}

.profile__inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 768px) {
  .profile__inner {
    padding: 12px;
    gap: 12px;
  }
}

.profile__card {
  padding: 20px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: var(--im-radius);
}

.profile__card-title {
  margin-bottom: 16px;
  font-size: 15px;
  font-weight: 600;
}

.profile__avatar-row {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile__avatar {
  position: relative;
  width: 88px;
  height: 88px;
  flex: none;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
}

/* 悬停才显出蒙层，常驻会把头像糊掉 */
.profile__avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  opacity: 0;
  transition: opacity 0.15s;
}

.profile__avatar:hover .profile__avatar-mask,
.profile__avatar-mask:has(.is-loading) {
  opacity: 1;
}

.profile__identity {
  min-width: 0;
}

.profile__nickname {
  font-size: 20px;
  font-weight: 600;
}

.profile__account {
  margin-top: 4px;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.profile__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.profile__facts {
  margin-top: 20px;
}

.profile__form {
  max-width: 480px;
}

.profile__readonly {
  color: var(--im-text-secondary);
}

.profile__actions {
  display: flex;
  gap: 12px;
}

.profile__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--im-text-secondary);
}

.profile__file-input {
  display: none;
}
</style>
