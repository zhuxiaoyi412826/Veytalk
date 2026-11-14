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

    <!-- 传内容界面：消息区在上可滚动，输入区固定在底部，与微信文件传输助手同款布局 -->
    <main
      v-else
      class="fh__body"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDrop"
    >
      <div ref="listEl" class="fh__list im-scroll" @scroll.passive="onListScroll">
        <div v-if="loadingList && !items.length" v-loading="true" class="fh__list-loading"></div>
        <div v-else-if="!items.length" class="fh__list-empty">
          你可以使用「手机 APP &gt; 文件传输助手」与电脑互传文件、文字和语音
        </div>
        <ul v-else class="fh__items">
          <li v-for="it in items" :key="it.messageId" class="fh__item">
            <!-- 文字 -->
            <template v-if="it.type === MSG_TEXT">
              <el-icon class="fh__item-ico" :size="26"><ChatDotRound /></el-icon>
              <div class="fh__item-main">
                <div class="fh__item-text">{{ it.text }}</div>
                <div class="fh__item-meta">{{ it.time }}</div>
              </div>
            </template>
            <!-- 语音：内嵌播放器，src 走带鉴权的 blob 地址 -->
            <template v-else-if="it.type === MSG_VOICE">
              <el-icon class="fh__item-ico" :size="26"><Microphone /></el-icon>
              <div class="fh__item-main">
                <audio class="fh__audio" controls preload="none" :src="mediaUrl(it.url)"></audio>
                <div class="fh__item-meta">语音{{ it.duration ? ' · ' + it.duration + 's' : '' }}<template v-if="it.time"> · {{ it.time }}</template></div>
              </div>
            </template>
            <!-- 图片 / 文件 -->
            <template v-else>
              <el-icon class="fh__item-ico" :size="26">
                <Picture v-if="it.isImage" />
                <Document v-else />
              </el-icon>
              <div class="fh__item-main">
                <div class="fh__item-name" :title="it.name">{{ it.name }}</div>
                <div class="fh__item-meta">{{ formatSize(it.size) }}<template v-if="it.time"> · {{ it.time }}</template></div>
              </div>
              <el-button class="fh__item-dl" circle :icon="Download" title="下载" @click="download(it)" />
            </template>
          </li>
        </ul>
      </div>

      <!-- 底部输入区：文件夹图标选文件、麦克风录语音，拖拽则整个页面都是投放区 -->
      <div class="fh__panel">
        <div class="fh__panel-tools">
          <el-icon class="fh__tool" :size="20" title="发送文件" @click="pickFiles"><FolderOpened /></el-icon>
          <el-icon
            class="fh__tool"
            :class="{ 'is-recording': recording }"
            :size="20"
            :title="recording ? `录音中 ${recordSeconds}s，点击停止并发送` : '发送语音'"
            @click="toggleRecord"
          ><Microphone /></el-icon>
          <span v-if="recording" class="fh__panel-status is-rec">● 录音中 {{ recordSeconds }}s，点击麦克风停止并发送</span>
          <span v-else-if="uploadingName" class="fh__panel-status">正在上传「{{ uploadingName }}」{{ uploadPercent }}%</span>
          <span v-else-if="sendingKind" class="fh__panel-status">正在发送…</span>
        </div>
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="5000"
          :disabled="!!sendingKind"
          placeholder="输入文字传到手机；支持 Ctrl+Enter 快速发送"
          @keydown.ctrl.enter="sendText"
        />
        <div class="fh__panel-foot">
          <span v-if="lastSync" class="fh__panel-sync">更新于 {{ lastSync }}</span>
          <el-button type="primary" :loading="sendingKind === 'text'" :disabled="!draft.trim() || !!sendingKind" @click="sendText">发送</el-button>
        </div>
      </div>

      <div v-if="dragOver" class="fh__drop-mask">松开鼠标，上传文件到手机</div>
      <input ref="fileInput" type="file" multiple class="fh__file" @change="onPick" />
    </main>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Download, Document, FolderOpened, Loading, Microphone, Picture, Promotion, Refresh } from '@element-plus/icons-vue'
import { createQrLogin, pollQrLoginStatus } from '@/api/qrLogin'
import { openAssistantSession } from '@/api/assistant'
import { uploadFileSmart } from '@/api/file'
import { sendMessage, fetchHistory } from '@/api/message'
import { downloadFile, mediaUrl } from '@/utils/media'
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

const MSG_TEXT = 1
const MSG_IMAGE = 2
const MSG_FILE = 3
const MSG_VOICE = 4
const LIST_SIZE = 50
const RECORD_MAX_SECONDS = 60
const LIST_POLL_MS = 6000
// 会话级登录标记：只存 sessionStorage，关掉页签即消失，再开必须重新扫码；
// 同页签内刷新仍有效，不至于手滑刷新就掉线
const FH_SESSION_KEY = 'im-fh-session'

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
      sessionStorage.setItem(FH_SESSION_KEY, '1')
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

/* ---------------- 传内容（文字 / 图片 / 文件 / 语音） ---------------- */
const conversationId = ref('')
const items = ref([])
const loadingList = ref(false)
const lastSync = ref('')
const dragOver = ref(false)
const uploadingName = ref('')
const uploadPercent = ref(0)
const fileInput = ref(null)
const listEl = ref(null)
// 用户停在列表底部附近时才自动滚到最新，翻历史时不打断阅读
let stickBottom = true
let listTimer = null

// 文字草稿与发送中标记（'text' | 'voice' | ''），用于互斥禁用输入区按钮
const draft = ref('')
const sendingKind = ref('')
// 录音状态：MediaRecorder 为浏览器原生 API，需安全上下文（https 或 localhost）
const recording = ref(false)
const recordSeconds = ref(0)
let mediaRecorder = null
let audioChunks = []
let recordTimer = null

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
    items.value = list
      .filter((m) => !m.recalled && [MSG_TEXT, MSG_IMAGE, MSG_FILE, MSG_VOICE].includes(Number(m.msgType)))
      .map(toItem)
    lastSync.value = new Date().toLocaleTimeString()
    if (showLoading) {
      // 手动刷新视为「回到底部看最新」；轮询只在用户本来就贴在底部时跟随
      stickBottom = true
    }
    scrollToBottom()
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
  const type = Number(m.msgType)
  return {
    messageId: m.messageId || m.clientMsgId,
    type,
    text: type === MSG_TEXT ? m.content || '' : '',
    name: extra.fileName || m.content || '未命名文件',
    size: extra.fileSize,
    duration: extra.duration,
    url: extra.fileUrl || '',
    time: (m.sendTime || '').slice(5, 16).replace('T', ' '),
    isImage: type === MSG_IMAGE || String(extra.contentType || '').startsWith('image/')
  }
}

function onListScroll() {
  const el = listEl.value
  if (!el) {
    return
  }
  stickBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom() {
  if (!stickBottom) {
    return
  }
  nextTick(() => {
    const el = listEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

// 拖拽经过子元素时父层会反复 dragleave，用 relatedTarget 判定真正离开才收起遮罩
function onDragLeave(e) {
  const container = e.currentTarget
  if (e.relatedTarget && container.contains(e.relatedTarget)) {
    return
  }
  dragOver.value = false
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

/* ---------------- 发送文字 ---------------- */
async function sendText() {
  const text = draft.value.trim()
  if (!text || sendingKind.value) {
    return
  }
  sendingKind.value = 'text'
  try {
    await sendMessage({
      conversationId: conversationId.value,
      msgType: MSG_TEXT,
      content: text,
      clientMsgId: newClientMsgId()
    })
    draft.value = ''
    await loadFiles(false)
  } catch (e) {
    ElMessage.error((e && e.message) || '发送文字失败')
  } finally {
    sendingKind.value = ''
  }
}

/* ---------------- 录音（语音） ---------------- */
function toggleRecord() {
  if (recording.value) {
    stopRecord()
  } else {
    startRecord()
  }
}

async function startRecord() {
  if (sendingKind.value) {
    return
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || typeof MediaRecorder === 'undefined') {
    ElMessage.error('当前浏览器不支持录音，请改用 Chrome / Edge')
    return
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    // 优先录后端 chat_voice 白名单放行的容器（m4a / ogg 安卓与网页都能播），
    // webm 只作兜底（需后端同步放行，见 FileBizType.CHAT_VOICE）
    const mime = ['audio/mp4', 'audio/ogg;codecs=opus'].find((t) => MediaRecorder.isTypeSupported(t)) || ''
    mediaRecorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream)
    audioChunks = []
    mediaRecorder.ondataavailable = (e) => {
      if (e.data && e.data.size) {
        audioChunks.push(e.data)
      }
    }
    mediaRecorder.onstop = onRecorderStop
    mediaRecorder.start()
    recording.value = true
    recordSeconds.value = 0
    recordTimer = setInterval(() => {
      recordSeconds.value += 1
      if (recordSeconds.value >= RECORD_MAX_SECONDS) {
        // 到达上限自动停止并发送，避免录进超大文件
        stopRecord()
      }
    }, 1000)
  } catch (e) {
    mediaRecorder = null
    ElMessage.error('无法访问麦克风，请检查浏览器权限')
  }
}

function stopRecord() {
  if (recordTimer) {
    clearInterval(recordTimer)
    recordTimer = null
  }
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop() // 异步触发 onstop → onRecorderStop
  }
  recording.value = false
}

// 放弃当前录音且不发送（退出/卸载时用）
function cancelRecord() {
  if (recordTimer) {
    clearInterval(recordTimer)
    recordTimer = null
  }
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.onstop = null
    const s = mediaRecorder.stream
    mediaRecorder.stop()
    if (s) {
      s.getTracks().forEach((t) => t.stop())
    }
  }
  mediaRecorder = null
  audioChunks = []
  recording.value = false
}

async function onRecorderStop() {
  const seconds = recordSeconds.value
  const stream = mediaRecorder && mediaRecorder.stream
  const mime = (mediaRecorder && mediaRecorder.mimeType) || 'audio/webm'
  const blob = new Blob(audioChunks, { type: mime })
  audioChunks = []
  if (stream) {
    stream.getTracks().forEach((t) => t.stop())
  }
  mediaRecorder = null
  if (!blob.size) {
    ElMessage.warning('录音为空，已取消')
    return
  }
  const ext = mime.includes('mp4') ? 'm4a' : mime.includes('ogg') ? 'ogg' : 'webm'
  const file = new File([blob], `voice-${Date.now()}.${ext}`, { type: mime })
  sendingKind.value = 'voice'
  try {
    // 语音走 chat_voice 上传，duration 随上传参数落进文件记录
    const vo = await uploadFileSmart(file, 'chat_voice', seconds || 1)
    await sendMessage({
      conversationId: conversationId.value,
      msgType: MSG_VOICE,
      content: String(vo.fileId),
      clientMsgId: newClientMsgId(),
      extra: {
        fileId: vo.fileId,
        fileName: vo.originalName || file.name,
        fileSize: vo.size,
        contentType: vo.contentType || mime,
        ext: vo.ext || ext,
        duration: seconds || 1,
        fileUrl: vo.url
      }
    })
    await loadFiles(false)
  } catch (e) {
    ElMessage.error((e && e.message) || '语音发送失败')
  } finally {
    sendingKind.value = ''
  }
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
  sessionStorage.removeItem(FH_SESSION_KEY)
  if (listTimer) {
    clearInterval(listTimer)
    listTimer = null
  }
  cancelRecord()
  draft.value = ''
  conversationId.value = ''
  items.value = []
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
  // 只认「本次页签内扫码授权过」的会话（sessionStorage）：关掉页面再开，
  // 即使 localStorage 里还有旧 token 也重新出码，符合“退出即需重新登录”
  if (getToken() && sessionStorage.getItem(FH_SESSION_KEY) === '1') {
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
  cancelRecord()
})
</script>

<style scoped>
.fh {
  /* 必须是定高而不是 min-height：全局 body{overflow:hidden} 下，min-height 会被内容撑开，
     内部 flex 链失去高度约束，列表的 overflow-y:auto 永不触发，输入面板被顶出视口 */
  height: 100vh;
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

/* ---------- 传内容主体：上消息列表、下输入面板 ---------- */
.fh__body {
  position: relative;
  flex: 1;
  width: 100%;
  max-width: 820px;
  margin: 0 auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.fh__file {
  display: none;
}

.fh__drop-mask {
  position: fixed;
  inset: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(64, 158, 255, 0.08);
  border: 2px dashed var(--im-primary, #409eff);
  color: var(--im-primary, #409eff);
  font-size: 16px;
  pointer-events: none;
}

.fh__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  padding: 4px 16px;
}

.fh__list-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.fh__list-loading {
  height: 100%;
  min-height: 120px;
}

.fh__items {
  margin: 0;
  padding: 0;
  list-style: none;
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

/* ---------- 底部输入面板 ---------- */
.fh__panel {
  flex: 0 0 auto;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fh__panel-tools {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 22px;
}

.fh__tool {
  color: var(--im-text-secondary);
  cursor: pointer;
}

.fh__tool:hover {
  color: var(--im-primary, #409eff);
}

.fh__tool.is-recording {
  color: var(--im-danger, #f56c6c);
  animation: fh-pulse 1s ease-in-out infinite;
}

@keyframes fh-pulse {
  50% {
    opacity: 0.35;
  }
}

.fh__panel-status {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.fh__panel-status.is-rec {
  color: var(--im-danger, #f56c6c);
}

.fh__panel-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.fh__panel-sync {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-right: auto;
}

.fh__item-text {
  font-size: 14px;
  color: var(--im-text);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}

.fh__audio {
  width: 100%;
  max-width: 320px;
  height: 36px;
}
</style>
