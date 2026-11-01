<template>
  <div class="fh">
    <!-- 顶栏：两种形态共用 -->
    <header class="fh__head">
      <el-icon class="fh__logo" :size="22"><Promotion /></el-icon>
      <span class="fh__brand">文件传输助手 · 网页版</span>
      <div v-if="phase === 'transfer'" class="fh__head-right">
        <el-button link :icon="Refresh" :loading="loadingList" @click="loadFiles(true)">刷新</el-button>
        <el-button link @click="logout">退出</el-button>
      </div>
    </header>

    <!-- 登录态：出码 + 轮询 -->
    <main v-if="phase !== 'transfer'" class="fh__login">
      <div class="fh__card">
        <div class="fh__hint">使用手机 APP 扫一扫，登录后即可在此电脑与手机之间互传文件</div>

        <div v-if="phase === 'qrcode' || phase === 'scanned'" class="fh__qr">
          <img v-if="qrcode" :src="qrcode" class="fh__qr-img" alt="登录二维码" />
          <div v-if="phase === 'scanned'" class="fh__qr-mask">
            <el-icon class="is-loading" :size="26"><Loading /></el-icon>
            <span>已扫码，请在手机上确认</span>
          </div>
        </div>
        <div v-else-if="phase === 'entering'" v-loading="true" class="fh__loading">正在进入…</div>
        <div v-else class="fh__err">{{ errorMsg || '二维码已失效' }}</div>

        <div v-if="phase === 'qrcode' || phase === 'scanned'" class="fh__desc">二维码剩余 {{ remain }} 秒</div>
        <el-button v-if="phase === 'expired' || phase === 'error'" class="fh__btn" @click="startQr">刷新二维码</el-button>
      </div>
    </main>

    <!-- 传文件界面 -->
    <main v-else class="fh__body">
      <div
        class="fh__drop"
        :class="{ 'is-over': dragOver, 'is-busy': !!uploadingName }"
        @dragover.prevent="dragOver = true"
        @dragleave.prevent="dragOver = false"
        @drop.prevent="onDrop"
        @click="pickFiles"
      >
        <el-icon class="fh__drop-ico" :size="40"><UploadFilled /></el-icon>
        <div class="fh__drop-text">
          <template v-if="uploadingName">
            正在上传「{{ uploadingName }}」 {{ uploadPercent }}%
          </template>
          <template v-else>
            把文件拖到这里，或点击选择文件上传
            <span class="fh__drop-sub">手机 APP 发给「文件传输助手」的文件也会显示在下面</span>
          </template>
        </div>
        <input ref="fileInput" type="file" multiple class="fh__file" @change="onPick" />
      </div>

      <div class="fh__list">
        <div class="fh__list-head">
          <span>文件（{{ files.length }}）</span>
          <span v-if="lastSync" class="fh__list-sync">更新于 {{ lastSync }}</span>
        </div>

        <div v-if="loadingList && !files.length" v-loading="true" class="fh__list-loading"></div>
        <el-empty v-else-if="!files.length" description="还没有文件，拖一个进来试试" :image-size="80" />
        <ul v-else class="fh__items">
          <li v-for="f in files" :key="f.messageId" class="fh__item">
            <el-icon class="fh__item-ico" :size="26">
              <Picture v-if="f.isImage" />
              <Document v-else />
            </el-icon>
            <div class="fh__item-main">
              <div class="fh__item-name" :title="f.name">{{ f.name }}</div>
              <div class="fh__item-meta">{{ formatSize(f.size) }}<template v-if="f.time"> · {{ f.time }}</template></div>
            </div>
            <el-button class="fh__item-dl" circle :icon="Download" title="下载" @click="download(f)" />
          </li>
        </ul>
      </div>
    </main>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Document, Loading, Picture, Promotion, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { createQrLogin, pollQrLoginStatus } from '@/api/qrLogin'
import { openAssistantSession } from '@/api/assistant'
import { uploadFileSmart } from '@/api/file'
import { sendMessage, fetchHistory } from '@/api/message'
import { downloadFile } from '@/utils/media'
import { clearToken, getToken, setToken } from '@/utils/token'

/**
 * 独立的「文件传输助手 · 网页版」整屏页（公开路由 /filehelper）。
 *
 * 对标微信文件传输助手网页版：电脑端不必装桌面客户端、也不必登录整套 IM，
 * 用手机 APP 扫码授权后，本页面原地切换成一个只用于跨设备传文件的界面——
 * 拖拽 / 点击上传、文件列表、逐条下载。后端能力（扫码登录、助手隔离单聊、
 * 文件上传下载、消息历史）全部复用现成链路，本页面不引入 WebSocket，
 * 靠定时轮询 + 手动刷新同步手机侧新发来的文件。
 *
 * 与 /transfer 的区别：/transfer 扫码后跳进完整聊天界面，这里始终停留在专用传文件页。
 */

const MSG_IMAGE = 2
const MSG_FILE = 3
const LIST_SIZE = 50
const LIST_POLL_MS = 6000

/* ---------------- 登录：出码 + 轮询 ---------------- */
const phase = ref('entering') // entering | qrcode | scanned | expired | error | transfer
const qrcode = ref('')
const scene = ref('')
const remain = ref(0)
const errorMsg = ref('')

let qrTimer = null
let deadline = 0

function stopQr() {
  if (qrTimer) {
    clearInterval(qrTimer)
    qrTimer = null
  }
}

async function startQr() {
  stopQr()
  errorMsg.value = ''
  qrcode.value = ''
  try {
    const res = await createQrLogin(window.location.origin)
    scene.value = res.scene
    qrcode.value = res.qrcode
    deadline = Date.now() + (res.expiresIn || 120) * 1000
    remain.value = res.expiresIn || 120
    phase.value = 'qrcode'
    qrTimer = setInterval(pollOnce, 2000)
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
    stopQr()
    errorMsg.value = '二维码已过期'
    phase.value = 'expired'
    return
  }
  try {
    const res = await pollQrLoginStatus(scene.value)
    if (res.status === 1) {
      phase.value = 'scanned'
    } else if (res.status === 2) {
      stopQr()
      setToken(res.token, res.tokenName)
      await enterTransfer()
    } else if (res.status === 3) {
      stopQr()
      errorMsg.value = '二维码已失效'
      phase.value = 'expired'
    }
  } catch (e) {
    // 单次轮询的网络抖动不打断流程；连续业务错误才停并提示
    if (e && e.code && e.code !== -1) {
      stopQr()
      errorMsg.value = e.message || '登录状态获取失败'
      phase.value = 'error'
    }
  }
}

/* ---------------- 传文件 ---------------- */
const conversationId = ref('')
const files = ref([])
const loadingList = ref(false)
const lastSync = ref('')
const dragOver = ref(false)
const uploadingName = ref('')
const uploadPercent = ref(0)
const fileInput = ref(null)
let listTimer = null

async function enterTransfer() {
  phase.value = 'entering'
  try {
    const cid = await openAssistantSession()
    conversationId.value = cid
    phase.value = 'transfer'
    await loadFiles()
    listTimer = setInterval(() => loadFiles(false), LIST_POLL_MS)
  } catch (e) {
    // 定位助手会话失败：退回出码，让用户重新扫码
    errorMsg.value = (e && e.message) || '进入文件传输助手失败'
    phase.value = 'error'
  }
}

async function loadFiles(showLoading = true) {
  if (!conversationId.value) {
    return
  }
  if (showLoading) {
    loadingList.value = true
  }
  try {
    const list = (await fetchHistory(conversationId.value, null, LIST_SIZE)) || []
    files.value = list
      .filter((m) => !m.recalled && (Number(m.msgType) === MSG_FILE || Number(m.msgType) === MSG_IMAGE))
      .map(toItem)
    lastSync.value = new Date().toLocaleTimeString()
  } catch (e) {
    // 轮询失败静默（下一轮会重来）；只有手动刷新才提示
    if (showLoading) {
      ElMessage.error((e && e.message) || '加载文件列表失败')
    }
  } finally {
    if (showLoading) {
      loadingList.value = false
    }
  }
}

function toItem(m) {
  const extra = m.extra || {}
  return {
    messageId: m.messageId || m.clientMsgId,
    name: extra.fileName || m.content || '未命名文件',
    size: extra.fileSize,
    url: extra.fileUrl || '',
    time: (m.sendTime || '').slice(5, 16),
    isImage: Number(m.msgType) === MSG_IMAGE || String(extra.contentType || '').startsWith('image/')
  }
}

function pickFiles() {
  if (!uploadingName.value) {
    fileInput.value && fileInput.value.click()
  }
}

function onPick(e) {
  const picked = e.target.files
  if (picked && picked.length) {
    handleFiles(picked)
  }
  // 清空以便同一个文件连续选择时仍能触发 change
  e.target.value = ''
}

function onDrop(e) {
  dragOver.value = false
  const dropped = e.dataTransfer && e.dataTransfer.files
  if (dropped && dropped.length) {
    handleFiles(dropped)
  }
}

async function handleFiles(fileList) {
  // 逐个串行上传：避免并发把带宽与后端线程占满，进度条也只需呈现当前一个
  for (const file of Array.from(fileList)) {
    await uploadOne(file)
  }
}

async function uploadOne(file) {
  uploadingName.value = file.name || '文件'
  uploadPercent.value = 0
  try {
    const vo = await uploadFileSmart(file, 'chat_file', null, (percent) => {
      uploadPercent.value = percent
    })
    // content 放文件 ID，服务端按文件记录回填 fileName/size/url 等元数据（防越权改写）
    await sendMessage({
      conversationId: conversationId.value,
      msgType: MSG_FILE,
      content: String(vo.fileId),
      clientMsgId: newClientMsgId(),
      extra: {
        fileId: vo.fileId,
        fileName: vo.originalName || file.name,
        fileSize: vo.size,
        contentType: vo.contentType,
        ext: vo.ext,
        fileUrl: vo.url
      }
    })
    await loadFiles(false)
  } catch (e) {
    ElMessage.error((e && e.message) || `上传「${file.name}」失败`)
  } finally {
    uploadingName.value = ''
    uploadPercent.value = 0
  }
}

function newClientMsgId() {
  return `fh-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function download(f) {
  if (!f.url) {
    ElMessage.warning('该文件暂不可下载')
    return
  }
  downloadFile(f.url, f.name)
}

function logout() {
  // 只清本地登录态并回到出码；服务端 web 设备令牌随会话自然过期
  clearToken()
  if (listTimer) {
    clearInterval(listTimer)
    listTimer = null
  }
  conversationId.value = ''
  files.value = []
  lastSync.value = ''
  startQr()
}

function formatSize(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

onMounted(() => {
  // 已登录（含刷新页面）直接进传文件界面，不再出码
  if (getToken()) {
    enterTransfer()
    return
  }
  startQr()
})

onBeforeUnmount(() => {
  stopQr()
  if (listTimer) {
    clearInterval(listTimer)
    listTimer = null
  }
})
</script>

<style scoped>
.fh {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--im-bg, #f5f7fa);
}

.fh__head {
  height: 56px;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  background: var(--im-bg-card, #fff);
  border-bottom: 1px solid var(--im-border, #ebeef5);
}

.fh__logo {
  color: var(--im-primary, #409eff);
}

.fh__brand {
  font-size: 16px;
  font-weight: 600;
  color: var(--im-text);
}

.fh__head-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ---------- 登录卡片 ---------- */
.fh__login {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.fh__card {
  width: 100%;
  max-width: 400px;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  padding: 32px 24px;
  text-align: center;
}

.fh__hint {
  font-size: 14px;
  color: var(--im-text);
  margin-bottom: 20px;
  line-height: 1.6;
}

.fh__qr {
  position: relative;
  display: inline-block;
  margin-bottom: 12px;
}

.fh__qr-img {
  width: 220px;
  height: 220px;
  display: block;
  border: 1px solid var(--im-border, #ebeef5);
  border-radius: 8px;
}

.fh__qr-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.92);
  border-radius: 8px;
  color: var(--im-primary, #409eff);
  font-size: 13px;
}

.fh__loading {
  padding: 40px 0;
}

.fh__err {
  padding: 24px 0;
  color: var(--im-danger, #f56c6c);
  font-size: 14px;
}

.fh__desc {
  font-size: 13px;
  color: var(--im-text-secondary);
  margin-bottom: 12px;
}

.fh__btn {
  width: 100%;
}

/* ---------- 传文件主体 ---------- */
.fh__body {
  flex: 1;
  width: 100%;
  max-width: 820px;
  margin: 0 auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.fh__drop {
  flex: 0 0 auto;
  border: 2px dashed var(--im-border, #dcdfe6);
  border-radius: 12px;
  background: var(--im-bg-card, #fff);
  padding: 28px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.fh__drop:hover,
.fh__drop.is-over {
  border-color: var(--im-primary, #409eff);
  background: rgba(64, 158, 255, 0.04);
}

.fh__drop.is-busy {
  cursor: progress;
  border-color: var(--im-primary, #409eff);
}

.fh__drop-ico {
  color: var(--im-primary, #409eff);
}

.fh__drop-text {
  font-size: 14px;
  color: var(--im-text);
  text-align: center;
  line-height: 1.6;
}

.fh__drop-sub {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.fh__file {
  display: none;
}

.fh__list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  padding: 12px 16px;
  overflow: hidden;
}

.fh__list-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--im-text-secondary);
  padding-bottom: 8px;
  border-bottom: 1px solid var(--im-border, #ebeef5);
}

.fh__list-sync {
  font-size: 12px;
}

.fh__list-loading {
  flex: 1;
  min-height: 120px;
}

.fh__items {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}

.fh__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid var(--im-border, #f2f3f5);
}

.fh__item:last-child {
  border-bottom: none;
}

.fh__item-ico {
  color: var(--im-primary, #409eff);
  flex: 0 0 auto;
}

.fh__item-main {
  flex: 1;
  min-width: 0;
}

.fh__item-name {
  font-size: 14px;
  color: var(--im-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fh__item-meta {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-top: 2px;
}

.fh__item-dl {
  flex: 0 0 auto;
}
</style>
