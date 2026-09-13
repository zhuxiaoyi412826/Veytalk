<template>
  <div class="login">
    <div class="login__card">
      <div class="login__brand">
        <div class="login__logo">IM</div>
        <h1 class="login__title">IM 即时通讯</h1>
        <p class="login__subtitle">单聊 · 消息回执 · 多端同步</p>
      </div>

      <el-tabs v-model="tab" class="login__tabs" stretch>
        <!-- ==================== 账号登录 ==================== -->
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
            <el-form-item label="验证码" prop="captchaCode">
              <div class="login__captcha-row">
                <el-input
                  v-model.trim="accountForm.captchaCode"
                  placeholder="图形验证码"
                  maxlength="8"
                  :prefix-icon="Key"
                  @keyup.enter="submitAccount"
                />
                <CaptchaImage :src="captcha.image" :loading="captchaLoading" @refresh="loadCaptcha" />
              </div>
            </el-form-item>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitAccount">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ==================== 短信登录 ==================== -->
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
              开发环境回显验证码：<b>{{ smsDebugCode }}</b>
            </p>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitSms">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ==================== 注册 ==================== -->
        <el-tab-pane label="注册" name="register">
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
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="再输入一次"
                show-password
                :prefix-icon="Lock"
              />
            </el-form-item>
            <el-form-item label="手机号" prop="phone">
              <el-input v-model.trim="registerForm.phone" placeholder="选填，用于短信登录" maxlength="11" clearable :prefix-icon="Iphone" />
            </el-form-item>
            <el-form-item label="验证码" prop="captchaCode">
              <div class="login__captcha-row">
                <el-input
                  v-model.trim="registerForm.captchaCode"
                  placeholder="图形验证码"
                  maxlength="8"
                  :prefix-icon="Key"
                  @keyup.enter="submitRegister"
                />
                <CaptchaImage :src="captcha.image" :loading="captchaLoading" @refresh="loadCaptcha" />
              </div>
            </el-form-item>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitRegister">
              注册并登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <!--
        debugCode 只在 dev profile 下由后端回显（captcha.expose-image-code），
        生产环境该字段为空，这里自然就不渲染，不需要前端再判一次环境。
      -->
      <p v-if="captcha.debugCode" class="login__debug">
        开发环境回显图形验证码：<b>{{ captcha.debugCode }}</b>
      </p>

      <p class="login__tip">
        演示账号：alice / bob / admin，密码均为 123456
      </p>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Key, Lock, Message, User } from '@element-plus/icons-vue'
import CaptchaImage from '@/components/CaptchaImage.vue'
import { fetchCaptchaImage, sendSmsCode as sendSmsCodeApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

defineOptions({ name: 'Login' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const tab = ref('account')
const submitting = ref(false)

/* ------------------------------ 图形验证码 ------------------------------ */

const captcha = reactive({ key: '', image: '', debugCode: '' })
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
    captcha.debugCode = vo?.debugCode || ''
  } catch {
    captcha.key = ''
    captcha.image = ''
    captcha.debugCode = ''
  } finally {
    captchaLoading.value = false
  }
}

/* ------------------------------ 短信验证码 ------------------------------ */

const smsSending = ref(false)
const smsCountdown = ref(0)
const smsDebugCode = ref('')
let smsTimer = null

function startCountdown(seconds) {
  stopCountdown()
  smsCountdown.value = Math.max(1, Math.ceil(Number(seconds) || 60))
  smsTimer = setInterval(() => {
    smsCountdown.value -= 1
    if (smsCountdown.value <= 0) {
      stopCountdown()
    }
  }, 1000)
}

function stopCountdown() {
  if (smsTimer) {
    clearInterval(smsTimer)
    smsTimer = null
  }
  smsCountdown.value = 0
}

/**
 * 发送短信验证码。
 *
 * 先单独校验手机号字段再发请求：整表 validate 会因为「短信验证码还没填」而失败，
 * 用户点「获取验证码」时看到红色错误落在另一个输入框上，很莫名。
 */
async function sendSmsCode() {
  try {
    await smsFormRef.value.validateField('phone')
  } catch {
    return
  }
  smsSending.value = true
  try {
    const vo = await sendSmsCodeApi({ phone: smsForm.phone, scene: 'login' })
    smsDebugCode.value = vo?.debugCode || ''
    // retryAfter 是后端算好的「还需等待多久」，比前端写死 60 秒更准，
    // 尤其是刚刚已经发过一次、被限流挡住的情况
    startCountdown(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success('验证码已发送')
  } finally {
    smsSending.value = false
  }
}

/* -------------------------------- 表单 -------------------------------- */

const accountFormRef = ref(null)
const smsFormRef = ref(null)
const registerFormRef = ref(null)

const accountForm = reactive({ account: '', password: '', captchaCode: '' })
const smsForm = reactive({ phone: '', smsCode: '' })
const registerForm = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  phone: '',
  captchaCode: ''
})

/** 与后端 RegisterRequest / SmsLoginRequest 上的注解保持一致，避免前端放行后端拒绝 */
const accountRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }]
}

const smsRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  smsCode: [
    { required: true, message: '请输入短信验证码', trigger: 'blur' },
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
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }]
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
      password: accountForm.password,
      captchaKey: captcha.key,
      captchaCode: accountForm.captchaCode
    })
    ElMessage.success('登录成功')
    redirectAfterLogin()
  } catch {
    // 验证码是一次性的：无论后端因为密码错还是验证码错而拒绝，这张图都已经作废，
    // 不换新的话用户会拿同一个答案反复提交并反复失败
    loadCaptcha()
    accountForm.captchaCode = ''
  } finally {
    submitting.value = false
  }
}

async function submitSms() {
  const valid = await smsFormRef.value.validate().catch(() => false)
  if (!valid) {
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
      captchaKey: captcha.key,
      captchaCode: registerForm.captchaCode
    })
    ElMessage.success('注册成功，已自动登录')
    redirectAfterLogin()
  } catch {
    loadCaptcha()
    registerForm.captchaCode = ''
  } finally {
    submitting.value = false
  }
}

/* ------------------------------- 生命周期 ------------------------------- */

onMounted(loadCaptcha)

onBeforeUnmount(stopCountdown)

/**
 * 切到需要图形验证码的 Tab 时，若图还没加载出来就补一次。
 * 不在每次切换都刷新：验证码有 5 分钟有效期，频繁刷新只会白白增加后端画图开销。
 */
watch(tab, (value) => {
  if ((value === 'account' || value === 'register') && !captcha.image) {
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
  font-size: 12px;
  color: var(--im-text-secondary);
  text-align: center;
}
</style>
