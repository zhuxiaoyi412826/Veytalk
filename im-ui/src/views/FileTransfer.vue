<template>
  <div class="ft">
    <div class="ft__card">
      <el-icon class="ft__logo" :size="40"><Promotion /></el-icon>
      <div class="ft__title">网页端文件传输助手</div>

      <!-- 已登录，直接进入助手会话 -->
      <template v-if="phase === 'entering'">
        <div v-loading="true" class="ft__loading">正在打开文件传输助手…</div>
      </template>

      <!-- 出码 + 轮询 -->
      <template v-else-if="phase === 'qrcode' || phase === 'scanned'">
        <div class="ft__qr">
          <img v-if="qrcode" :src="qrcode" alt="登录二维码" class="ft__qr-img" />
          <div v-if="phase === 'scanned'" class="ft__qr-mask">
            <el-icon class="is-loading" :size="26"><Loading /></el-icon>
            <span>已扫码，请在手机上确认</span>
          </div>
        </div>
        <div class="ft__desc">用手机 APP 扫一扫登录，登录后即可在此收发文件。<br />二维码剩余 {{ remain }} 秒。</div>
      </template>

      <!-- 过期 / 出错 -->
      <template v-else-if="phase === 'expired' || phase === 'error'">
        <div class="ft__desc ft__desc--error">{{ errorMsg || '二维码已失效' }}</div>
        <el-button class="ft__btn" @click="startQr">刷新二维码</el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Loading, Promotion } from '@element-plus/icons-vue'
import { createQrLogin, pollQrLoginStatus } from '@/api/qrLogin'
import { openAssistantSession } from '@/api/assistant'
import { getToken, setToken } from '@/utils/token'
import { asId } from '@/utils/id'

/**
 * 网页端扫码登录 + 文件传输助手落地页（公开整屏，路由 /transfer）。
 *
 * 主流程是「本机出码 + 轮询」：进页调 create 拿到二维码，每 2 秒轮询 status；
 * APP 扫到二维码里的 {origin}/transfer?scene=xxx 后由 APP 侧（他人实现）调 scanned / authorize，
 * 本页面只管轮询——一旦拿到 status=2 的一次性登录包，setToken 后即跳进助手会话收发文件。
 * 已带 scene 的 URL（APP 回跳）只是兼容，本机仍以自己的 create 为准。
 */

const route = useRoute()
const router = useRouter()

const phase = ref('entering') // entering | qrcode | scanned | expired | error
const qrcode = ref('')
const scene = ref('')
const remain = ref(0)
const errorMsg = ref('')

let pollTimer = null
let deadline = 0

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function enterAssistant() {
  phase.value = 'entering'
  try {
    const conversationId = await openAssistantSession()
    router.replace({ name: 'chat', params: { conversationId: asId(conversationId) } })
  } catch (e) {
    // 定位会话失败：此时已登录，回主界面让用户自行进入助手
    router.replace({ name: 'chat' })
  }
}

async function startQr() {
  stopPolling()
  errorMsg.value = ''
  qrcode.value = ''
  try {
    const res = await createQrLogin(window.location.origin)
    scene.value = res.scene
    qrcode.value = res.qrcode
    deadline = Date.now() + (res.expiresIn || 120) * 1000
    remain.value = res.expiresIn || 120
    phase.value = 'qrcode'
    pollTimer = setInterval(pollOnce, 2000)
  } catch (e) {
    errorMsg.value = (e && e.message) || '二维码获取失败，请稍后重试'
    phase.value = 'error'
  }
}

async function pollOnce() {
  if (!scene.value) {
    return
  }
  const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
  remain.value = left
  if (left <= 0) {
    stopPolling()
    errorMsg.value = '二维码已过期'
    phase.value = 'expired'
    return
  }
  try {
    const res = await pollQrLoginStatus(scene.value)
    if (res.status === 1) {
      phase.value = 'scanned'
    } else if (res.status === 2) {
      stopPolling()
      setToken(res.token, res.tokenName)
      await enterAssistant()
    } else if (res.status === 3) {
      stopPolling()
      errorMsg.value = '二维码已失效'
      phase.value = 'expired'
    }
  } catch (e) {
    // 单次轮询失败不打断流程，网络抖动下一轮会重来；连续业务错误才提示
    if (e && e.code && e.code !== -1) {
      stopPolling()
      errorMsg.value = e.message || '登录状态获取失败'
      phase.value = 'error'
    }
  }
}

onMounted(() => {
  // 已登录（含 APP 回跳时先完成了登录）直接进助手，不再出码
  if (getToken()) {
    enterAssistant()
    return
  }
  // ?scene= 仅兼容 APP 回跳；本机主流程始终自己出码轮询
  startQr()
})

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.ft {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--im-bg, #f5f7fa);
  padding: 20px;
}

.ft__card {
  width: 100%;
  max-width: 380px;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  padding: 32px 24px;
  text-align: center;
}

.ft__logo {
  color: var(--im-primary, #409eff);
  margin-bottom: 12px;
}

.ft__title {
  font-size: 18px;
  font-weight: 600;
  color: var(--im-text);
  margin-bottom: 20px;
}

.ft__loading {
  padding: 24px 0;
  color: var(--im-text-secondary);
  font-size: 14px;
}

.ft__qr {
  position: relative;
  display: inline-block;
  margin-bottom: 16px;
}

.ft__qr-img {
  width: 220px;
  height: 220px;
  display: block;
  border: 1px solid var(--im-border, #ebeef5);
  border-radius: 8px;
}

.ft__qr-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 8px;
  color: var(--im-primary, #409eff);
  font-size: 13px;
}

.ft__desc {
  font-size: 13px;
  color: var(--im-text-secondary);
  line-height: 1.6;
  margin-bottom: 20px;
}

.ft__desc--error {
  color: var(--im-danger, #f56c6c);
}

.ft__btn {
  width: 100%;
}
</style>
