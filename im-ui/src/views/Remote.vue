<script setup>
/**
 * 远程控制（控制端）。
 *
 * 页面分两个形态：无会话时显示设备清单 + 我的会话历史；
 * 会话进行中切换成「画面 + 右侧工具面板」的全宽工作区。
 *
 * 授权链路：invite → 被控端弹窗 → 轮询 detail 拿 ticket/aesKey → 建数据面 WS。
 * 屏幕渲染用「关键帧定尺寸 + 脏块按坐标贴图」，与 Agent 端 64x64 网格协议对应。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  endRemoteSession,
  fetchRemoteAudit,
  fetchRemoteDevices,
  fetchRemoteSession,
  fetchRemoteSessionPage,
  inviteRemote,
  inviteRemoteByCode
} from '@/api/remote'
import { FRAME_FILE, FRAME_SCREEN, RemoteControlSocket } from '@/utils/remoteWs'
import { getToken } from '@/utils/token'

const STATUS_TEXT = { 0: '离线', 1: '空闲', 2: '使用中', 3: '拒绝接入' }
const STATUS_TAG = { 0: 'info', 1: 'success', 2: 'warning', 3: 'danger' }

/* ==================== 设备与发起 ==================== */

const devices = ref([])
const devicesLoading = ref(false)
const inviteForm = reactive({ visible: false, deviceId: '', deviceName: '', permission: 'operate' })
const inviteWaiting = ref('')

async function loadDevices() {
  devicesLoading.value = true
  try {
    devices.value = (await fetchRemoteDevices()) || []
  } finally {
    devicesLoading.value = false
  }
  probeLocalAgent()
}

/* 本机识别码：读本机 Agent 在 127.0.0.1 上的只读回环接口（与 Agent 默认端口一致）。
   识别码由 Agent 进程生成持有，网页与它本是两个进程，没有这条通道页面无从展示；
   本机没跑 Agent 时 fetch 失败，面板隐藏不干扰。loopback 属可信源，https 页面也不拦 */
const LOCAL_INFO_URL = 'http://127.0.0.1:18923/local-info'
const localAgent = ref(null)

async function probeLocalAgent() {
  try {
    const resp = await fetch(LOCAL_INFO_URL, { signal: AbortSignal.timeout(800) })
    localAgent.value = resp.ok ? await resp.json() : null
  } catch {
    localAgent.value = null
  }
}

function openInvite(row) {
  Object.assign(inviteForm, { visible: true, deviceId: row.deviceId, deviceName: row.deviceName, permission: 'operate' })
}

async function doInvite() {
  try {
    const result = await inviteRemote({ deviceId: inviteForm.deviceId, permission: inviteForm.permission })
    inviteForm.visible = false
    inviteWaiting.value = `已向「${result.deviceName}」发起远程请求，等待对方授权…`
    startPolling(result.sessionId, result.deviceName, result.permission)
  } catch {
    // 设备离线/忙/拒绝等提示由 request 拦截器统一弹出
  }
}

/* 凭识别码连接：对方 Agent 无需登录账号，只要在线并报出识别码即可 */
const codeForm = reactive({ code: '', permission: 'operate' })

/** 复制本机/设备识别码：发给控制方，对方在「凭识别码连接」里填它 */
async function copyCode(code) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success(`识别码 ${code} 已复制，发给控制方即可`)
  } catch {
    ElMessage.warning(`浏览器不允许剪贴板访问，请手动记录：${code}`)
  }
}

async function doInviteByCode() {
  const code = codeForm.code.trim().toUpperCase()
  if (!/^[A-Z0-9]{6,12}$/.test(code)) {
    ElMessage.warning('识别码需为 6-12 位字母或数字')
    return
  }
  try {
    const result = await inviteRemoteByCode({ code, permission: codeForm.permission })
    inviteWaiting.value = `已向识别码「${code}」的设备发起远程请求，等待对方授权…`
    codeForm.code = ''
    startPolling(result.sessionId, result.deviceName, result.permission)
  } catch {
    // 不在线/码错/拒绝等提示由 request 拦截器统一弹出
  }
}

/** 轮询授权进度：2 秒一次，最多 70 秒（覆盖后端 60s 邀请超时） */
let pollTimer = null
function startPolling(sessionId, deviceName, permission) {
  let elapsed = 0
  stopPolling()
  pollTimer = setInterval(async () => {
    elapsed += 2
    try {
      const detail = await fetchRemoteSession(sessionId)
      if (detail.status === 'active' && detail.ticket) {
        stopPolling()
        inviteWaiting.value = ''
        await openSession(detail, deviceName, permission)
      } else if (detail.status !== 'inviting') {
        stopPolling()
        inviteWaiting.value = ''
        ElMessage.warning(`会话未建立：${detail.status === 'rejected' ? '对方拒绝了请求' : '会话已结束'}`)
      } else if (elapsed >= 70) {
        stopPolling()
        inviteWaiting.value = ''
        ElMessage.warning('对方未在有效期内响应')
      }
    } catch {
      if (elapsed >= 70) {
        stopPolling()
        inviteWaiting.value = ''
      }
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/* ==================== 会话 ==================== */

const session = ref(null) // { socket, sessionId, permission, deviceName, aesKey }
const socket = ref(null)
const screenOn = ref(false)
const quality = ref(75)
const fps = ref(10)
const bytes = ref(0)
const sessionLogs = ref([])
const canvasRef = ref(null)

async function openSession(detail, deviceName) {
  const sock = new RemoteControlSocket({
    sessionId: detail.sessionId,
    ticket: detail.ticket,
    satoken: getToken(),
    aesKeyB64: detail.aesKey || '',
    onEnvelope: handleEnvelope,
    onBinaryFrame: handleBinaryFrame,
    onClose: () => {
      if (session.value) {
        pushLog('连接已断开，会话结束')
        teardownSession()
      }
    }
  })
  session.value = {
    sessionId: detail.sessionId,
    permission: detail.permission,
    deviceName: deviceName || detail.deviceName || '设备'
  }
  socket.value = sock
  try {
    await sock.connect()
    pushLog(`已连接中继，权限：${detail.permission === 'operate' ? '可操作' : '只读'}`)
    loadDevices()
  } catch (e) {
    ElMessage.error(e.message || '数据通道建立失败')
    teardownSession()
  }
}

function pushLog(text) {
  sessionLogs.value.unshift(`${new Date().toLocaleTimeString()}　${text}`)
  if (sessionLogs.value.length > 50) {
    sessionLogs.value.pop()
  }
}

function handleEnvelope(env) {
  const data = env.data || {}
  switch (env.type) {
    case 'session-start':
      pushLog('会话已绑定，可以开启画面')
      startScreen()
      break
    case 'session-closed':
      pushLog(`会话结束：${data.reason || '对方关闭'}`)
      teardownSession()
      break
    case 'error':
      if (data.code === 'READONLY') {
        pushLog('只读模式：输入操作被拒绝')
      } else if (!data.reqSeq) {
        pushLog(`通道错误：${data.message || data.code}`)
      }
      break
    default:
      break
  }
}

async function teardownSession() {
  if (socket.value) {
    socket.value.close()
    socket.value = null
  }
  session.value = null
  screenOn.value = false
  clearTransfers()
  loadDevices()
  loadSessions()
}

async function endSession() {
  const current = session.value
  if (!current) {
    return
  }
  try {
    await endRemoteSession(current.sessionId)
  } catch {
    // 后端可能已因断线收尾，忽略
  }
  await teardownSession()
}

/* ==================== 屏幕渲染 ==================== */

const stageRef = ref(null)
let renderChain = Promise.resolve()

function handleBinaryFrame({ frameType, meta, payload }) {
  if (frameType === FRAME_SCREEN) {
    renderChain = renderChain.then(() => drawScreen(meta, payload)).catch(() => {})
  } else if (frameType === FRAME_FILE) {
    receiveChunk(meta, payload)
  }
}

async function drawScreen(meta, payload) {
  const canvas = canvasRef.value
  if (!canvas || !payload.length) {
    return
  }
  const bitmap = await createImageBitmap(new Blob([payload], { type: 'image/jpeg' }))
  if (meta.key) {
    canvas.width = meta.screenW || meta.w
    canvas.height = meta.screenH || meta.h
  }
  const ctx = canvas.getContext('2d')
  ctx.drawImage(bitmap, meta.x, meta.y, meta.w, meta.h)
  bitmap.close()
}

function startScreen() {
  socket.value?.sendEnvelope('screen-start', {
    fps: fps.value,
    quality: quality.value,
    monitor: monitorIndex.value
  })
  screenOn.value = true
}

function stopScreen() {
  socket.value?.sendEnvelope('screen-stop', {})
  screenOn.value = false
}

function applyScreenParams() {
  if (screenOn.value) {
    startScreen()
  }
}

/* 显示器切换：Agent 帧元数据里带 screenW/H，切换由 monitor-switch 完成 */
const monitorIndex = ref(0)
function switchMonitor(index) {
  monitorIndex.value = index
  socket.value?.request('monitor-switch', { index }).catch(() => {})
}

/* ==================== 输入采集 ==================== */

let lastMoveSent = 0

function normalized(event) {
  const canvas = canvasRef.value
  if (!canvas) {
    return null
  }
  const rect = canvas.getBoundingClientRect()
  // canvas 以 CSS 缩放显示，按比例换算回 0~10000
  const x = Math.round(((event.clientX - rect.left) / rect.width) * 10000)
  const y = Math.round(((event.clientY - rect.top) / rect.height) * 10000)
  return { x: clamp(x), y: clamp(y) }
}

function clamp(v) {
  return Math.max(0, Math.min(10000, v))
}

const buttonName = (event) => (event.button === 2 ? 'right' : event.button === 1 ? 'middle' : 'left')

function onMouseMove(event) {
  if (!canInput() || !screenOn.value) {
    return
  }
  const now = Date.now()
  if (now - lastMoveSent < 40) {
    return
  }
  lastMoveSent = now
  const point = normalized(event)
  point && socket.value.sendEnvelope('mouse', { action: 'move', ...point })
}

function onMouseDown(event) {
  if (!canInput()) {
    return
  }
  event.preventDefault()
  stageRef.value?.focus()
  const point = normalized(event)
  point && socket.value.sendEnvelope('mouse', { action: 'down', button: buttonName(event), ...point })
}

function onMouseUp(event) {
  if (!canInput()) {
    return
  }
  const point = normalized(event)
  point && socket.value.sendEnvelope('mouse', { action: 'up', button: buttonName(event), ...point })
}

function onWheel(event) {
  if (!canInput()) {
    return
  }
  const point = normalized(event)
  point && socket.value.sendEnvelope('mouse', { action: 'wheel', deltaY: -event.deltaY, ...point })
}

function canInput() {
  return session.value && session.value.permission === 'operate' && socket.value?.ready
}

/** JS keyCode → Java KeyEvent.VK_*：只有符号键编号体系不同，其余直通 */
const JAVA_KEY_OVERRIDES = { 186: 59, 187: 61, 189: 45, 219: 91, 220: 92, 221: 93 }

function javaKeyCode(keyCode) {
  return JAVA_KEY_OVERRIDES[keyCode] || keyCode
}

const PASS_THROUGH_KEYS = new Set(['F5', 'F11', 'F12'])

function onKeyDown(event) {
  if (!canInput() || event.target.tagName === 'INPUT' || event.target.tagName === 'TEXTAREA') {
    return
  }
  if (event.key === 'Escape') {
    endSession()
    return
  }
  if (PASS_THROUGH_KEYS.has(event.key)) {
    return
  }
  event.preventDefault()
  socket.value.sendEnvelope('key', { action: 'press', keyCode: javaKeyCode(event.keyCode) })
}

function onKeyUp(event) {
  if (!canInput() || event.target.tagName === 'INPUT' || event.target.tagName === 'TEXTAREA') {
    return
  }
  if (PASS_THROUGH_KEYS.has(event.key)) {
    return
  }
  event.preventDefault()
  socket.value.sendEnvelope('key', { action: 'release', keyCode: javaKeyCode(event.keyCode) })
}

/* ==================== 文件面板 ==================== */

const filePath = ref('')
const fileItems = ref([])
const fileRoots = ref([])
const fileLoading = ref(false)
const transfers = reactive({})
const uploadInput = ref(null)

async function listDir(path) {
  if (!socket.value?.ready) {
    return
  }
  fileLoading.value = true
  try {
    const result = await socket.value.request('list-dir', path ? { path } : {}, 30000)
    if (result.error) {
      ElMessage.error(result.error)
      return
    }
    filePath.value = result.path || ''
    fileItems.value = result.items || []
    fileRoots.value = result.roots || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    fileLoading.value = false
  }
}

function openItem(row) {
  if (row.dir) {
    listDir(row.path)
  }
}

function upOneLevel() {
  const current = filePath.value.replace(/[\\/]+$/, '')
  const index = Math.max(current.lastIndexOf('\\'), current.lastIndexOf('/'))
  // 回到盘符根时保留结尾分隔符（C:\），否则 checkPath 会解析成进程工作目录
  listDir(index <= 2 ? current.substring(0, index + 1) : current.substring(0, index))
}

async function downloadItem(row) {
  if (row.dir) {
    return
  }
  const transferId = randomId()
  transfers[transferId] = { name: row.name, total: 0, size: Number(row.size) || 0, chunks: {}, received: 0, done: false }
  try {
    await socket.value.request('file-get', { transferId, path: row.path }, 120000)
    finishDownload(transferId)
  } catch (e) {
    delete transfers[transferId]
    ElMessage.error(e.message)
  }
}

function receiveChunk(meta, payload) {
  const transfer = transfers[meta.transferId]
  if (!transfer || transfer.done) {
    return
  }
  transfer.total = meta.total || transfer.total
  transfer.size = meta.size || transfer.size
  transfer.chunks[meta.index] = payload
  transfer.received += payload.length
  const filled = Object.keys(transfer.chunks).length
  if (transfer.total && filled >= transfer.total) {
    finishDownload(meta.transferId)
  }
}

function finishDownload(transferId) {
  const transfer = transfers[transferId]
  if (!transfer || transfer.done) {
    return
  }
  transfer.done = true
  const ordered = []
  for (let i = 0; i < transfer.total; i++) {
    if (transfer.chunks[i]) {
      ordered.push(transfer.chunks[i])
    }
  }
  const blob = new Blob(ordered, { type: 'application/octet-stream' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = transfer.name
  link.click()
  setTimeout(() => URL.revokeObjectURL(link.href), 5000)
  delete transfers[transferId]
  ElMessage.success(`已下载 ${transfer.name}`)
}

function clearTransfers() {
  for (const key of Object.keys(transfers)) {
    delete transfers[key]
  }
}

async function uploadFiles(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length) {
    return
  }
  if (!filePath.value) {
    ElMessage.warning('请先选择上传目录')
    return
  }
  for (const file of files) {
    try {
      await uploadOne(file)
    } catch (e) {
      ElMessage.error(`${file.name}: ${e.message}`)
    }
  }
  listDir(filePath.value)
}

async function uploadOne(file) {
  const transferId = randomId()
  const CHUNK = 64 * 1024
  const total = Math.max(1, Math.ceil(file.size / CHUNK))
  // file-put 的 result 即「可以开始灌块」信号（被控端已建好 .part 临时文件）
  await socket.value.request(
    'file-put',
    { transferId, name: file.name, dir: filePath.value, size: file.size },
    10000
  )
  for (let i = 0; i < total; i++) {
    const slice = new Uint8Array(await file.slice(i * CHUNK, (i + 1) * CHUNK).arrayBuffer())
    await socket.value.sendBinaryFrame(FRAME_FILE, { transferId, name: file.name, index: i, total }, slice)
  }
  ElMessage.success(`已上传 ${file.name}`)
}

async function removeItem(row) {
  await ElMessageBox.confirm(`确定删除 ${row.path} ？${row.dir ? '（含目录内全部内容）' : ''}`, '高危操作', {
    type: 'warning'
  })
  try {
    await socket.value.request('rm', { path: row.path }, 60000)
    ElMessage.success('已删除')
    listDir(filePath.value)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function renameItem(row) {
  const { value } = await ElMessageBox.prompt('新名称', '重命名', { inputValue: row.name })
  if (!value || value === row.name) {
    return
  }
  const sep = row.path.includes('\\') ? '\\' : '/'
  const parent = row.path.replace(/[\\/]+$/, '').replace(/[^\\/]+$/, '')
  try {
    await socket.value.request('rename', { from: row.path, to: parent + value }, 15000)
    listDir(filePath.value)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function mkdir() {
  const { value } = await ElMessageBox.prompt('目录名', '新建目录')
  if (!value) {
    return
  }
  const sep = (filePath.value.includes('\\') ? '\\' : '/')
  try {
    await socket.value.request('mkdir', { path: joinPath(filePath.value, value) }, 15000)
    listDir(filePath.value)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function joinPath(dir, name) {
  if (!dir) {
    return name
  }
  const sep = dir.includes('\\') ? '\\' : '/'
  return dir.endsWith(sep) || dir.endsWith('\\') || dir.endsWith('/') ? dir + name : dir + sep + name
}

function randomId() {
  return `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
}

/* ==================== 系统面板 ==================== */

const processes = ref([])
const psLoading = ref(false)
const execCmd = ref('')
const execOutput = ref('')
const execRunning = ref(false)

async function loadProcesses() {
  psLoading.value = true
  try {
    const result = await socket.value.request('ps-list', {}, 15000)
    processes.value = result.items || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    psLoading.value = false
  }
}

async function killProcess(row) {
  await ElMessageBox.confirm(`确定结束进程 ${row.command || row.pid}（pid=${row.pid}）？`, '高危操作', {
    type: 'warning'
  })
  try {
    await socket.value.request('ps-kill', { pid: Number(row.pid) }, 15000)
    ElMessage.success('已结束')
    loadProcesses()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function runExec() {
  if (!execCmd.value.trim() || execRunning.value) {
    return
  }
  execRunning.value = true
  execOutput.value = '执行中…'
  try {
    const result = await socket.value.request('exec', { cmd: execCmd.value }, 40000)
    execOutput.value = result.output || '(无输出)'
  } catch (e) {
    execOutput.value = `失败：${e.message}`
  } finally {
    execRunning.value = false
  }
}

async function power(action) {
  const text = { shutdown: '关机', reboot: '重启', lock: '锁屏' }[action]
  await ElMessageBox.confirm(`确定对被控端执行「${text}」？`, '高危操作', { type: 'warning' })
  try {
    await socket.value.request('power', { action }, 15000)
    ElMessage.success(`已发出${text}指令`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function pushClipboard() {
  try {
    const text = await navigator.clipboard.readText()
    if (!text) {
      ElMessage.info('剪贴板为空')
      return
    }
    await socket.value.request('clip-sync', { text }, 10000)
    ElMessage.success('已同步到对端剪贴板')
  } catch (e) {
    ElMessage.error(e.message || '读取剪贴板失败')
  }
}

/* ==================== 会话历史 / 审计 ==================== */

const history = reactive({ rows: [], total: 0, current: 1, size: 10 })
const audit = reactive({ visible: false, rows: [], total: 0, current: 1, sessionId: '' })

async function loadSessions() {
  const page = await fetchRemoteSessionPage({ current: history.current, size: history.size })
  history.rows = page.records || []
  history.total = Number(page.total) || 0
}

async function openAudit(row) {
  audit.sessionId = String(row.id)
  audit.current = 1
  audit.visible = true
  await loadAudit()
}

async function loadAudit() {
  const page = await fetchRemoteAudit(audit.sessionId, { current: audit.current, size: 20 })
  audit.rows = page.records || []
  audit.total = Number(page.total) || 0
}

function statusText(status) {
  return { inviting: '等待授权', active: '进行中', rejected: '被拒绝', ended: '已结束' }[status] || status
}

function formatSize(bytesValue) {
  const v = Number(bytesValue) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${(v / 1024 / 1024).toFixed(2)} MB`
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '-'
}

/* ==================== 生命周期 ==================== */

let keydownHandler = null
let keyupHandler = null

onMounted(() => {
  loadDevices()
  loadSessions()
  keydownHandler = (e) => onKeyDown(e)
  keyupHandler = (e) => onKeyUp(e)
  document.addEventListener('keydown', keydownHandler)
  document.addEventListener('keyup', keyupHandler)
})

onBeforeUnmount(() => {
  stopPolling()
  if (keydownHandler) {
    document.removeEventListener('keydown', keydownHandler)
    document.removeEventListener('keyup', keyupHandler)
  }
  if (socket.value) {
    socket.value.close()
  }
})

const inSession = computed(() => !!session.value)
</script>

<template>
  <div class="remote-page">
    <!-- ============ 无会话：设备 + 历史 ============ -->
    <div v-if="!inSession" class="remote-entry">
      <el-card shadow="never" class="remote-entry__card">
        <template #header>
          <div class="card-header">
            <span>我的设备</span>
            <el-button size="small" :loading="devicesLoading" @click="loadDevices">刷新</el-button>
          </div>
        </template>
        <el-alert
          type="info"
          :closable="false"
          title="别人控本机：先在本机启动 Agent，页面上方绿色「本机识别码」即从本机 Agent 读取（没显示说明本机 Agent 未运行）；用当前账号登录的 Agent 还会进下表，识别码列可点击复制。控别人机器：在下方输入对方识别码点「凭识别码连接」。"
          style="margin-bottom: 12px"
        />
        <div v-if="localAgent" class="local-code">
          <span class="local-code__label">本机识别码</span>
          <span
            class="device-code"
            title="点击复制，发给控制方即可被控"
            @click="copyCode(localAgent.accessCode)"
          >{{ localAgent.accessCode }}</span>
          <el-tag size="small" :type="localAgent.online ? 'success' : 'info'">
            {{ localAgent.online ? '已连中继' : '未连中继' }}
          </el-tag>
          <span class="local-code__name">{{ localAgent.deviceName }}（读自本机 Agent，点击识别码可复制）</span>
        </div>
        <div class="code-invite">
          <span class="code-invite__label">对方识别码</span>
          <el-input
            v-model="codeForm.code"
            placeholder="输入对方识别码（如 A1B2C3）"
            maxlength="12"
            class="code-invite__input"
            @keyup.enter="doInviteByCode"
          />
          <el-radio-group v-model="codeForm.permission">
            <el-radio value="operate">完全控制</el-radio>
            <el-radio value="readonly">仅观看</el-radio>
          </el-radio-group>
          <el-button type="primary" @click="doInviteByCode">凭识别码连接</el-button>
        </div>
        <div v-if="inviteWaiting" class="invite-waiting">
          <el-icon class="is-loading"><Loading /></el-icon>
          {{ inviteWaiting }}
        </div>
        <el-table :data="devices" v-loading="devicesLoading" empty-text="暂无设备，请先在被控机启动 Agent">
          <el-table-column prop="deviceName" label="设备名称" min-width="140" />
          <el-table-column label="识别码" width="110">
            <template #default="{ row }">
              <span
                v-if="row.accessCode"
                class="device-code"
                title="点击复制，发给控制方即可被控"
                @click="copyCode(row.accessCode)"
              >{{ row.accessCode }}</span>
              <span v-else class="device-code--none">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="os" label="系统" min-width="140" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="STATUS_TAG[row.status]" size="small">{{ STATUS_TEXT[row.status] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近在线" width="160">
            <template #default="{ row }">{{ formatTime(row.lastOnlineTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                type="primary"
                :disabled="row.status !== 1"
                @click="openInvite(row)"
              >发起远程</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="remote-entry__card">
        <template #header><span>远程会话历史</span></template>
        <el-table :data="history.rows" empty-text="暂无会话记录">
          <el-table-column prop="deviceName" label="设备" width="140">
            <template #default="{ row }">{{ row.deviceId }}</template>
          </el-table-column>
          <el-table-column label="权限" width="90">
            <template #default="{ row }">{{ row.permission === 'operate' ? '可操作' : '只读' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ statusText(row.status) }}</template>
          </el-table-column>
          <el-table-column label="流量" width="100">
            <template #default="{ row }">{{ formatSize(row.bytes) }}</template>
          </el-table-column>
          <el-table-column prop="endReason" label="结束原因" width="130" show-overflow-tooltip />
          <el-table-column label="开始时间" width="160">
            <template #default="{ row }">{{ formatTime(row.startTime || row.createTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openAudit(row)">审计</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="history.current"
          :page-size="history.size"
          :total="history.total"
          layout="prev, pager, next"
          style="margin-top: 8px; justify-content: flex-end"
          @current-change="loadSessions"
        />
      </el-card>
    </div>

    <!-- ============ 会话工作区 ============ -->
    <div v-else class="remote-work">
      <div class="remote-toolbar">
        <span class="remote-toolbar__title">正在控制「{{ session.deviceName }}」</span>
        <el-tag size="small" :type="session.permission === 'operate' ? 'success' : 'warning'">
          {{ session.permission === 'operate' ? '可操作' : '只读' }}
        </el-tag>
        <el-divider direction="vertical" />
        <el-button size="small" @click="screenOn ? stopScreen() : startScreen()">
          {{ screenOn ? '停止画面' : '开始画面' }}
        </el-button>
        <span class="toolbar-label">画质</span>
        <el-slider v-model="quality" :min="30" :max="95" :step="5" size="small" class="toolbar-slider"
                   @change="applyScreenParams" />
        <span class="toolbar-label">帧率</span>
        <el-select v-model="fps" size="small" class="toolbar-select" @change="applyScreenParams">
          <el-option v-for="v in [5, 10, 15, 20]" :key="v" :label="`${v} fps`" :value="v" />
        </el-select>
        <el-button size="small" @click="switchMonitor(0)">屏1</el-button>
        <el-button size="small" @click="switchMonitor(1)">屏2</el-button>
        <el-button size="small" @click="pushClipboard">发剪贴板</el-button>
        <div class="remote-toolbar__spacer" />
        <el-button size="small" type="danger" @click="endSession">结束会话 (Esc)</el-button>
      </div>

      <div class="remote-body">
        <div ref="stageRef" class="remote-stage" tabindex="0"
             @mousemove="onMouseMove" @mousedown="onMouseDown" @mouseup="onMouseUp" @wheel.prevent="onWheel"
             @contextmenu.prevent>
          <canvas ref="canvasRef" class="remote-canvas" />
          <div v-if="!screenOn" class="remote-stage__hint">画面未开启，点击工具栏「开始画面」</div>
        </div>

        <div class="remote-side">
          <el-tabs class="remote-side__tabs">
            <el-tab-pane label="文件">
              <div class="panel-toolbar">
                <el-button size="small" :disabled="!filePath" @click="upOneLevel">上级</el-button>
                <el-select v-model="filePath" size="small" filterable allow-create placeholder="路径"
                           class="path-select" @change="listDir(filePath)">
                  <el-option v-for="r in fileRoots" :key="r.name" :label="r.name" :value="r.path" />
                </el-select>
                <el-button size="small" :loading="fileLoading" @click="listDir(filePath)">刷新</el-button>
              </div>
              <div class="panel-toolbar">
                <el-button size="small" @click="mkdir">新建目录</el-button>
                <el-button size="small" @click="uploadInput && uploadInput.click()">上传文件</el-button>
                <input ref="uploadInput" type="file" multiple style="display: none" @change="uploadFiles" />
              </div>
              <div v-if="Object.keys(transfers).length" class="transfer-list">
                <div v-for="(t, id) in transfers" :key="id" class="transfer-item">
                  {{ t.name }}：{{ t.total ? `${Object.keys(t.chunks).length}/${t.total} 块` : '等待分块…' }}
                </div>
              </div>
              <el-table :data="fileItems" height="calc(100% - 96px)" size="small" empty-text="点击刷新或选择盘符">
                <el-table-column label="名称" min-width="120" show-overflow-tooltip>
                  <template #default="{ row }">
                    <a v-if="row.dir" class="link" @click="openItem(row)">📁 {{ row.name }}</a>
                    <span v-else>📄 {{ row.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="大小" width="80">
                  <template #default="{ row }">{{ row.dir ? '-' : formatSize(row.size) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="130">
                  <template #default="{ row }">
                    <el-button v-if="!row.dir" size="small" link type="primary" @click="downloadItem(row)">下载</el-button>
                    <el-button size="small" link type="primary" @click="renameItem(row)">改名</el-button>
                    <el-button size="small" link type="danger" @click="removeItem(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="系统">
              <div class="panel-toolbar">
                <el-button size="small" :loading="psLoading" @click="loadProcesses">进程列表</el-button>
              </div>
              <el-table :data="processes" height="220" size="small" empty-text="点击加载进程">
                <el-table-column prop="pid" label="PID" width="70" />
                <el-table-column prop="command" label="命令" min-width="120" show-overflow-tooltip />
                <el-table-column label="" width="60">
                  <template #default="{ row }">
                    <el-button size="small" link type="danger" @click="killProcess(row)">结束</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div class="panel-toolbar" style="margin-top: 10px">
                <el-input v-model="execCmd" size="small" placeholder="cmd 命令，如 ipconfig"
                          @keyup.enter="runExec" />
                <el-button size="small" type="primary" :loading="execRunning" @click="runExec">执行</el-button>
              </div>
              <pre class="exec-output">{{ execOutput }}</pre>
              <div class="panel-toolbar" style="margin-top: 8px">
                <el-button size="small" @click="power('lock')">锁屏</el-button>
                <el-button size="small" @click="power('reboot')">重启</el-button>
                <el-button size="small" type="danger" plain @click="power('shutdown')">关机</el-button>
              </div>
            </el-tab-pane>

            <el-tab-pane label="动态">
              <div class="session-log">
                <div v-for="(line, i) in sessionLogs" :key="i">{{ line }}</div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </div>
    </div>

    <!-- 审计抽屉 -->
    <el-drawer v-model="audit.visible" title="会话审计记录" size="520px">
      <el-table :data="audit.rows" size="small" empty-text="暂无记录">
        <el-table-column prop="action" label="动作" width="130" />
        <el-table-column prop="detail" label="详情" min-width="180" show-overflow-tooltip />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="audit.current"
        :page-size="20"
        :total="audit.total"
        layout="prev, pager, next"
        style="margin-top: 8px; justify-content: flex-end"
        @current-change="loadAudit"
      />
    </el-drawer>

    <!-- 邀请弹窗 -->
    <el-dialog v-model="inviteForm.visible" title="发起远程控制" width="380px">
      <p>目标设备：{{ inviteForm.deviceName }}</p>
      <el-radio-group v-model="inviteForm.permission">
        <el-radio value="operate">请求完全控制（键鼠 + 文件 + 系统）</el-radio>
        <el-radio value="readonly">仅请求观看屏幕</el-radio>
      </el-radio-group>
      <p class="dialog-hint">对方在其 Agent 弹窗中可再次降档，最终权限以授权为准。</p>
      <template #footer>
        <el-button @click="inviteForm.visible = false">取消</el-button>
        <el-button type="primary" @click="doInvite">发送邀请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.remote-page {
  height: 100%;
  overflow: auto;
  padding: 12px;
  box-sizing: border-box;
}
.remote-entry {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 1000px;
  margin: 0 auto;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.local-code {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: var(--el-color-success-light-9);
  border-radius: 4px;
}
.local-code__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.local-code__name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.code-invite__label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.device-code {
  font-family: var(--el-font-family-mono, monospace);
  font-weight: 600;
  letter-spacing: 1px;
  color: var(--el-color-primary);
  cursor: pointer;
}
.device-code--none {
  color: var(--el-text-color-placeholder);
}
.code-invite {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.code-invite__input {
  width: 210px;
}
.invite-waiting {
  padding: 8px 12px;
  margin-bottom: 8px;
  background: var(--el-color-primary-light-9);
  border-radius: 4px;
  color: var(--el-color-primary);
}
.remote-work {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 560px;
  gap: 8px;
}
.remote-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
.remote-toolbar__title {
  font-weight: 600;
}
.remote-toolbar__spacer {
  flex: 1;
}
.toolbar-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.toolbar-slider {
  width: 110px;
}
.toolbar-select {
  width: 88px;
}
.remote-body {
  flex: 1;
  display: flex;
  gap: 8px;
  min-height: 0;
}
.remote-stage {
  position: relative;
  flex: 1;
  background: #17181c;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  outline: none;
  overflow: hidden;
  min-width: 0;
}
.remote-canvas {
  max-width: 100%;
  max-height: 100%;
  cursor: crosshair;
}
.remote-stage__hint {
  position: absolute;
  color: #9aa0a6;
  font-size: 13px;
  pointer-events: none;
}
.remote-side {
  width: 360px;
  flex-shrink: 0;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  overflow: hidden;
}
.remote-side__tabs {
  height: 100%;
  padding: 0 10px;
  box-sizing: border-box;
}
.remote-side__tabs :deep(.el-tabs__content) {
  height: calc(100% - 40px);
}
.remote-side__tabs :deep(.el-tab-pane) {
  height: 100%;
}
.panel-toolbar {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 6px;
}
.path-select {
  flex: 1;
  min-width: 120px;
}
.link {
  color: var(--el-color-primary);
  cursor: pointer;
}
.transfer-list {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.transfer-item {
  line-height: 20px;
}
.exec-output {
  height: 160px;
  overflow: auto;
  background: #17181c;
  color: #9fd59f;
  font-size: 12px;
  padding: 8px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.session-log {
  font-size: 12px;
  line-height: 22px;
  color: var(--el-text-color-regular);
  height: 100%;
  overflow: auto;
}
.dialog-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 8px;
}
@media (max-width: 900px) {
  .remote-body {
    flex-direction: column;
  }
  .remote-side {
    width: 100%;
    height: 320px;
  }
}
</style>
