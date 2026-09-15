<template>
  <div class="login">
    <div class="login__card">
      <div class="login__brand">
        <div class="login__logo">IM</div>
        <h1 class="login__title">IM 即时通讯</h1>
        <p class="login__subtitle">单聊 · 消息回执 · 多端同步</p>
      </div>

      <el-tabs v-model="tab" class="login__tabs" stretch>
        <!-- ==================== 账号登录（无需验证码） ==================== -->
        <el-tab-pane label="账号登录" name="account">
          <el-form
            ref="accountFormRef"
            :model="accountForm"
            :rules="accountRules"
            label-position="top"
            size="large"
            @submit.prevent="submitAccount"
          >
            <el-form-item label="账号" prop="account">
              <el-input v-model.trim="accountForm.account" placeholder="用户名或手机号" clearable :prefix-icon="User" />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="accountForm.password"
                type="password"
                placeholder="请输入密码"
                show-password
                :prefix-icon="Lock"
                @keyup.enter="submitAccount"
              />
            </el-form-item>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitAccount">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ==================== 短信登录（先图形验证码，再短信验证码） ==================== -->
        <el-tab-pane label="短信登录" name="sms">
          <el-form
            ref="smsFormRef"
            :model="smsForm"
            :rules="smsRules"
            label-position="top"
            size="large"
            @submit.prevent="submitSms"
          >
            <el-form-item label="手机号" prop="phone">
              <el-input v-model.trim="smsForm.phone" placeholder="11 位手机号" maxlength="11" clearable :prefix-icon="Iphone" />
            </el-form-item>

            <!-- 第一步：图形验证码，作为发送短信的闸门 -->
            <el-form-item label="图形验证码" prop="captchaCode">
              <div class="login__captcha-row">
                <el-input
                  v-model.trim="smsForm.captchaCode"
                  placeholder="先完成图形验证"
                  maxlength="8"
                  :prefix-icon="Key"
                />
                <CaptchaImage :src="captcha.image" :loading="captchaLoading" @refresh="loadCaptcha" />
              </div>
            </el-form-item>

            <!-- 第二步：短信验证码 -->
            <el-form-item label="短信验证码" prop="smsCode">
              <div class="login__captcha-row">
                <el-input
                  v-model.trim="smsForm.smsCode"
                  placeholder="6 位数字"
                  maxlength="6"
                  :prefix-icon="Message"
                  @keyup.enter="submitSms"
                />
                <el-button :disabled="smsCountdown > 0" :loading="smsSending" @click="sendSmsCode">
                  {{ smsCountdown > 0 ? `${smsCountdown} 秒后重发` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <p v-if="smsDebugCode" class="login__debug">
              开发环境回显短信验证码：<b>{{ smsDebugCode }}</b>
            </p>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitSms">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ==================== 邮箱登录 ==================== -->
        <el-tab-pane label="邮箱登录" name="email">
          <el-form
            ref="emailFormRef"
            :model="emailForm"
            :rules="emailRules"
            label-position="top"
            size="large"
            @submit.prevent="submitEmail"
          >
            <el-form-item label="邮箱" prop="email">
              <el-input v-model.trim="emailForm.email" placeholder="用于接收验证码的邮箱" clearable :prefix-icon="Message" />
            </el-form-item>

            <!-- 第一步：图形验证码，作为发送邮件的闸门 -->
            <el-form-item label="图形验证码" prop="captchaCode">
              <div class="login__captcha-row">
                <el-input
                  v-model.trim="emailForm.captchaCode"
                  placeholder="先完成图形验证"
                  maxlength="8"
                  :prefix-icon="Key"
                />
                <CaptchaImage :src="captcha.image" :loading="captchaLoading" @refresh="loadCaptcha" />
              </div>
            </el-form-item>

            <!-- 第二步：邮箱验证码 -->
            <el-form-item label="邮箱验证码" prop="emailCode">
              <div class="login__captcha-row">
                <el-input
                  v-model.trim="emailForm.emailCode"
                  placeholder="6 位数字"
                  maxlength="6"
                  :prefix-icon="Key"
                  @keyup.enter="submitEmail"
                />
                <el-button :disabled="emailCountdown > 0" :loading="emailSending" @click="sendEmailCode">
                  {{ emailCountdown > 0 ? `${emailCountdown} 秒后重发` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitEmail">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <!-- 注册入口移到底部 -->
      <p class="login__tip">
        未登录请
        <el-link type="primary" :underline="false" @click="openRegister">注册</el-link>
      </p>
    </div>

    <!-- ==================== 注册弹窗（无需验证码） ==================== -->
    <el-dialog v-model="registerVisible" title="注册账号" width="min(420px, calc(100vw - 32px))" append-to-body>
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-position="top"
        size="large"
        @submit.prevent="submitRegister"
      >
        <el-form-item label="账号" prop="username">
          <el-input v-model.trim="registerForm.username" placeholder="字母开头，4-32 位字母数字下划线" clearable :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model.trim="registerForm.nickname" placeholder="留空则与账号相同" maxlength="32" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="registerForm.password" type="password" placeholder="6-32 位" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="registerForm.confirmPassword" type="password" placeholder="再输入一次" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="registerForm.phone" placeholder="选填，用于短信登录" maxlength="11" clearable :prefix-icon="Iphone" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model.trim="registerForm.email" placeholder="选填，用于邮箱登录" clearable :prefix-icon="Message" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRegister">注册并登录</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Key, Lock, Message, User } from '@element-plus/icons-vue'
import CaptchaImage from '@/components/CaptchaImage.vue'
import {
  fetchCaptchaImage,
  sendSmsCode as sendSmsCodeApi,
  sendEmailCode as sendEmailCodeApi
} from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

defineOptions({ name: 'Login' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const tab = ref('account')
const submitting = ref(false)

/* ------------------------------ 图形验证码 ------------------------------ */

const captcha = reactive({ key: '', image: '' })
const captchaLoading = ref(false)

/**
 * 拉取图形验证码。
 *
 * 失败时不弹提示（request.js 已经弹过），但要把 key 清空：
 * 留着旧 key 的话用户填了旧图上的字，提交时后端返回「验证码已过期」，
 * 而界面上显示的图跟提示完全对不上，排查起来很费劲。
 */
async function loadCaptcha() {
  captchaLoading.value = true
  try {
    const vo = await fetchCaptchaImage()
    captcha.key = vo?.captchaKey || ''
    captcha.image = vo?.image || ''
  } catch {
    captcha.key = ''
    captcha.image = ''
  } finally {
    captchaLoading.value = false
  }
}

/* ------------------------------ 通用倒计时 ------------------------------ */

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

/* ------------------------------ 短信验证码 ------------------------------ */

const smsSending = ref(false)
const smsDebugCode = ref('')
const sms = useCountdown()
const smsCountdown = sms.seconds

/**
 * 发送短信验证码：先校验手机号与图形验证码，图形验证码通过后后端才发短信。
 *
 * 图形验证码是一次性的——无论发送成功还是被拒，这张图都已作废，
 * 因此每次尝试后都要换新图并清空输入，避免用户拿旧答案反复提交。
 */
async function sendSmsCode() {
  try {
    await smsFormRef.value.validateField(['phone', 'captchaCode'])
  } catch {
    return
  }
  smsSending.value = true
  try {
    const vo = await sendSmsCodeApi({
      phone: smsForm.phone,
      scene: 'login',
      captchaKey: captcha.key,
      captchaCode: smsForm.captchaCode
    })
    smsDebugCode.value = vo?.debugCode || ''
    sms.start(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success('短信验证码已发送')
  } finally {
    smsSending.value = false
    loadCaptcha()
    smsForm.captchaCode = ''
  }
}

/* ------------------------------ 邮箱验证码 ------------------------------ */

const emailSending = ref(false)
const email = useCountdown()
const emailCountdown = email.seconds

/**
 * 发送邮箱验证码：先校验邮箱与图形验证码，图形验证码通过后后端才发邮件。
 *
 * 图形验证码是一次性的——无论发送成功还是被拒，这张图都已作废，
 * 因此每次尝试后都要换新图并清空输入，避免用户拿旧答案反复提交。
 */
async function sendEmailCode() {
  try {
    await emailFormRef.value.validateField(['email', 'captchaCode'])
  } catch {
    return
  }
  emailSending.value = true
  try {
    const vo = await sendEmailCodeApi({
      email: emailForm.email,
      captchaKey: captcha.key,
      captchaCode: emailForm.captchaCode
    })
    email.start(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success('邮箱验证码已发送，请注意查收')
  } finally {
    emailSending.value = false
    loadCaptcha()
    emailForm.captchaCode = ''
  }
}

/* -------------------------------- 表单 -------------------------------- */

const accountFormRef = ref(null)
const smsFormRef = ref(null)
const emailFormRef = ref(null)
const registerFormRef = ref(null)

const accountForm = reactive({ account: '', password: '' })
const smsForm = reactive({ phone: '', captchaCode: '', smsCode: '' })
const emailForm = reactive({ email: '', captchaCode: '', emailCode: '' })

const registerVisible = ref(false)
const registerForm = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  phone: '',
  email: ''
})

/** 与后端 LoginRequest / SmsLoginRequest / EmailLoginRequest 上的注解保持一致 */
const accountRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const smsRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ]
}

const emailRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ]
}

const registerRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[A-Za-z][A-Za-z0-9_]{3,31}$/, message: '字母开头，4-32 位字母、数字或下划线', trigger: 'blur' }
  ],
  nickname: [{ max: 32, message: '昵称最多 32 个字符', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

/**
 * 登录成功后的跳转。
 *
 * redirect 只接受站内绝对路径：`//evil.com` 这类协议相对地址同样以 '/' 开头，
 * 但会被浏览器当成外部站点，等于开了一个重定向漏洞。
 */
function redirectAfterLogin() {
  const target = route.query.redirect
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
    router.replace(target)
    return
  }
  router.replace({ name: 'chat' })
}

async function submitAccount() {
  const valid = await accountFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await auth.login({
      loginType: 'username',
      account: accountForm.account,
      password: accountForm.password
    })
    ElMessage.success('登录成功')
    redirectAfterLogin()
  } finally {
    submitting.value = false
  }
}

async function submitSms() {
  // 登录只校验手机号与短信验证码：图形验证码仅是「发送验证码」的闸门，
  // 发送成功后已被后端消费并清空，登录阶段不能再拿它卡住用户
  try {
    await smsFormRef.value.validateField(['phone', 'smsCode'])
  } catch {
    return
  }
  submitting.value = true
  try {
    await auth.loginBySms({ phone: smsForm.phone, smsCode: smsForm.smsCode })
    ElMessage.success('登录成功')
    redirectAfterLogin()
  } finally {
    submitting.value = false
  }
}

async function submitEmail() {
  // 同短信登录：登录只校验邮箱与邮箱验证码，不再校验已被消费的图形验证码
  try {
    await emailFormRef.value.validateField(['email', 'emailCode'])
  } catch {
    return
  }
  submitting.value = true
  try {
    await auth.loginByEmail({ email: emailForm.email, emailCode: emailForm.emailCode })
    ElMessage.success('登录成功')
    redirectAfterLogin()
  } finally {
    submitting.value = false
  }
}

function openRegister() {
  registerVisible.value = true
}

async function submitRegister() {
  const valid = await registerFormRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await auth.register({
      username: registerForm.username,
      password: registerForm.password,
      // 昵称留空时后端会拿账号顶上，前端不必代劳
      nickname: registerForm.nickname || undefined,
      phone: registerForm.phone || undefined,
      email: registerForm.email || undefined
    })
    registerVisible.value = false
    ElMessage.success('注册成功，已自动登录')
    // 注册成功后端直接返回登录态，跳转到聊天页面
    redirectAfterLogin()
  } finally {
    submitting.value = false
  }
}

/* ------------------------------- 生命周期 ------------------------------- */

onMounted(loadCaptcha)

onBeforeUnmount(() => {
  sms.stop()
  email.stop()
})

/**
 * 切到短信 Tab 时，若图形验证码还没加载出来就补一次。
 * 不在每次切换都刷新：验证码有 5 分钟有效期，频繁刷新只会白白增加后端画图开销。
 */
watch(tab, (value) => {
  if ((value === 'sms' || value === 'email') && !captcha.image) {
    loadCaptcha()
  }
})
</script>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: linear-gradient(135deg, #3a7bd5 0%, #2e3238 100%);
  overflow: auto;
}

.login__card {
  /* min() 而不是固定 400px：视口比 400 窄时固定宽度会横向溢出，
     而登录页是 overflow:auto，溢出表现为要左右拖才能看到输入框 */
  width: min(400px, calc(100vw - 32px));
  padding: 32px 36px 24px;
  background: var(--im-panel);
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.24);
}

.login__brand {
  text-align: center;
  margin-bottom: 8px;
}

.login__logo {
  width: 52px;
  height: 52px;
  margin: 0 auto 10px;
  border-radius: 14px;
  background: var(--im-primary);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  line-height: 52px;
  letter-spacing: 1px;
}

.login__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.login__subtitle {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.login__captcha-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.login__captcha-row :deep(.el-input) {
  flex: 1;
}

.login__submit {
  width: 100%;
  margin-top: 4px;
}

.login__debug {
  margin: 10px 0 0;
  font-size: 12px;
  color: #e6a23c;
}

.login__tip {
  margin: 16px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--im-border);
  font-size: 13px;
  color: var(--im-text-secondary);
  text-align: center;
}
</style>
