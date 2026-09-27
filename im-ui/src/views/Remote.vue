<script setup>
/**
 * 远程控制（控制端）。
 *
 * 页面分两个形态：无会话时显示设备清单 + 我的会话历史；
 * 会话进行中切换成「画面 + 右侧工具面板」的全宽工作区。
 *
 * 授权链路：invite → 被控端弹窗 → 轮询 detail 拿 ticket/aesKey → 建数据面 WS。
 * 屏幕渲染统一走「离屏底图 + rAF 原子贴屏」：整帧刷底图、脏块按坐标并进底图，
 * 与 Agent 端 64x64 网格协议对应；两种帧可以混着来（自适应模式正是这么发的）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
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
import { DirectChannel } from '@/utils/directChannel'
import { AvcDecoder } from '@/utils/avc'
import { getToken } from '@/utils/token'
import { openFilePicker } from '@/utils/picker'

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
/* 直连链路：与中继并存而非替换——服务端发起的帧（会话关闭/错误）与发往服务端的
   direct-stats 永远走中继，被控端则是逐消息选路（直连通了就发直连），
   所以两条链路都常开，业务发送口按 link() 实时挑，用户感知不到切换。 */
const direct = ref(null)
const directInfo = ref(null) // 服务端 session-start/detail 下发的 direct 节点（含 token）
const directCandidates = ref(null) // 被控端上报的可连地址（lan/tcpPort/udpPort/公网映射）
const linkPath = ref('relay') // relay | tcp（局域网）| udp（打洞）
/* 工具栏两个开关：连接方式用于打洞异常时快速排除变量，编码用于绕开解不动的显卡 */
const directMode = ref('auto') // auto=尽力直连 relay=强制中继 lan=仅局域网
const codecWanted = ref('auto') // auto=优先 H.264 jpeg=强制 JPEG
const codecActual = ref('') // 被控端 screen-codec 回报：h264 / jpeg
const codecNote = ref('') // 编码器名或回落原因
const LINK_TEXT = { relay: '中继', tcp: '局域网直连', udp: 'UDP 直连' }
let directOpening = false
const screenOn = ref(false)
const quality = ref(75)
const fps = ref(20)
const bytes = ref(0)
const sessionLogs = ref([])
const canvasRef = ref(null)

/* 推流模式：auto=自适应（默认，小变化发脏块、大变化发整帧）tile=纯脏块 full=纯整帧。
   取值直接作为 screen-start 的 full 参数下发（0/1/2），与 Agent 端同一套语义。 */
const screenMode = ref('auto')
const MODE_PARAM = { tile: 0, full: 1, auto: 2 }

/* 会话流量：ws 层按链路真实字节累加，这里每秒取一次快照做实时展示 */
const traffic = ref({ rx: 0, tx: 0, seconds: 0 })
let trafficTimer = null
/** 中继与直连的字节合并展示：直连时服务器看到的为 0，只有本端计数能反映真实带宽 */
function combinedStats() {
  const relay = socket.value ? socket.value.stats() : null
  const lan = direct.value && direct.value.currentPath ? direct.value.stats() : null
  if (!relay) {
    return lan || { rx: 0, tx: 0, seconds: 0 }
  }
  if (!lan) {
    return relay
  }
  return {
    rx: relay.rx + lan.rx,
    tx: relay.tx + lan.tx,
    seconds: Math.max(relay.seconds, lan.seconds)
  }
}

async function openSession(detail, deviceName) {
  const aesKey = detail.aesKey || ''
  const sock = new RemoteControlSocket({
    sessionId: detail.sessionId,
    ticket: detail.ticket,
    satoken: getToken(),
    aesKeyB64: aesKey,
    onEnvelope: handleEnvelope,
    onBinaryFrame: handleBinaryFrame,
    onClose: () => {
      if (session.value) {
        pushLog('连接已断开，会话结束')
        teardownSession('连接断开')
      }
    }
  })
  session.value = {
    sessionId: detail.sessionId,
    permission: detail.permission,
    deviceName: deviceName || detail.deviceName || '设备',
    aesKey
  }
  socket.value = sock
  // REST 轮询也能拿到 direct 节点：session-start 那一帧万一丢了，这里就是唯一凭据
  directInfo.value = detail.direct || null
  avc = new AvcDecoder({ onFrame: drawH264Frame, onFail: onAvcFail })
  startTraffic()
  try {
    await sock.connect()
    pushLog(`已连接中继，权限：${detail.permission === 'operate' ? '可操作' : '只读'}`)
    loadDevices()
  } catch (e) {
    const reason = e.message || '数据通道建立失败'
    // 浏览器（尤其手机）对不受信任的自签证书：页面能点「继续访问」绕过，wss 不能，
    // 于是表现为“授权成功但一闪即退”。这个分辨不开的错误直接附上对应处置法。
    const browser = globalThis.location?.protocol === 'https:' && !globalThis.window?.__IM_DIRECT__?.isDesktop
    ElMessage.error(browser ? `${reason}；手机需将 certs/ca.pem 装为 CA 证书并开启完全信任，或改走 npm run dev:lan` : reason)
    pushLog(`数据通道未建立：${reason}`)
    teardownSession('数据通道未建立')
  }
}

/** 当前该用哪条链路发业务帧：直连就绪就用它，否则中继 */
function link() {
  const lan = direct.value
  return lan && lan.ready ? lan : socket.value
}

/** 业务发送口：ready 与否都按中继的语义返回（未就绪返回 null / 抛「连接未就绪」） */
function sendEnvelope(type, data) {
  return link()?.sendEnvelope(type, data) ?? null
}

function request(type, data, timeoutMs) {
  const target = link()
  if (!target) {
    return Promise.reject(new Error('连接未就绪'))
  }
  return target.request(type, data, timeoutMs)
}

/** 上传块：直连侧写不进（刚断链/窗口满）就立刻改投中继，文件不能断在半截 */
async function sendFileFrame(meta, payload) {
  const lan = direct.value
  if (lan && lan.ready && (await lan.sendBinaryFrame(FRAME_FILE, meta, payload))) {
    return
  }
  const relay = socket.value
  if (!relay || !relay.ready) {
    throw new Error('连接已断开')
  }
  await relay.sendBinaryFrame(FRAME_FILE, meta, payload)
}

/**
 * 按阶梯尝试直连：局域网 WebSocket → Electron UDP 打洞。
 *
 * 只在拿到被控端候选地址后才有意义（地址只能经中继送过来），
 * 失败不重试：对称 NAT、防火墙拒连这类原因不会因为再试一次而变通，
 * 需要重试时工具栏「连接方式」切一下即可。
 */
async function tryDirect() {
  const info = directInfo.value
  const candidates = directCandidates.value
  if (!info || !info.enabled || !candidates || directOpening || !session.value) {
    return
  }
  if (directMode.value === 'relay' || (direct.value && direct.value.currentPath)) {
    return
  }
  directOpening = true
  const channel = new DirectChannel({
    sessionId: session.value.sessionId,
    aesKeyB64: session.value.aesKey,
    // 「仅局域网」就是把 UDP 档关掉：同一套握手与选路，少一个分支少一处分裂
    direct: directMode.value === 'lan' ? { ...info, udp: false } : info,
    candidates,
    onEnvelope: handleEnvelope,
    onBinaryFrame: handleBinaryFrame,
    onPath: (path) => {
      linkPath.value = path || 'relay'
    },
    onFallback: (reason) => noteDirectFailure(reason)
  })
  try {
    const path = await channel.connect()
    if (!path) {
      channel.close()
      return
    }
    direct.value = channel
    linkPath.value = path
    pushLog(`直连已建立（${LINK_TEXT[path] || path}），画面与输入不再过服务器`)
  } catch (e) {
    noteDirectFailure(e && e.message ? e.message : String(e))
  } finally {
    directOpening = false
  }
}

function noteDirectFailure(reason) {
  linkPath.value = 'relay'
  pushLog(`直连未生效：${reason || 'unknown'}（继续走中继）`)
}

/** 拆掉直连链路：中继不受影响，业务帧自然回到服务器上 */
function stopDirect() {
  const channel = direct.value
  direct.value = null
  linkPath.value = 'relay'
  if (channel) {
    channel.close()
  }
}

function applyDirectMode() {
  stopDirect()
  tryDirect()
}

/** 链路标签：中继/局域网直连/UDP 直连，直连时提示服务器看到的字节为 0 */
const linkLine = computed(() => {
  const codec = codecActual.value === 'h264' ? 'H.264' : codecActual.value === 'jpeg' ? 'JPEG' : ''
  const parts = [`链路 ${LINK_TEXT[linkPath.value] || '中继'}`]
  if (codec) {
    parts.push(`编码 ${codec}${codecNote.value ? `(${codecNote.value})` : ''}`)
  }
  return parts.join(' · ')
})

function pushLog(text) {
  sessionLogs.value.unshift(`${new Date().toLocaleTimeString()}　${text}`)
  if (sessionLogs.value.length > 50) {
    sessionLogs.value.pop()
  }
}

/* ==================== 会话流量 ==================== */

function startTraffic() {
  stopTraffic()
  traffic.value = { rx: 0, tx: 0, seconds: 0 }
  let ticks = 0
  trafficTimer = setInterval(() => {
    traffic.value = combinedStats()
    // 直连字节不过服务器，落库总量全靠两端自报；30 秒一次与 Agent 同频，
    // 频繁了只会把审计表刷满同样的数字
    if (++ticks % 30 === 0) {
      reportDirectStats()
    }
  }, 1000)
}

/** 把本端直连字节报给服务端（必定走中继：这帧的用途就是告诉服务器直连通没通） */
function reportDirectStats() {
  const channel = direct.value
  if (!channel || !socket.value || !socket.value.ready) {
    return
  }
  const stats = channel.stats()
  if (!stats.rx && !stats.tx) {
    return
  }
  socket.value.sendEnvelope('direct-stats', { down: stats.rx, up: stats.tx, path: channel.currentPath })
}

function stopTraffic() {
  if (trafficTimer) {
    clearInterval(trafficTimer)
    trafficTimer = null
  }
}

function formatBytes(value) {
  const v = Number(value) || 0
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(2)} MB`
  return `${(v / 1024 / 1024 / 1024).toFixed(2)} GB`
}

function formatDuration(seconds) {
  const s = Math.max(0, Math.round(Number(seconds) || 0))
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}`
}

/** 平均码率：bytesPerSecond → Mbps（按 10^6 而非 2^20，与云厂商带宽口径一致，能直接对规格表） */
function formatMbps(bytesPerSecond) {
  const m = ((Number(bytesPerSecond) || 0) * 8) / 1000 / 1000
  return m >= 1 ? `${m.toFixed(1)} Mbps` : `${(m * 1000).toFixed(0)} kbps`
}

/** 工具栏一屏内看得完的简讯：下行/上行/合计 + 时长 + 平均下行码率 */
const trafficLine = computed(() => {
  const t = traffic.value
  if (!t.rx && !t.tx) {
    return ''
  }
  const rate = t.seconds ? formatMbps(t.rx / t.seconds) : '—'
  const via = linkPath.value === 'relay' ? '' : ` · ${LINK_TEXT[linkPath.value]}`
  return `↓${formatBytes(t.rx)} ↑${formatBytes(t.tx)} · 计${formatBytes(t.rx + t.tx)} · ${formatDuration(t.seconds)} · ${rate}${via}`
})

/**
 * 会话结束汇总。用常驻通知（duration 0）而不是 toast：验收带宽时就是要把它住在那儿
 * 让人抄数字；不阻断操作也不强迫点确认，路由已切回设备列表也不会报错。
 * deviceName 是被控端自填的字符串，拼 HTML 前必须转义。
 */
function reportTraffic(summary, deviceName, reason) {
  if (!summary || (!summary.rx && !summary.tx)) {
    return
  }
  const total = summary.rx + summary.tx
  const esc = (s) =>
    String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
  const perHour = summary.seconds > 30 ? formatBytes((total * 3600) / summary.seconds) : ''
  // 链路取收尾前抓的快照：那时 linkPath 已被 teardown 复位成中继，不存下来就永远报「中继」
  const path = summary.path || linkPath.value
  const codec = summary.codec || codecActual.value
  ElNotification({
    title: `会话结束 · ${esc(deviceName || '设备')}`,
    dangerouslyUseHTMLString: true,
    message:
      `时长 ${formatDuration(summary.seconds)}（${esc(reason || '已关闭')}）<br>` +
      `接收 ${formatBytes(summary.rx)}　发送 ${formatBytes(summary.tx)}　合计 ${formatBytes(total)}<br>` +
      `平均下行 ${summary.seconds ? formatMbps(summary.rx / summary.seconds) : '—'}` +
      (perHour ? `<br>按此速率 1 小时约 ${perHour}` : '') +
      `<br>链路 ${esc(LINK_TEXT[path] || '中继')}　编码 ${esc(codec || '—')}` +
      (path !== 'relay'
        ? '<br>本会话为直连，服务器看到的字节仅中继部分，实测带宽以本通知为准'
        : ''),
    type: 'info',
    duration: 0
  })
}

function handleEnvelope(env) {
  const data = env.data || {}
  switch (env.type) {
    case 'session-start':
      // 直连参数与会话参数同一帧下发，token 不另开一条帧只会多一个丢帧重来的机会
      if (data.direct && data.direct.enabled) {
        directInfo.value = data.direct
      }
      pushLog('会话已绑定，可以开启画面')
      startScreen()
      break
    case 'direct-candidates':
      // 被控端可能发两次（先局域网、拿到公网映射再补一次），后者覆盖前者
      directCandidates.value = data
      if (linkPath.value === 'relay') {
        tryDirect()
      }
      break
    case 'screen-codec':
      codecActual.value = data.codec || ''
      codecNote.value = data.info || ''
      pushLog(
        data.codec === 'h264'
          ? `硬编推流已开启：${data.info || 'h264'} @${data.fps || 0}fps`
          : `画面编码 JPEG${data.info ? `（${data.info}）` : ''}`
      )
      if (data.codec !== 'h264' && codecWanted.value !== 'jpeg') {
        // 被控端不具备硬编条件，记住它的选择别再每帧重复回落
        codecWanted.value = 'jpeg'
      }
      break
    case 'session-closed':
      pushLog(`会话结束：${data.reason || '对方关闭'}`)
      teardownSession(data.reason || '对方关闭')
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

async function teardownSession(reason) {
  // 幂等：握手失败时 onclose 与 connect() 的 catch 会各进来一次，第二次直接返回，
  // 否则会往审计里写两条 0 字节的收尾、并把设备/会话列表刷两遍
  if (!session.value && !socket.value) {
    return
  }
  // 收尾前先把统计取出来：socket 一 close 就再也读不到本轮会话的字节了
  const summary = combinedStats()
  const deviceName = session.value?.deviceName
  const path = linkPath.value
  const codec = codecActual.value
  stopTraffic()
  stopDirect()
  // 全屏层是盖在整页上的：会话一结束不退出，回到设备列表时会被一层黑屏顶掉（只能刷新页面）
  exitPortrait()
  if (avc) {
    avc.close()
    avc = null
  }
  if (socket.value) {
    socket.value.close()
    socket.value = null
  }
  session.value = null
  screenOn.value = false
  directInfo.value = null
  directCandidates.value = null
  codecActual.value = ''
  codecNote.value = ''
  linkPath.value = 'relay'
  clearTransfers()
  loadDevices()
  loadSessions()
  reportTraffic({ ...summary, path, codec }, deviceName, reason)
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

/* 双缓冲渲染：脏块先合成到离屏画布，再用 requestAnimationFrame 一次性贴到可见画布。
   旧实现把每个 64x64 脏块在到达瞬间直绘到可见画布、且用 promise 链逐块串行解码，
   于是一次真实变化会看到块「一块块蹦出来」（割裂感），突发帧堆积时还要「刷好几次才追上」。
   现在：一帧内到达的所有块都并进离屏缓冲，只在 rAF 里原子呈现一次，观感接近商用远控。 */
const backCanvas = document.createElement('canvas')
const backCtx = backCanvas.getContext('2d')
let blitScheduled = false
let decodeQueue = []
let activeDecoders = 0
let frameGeneration = 0
const DECODE_CONCURRENCY = 4
/* H.264 解码器：每个会话新建一个实例（解帧回调要回到本会话的背缓冲），
   会话收尾时 close。 JPEG 路走的是 createImageBitmap，两者互不干扰。 */
let avc = null

function scheduleBlit() {
  if (blitScheduled) {
    return
  }
  blitScheduled = true
  requestAnimationFrame(() => {
    blitScheduled = false
    const canvas = canvasRef.value
    if (!canvas || !backCanvas.width || !backCanvas.height) {
      return
    }
    if (canvas.width !== backCanvas.width) {
      canvas.width = backCanvas.width
    }
    if (canvas.height !== backCanvas.height) {
      canvas.height = backCanvas.height
    }
    canvas.getContext('2d').drawImage(backCanvas, 0, 0)
  })
}

function handleBinaryFrame({ frameType, meta, payload }) {
  if (frameType === FRAME_SCREEN) {
    enqueueScreen(meta, payload)
  } else if (frameType === FRAME_FILE) {
    receiveChunk(meta, payload)
  }
}

/* 整帧模式（meta.full=1）：被控端发来的是一整屏完整画面。用「最新帧优先」单路解码——
   解码慢于帧到达时自动丢弃中间帧、只解最新的一帧，既不积压延迟、也永远不会把正在解码的帧
   作废（这正是旧 key 逻辑每帧 frameGeneration++ 会闪白丢帧的原因）。
   脏块（meta.full=0）：走并发解码 + 坐标贴图。两种帧都合进同一张离屏底图再贴屏，
   自适应模式下一整帧 + 若干脏块混发时，底图才是它们共同的合成结果，不会出现两套渲染各自为政。 */
let latestFullFrame = null
let fullDecoding = false
/* 到达序号与「最后一次完整帧的序号」：自适应模式下一整帧与脏块混发，
   两者又分别在异步解码，光靠「清队列」无法说明谁更新——
   比整帧早到的块被整帧涵盖（该丢），比整帧晚到的块必须活下来（不然那块区域
   会一直停在旧画面上，直到它下次变化才被修正）。用单调序号判定就能两全。 */
let arrivalSeq = 0
let fullDrawnSeq = 0

/** 载荷编码格式由 Agent 逐帧标注：平色/文字块走 PNG 无损，照片类走 JPEG */
function mimeOf(meta) {
  return meta && meta.fmt === 'png' ? 'image/png' : 'image/jpeg'
}

function enqueueScreen(meta, payload) {
  if (!payload || !payload.length) {
    return
  }
  if (meta.codec === 'h264' || meta.fmt === 'h264') {
    if (!avc) {
      return
    }
    // 一个访问单元就是一整屏：没脏块概念，但序号照进，并把它记为最新完整帧，
    // 才能让在途中还没解完的 JPEG 脏块不会盖回已经刷新的 H.264 画面上
    fullDrawnSeq = ++arrivalSeq
    avc.push(meta, payload)
    return
  }
  if (meta.full) {
    // 完整帧：只保留最新的一帧待解码，晚到的旧帧直接覆盖丢弃（latest-wins）
    const seq = ++arrivalSeq
    // 比这一整帧早到的脏块已被它涵盖，从队列里清掉省得白解码；
    // 已在解码中的，由 decodeOne 里那个 fullDrawnSeq 事后检查兜住
    decodeQueue.length = 0
    latestFullFrame = { meta, payload, seq }
    if (!fullDecoding) {
      pumpFullFrame()
    }
    return
  }
  if (meta.key) {
    // 脏块序列起始的关键帧自带整屏，此前排队的脏块全部作废，清空避免旧块覆盖新帧
    decodeQueue.length = 0
    frameGeneration++
    backCanvas.width = meta.screenW || meta.w
    backCanvas.height = meta.screenH || meta.h
  }
  decodeQueue.push({ meta, payload, gen: frameGeneration, seq: ++arrivalSeq })
  pumpDecoders()
}

/* 整帧解码循环：始终取最新帧解码并合进底图，解完若又有新帧就继续，无帧则退出等待下次入队 */
async function pumpFullFrame() {
  while (latestFullFrame) {
    const item = latestFullFrame
    latestFullFrame = null
    fullDecoding = true
    try {
      const bitmap = await createImageBitmap(new Blob([item.payload], { type: mimeOf(item.meta) }))
      drawFullFrame(bitmap, item.meta, item.seq)
      bitmap.close()
    } catch {
      // 单帧解码失败跳过，下一帧是完整画面会立即覆盖，不会残留花屏
    } finally {
      fullDecoding = false
    }
  }
}

function drawFullFrame(bitmap, meta, seq) {
  const w = meta.screenW || meta.w
  const h = meta.screenH || meta.h
  if (!w || !h) {
    return
  }
  // 尺寸变化就要重设底图（重设会清空内容），此时旧脏块已无意义，一律作废
  if (backCanvas.width !== w || backCanvas.height !== h) {
    backCanvas.width = w
    backCanvas.height = h
  }
  // 整帧覆盖全图，把底图重建到这一帧的状态；记下它的到达序号，
  // 比它更早的脏块就再也不会往回盖（晚于它的块序号更大，不受影响）
  backCtx.drawImage(bitmap, 0, 0, w, h)
  fullDrawnSeq = seq
  scheduleBlit()
}

/** H.264 解出来就是一整屏：尺寸取 displayWidth/Height（裁剪窗与编码尺寸不一致时它以 display 为准） */
function drawH264Frame(frame) {
  try {
    const w = frame.displayWidth || frame.codedWidth
    const h = frame.displayHeight || frame.codedHeight
    if (!w || !h) {
      return
    }
    if (backCanvas.width !== w || backCanvas.height !== h) {
      backCanvas.width = w
      backCanvas.height = h
      // 分辨率变了（切显示器/改帧率重开 ffmpeg）：旧脏块坐标全废，只能作废重等关键帧
      frameGeneration++
    }
    backCtx.drawImage(frame, 0, 0, w, h)
    scheduleBlit()
  } finally {
    // VideoFrame 不显式 close 很快就会把解码器的帧池耗光，表现为「看几秒就定住」
    try {
      frame.close()
    } catch {
      // 已被回收
    }
  }
}

/** 解不动就换回 JPEG：没硬件解码、显卡驱动太老、连续丢包都可能，不能把画面整条搞死 */
function onAvcFail(reason) {
  pushLog(`H.264 解码失败，回落 JPEG：${reason || 'unknown'}`)
  codecWanted.value = 'jpeg'
  if (session.value && screenOn.value) {
    startScreen()
  }
}

function pumpDecoders() {
  while (activeDecoders < DECODE_CONCURRENCY && decodeQueue.length) {
    activeDecoders++
    decodeOne(decodeQueue.shift())
  }
}

async function decodeOne(item) {
  try {
    if (item.gen !== frameGeneration) {
      return
    }
    const bitmap = await createImageBitmap(new Blob([item.payload], { type: mimeOf(item.meta) }))
    // 解码期间可能已来新关键帧，过期块直接丢弃；
    // 比最后一整帧还旧的块同样丢弃，否则会把新画面退回去
    if (item.gen === frameGeneration && item.seq > fullDrawnSeq) {
      backCtx.drawImage(bitmap, item.meta.x, item.meta.y, item.meta.w, item.meta.h)
      scheduleBlit()
    }
    bitmap.close()
  } catch {
    // 单块解码失败不影响后续，关键帧会自愈
  } finally {
    activeDecoders--
    pumpDecoders()
  }
}

function startScreen() {
  sendEnvelope('screen-start', {
    fps: fps.value,
    quality: quality.value,
    monitor: monitorIndex.value,
    // 推流模式：0=纯脏块 1=纯整帧 2=自适应（默认）。自适应把脏块的带宽优势和
    // 整帧的观感各拿到一半：变化面积小就只发脏块，拖窗口/切屏这类全屏变化直接发整帧。
    full: MODE_PARAM[screenMode.value] ?? 2,
    /* 编码偏好：h264 时被控端自抓屏走硬编，不具备条件（无 ffmpeg/无硬件编码器/
       非 Windows）它自己回 jpeg 并用 screen-codec 帧告知原因，所以这里「auto」
       不是猜测而是直接要求，失败信息反而更完整。 */
    codec: codecParam()
  })
  screenOn.value = true
}

/** 本端能不能解 H.264：WebCodecs 与 WebCrypto 同样只在 secure context 存在 */
function codecParam() {
  if (codecWanted.value === 'jpeg') {
    return 'jpeg'
  }
  return avc && avc.supported ? 'h264' : 'jpeg'
}

function stopScreen() {
  sendEnvelope('screen-stop', {})
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
  // 切屏会让背缓冲尺寸突变：先清 H.264 解码器状态，等新一路的关键帧
  avc && avc.reset()
  request('monitor-switch', { index }).catch(() => {})
}

/* ==================== 输入采集 ==================== */

let lastMoveSent = 0

/** 客户端坐标 → 归一化 0~10000（canvas 以 CSS 缩放显示，按比例换算）。鼠标与触摸共用 */
function pointFromClient(clientX, clientY) {
  const canvas = canvasRef.value
  if (!canvas) {
    return null
  }
  const rect = canvas.getBoundingClientRect()
  let nx
  let ny
  if (rotateFs.value) {
    // CSS 伪全屏把画布顺时针旋转了 90°：画布局部 +x 轴指向屏幕下方、+y 轴指向屏幕左方，
    // getBoundingClientRect 拿到的是旋转后的屏幕包围盒，需按此反向换算回画布坐标，否则点击错位。
    nx = (clientY - rect.top) / rect.height
    ny = 1 - (clientX - rect.left) / rect.width
  } else {
    nx = (clientX - rect.left) / rect.width
    ny = (clientY - rect.top) / rect.height
  }
  return { x: clamp(Math.round(nx * 10000)), y: clamp(Math.round(ny * 10000)) }
}

function normalized(event) {
  return pointFromClient(event.clientX, event.clientY)
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
  point && sendEnvelope('mouse', { action: 'move', ...point })
}

function onMouseDown(event) {
  if (!canInput()) {
    return
  }
  event.preventDefault()
  stageRef.value?.focus()
  const point = normalized(event)
  point && sendEnvelope('mouse', { action: 'down', button: buttonName(event), ...point })
}

function onMouseUp(event) {
  if (!canInput()) {
    return
  }
  const point = normalized(event)
  point && sendEnvelope('mouse', { action: 'up', button: buttonName(event), ...point })
}

function onWheel(event) {
  if (!canInput()) {
    return
  }
  const point = normalized(event)
  point && sendEnvelope('mouse', { action: 'wheel', deltaY: -event.deltaY, ...point })
}

/* 触摸输入：手机没有鼠标事件，单指按下/移动/抬起映射为左键 down/move/up，
   双指竖向滑动映射为滚轮。preventDefault 抑制浏览器把触摸再合成一份鼠标事件（避免双发）。 */
let lastTouchScrollY = 0

function onTouchStart(event) {
  if (!canInput()) {
    return
  }
  event.preventDefault()
  stageRef.value?.focus()
  if (event.touches.length === 1) {
    const t = event.touches[0]
    const point = pointFromClient(t.clientX, t.clientY)
    point && sendEnvelope('mouse', { action: 'down', button: 'left', ...point })
  } else if (event.touches.length === 2) {
    lastTouchScrollY = event.touches[0].clientY
  }
}

function onTouchMove(event) {
  if (!canInput() || !screenOn.value) {
    return
  }
  event.preventDefault()
  if (event.touches.length === 1) {
    const now = Date.now()
    if (now - lastMoveSent < 40) {
      return
    }
    lastMoveSent = now
    const t = event.touches[0]
    const point = pointFromClient(t.clientX, t.clientY)
    point && sendEnvelope('mouse', { action: 'move', ...point })
  } else if (event.touches.length === 2) {
    const y = event.touches[0].clientY
    const delta = lastTouchScrollY - y
    lastTouchScrollY = y
    if (Math.abs(delta) < 4) {
      return
    }
    const point = pointFromClient(event.touches[0].clientX, y)
    point && sendEnvelope('mouse', { action: 'wheel', deltaY: delta * 3, ...point })
  }
}

function onTouchEnd(event) {
  if (!canInput()) {
    return
  }
  event.preventDefault()
  const t = event.changedTouches[0]
  if (!t) {
    return
  }
  const point = pointFromClient(t.clientX, t.clientY)
  point && sendEnvelope('mouse', { action: 'up', button: 'left', ...point })
}

/* 横屏全屏：手机竖屏看电脑画面又小又扁，一键铺满屏幕操控更顺手，再一键回竖屏。
   关键：原生 Fullscreen API 与 screen.orientation.lock 都只在「安全上下文」(https/localhost)
   可用；手机用局域网 http://IP:5173 访问时 document.fullscreenEnabled=false，
   requestFullscreen 会静默失败——这正是之前「点了没反应」的原因。
   因此 http 下退化为 CSS 伪全屏（fixed 铺满视口）。
   而「进了全屏」与「页面真的转成横屏」是两件事：微信/QQ/UC 这类内置浏览器 requestFullscreen 会成功，
   但系统不把页面转横、orientation.lock 一律 reject，表现就是「点了全屏、画面还是竖着一条」。
   所以统一按「已全屏 且 视口仍是竖的」补一层 CSS 顺时针旋转，浏览器真转横了就立刻不叠加。 */
const isFullscreen = ref(false) // 原生全屏状态（由 fullscreenchange 同步）
const pseudoFs = ref(false) // http 下的 CSS 伪全屏状态
const isFs = computed(() => isFullscreen.value || pseudoFs.value)
/* 全屏且视口仍是竖的 → 顺时针旋转 90° 铺满。不再依赖 @media/matchMedia 的方向检测
   （部分手机浏览器根本不改视口方向），也不限定只有伪全屏才转：原生全屏转不动时同样要转。
   rotateFs 与旋转同条件，供 pointFromClient 做坐标补偿。 */
const portraitVp = ref(false) // 当前视口是竖的（innerHeight > innerWidth）
function syncPortraitViewport() {
  portraitVp.value = window.innerHeight > window.innerWidth
}
const rotateFs = computed(() => isFs.value && portraitVp.value)
// 旋转样式：宽高对调 + rotate(90deg) 顺时针铺满；dvh 排在 vh 之后，老内核认不了就自动退回 vh
const stageStyle = computed(() =>
  rotateFs.value
    ? 'width:100vh;width:100dvh;height:100vw;transform-origin:0 0;transform:rotate(90deg) translateY(-100%)'
    : null
)

async function enterLandscapeFullscreen() {
  const el = stageRef.value
  // X5 / IE 系内核只给带前缀的版本，拿不到方法就直接走伪全屏
  const request = el?.requestFullscreen || el?.webkitRequestFullscreen || el?.msRequestFullscreen
  let native = false
  if (request) {
    try {
      await request.call(el)
      // 部分浏览器即便在不安全上下文也不报错，需用 fullscreenElement 兜底确认是否真进了全屏
      native = !!(document.fullscreenElement || document.webkitFullscreenElement)
    } catch {
      native = false
    }
    if (native) {
      try {
        await screen.orientation?.lock?.('landscape')
      } catch {
        // iOS 与几乎所有国产内置浏览器都拒绝方向锁，交由下面的 CSS 旋转兜住
      }
    }
  }
  if (!native) {
    // http 局域网 / 不支持原生全屏：CSS 伪全屏铺满，旋转与否由 rotateFs 按视口方向决定
    pseudoFs.value = true
  }
  // 内置浏览器不一定补发 fullscreenchange，这里直接按实测结果同步，否则按钮与旋转状态会卡在旧值
  isFullscreen.value = native
  // 全屏切换后视口尺寸要稍后才稳定：延一拍再判方向，否则会多转或漏转一次
  setTimeout(syncPortraitViewport, 320)
}

async function exitPortrait() {
  pseudoFs.value = false
  isFullscreen.value = false
  try {
    screen.orientation?.unlock?.()
  } catch {
    // 不支持则忽略
  }
  if (document.fullscreenElement || document.webkitFullscreenElement) {
    try {
      await (document.exitFullscreen?.() || document.webkitExitFullscreen?.())
    } catch {
      // 已不在全屏则忽略
    }
  }
  setTimeout(syncPortraitViewport, 120)
}

function onFullscreenChange() {
  isFullscreen.value = !!(document.fullscreenElement || document.webkitFullscreenElement)
  syncPortraitViewport()
}

function canInput() {
  return session.value && session.value.permission === 'operate' && link()?.ready
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
  sendEnvelope('key', { action: 'press', keyCode: javaKeyCode(event.keyCode) })
}

function onKeyUp(event) {
  if (!canInput() || event.target.tagName === 'INPUT' || event.target.tagName === 'TEXTAREA') {
    return
  }
  if (PASS_THROUGH_KEYS.has(event.key)) {
    return
  }
  event.preventDefault()
  sendEnvelope('key', { action: 'release', keyCode: javaKeyCode(event.keyCode) })
}

/* ==================== 文件面板 ==================== */

const filePath = ref('')
const fileItems = ref([])
const fileRoots = ref([])
const fileLoading = ref(false)
const transfers = reactive({})
const uploadInput = ref(null)

async function listDir(path) {
  if (!link()?.ready) {
    return
  }
  fileLoading.value = true
  try {
    const result = await request('list-dir', path ? { path } : {}, 30000)
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
    await request('file-get', { transferId, path: row.path }, 120000)
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

function pickUploadFile() {
  openFilePicker(uploadInput.value)
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
  await request('file-put', { transferId, name: file.name, dir: filePath.value, size: file.size }, 10000)
  for (let i = 0; i < total; i++) {
    const slice = new Uint8Array(await file.slice(i * CHUNK, (i + 1) * CHUNK).arrayBuffer())
    await sendFileFrame({ transferId, name: file.name, index: i, total }, slice)
  }
  ElMessage.success(`已上传 ${file.name}`)
}

async function removeItem(row) {
  await ElMessageBox.confirm(`确定删除 ${row.path} ？${row.dir ? '（含目录内全部内容）' : ''}`, '高危操作', {
    type: 'warning'
  })
  try {
    await request('rm', { path: row.path }, 60000)
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
    await request('rename', { from: row.path, to: parent + value }, 15000)
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
    await request('mkdir', { path: joinPath(filePath.value, value) }, 15000)
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
    const result = await request('ps-list', {}, 15000)
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
    await request('ps-kill', { pid: Number(row.pid) }, 15000)
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
    const result = await request('exec', { cmd: execCmd.value }, 40000)
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
    await request('power', { action }, 15000)
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
    await request('clip-sync', { text }, 10000)
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
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('webkitfullscreenchange', onFullscreenChange)
  // 全屏旋转只看视口宽高，而转屏、地址栏收起、原生全屏进出都只反映在 resize 上
  syncPortraitViewport()
  window.addEventListener('resize', syncPortraitViewport)
  window.addEventListener('orientationchange', syncPortraitViewport)
})

onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('resize', syncPortraitViewport)
  window.removeEventListener('orientationchange', syncPortraitViewport)
  if (keydownHandler) {
    document.removeEventListener('keydown', keydownHandler)
    document.removeEventListener('keyup', keyupHandler)
  }
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange)
  decodeQueue.length = 0
  latestFullFrame = null
  stopTraffic()
  // 全屏是盖在整页上的固定层：会话结束不退出，设备列表就会被一层永久黑屏顶掉（只能刷新页面）
  exitPortrait()
  // 先补报一次直连字节再拆链：服务端落库的总量靠这一帧，路由切走不报就成了直连白跑
  reportDirectStats()
  stopDirect()
  if (avc) {
    avc.close()
    avc = null
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
          <el-option v-for="v in [10, 15, 20, 30]" :key="v" :label="`${v} fps`" :value="v" />
        </el-select>
        <el-tooltip
          effect="dark"
          placement="bottom"
          content="自适应：小变化只发脏块（文字区走 PNG 无损），拖窗口/切屏这类全屏变化才发整帧；最省流量=纯脏块，最清晰=纯整帧"
        >
          <span class="toolbar-field">
            <span class="toolbar-label">推流</span>
            <el-select v-model="screenMode" size="small" class="toolbar-select mode-select" @change="applyScreenParams">
              <el-option label="自适应" value="auto" />
              <el-option label="最省流量" value="tile" />
              <el-option label="最清晰" value="full" />
            </el-select>
          </span>
        </el-tooltip>
        <el-button size="small" @click="switchMonitor(0)">屏1</el-button>
        <el-button size="small" @click="switchMonitor(1)">屏2</el-button>
        <el-button size="small" @click="pushClipboard">发剪贴板</el-button>
        <el-divider direction="vertical" />
        <el-tooltip
          effect="dark"
          placement="bottom"
          content="自动=尽力直连（局域网/打洞），失败自动回落中继；强制中继用于打洞异常时排除变量；仅局域网=只试同网段 WebSocket，不碰 UDP"
        >
          <span class="toolbar-field">
            <span class="toolbar-label">链路</span>
            <el-select v-model="directMode" size="small" class="toolbar-select mode-select" @change="applyDirectMode">
              <el-option label="自动" value="auto" />
              <el-option label="强制中继" value="relay" />
              <el-option label="仅局域网" value="lan" />
            </el-select>
          </span>
        </el-tooltip>
        <el-tooltip
          effect="dark"
          placement="bottom"
          content="H.264 需被控端有 ffmpeg 与硬件编码器；不具备时它自己回落 JPEG 并用 screen-codec 帧告知原因，本端解不动也会连续失败后换回"
        >
          <span class="toolbar-field">
            <span class="toolbar-label">编码</span>
            <el-select v-model="codecWanted" size="small" class="toolbar-select mode-select" @change="applyScreenParams">
              <el-option label="H.264 优先" value="auto" />
              <el-option label="JPEG" value="jpeg" />
            </el-select>
          </span>
        </el-tooltip>
        <el-tag size="small" :type="linkPath === 'relay' ? 'info' : 'success'">{{ linkLine }}</el-tag>
        <span v-if="trafficLine" class="toolbar-traffic">{{ trafficLine }}</span>
        <div class="remote-toolbar__spacer" />
        <el-button size="small" type="danger" @click="endSession">结束会话 (Esc)</el-button>
      </div>

      <div class="remote-body">
        <div ref="stageRef" class="remote-stage" :class="{ 'is-pseudo-fs': pseudoFs }" :style="stageStyle" tabindex="0"
             @mousemove="onMouseMove" @mousedown="onMouseDown" @mouseup="onMouseUp" @wheel.prevent="onWheel"
             @touchstart="onTouchStart" @touchmove="onTouchMove" @touchend="onTouchEnd" @touchcancel="onTouchEnd"
             @contextmenu.prevent>
          <canvas ref="canvasRef" class="remote-canvas" />
          <div v-if="!screenOn" class="remote-stage__hint">画面未开启，点击工具栏「开始画面」</div>
          <!-- 全屏按钮浮在画面上：必须把它的触摸/鼠标事件全部 .stop 隔离，否则会冒泡到 .remote-stage
               被 onTouchEnd/onMouseUp 的 preventDefault 拦截——尤其 touchend 被 preventDefault 后浏览器
               不再合成 click，按钮就“点了没反应”（这正是之前手机点全屏无效的根因）。 -->
          <div class="stage-fs" @mousedown.stop @mouseup.stop
               @touchstart.stop @touchmove.stop @touchend.stop @touchcancel.stop>
            <el-button v-if="!isFs" size="small" type="primary" plain @click="enterLandscapeFullscreen">
              全屏
            </el-button>
            <el-button v-else size="small" type="info" plain @click="exitPortrait">
              退出全屏
            </el-button>
          </div>
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
                <el-button size="small" @click="pickUploadFile">上传文件</el-button>
                <input ref="uploadInput" type="file" multiple class="remote-upload-input" @change="uploadFiles" />
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
.mode-select {
  width: 96px;
}
.toolbar-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
/* 实时流量：等宽字防数字跳动把布局挤得抽跳，色调走次要信息不抢画面 */
.toolbar-traffic {
  padding: 2px 8px;
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  white-space: nowrap;
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
  /* 画面区绝对不能没：flex:1 的基准是 0，手机上一旦工具栏换行占高、侧栏定高，
     剩下的空间可以被压到 0——表现就是「流量在涨、画面区一点不剩」的黑屏错觉 */
  min-height: 200px;
  /* 手机触摸控制时禁止浏览器手势（下拉刷新/双指缩放/滚动），否则会吞掉 touchmove */
  touch-action: none;
}
.remote-stage:fullscreen {
  border-radius: 0;
}
/* 全屏（含伪全屏、含旋转）下画布一律按舞台本地盒百分比自适应 contain：
   旋转时本地盒是 100dvh×100vw，若用 vw/vh 视口单位会算错方向，必须用 100% 才不变形 */
.remote-stage:fullscreen .remote-canvas,
.remote-stage.is-pseudo-fs .remote-canvas {
  max-width: 100%;
  max-height: 100%;
}
/* http 局域网下的 CSS 伪全屏：铺满视口、盖住其余 UI（原生 Fullscreen API 在非安全上下文不可用） */
.remote-stage.is-pseudo-fs {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 3000;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  border-radius: 0;
}
/* 旋转由 stageStyle 内联样式驱动（@media/matchMedia 在部分手机浏览器不生效），
   .is-pseudo-fs 只负责铺满与层级，宽高/transform 由内联覆盖。 */
.remote-canvas {
  max-width: 100%;
  max-height: 100%;
  cursor: crosshair;
}
.stage-fs {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 5;
  display: flex;
  gap: 6px;
}
/* 同聊天页：display:none 的 input 在部分手机浏览器上 click() 无效，改为渲染但不可见 */
.remote-upload-input {
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
  /* 窄屏下工具栏改单行横向滚动：不再换行堆出六七排，把画面区顶到屏外
     （这正是手机浏览器「连上了却看不到画面」的直接原因） */
  .remote-toolbar {
    flex-wrap: nowrap;
    overflow-x: auto;
    align-items: center;
  }
  .remote-toolbar > * {
    flex-shrink: 0;
  }
  .remote-toolbar .el-tag {
    max-width: 46vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* 画面区给一个按宽算的高度（16:9 约 56vw），侧栏定高，整页交给 .remote-page 滚动
     ——不再让三块内容去抢那一点 flex 剩余空间 */
  .remote-work {
    height: auto;
  }
  .remote-body {
    flex: none;
    flex-direction: column;
  }
  .remote-stage {
    flex: none;
    height: 56vw;
    min-height: 220px;
  }
  /* 手机横过来（视口宽 < 900）且是原生全屏时，浏览器自己转了横、我们不叠加旋转，
     上面那条 height:56vw 会照样命中 :fullscreen 元素把画面压成一条，用更高优先级的选择器复位。 */
  .remote-stage:fullscreen {
    height: 100%;
    min-height: 0;
  }
  .remote-side {
    width: 100%;
    height: 320px;
  }
}
</style>
