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
            手机号不能直接改：UpdateProfileRequest 里没有 phone 字段，
            换绑手机要走短信验证流程，放进这个表单会造成「改了但没生效」的假象。
            绑定 / 换绑都通过右侧按钮弹出的短信验证对话框完成（无需图形验证码）。
          -->
          <el-form-item label="手机号">
            <span class="profile__readonly">{{ maskPhone(auth.phone) || '未绑定' }}</span>
            <el-button class="profile__phone-btn" link type="primary" @click="openPhoneDialog">
              {{ auth.phone ? '换绑' : '绑定' }}
            </el-button>
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
        <div v-if="firstTimeSet" class="profile__hint profile__hint--warn">
          当前账号通过验证码登录自动创建，尚未设置密码，设置后即可用「账号登录」。
        </div>
        <div class="profile__actions">
          <el-button :icon="Lock" @click="openPasswordDialog">{{ firstTimeSet ? '设置密码' : '修改密码' }}</el-button>
          <el-button type="danger" plain :icon="SwitchButton" @click="onLogout">退出登录</el-button>
        </div>
      </section>
    </div>

    <!-- ==================== 修改 / 首次设置密码 ==================== -->
    <el-dialog
      v-model="passwordDialog.visible"
      :title="firstTimeSet ? '设置密码' : '修改密码'"
      width="420px"
      @closed="resetPasswordForm"
    >
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="90px">
        <el-form-item v-if="!firstTimeSet" label="原密码" prop="oldPassword">
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
          <el-checkbox v-model="passwordForm.logoutAll">{{ firstTimeSet ? '设置后退出所有设备' : '修改后退出所有设备（包括当前）' }}</el-checkbox>
          <div class="profile__hint">
            {{ firstTimeSet
              ? '首次设置密码默认保留当前登录态，勾选后才会踢掉所有设备。'
              : '后端默认就是这个行为：不勾选才会保留当前登录态。改了密码还留着旧设备的登录，等于没改。' }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="passwordDialog.saving" @click="submitPassword">{{ firstTimeSet ? '确定设置' : '确定修改' }}</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 绑定 / 换绑手机号 ==================== -->
    <el-dialog
      v-model="phoneDialog.visible"
      :title="auth.phone ? '换绑手机号' : '绑定手机号'"
      width="420px"
      @closed="resetPhoneForm"
    >
      <el-form ref="phoneFormRef" :model="phoneForm" :rules="phoneRules" label-width="90px">
        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="phoneForm.phone" maxlength="11" placeholder="请输入要绑定的手机号" clearable />
        </el-form-item>
        <el-form-item label="验证码" prop="smsCode">
          <div class="profile__sms-row">
            <el-input
              v-model.trim="phoneForm.smsCode"
              maxlength="6"
              placeholder="6 位短信验证码"
              @keyup.enter="submitPhone"
            />
            <el-button
              :disabled="smsCountdown > 0 || smsSending"
              :loading="smsSending"
              @click="sendBindSms"
            >
              {{ smsCountdown > 0 ? `${smsCountdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <div v-if="smsDebugCode" class="profile__hint profile__hint--warn">
        开发环境短信验证码：{{ smsDebugCode }}
      </div>
      <template #footer>
        <el-button @click="phoneDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="phoneDialog.saving" @click="submitPhone">确定绑定</el-button>
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
import { sendSmsCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { signOut } from '@/stores'
import { formatFileSize, formatDateTime, maskPhone } from '@/utils/format'
import { openFilePicker } from '@/utils/picker'

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
  openFilePicker(avatarInputRef.value)
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

/** 验证码登录自动建号、从未设过密码的账号：首次设置无需原密码 */
const firstTimeSet = computed(() => !auth.passwordSet)

const passwordRules = computed(() => ({
  // 首次设置时不渲染也不校验原密码，与后端哨兵密码的放行逻辑一致
  oldPassword: firstTimeSet.value ? [] : [{ required: true, message: '请输入原密码', trigger: 'blur' }],
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
}))

function openPasswordDialog() {
  // 首次设置默认不踢下线，避免刚设完密码就被登出；修改密码仍默认踢下线
  passwordForm.logoutAll = !firstTimeSet.value
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
  // 先快照当前是否为首次设置：loadCurrentUser 后 firstTimeSet 会翻转为 false，影响文案
  const wasFirstTime = firstTimeSet.value
  try {
    const stayed = await auth.changePassword({
      // 首次设置时无原密码，不传该字段（后端哨兵分支会跳过校验）
      oldPassword: wasFirstTime ? undefined : passwordForm.oldPassword,
      newPassword: passwordForm.newPassword,
      logoutAll: passwordForm.logoutAll
    })
    passwordDialog.visible = false
    if (stayed) {
      // 设完密码后 passwordSet 已变，刷新一次资料让按钮文案同步
      await auth.loadCurrentUser()
      ElMessage.success(wasFirstTime ? '密码已设置' : '密码已修改')
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

/* ------------------------------ 绑定手机号 ------------------------------ */

/**
 * 通用倒计时：发送短信验证码后禁用按钮，避免频繁重发。
 * 与 Login.vue 里的实现保持一致（那边是登录发码，这边是绑定发码）。
 */
function useCountdown() {
  const seconds = ref(0)
  let timer = null
  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    seconds.value = 0
  }
  function start(value) {
    stop()
    seconds.value = Math.max(1, Math.ceil(Number(value) || 60))
    timer = setInterval(() => {
      seconds.value -= 1
      if (seconds.value <= 0) {
        stop()
      }
    }, 1000)
  }
  return { seconds, start, stop }
}

const phoneFormRef = ref(null)
const phoneDialog = reactive({ visible: false, saving: false })
const phoneForm = reactive({ phone: '', smsCode: '' })
const smsSending = ref(false)
const smsDebugCode = ref('')
const sms = useCountdown()
const smsCountdown = sms.seconds

const phoneRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ]
}

function openPhoneDialog() {
  phoneForm.phone = ''
  phoneForm.smsCode = ''
  smsDebugCode.value = ''
  phoneDialog.visible = true
}

function resetPhoneForm() {
  phoneForm.phone = ''
  phoneForm.smsCode = ''
  smsDebugCode.value = ''
  sms.stop()
  phoneFormRef.value?.clearValidate()
}

/**
 * 发送绑定验证码。
 *
 * scene=bind 且用户已登录，后端跳过图形验证码闸门，因此这里只校验手机号本身，
 * 不需要像登录页那样先填图形验证码。
 */
async function sendBindSms() {
  try {
    await phoneFormRef.value.validateField('phone')
  } catch {
    return
  }
  smsSending.value = true
  try {
    const vo = await sendSmsCode({ phone: phoneForm.phone, scene: 'bind' })
    smsDebugCode.value = vo?.debugCode || ''
    sms.start(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success('短信验证码已发送')
  } catch {
    // 提示已由 request.js 弹出（如发送过于频繁）
  } finally {
    smsSending.value = false
  }
}

async function submitPhone() {
  const valid = await phoneFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  phoneDialog.saving = true
  try {
    await auth.bindPhone({ phone: phoneForm.phone, smsCode: phoneForm.smsCode })
    phoneDialog.visible = false
    ElMessage.success('手机号已绑定')
  } catch {
    // 验证码错误 / 号码被占用等提示已弹出，保留对话框让用户重试
  } finally {
    phoneDialog.saving = false
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

.profile__phone-btn {
  margin-left: 12px;
}

.profile__sms-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.profile__sms-row :deep(.el-input) {
  flex: 1;
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

.profile__hint--warn {
  margin-bottom: 12px;
  color: #e6a23c;
}

/* 同聊天页：display:none 的 input 在部分手机浏览器上 click() 无效，改为渲染但不可见 */
.profile__file-input {
  position: fixed;
  top: 0;
  left: 0;
  width: 1px;
  height: 1px;
  padding: 0;
  border: 0;
  opacity: 0;
  pointer-events: none;
  z-index: -1;
}
</style>
