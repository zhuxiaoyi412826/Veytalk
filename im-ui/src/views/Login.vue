<template>
  <div class="login">
    <div class="login__card">
      <div class="login__brand">
        <div class="login__logo">IM</div>
        <h1 class="login__title">IM 即时通讯</h1>
        <p class="login__subtitle">单聊 · 消息回执 · 多端同步</p>
      </div>

      <!-- 原生壳（exe/APK）专属：后端地址运行时覆盖，热点换 IP 时改这里即可，无需重打包 -->
      <div v-if="showServerConfig" style="display:flex; gap:8px; margin-bottom:10px">
        <el-input
          v-model="serverUrl"
          size="small"
          placeholder="服务器地址，如 http://192.168.17.18:8080（留空=打包默认）"
          clearable
        />
        <el-button size="small" @click="saveServerUrl">保存</el-button>
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
            <p v-if="emailDebugCode" class="login__debug">
              开发环境回显邮箱验证码：<b>{{ emailDebugCode }}</b>
            </p>
            <el-button type="primary" class="login__submit" :loading="submitting" @click="submitEmail">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <!-- 注册入口与找回密码都放在底部 -->
      <p class="login__tip">
        未登录请
        <el-link type="primary" underline="never" @click="openRegister">注册</el-link>
        <span class="login__tip-sep">|</span>
        <el-link type="primary" underline="never" @click="openReset">忘记密码</el-link>
        <span class="login__tip-sep">|</span>
        <!-- 用 APP 扫码进入「文件传输助手 · 网页版」，电脑与手机互传文件 -->
        <el-link type="primary" underline="never" @click="goFileHelper">文件传输助手</el-link>
        <!-- 桌面端专属：后端地址不对的时候，这里是唯一的自救入口，所以必须挂在登录页
             而不是设置页（后端不通就登不进去，永远进不到设置页）。Web 部署同源，不显。 -->
        <template v-if="canServerDebug">
          <span class="login__tip-sep">|</span>
          <el-link type="info" underline="never" @click="serverDebugVisible = true">开发调试</el-link>
        </template>
      </p>
    </div>

    <!-- ==================== 注册弹窗（无需验证码） ==================== -->
    <el-dialog
      v-model="registerVisible"
      class="im-dialog--fit"
      title="注册账号"
      width="min(420px, calc(100vw - 32px))"
      append-to-body
    >
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-position="top"
        size="large"
        scroll-to-error
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
        <!--
          协议勾选。文本名与版本号都从 /api/agreement/list 来，前端不写死三份：
          后台哪天多发布一份必签文本，这里的句子与提交的版本号会自动跟上。
          整行按「有没有已发布的文本」决定显不显示 —— 迁移脚本没跑时后端也会放行注册（见
          AgreementServiceImpl.validateForRegister），此时摆一个点开只能看到空页的必选框比不摆更糟。
        -->
        <el-form-item v-if="requiredAgreements.length" prop="agree">
          <el-checkbox v-model="registerForm.agree">
            我已阅读并同意
            <router-link
              v-for="item in requiredAgreements"
              :key="item.code"
              class="login__legal-link"
              :to="{ name: 'legal', params: { code: item.code }, query: { version: item.version } }"
              target="_blank"
            >《{{ item.codeName }} v{{ item.version }}》</router-link>
          </el-checkbox>
        </el-form-item>
        <!--
          可选条款单独一个框，而且只在勾了的时候才提交：
          没勾不等于「他拒绝过」，把空框翻译成 accept=false 是在伪造一份他没做过的表态。
          没交的那一份会在首次登录的再同意弹窗里再问一次，那时才拿得到真表态。
        -->
        <el-form-item v-if="optionalAgreements.length" prop="agreeOptional">
          <el-checkbox v-model="registerForm.agreeOptional">
            （可选）同意
            <router-link
              v-for="item in optionalAgreements"
              :key="item.code"
              class="login__legal-link"
              :to="{ name: 'legal', params: { code: item.code }, query: { version: item.version } }"
              target="_blank"
            >《{{ item.codeName }} v{{ item.version }}》</router-link>
            <span class="login__legal-tip">，不勾选不影响注册</span>
          </el-checkbox>
        </el-form-item>
        <!--
          清单没拉下来时把「为什么」和「下一步做什么」摆在脸上。以前这里只会安静地什么都不显示：
          dev 代理连不上后端（后端正在重启）与后台一份文本都没发布，在页面上是同一种样子，
          而前者会让注册请求注定失败却看不出缘由。这一行只在「列表为空 且 拉过失败」时出现。
        -->
        <el-form-item v-if="agreementError">
          <span class="login__legal-error">
            条款未能加载：{{ agreementError }}
            <el-link type="primary" underline="never" :disabled="agreementLoading" @click="retryAgreements">
              {{ agreementLoading ? '重试中…' : '重试' }}
            </el-link>
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRegister">注册并登录</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 找回密码弹窗 ====================
         两条通道共用一个弹窗：发码接口不同，但校验链路完全一样
         （图形验证码 → 6 位码 → 新密码）。图形验证码与登录 Tab 共用一份，
         因为它本来就是一次性的，分开存反而会出现“两张图都只能用一个答案”。 -->
    <el-dialog
      v-model="resetVisible"
      class="im-dialog--fit"
      title="找回密码"
      width="min(420px, calc(100vw - 32px))"
      append-to-body
    >
      <el-form
        ref="resetFormRef"
        :model="resetForm"
        :rules="resetRules"
        label-position="top"
        size="large"
        scroll-to-error
        @submit.prevent="submitReset"
      >
        <el-form-item label="找回方式">
          <el-radio-group v-model="resetForm.resetType" @change="onResetTypeChange">
            <el-radio-button value="phone">手机验证码</el-radio-button>
            <el-radio-button value="email">邮箱验证码</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="resetForm.resetType === 'phone'" label="手机号" prop="phone">
          <el-input
            v-model.trim="resetForm.phone"
            placeholder="注册时绑定的手机号"
            maxlength="11"
            clearable
            :prefix-icon="Iphone"
          />
        </el-form-item>
        <el-form-item v-else label="邮箱" prop="email">
          <el-input
            v-model.trim="resetForm.email"
            placeholder="注册时绑定的邮箱"
            clearable
            :prefix-icon="Message"
          />
        </el-form-item>

        <el-form-item label="图形验证码" prop="captchaCode">
          <div class="login__captcha-row">
            <el-input v-model.trim="resetForm.captchaCode" placeholder="先完成图形验证" maxlength="8" :prefix-icon="Key" />
            <CaptchaImage :src="captcha.image" :loading="captchaLoading" @refresh="loadCaptcha" />
          </div>
        </el-form-item>

        <el-form-item label="验证码" prop="code">
          <div class="login__captcha-row">
            <el-input
              v-model.trim="resetForm.code"
              placeholder="6 位数字"
              maxlength="6"
              :prefix-icon="Key"
              @keyup.enter="submitReset"
            />
            <el-button :disabled="resetCountdown > 0" :loading="resetSending" @click="sendResetCode">
              {{ resetCountdown > 0 ? `${resetCountdown} 秒后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <p v-if="resetDebugCode" class="login__debug">
          开发环境回显验证码：<b>{{ resetDebugCode }}</b>
        </p>

        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="resetForm.newPassword" type="password" placeholder="6-32 位" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="resetForm.confirmPassword"
            type="password"
            placeholder="再输入一次"
            show-password
            :prefix-icon="Lock"
            @keyup.enter="submitReset"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">重置密码</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 后端地址（开发调试，原生壳） ====================
         Electron 读写走主进程 IPC、Tauri / 安卓壳走 localStorage 覆盖，都不依赖后端链路可达——
         这正是要修「请求发不出去」的那个场景，弹窗自己不能依赖那条路。 -->
    <ServerDebugDialog v-model="serverDebugVisible" />
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Key, Lock, Message, User } from '@element-plus/icons-vue'
import CaptchaImage from '@/components/CaptchaImage.vue'
import ServerDebugDialog from '@/components/ServerDebugDialog.vue'
import { canDebug } from '@/utils/servercfg'
import { getServerBridge } from '@/utils/serverBridge'
import {
  fetchCaptchaImage,
  sendSmsCode as sendSmsCodeApi,
  sendEmailCode as sendEmailCodeApi,
  sendResetSmsCode as sendResetSmsCodeApi,
  sendResetEmailCode as sendResetEmailCodeApi,
  resetPassword as resetPasswordApi
} from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useAgreementStore } from '@/stores/agreement'
import { isNativeShell } from '@/utils/env'

defineOptions({ name: 'Login' })

/**
 * 后端 ResultCode：2023 协议版本不符 / 2024 必签项缺一份。
 * 两个码对应两种完全不同的引导，见 handleRegisterConsentError。
 */
const CODE_AGREEMENT_VERSION_MISMATCH = 2023
const CODE_AGREEMENT_REQUIRED = 2024

// 原生壳（Tauri exe / Capacitor APK）下显示「服务器地址」输入框：
// 存 localStorage.IM_SERVER_URL_OVERRIDE，env.js 的 serverBase() 每次请求实时读取，保存后立即生效
const showServerConfig = isNativeShell()
const serverUrl = ref(
  (typeof localStorage !== 'undefined' && localStorage.getItem('IM_SERVER_URL_OVERRIDE')) || ''
)
function saveServerUrl() {
  const v = serverUrl.value.trim().replace(/\/+$/, '')
  if (v) {
    localStorage.setItem('IM_SERVER_URL_OVERRIDE', v)
  } else {
    localStorage.removeItem('IM_SERVER_URL_OVERRIDE')
  }
  ElMessage.success(v ? `服务器地址已切换为 ${v}，立即生效` : '已恢复打包默认地址')
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const agreement = useAgreementStore()

const tab = ref('account')
const submitting = ref(false)

/* ------------------------------ 后端地址（原生壳） ------------------------------ */

const serverDebugVisible = ref(false)
// 判定交给纯函数：拿不到调试桥（Web 同源部署）就整条不显，
// 给一个点了只能报「不支持」的入口比没有入口更糟；桥在 Electron 走 IPC、Tauri / 安卓走 localStorage
const canServerDebug = computed(() => canDebug(getServerBridge()))

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
const emailDebugCode = ref('')
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
    // 开发环境（im.captcha.expose-email-code=true）才带 debugCode；
    // 没配 SMTP 的开发机靠 mock-mail-when-unconfigured 降级，同样从这儿拿码。
    emailDebugCode.value = vo?.debugCode || ''
    email.start(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success(emailDebugCode.value ? '邮箱验证码已发送' : '邮箱验证码已发送，请注意查收')
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
  email: '',
  agree: false,
  agreeOptional: false
})

/**
 * 必签与可选各一堆，划分依据是当前生效版上的 required 字段。
 *
 * required 是后台发布时定的法务口径，不是前端能猜的：将来加一份「未成年人保护规则」只需
 * 后台发一版，这里三处（勾选行、提交项、错误提示）会自动跟上。
 */
const requiredAgreements = computed(() => agreement.list.filter((item) => item.required !== false))
const optionalAgreements = computed(() => agreement.list.filter((item) => item.required === false))
const requiredNames = computed(() => requiredAgreements.value.map((item) => `《${item.codeName}》`).join('、'))

/**
 * 「清单为空 且 上一次拉取失败」才有的错误文案（成功一次就自动消失）。
 *
 * 为什么要附带「清单为空」这个条件：拉取失败时 store 故意留着旧 list（见 stores/agreement.js），
 * 那种情况下勾选行照常渲染、提交带的是上一个成功版本，不必再多喊一句失败；
 * 只有从来没成功过（典型就是后端没起、dev 代理回了 502）才真的需要解释为什么没有协议可勾。
 */
const agreementError = computed(() => (!agreement.list.length ? agreement.listError : ''))

/** 拉清单的进行态：只用来给「重试」那个链接一个 loading 文案，避免连点发一串请求 */
const agreementLoading = ref(false)

async function loadAgreements() {
  agreementLoading.value = true
  try {
    await agreement.refreshList(true)
  } finally {
    agreementLoading.value = false
  }
}

/**
 * 注册随表单一起交出去的表态项：必签那几份必然在内（不勾就上不去校验），
 * 可选的那一份只有他主动勾了才提交。
 *
 * 这里不会出现 accept=false：注册页的空框是「没问过」而不是「问过且拒了」，
 * 把前者写成后者就是往证据表里塞一条伪造的拒签记录。
 */
const consentItems = computed(() => {
  const items = requiredAgreements.value.map((item) => ({ code: item.code, version: item.version, accept: true }))
  if (registerForm.agreeOptional) {
    optionalAgreements.value.forEach((item) => items.push({ code: item.code, version: item.version, accept: true }))
  }
  return items
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
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  agree: [
    {
      validator: (rule, value, callback) => {
        if (!registerForm.agree) {
          callback(new Error(`请先阅读并同意${requiredNames.value}`))
          return
        }
        callback()
      },
      trigger: 'change'
    }
  ]
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
  registerForm.agree = false
  registerForm.agreeOptional = false
  registerVisible.value = true
  // 每次打开都重拉一次清单：可能有人正好在填表时后台发了新版，
  // 拿旧版本号提交会被服务端挡回 2023，不如开口前先对一次真值
  // 不 await：弹窗得立即开，拉失败会由 agreementError 那一行自己说清楚
  loadAgreements()
}

function goFileHelper() {
  // 盖会话级标记：本标签页内已登录则直进传文件界面；关标签即失效，需重新扫码
  sessionStorage.setItem('im-fh-session', '1')
  router.push({ name: 'file-helper' })
}

/**
 * 前端校验没过时给一句 toast。
 *
 * 光靠表单内联红字不够：注册弹窗有 8 个表单项，实测在 490px 高的视口里首屏只露到
 * 「确认密码」，协议那一行连它的错误文字都在屏幕外——用户点完按钮看到的就是
 * 「什么都没发生」（这就是这个 bug 被报上来的原话）。所以内联错误之外再补一条 toast，
 * 文案取第一条失败信息；配合 el-form 的 scroll-to-error（把出错那一项滚进视野）
 * 与 .im-dialog--fit（让弹窗本身不超出屏幕），三处一起才把这条路铺平。
 * 没勾协议也走这里：它是这一整套合规链路上唯一会静默拦住注册的一步，必须喊得响。
 */
function hintInvalid(fields) {
  const first = fields && typeof fields === 'object' ? Object.values(fields)[0] : null
  const message = Array.isArray(first) && first[0] ? first[0].message : '请先按提示完善表单'
  ElMessage.warning(message)
}

async function submitRegister() {
  // validate() 失败时 reject 的是「字段 -> 错误」映射，接住它才能拿到第一条文案
  const fields = await registerFormRef.value.validate().then(() => null).catch((f) => f)
  if (fields) {
    hintInvalid(fields)
    return
  }
  // 清单从没拉到过 = 后端不可用（或迁移脚本没跑），此时不发注定失败的注册请求：
  // 再碰一次墙只会让用户读到第二遍同样的报错，不如把「重试」这个动作直接指给他，
  // 同时顺手重拉一次——后端刚重启完的情形在这里就能自行恢复
  if (agreementError.value) {
    ElMessage.error(`条款未能加载（${agreementError.value}），请确认后端已启动`)
    loadAgreements()
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
      email: registerForm.email || undefined,
      // version 原样回传清单里的那一版：后端据此判定「他同意的是哪一份文本」，
      // 不传或传旧版都会被拒，这一位是整条同意链能对上的唯一凭据。
      // 必须写 .value：consentItems 是 computed，直接把 ref 本体交出去的话，axios 在
      // JSON.stringify 阶段就抛「Converting circular structure to JSON」（ComputedRefImpl.dep
      // → Dep.computed 成环），请求压根没发出去，却被 request.js 报成「无法连接服务器」——
      // 2026-11-08 那次「点注册报后端没起、网络面板却一个请求都没有」就是漏了这六个字符
      agreements: consentItems.value
    })
    registerVisible.value = false
    ElMessage.success('注册成功，已自动登录')
    // 注册成功后端直接返回登录态，跳转到聊天页面
    redirectAfterLogin()
  } catch (e) {
    handleRegisterConsentError(e)
  } finally {
    submitting.value = false
  }
}

/**
 * 注册被协议环节挡下来时的两种引导（后端把两个码分开给，就是为了让这两句话能分开写）：
 *  2023 版本不符——清单过期了，重拉一次让勾选框指向新版，他再勾一次就是同意新版；
 *  2024 必签缺项——通常是清单没拉下来（勾选行因此被隐藏了），补拉一次把行摆出来。
 * request.js 已经把后端文案弹过了，这里只补一句「下一步做什么」。
 */
async function handleRegisterConsentError(e) {
  if (!e || (e.code !== CODE_AGREEMENT_VERSION_MISMATCH && e.code !== CODE_AGREEMENT_REQUIRED)) {
    return
  }
  registerForm.agree = false
  registerForm.agreeOptional = false
  await loadAgreements()
  ElMessage.warning(
    e.code === CODE_AGREEMENT_VERSION_MISMATCH ? '条款已有更新，请重新勾选确认' : '请先阅读并勾选必签条款'
  )
}

/* ------------------------------ 找回密码 ------------------------------ */

const resetVisible = ref(false)
const resetSending = ref(false)
const resetting = ref(false)
const resetDebugCode = ref('')
const resetFormRef = ref(null)
const resetCd = useCountdown()
const resetCountdown = resetCd.seconds

const resetForm = reactive({
  resetType: 'phone',
  phone: '',
  email: '',
  captchaCode: '',
  code: '',
  newPassword: '',
  confirmPassword: ''
})

/**
 * 手机号与邮箱二选一必填，且只对当前选中的通道生效。
 *
 * 用 validator 而不是 required：隐藏起来的那一项不应该参与校验，
 * 否则切到邮箱通道后，空的手机号会一直报错挡住提交。
 * 同样的约束后端放在服务层做（Bean Validation 表达不了“二选一必填”）。
 */
const resetRules = {
  phone: [
    {
      validator: (rule, value, callback) => {
        if (resetForm.resetType !== 'phone') {
          callback()
          return
        }
        if (!value) {
          callback(new Error('请输入手机号'))
          return
        }
        if (!/^1[3-9]\d{9}$/.test(value)) {
          callback(new Error('手机号格式不正确'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  email: [
    {
      validator: (rule, value, callback) => {
        if (resetForm.resetType !== 'email') {
          callback()
          return
        }
        if (!value) {
          callback(new Error('请输入邮箱'))
          return
        }
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
          callback(new Error('邮箱格式不正确'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== resetForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

function onResetTypeChange() {
  // 切通道时清掉已填的码：两个通道的验证码在后端是分场景分目标存的，
  // 手机通道的码填进邮箱通道一律校验不过，留在框里只会误导用户。
  resetForm.captchaCode = ''
  resetForm.code = ''
  resetDebugCode.value = ''
  resetFormRef.value?.clearValidate()
}

function openReset() {
  // 把当前 Tab 里已经填好的手机号 / 邮箱带过去，省一次重复输入
  if (tab.value === 'sms' && smsForm.phone) {
    resetForm.resetType = 'phone'
    resetForm.phone = smsForm.phone
  } else if (tab.value === 'email' && emailForm.email) {
    resetForm.resetType = 'email'
    resetForm.email = emailForm.email
  }
  resetVisible.value = true
  if (!captcha.image) {
    loadCaptcha()
  }
}

/**
 * 发找回验证码。
 *
 * 不传 scene：场景由后端 AuthServiceImpl 钉死为 reset。
 * 客户端能自己选场景的话，登录场景的码就能拿去改密码。
 */
async function sendResetCode() {
  const byEmail = resetForm.resetType === 'email'
  try {
    await resetFormRef.value.validateField(byEmail ? ['email', 'captchaCode'] : ['phone', 'captchaCode'])
  } catch {
    return
  }
  resetSending.value = true
  try {
    const payload = byEmail
      ? { email: resetForm.email, captchaKey: captcha.key, captchaCode: resetForm.captchaCode }
      : { phone: resetForm.phone, captchaKey: captcha.key, captchaCode: resetForm.captchaCode }
    const vo = await (byEmail ? sendResetEmailCodeApi(payload) : sendResetSmsCodeApi(payload))
    resetDebugCode.value = vo?.debugCode || ''
    resetCd.start(vo?.retryAfter || vo?.expiresIn || 60)
    ElMessage.success(resetDebugCode.value ? '验证码已发送' : '验证码已发送，请注意查收')
  } finally {
    resetSending.value = false
    // 图形验证码是一次性的，无论成功还是被拒都要换新图
    loadCaptcha()
    resetForm.captchaCode = ''
  }
}

async function submitReset() {
  const byEmail = resetForm.resetType === 'email'
  const fields = byEmail
    ? ['email', 'code', 'newPassword', 'confirmPassword']
    : ['phone', 'code', 'newPassword', 'confirmPassword']
  const bad = await resetFormRef.value.validateField(fields).then(() => null).catch((f) => f)
  if (bad) {
    hintInvalid(bad)
    return
  }
  resetting.value = true
  try {
    const payload = {
      resetType: resetForm.resetType,
      code: resetForm.code,
      newPassword: resetForm.newPassword
    }
    // 只传当前通道的目标：另一项传空字符串反而会撞上 DTO 上的格式校验
    if (byEmail) {
      payload.email = resetForm.email
    } else {
      payload.phone = resetForm.phone
    }
    await resetPasswordApi(payload)
    resetVisible.value = false
    ElMessage.success('密码已重置，该账号在所有设备上的登录态已失效，请用新密码重新登录')
    // 手机本身就是账号登录的入参，直接填回去；邮箱登录不用密码，只切 Tab
    tab.value = 'account'
    if (!byEmail) {
      accountForm.account = resetForm.phone
    }
    accountForm.password = ''
    resetForm.code = ''
    resetForm.newPassword = ''
    resetForm.confirmPassword = ''
    resetDebugCode.value = ''
    resetCd.stop()
  } finally {
    resetting.value = false
  }
}

/* ------------------------------- 生命周期 ------------------------------- */

onMounted(loadCaptcha)

onBeforeUnmount(() => {
  sms.stop()
  email.stop()
  resetCd.stop()
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

.login__tip-sep {
  margin: 0 8px;
  color: var(--im-border);
}

/* 注册弹窗里的协议链接：跟在勾选框文案后面，不能把行高撑开 */
.login__legal-link {
  color: var(--im-primary, #409eff);
  text-decoration: none;
}

.login__legal-link:hover {
  text-decoration: underline;
}

.login__legal-tip {
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* 清单加载失败那一行：得比旁边的辅助文案略略抢一眼，但不能当成错误红吓 */
.login__legal-error {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-warning, #e6a23c);
}
</style>
