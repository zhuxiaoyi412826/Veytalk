<script setup>
/**
 * 直播大厅 / 直播间。
 *
 * 一个组件两副形态，靠路由参数 roomId 切换：
 * - 大厅（无 roomId）：房间列表（直播中恒排最前）+ 开播面板；
 * - 直播间（有 roomId）：hls.js 播放器 + 独立弹幕 WS + 点赞。
 *
 * 三条通道彼此独立，这是「抖音式」方案的关键：
 * 1. 控制面 REST（api/live.js）：房间列表/详情/开播/关播/心跳；
 * 2. 媒体面：观众用 hls.js 从 Nginx 拉 m3u8，主播端由 Electron 原生 ffmpeg 切 HLS PUT 上去，
 *    Java 与本页都不碰媒体字节；
 * 3. 弹幕面：独立 /ws/live 通道，握手把 satoken 挂在 query 上（升级请求带不了自定义头）。
 *
 * 实际「开播推流」只在 Electron 桌面端可用（window.__IM_LIVE__ 由 preload 注入）；
 * 浏览器/手机端只观看——这正是定案的「本地软件开播、浏览器与 App 观看」模式。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import Hls from 'hls.js'
import {
  fetchLivePage,
  fetchLiveRoom,
  fetchMyLive,
  liveHeartbeat,
  startLive,
  stopLive,
  abortLive
} from '@/api/live'
import { getToken } from '@/utils/token'
import { wsBaseURL, isElectron, livePlayBase } from '@/utils/env'
import { useAuthStore } from '@/stores/auth'
import UserAvatar from '@/components/UserAvatar.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/* ---------- 房间状态文案 ---------- */
const STATUS_TEXT = { 1: '直播中', 2: '已结束', 3: '已封禁' }
const STATUS_TAG = { 1: 'success', 2: 'info', 3: 'danger' }
const SOURCE_TEXT = { screen: '屏幕分享', camera: '摄像头' }

/** 当前是否处于直播间形态（路由带 roomId） */
const roomId = computed(() => route.params.roomId || '')

/* ==================== 大厅：房间列表 ==================== */
const rooms = ref([])
const listLoading = ref(false)
const listQuery = reactive({ current: 1, size: 24, status: null, total: 0 })

async function loadRooms() {
  listLoading.value = true
  try {
    const page = await fetchLivePage({
      current: listQuery.current,
      size: listQuery.size,
      status: listQuery.status ?? undefined
    })
    rooms.value = page?.records || []
    listQuery.total = page?.total || 0
  } catch {
    // request.js 已弹过提示
  } finally {
    listLoading.value = false
  }
}

function onFilterStatus(status) {
  listQuery.status = status
  listQuery.current = 1
  loadRooms()
}

function enterRoom(id) {
  router.push({ name: 'live-room', params: { roomId: String(id) } })
}

/* ==================== 开播面板 ==================== */
const startDialog = ref(false)
const starting = ref(false)
const startForm = reactive({
  title: '',
  notice: '',
  sourceType: 'screen',
  resolution: '720p',
  bitrateKbps: 2500,
  displayId: null
})
/** 本机显示器列表（多屏选择用）；单屏或浏览器端为空，不显示选择项 */
const displays = ref([])

/** 桌面端原生推流桥（preload 注入）；浏览器下为空，开播按钮据此禁用 */
const liveBridge = computed(() => (isElectron() ? window.__IM_LIVE__ : null))
const canBroadcast = computed(() => !!liveBridge.value && typeof liveBridge.value.startPush === 'function')

/** 我正在直播中的房间（用于开播后切到主播面板 / 刷新后恢复现场） */
const myLiving = ref(null)
const pushInfo = reactive({ roomId: '', streamKey: '', pushUrl: '', heartbeatSeconds: 15 })
const pushState = ref('idle') // idle | starting | pushing | stopped | error
const pushError = ref('')
let heartbeatTimer = null

async function loadMyLive() {
  try {
    const vo = await fetchMyLive()
    myLiving.value = vo && vo.status === 1 ? vo : null
  } catch {
    myLiving.value = null
  }
}

async function openStartDialog() {
  if (!canBroadcast.value) {
    ElMessage.warning('开播需使用桌面客户端（浏览器/手机端仅供观看）')
    return
  }
  startForm.title = ''
  startForm.notice = ''
  startForm.displayId = null
  // 多屏时让用户选抓哪块屏；拉取失败/单屏都不影响开播（display 缺省=整个虚拟桌面）
  displays.value = []
  try {
    const list = (await liveBridge.value.listDisplays()) || []
    displays.value = list
    const primary = list.find((d) => d.primary) || list[0]
    if (primary) startForm.displayId = primary.id
  } catch { /* 无桥或失败：保持空，按整桌面抓 */ }
  startDialog.value = true
}

async function submitStart() {
  if (!startForm.title.trim()) {
    ElMessage.warning('请填写房间标题')
    return
  }
  starting.value = true
  try {
    // 1) 控制面建房，拿 streamKey / pushUrl（streamKey 只此一次）
    const info = await startLive({
      title: startForm.title.trim(),
      notice: startForm.notice.trim() || undefined,
      sourceType: startForm.sourceType,
      resolution: startForm.resolution,
      bitrateKbps: Number(startForm.bitrateKbps) || 2500
    })
    pushInfo.roomId = String(info.roomId)
    pushInfo.streamKey = info.streamKey
    pushInfo.pushUrl = info.pushUrl
    pushInfo.heartbeatSeconds = info.heartbeatSeconds || 15
    startDialog.value = false

    // 2) 媒体面：让主进程拉起 ffmpeg 切 HLS 并 PUT 到 pushUrl
    pushState.value = 'starting'
    pushError.value = ''
    await liveBridge.value.startPush({
      pushUrl: pushInfo.pushUrl,
      sourceType: startForm.sourceType,
      resolution: startForm.resolution,
      bitrateKbps: Number(startForm.bitrateKbps) || 2500,
      // 只传 displayId（纯数字）；displays.value.find(...) 得到的是 Vue 响应式 Proxy，
      // 直接过 IPC 会触发「An object could not be cloned」。主进程按 id 用 screen.getAllDisplays() 查 bounds。
      displayId: startForm.displayId ?? null
    })

    myLiving.value = { id: pushInfo.roomId, title: startForm.title.trim(), status: 1 }
    startHeartbeat()
    ElMessage.success('已开播')
    // 主播自己也进直播间，便于自监画面与看弹幕
    enterRoom(pushInfo.roomId)
  } catch (e) {
    pushState.value = 'error'
    pushError.value = e?.message || '开播失败'
    // 房已建但推流没起来：调 abort 直接删房（后端按「从未收到推流心跳」护栏判定），
    // 不留一个空场的 ENDED 房在大厅挂一小时；失败再退回 stop 关播兑底
    if (pushInfo.roomId) {
      const rid = pushInfo.roomId
      pushInfo.roomId = ''
      abortLive(rid).catch(() => stopLive(rid).catch(() => {}))
    }
    ElMessage.error(pushError.value)
  } finally {
    starting.value = false
  }
}

/**
 * 推流心跳：按 heartbeatSeconds 周期续期。
 * 返回 false（房间已不存在/已结束，例如被超时巡检关掉或后台封禁）时必须立刻停推。
 */
function startHeartbeat() {
  stopHeartbeat()
  const intervalMs = Math.max(3, pushInfo.heartbeatSeconds) * 1000
  heartbeatTimer = setInterval(async () => {
    if (!pushInfo.roomId) {
      stopHeartbeat()
      return
    }
    try {
      const alive = await liveHeartbeat(pushInfo.roomId)
      if (alive === false) {
        ElMessage.warning('直播已结束（服务端已关房），推流停止')
        await endBroadcast(false)
      }
    } catch {
      // 单次心跳失败不当结束：网络抖动很常见，等下一次；服务端有超时巡检兜底
    }
  }, intervalMs)
}

function stopHeartbeat() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

/** 结束直播。callStop=true 时才回调后端关播（心跳判定已关房的情况下不必再关一次） */
async function endBroadcast(callStop = true) {
  stopHeartbeat()
  if (liveBridge.value && typeof liveBridge.value.stopPush === 'function') {
    try {
      await liveBridge.value.stopPush()
    } catch {
      // 停推失败无副作用
    }
  }
  pushState.value = 'stopped'
  const id = pushInfo.roomId
  pushInfo.roomId = ''
  pushInfo.streamKey = ''
  pushInfo.pushUrl = ''
  myLiving.value = null
  if (callStop && id) {
    try {
      await stopLive(id)
      ElMessage.success('直播已结束')
    } catch {
      // request.js 已提示
    }
  }
  // 回到大厅并刷新
  if (roomId.value) {
    router.push({ name: 'live' })
  } else {
    loadRooms()
  }
}

async function confirmEndBroadcast() {
  try {
    await ElMessageBox.confirm('确定结束本场直播吗？', '结束直播', { type: 'warning' })
  } catch {
    return
  }
  endBroadcast(true)
}

/* ==================== 直播间：播放器 + 弹幕 ==================== */
const room = ref(null)
const roomLoading = ref(false)
const videoEl = ref(null)
const danmakuList = ref([])
const danmakuText = ref('')
const onlineCount = ref(0)
const likeCount = ref(0)
const wsStatus = ref('idle') // idle | connecting | open | closed
/** 直播已结束但聊天室保留（ENDED 聊天模式）：画面黑屏、弹幕仍可发 */
const streamEnded = ref(false)
let hls = null
let danmakuWs = null
let pingTimer = null

const isAnchor = computed(() => !!room.value && room.value.mine)

async function openRoom(id) {
  roomLoading.value = true
  try {
    const vo = await fetchLiveRoom(id)
    room.value = vo
    // 已结束且聊天模式也超时（后端不再下发 danmakuWs）：只提示并黑屏
    if (!vo || (vo.status !== 1 && !vo.danmakuWs)) {
      ElMessage.info('该直播已结束')
      teardownRoom()
      return
    }
    likeCount.value = 0
    danmakuList.value = []
    // 已结束但处于聊天模式（status=2 且带 danmakuWs）：不起播放器（黑屏），只接聊天室
    streamEnded.value = vo.status !== 1
    await nextTick()
    if (vo.status === 1) {
      setupPlayer(vo.playUrl)
    } else {
      destroyPlayer()
    }
    connectDanmaku(vo.danmakuWs)
  } catch {
    room.value = null
    streamEnded.value = false
  } finally {
    roomLoading.value = false
  }
}

function setupPlayer(playUrl) {
  destroyPlayer()
  const video = videoEl.value
  if (!video || !playUrl) {
    return
  }
  // playUrl 后端下发：自测是相对路径（/hls/...，走前端同源代理），生产可能是绝对 CDN 地址。
  // 相对路径按环境补 host：Web 用同源（空串），Electron 用本机流媒体绝对地址（见 env.js livePlayBase）
  const src = /^https?:\/\//i.test(playUrl) ? playUrl : livePlayBase() + playUrl
  // iOS/Safari 原生支持 HLS，直接喂 src；其余走 hls.js（MSE）
  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = src
    video.play().catch(() => {})
    return
  }
  if (Hls.isSupported()) {
    // liveSyncDurationCount 3→2：播放位距直播边缘少回退一个切片，延迟降约 1~2s（推流端切 1s 片后再降）。
    // 若卡顿明显可回调 3；网络稳定也可压到 1
    hls = new Hls({ lowLatencyMode: true, liveSyncDurationCount: 2 })
    const inst = hls
    let netRetries = 0
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      netRetries = 0
      video.play().catch(() => {})
    })
    hls.on(Hls.Events.ERROR, (_evt, data) => {
      if (!data?.fatal) {
        return
      }
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        // 开播头几秒 init.mp4/m3u8 还没 PUT 上来，首次加载必然 404。旧实现只 startLoad 一次，
        // 失败后无人接管——表现为「点开播看不到画面，必须回大厅再进」。改为每秒重试一次、最多 30 次，
        // 覆盖 ffmpeg 启动+首片上传窗口；manifest 成功解析后计数清零
        if (netRetries++ < 30) {
          setTimeout(() => {
            // inst 可能已被 destroyPlayer（退房/关播）销毁，守卫防止操作死实例
            if (hls === inst) {
              inst.loadSource(src)
            }
          }, 1000)
        } else {
          ElMessage.error('直播流加载失败，请稍后重试')
        }
      } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
        hls.recoverMediaError()
      }
    })
    hls.loadSource(src)
    hls.attachMedia(video)
  } else {
    ElMessage.error('当前浏览器不支持 HLS 播放')
  }
}

function destroyPlayer() {
  if (hls) {
    hls.destroy()
    hls = null
  }
  if (videoEl.value) {
    videoEl.value.removeAttribute('src')
    videoEl.value.load?.()
  }
}

/** 弹幕 WS：握手地址由后端下发（/ws/live?roomId=X），前端补 ws(s)://host 前缀与 satoken */
function connectDanmaku(path) {
  closeDanmaku()
  if (!path) {
    return
  }
  const token = getToken()
  const sep = path.includes('?') ? '&' : '?'
  const url = `${wsBaseURL()}${path}${sep}satoken=${encodeURIComponent(token)}`
  wsStatus.value = 'connecting'
  try {
    danmakuWs = new WebSocket(url)
  } catch {
    wsStatus.value = 'closed'
    return
  }
  danmakuWs.onopen = () => {
    wsStatus.value = 'open'
    // 保活：弹幕通道不像 IM 主连接那样有服务端心跳，这里定期 ping 防中间层掐空闲连接
    pingTimer = setInterval(() => sendFrame({ type: 'ping' }), 25000)
  }
  danmakuWs.onmessage = (evt) => onWsFrame(evt.data)
  danmakuWs.onclose = () => {
    wsStatus.value = 'closed'
    if (pingTimer) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }
  danmakuWs.onerror = () => {
    wsStatus.value = 'closed'
  }
}

function closeDanmaku() {
  if (pingTimer) {
    clearInterval(pingTimer)
    pingTimer = null
  }
  if (danmakuWs) {
    danmakuWs.onclose = null
    try {
      danmakuWs.close()
    } catch {
      // 忽略
    }
    danmakuWs = null
  }
  wsStatus.value = 'idle'
}

function onWsFrame(raw) {
  let frame
  try {
    frame = JSON.parse(raw)
  } catch {
    return
  }
  switch (frame.type) {
    case 'danmaku':
      appendDanmaku(frame)
      break
    case 'online':
      onlineCount.value = frame.count || 0
      break
    case 'like':
      likeCount.value += frame.count || 0
      break
    case 'notice':
      appendSystem(`公告：${frame.content}`)
      break
    case 'system':
      appendSystem(frame.content)
      break
    case 'ended': {
      // 主播关播：画面黑屏并提示，但聊天室保留（服务端 ended-chat-minutes 到期后才断开）
      const text = frame.content || '主播已结束直播'
      streamEnded.value = true
      if (room.value) {
        room.value.status = 2
      }
      destroyPlayer()
      appendSystem(text)
      ElMessage.info(text)
      break
    }
    case 'error':
      ElMessage.warning(frame.content || '发送失败')
      break
    default:
      // pong 等无需处理
      break
  }
}

function appendDanmaku(frame) {
  danmakuList.value.push({
    id: `${frame.ts || Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    kind: 'danmaku',
    nickname: frame.nickname || '观众',
    avatar: frame.avatar || '',
    anchor: !!frame.anchor,
    content: frame.content || ''
  })
  trimAndScroll()
}

function appendSystem(content) {
  danmakuList.value.push({
    id: `sys-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    kind: 'system',
    content
  })
  trimAndScroll()
}

/** 弹幕是流水，留最近 200 条足够；再多只会拖慢渲染且没人回看 */
function trimAndScroll() {
  if (danmakuList.value.length > 200) {
    danmakuList.value.splice(0, danmakuList.value.length - 200)
  }
  nextTick(() => {
    const box = document.querySelector('.live-danmaku__list')
    if (box) {
      box.scrollTop = box.scrollHeight
    }
  })
}

function sendFrame(obj) {
  if (danmakuWs && danmakuWs.readyState === WebSocket.OPEN) {
    danmakuWs.send(JSON.stringify(obj))
  }
}

function sendDanmaku() {
  const content = danmakuText.value.trim()
  if (!content) {
    return
  }
  if (wsStatus.value !== 'open') {
    ElMessage.warning('弹幕连接未就绪')
    return
  }
  sendFrame({ type: 'danmaku', content })
  danmakuText.value = ''
}

function sendLike() {
  sendFrame({ type: 'like', count: 1 })
  // 本地先给一点即时反馈，真实计数以服务端合并广播为准
  likeCount.value += 1
}

function teardownRoom() {
  destroyPlayer()
  closeDanmaku()
  room.value = null
  danmakuList.value = []
  onlineCount.value = 0
  streamEnded.value = false
}

/* ==================== 生命周期 / 路由联动 ==================== */
function syncRoute() {
  if (roomId.value) {
    openRoom(roomId.value)
  } else {
    teardownRoom()
    loadRooms()
    loadMyLive()
  }
}

onMounted(() => {
  if (liveBridge.value && typeof liveBridge.value.onState === 'function') {
    liveBridge.value.onState((state) => {
      const s = state?.state || 'idle'
      pushState.value = s
      if (state?.error) {
        pushError.value = state.error
      }
      // 只有「推流意外中断（error）」才需要前端联动收尾并回调后端关房；
      // 'stopped' 多半是我们自己 endBroadcast→stopPush 触发的回声，不再重复处理，否则会递归
      if (s === 'error' && myLiving.value) {
        endBroadcast(true)
      }
    })
  }
  syncRoute()
})

onBeforeUnmount(() => {
  teardownRoom()
  stopHeartbeat()
})

watch(() => route.params.roomId, () => syncRoute())
</script>

<template>
  <div class="live">
    <!-- ==================== 直播间形态 ==================== -->
    <div v-if="roomId" class="live-room">
      <div class="live-room__top">
        <el-button text :icon="'ArrowLeft'" @click="router.push({ name: 'live' })">返回大厅</el-button>
        <div v-if="room" class="live-room__meta">
          <UserAvatar :src="room.anchorAvatar" :name="room.anchorName" :size="30" />
          <span class="live-room__anchor im-ellipsis">{{ room.anchorName }}</span>
          <el-tag size="small" :type="STATUS_TAG[room.status] || 'info'">
            {{ STATUS_TEXT[room.status] || '未知' }}
          </el-tag>
          <span class="live-room__online">👁 {{ onlineCount }}</span>
          <span class="live-room__like">❤ {{ likeCount }}</span>
        </div>
      </div>

      <div class="live-room__body">
        <div class="live-room__stage">
          <div class="live-player">
            <video ref="videoEl" class="live-player__video" controls playsinline></video>
            <div v-if="roomLoading" class="live-player__mask">加载中…</div>
            <div v-else-if="!room || room.status !== 1 || streamEnded" class="live-player__mask">
              {{ streamEnded || (room && room.status === 2) ? '主播已结束直播，可继续聊天' : '直播已结束' }}
            </div>
          </div>
          <div v-if="room" class="live-room__title">
            <h3 class="im-ellipsis">{{ room.title }}</h3>
            <p v-if="room.notice" class="live-room__notice">{{ room.notice }}</p>
          </div>
        </div>

        <aside class="live-danmaku">
          <div class="live-danmaku__head">
            <span>互动</span>
            <span class="live-danmaku__ws" :class="'live-danmaku__ws--' + wsStatus">
              {{ wsStatus === 'open' ? '已连接' : wsStatus === 'connecting' ? '连接中' : '未连接' }}
            </span>
          </div>
          <div class="live-danmaku__list">
            <div
              v-for="item in danmakuList"
              :key="item.id"
              class="live-danmaku__item"
              :class="{ 'live-danmaku__item--system': item.kind === 'system' }"
            >
              <template v-if="item.kind === 'system'">
                <span class="live-danmaku__sys">{{ item.content }}</span>
              </template>
              <template v-else>
                <UserAvatar :src="item.avatar" :name="item.nickname" :size="24" />
                <span class="live-danmaku__nick" :class="{ 'live-danmaku__nick--anchor': item.anchor }">
                  {{ item.anchor ? '主播 · ' : '' }}{{ item.nickname }}
                </span>
                <span class="live-danmaku__text">{{ item.content }}</span>
              </template>
            </div>
            <div v-if="!danmakuList.length" class="live-danmaku__empty">还没有互动，来说点什么吧</div>
          </div>
          <div class="live-danmaku__input">
            <el-input
              v-model="danmakuText"
              placeholder="发条弹幕…"
              maxlength="100"
              size="default"
              @keyup.enter="sendDanmaku"
            />
            <el-button type="primary" :disabled="wsStatus !== 'open'" @click="sendDanmaku">发送</el-button>
            <el-button class="live-danmaku__like" @click="sendLike">❤ 点赞</el-button>
          </div>
        </aside>
      </div>
    </div>

    <!-- ==================== 大厅形态 ==================== -->
    <div v-else class="live-lobby">
      <div class="live-lobby__head">
        <div class="live-lobby__filters">
          <el-radio-group :model-value="listQuery.status" size="default" @change="onFilterStatus">
            <el-radio-button :value="null">全部</el-radio-button>
            <el-radio-button :value="1">直播中</el-radio-button>
            <el-radio-button :value="2">已结束</el-radio-button>
          </el-radio-group>
        </div>
        <div class="live-lobby__actions">
          <el-button v-if="myLiving" type="danger" plain @click="confirmEndBroadcast">
            结束我的直播
          </el-button>
          <el-button type="primary" :disabled="!canBroadcast" @click="openStartDialog">
            {{ canBroadcast ? '开播' : '开播（需桌面端）' }}
          </el-button>
        </div>
      </div>

      <!-- 主播面板：正在直播时展示推流状态与关键信息 -->
      <div v-if="myLiving" class="live-anchor">
        <div class="live-anchor__row">
          <el-tag type="success" effect="dark">直播中</el-tag>
          <span class="live-anchor__title im-ellipsis">{{ myLiving.title }}</span>
          <span class="live-anchor__state">推流状态：{{ pushState }}</span>
        </div>
        <div v-if="pushError" class="live-anchor__error">{{ pushError }}</div>
        <div class="live-anchor__row live-anchor__row--btns">
          <el-button size="small" @click="enterRoom(myLiving.id)">进入我的直播间</el-button>
          <el-button size="small" type="danger" @click="confirmEndBroadcast">结束直播</el-button>
        </div>
      </div>

      <div v-loading="listLoading" class="live-grid">
        <div
          v-for="item in rooms"
          :key="item.id"
          class="live-card"
          :class="{ 'live-card--ended': item.status === 3 }"
          @click="(item.status === 1 || item.status === 2) && enterRoom(item.id)"
        >
          <div class="live-card__cover">
            <img v-if="item.cover" :src="item.cover" alt="" />
            <div v-else class="live-card__placeholder">
              <el-icon :size="34"><VideoCamera /></el-icon>
            </div>
            <span class="live-card__status" :class="'live-card__status--' + item.status">
              {{ STATUS_TEXT[item.status] || '未知' }}
            </span>
            <span v-if="item.status === 1" class="live-card__online">👁 {{ item.onlineCount || 0 }}</span>
          </div>
          <div class="live-card__info">
            <div class="live-card__title im-ellipsis">{{ item.title }}</div>
            <div class="live-card__anchor">
              <UserAvatar :src="item.anchorAvatar" :name="item.anchorName" :size="22" />
              <span class="im-ellipsis">{{ item.anchorName }}</span>
              <span v-if="item.sourceType" class="live-card__source">{{ SOURCE_TEXT[item.sourceType] || item.sourceType }}</span>
            </div>
          </div>
        </div>
        <el-empty v-if="!listLoading && !rooms.length" description="暂无直播" class="live-grid__empty" />
      </div>

      <div v-if="listQuery.total > listQuery.size" class="live-lobby__pager">
        <el-pagination
          layout="prev, pager, next"
          :total="listQuery.total"
          :page-size="listQuery.size"
          :current-page="listQuery.current"
          @current-change="(p) => { listQuery.current = p; loadRooms() }"
        />
      </div>
    </div>

    <!-- ==================== 开播对话框 ==================== -->
    <el-dialog v-model="startDialog" title="开始直播" width="460px">
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="startForm.title" maxlength="60" show-word-limit placeholder="给房间起个标题" />
        </el-form-item>
        <el-form-item label="公告">
          <el-input v-model="startForm.notice" type="textarea" :rows="2" maxlength="512" placeholder="进房时展示给观众（可空）" />
        </el-form-item>
        <el-form-item label="推流源">
          <el-radio-group v-model="startForm.sourceType">
            <el-radio-button value="screen">屏幕分享</el-radio-button>
            <el-radio-button value="camera">摄像头</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="startForm.sourceType === 'screen' && displays.length > 1" label="选择屏幕">
          <el-select v-model="startForm.displayId" style="width: 240px">
            <el-option v-for="d in displays" :key="d.id" :label="d.label" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="分辨率">
          <el-select v-model="startForm.resolution" style="width: 140px">
            <el-option label="480p" value="480p" />
            <el-option label="720p" value="720p" />
            <el-option label="1080p" value="1080p" />
          </el-select>
        </el-form-item>
        <el-form-item label="码率">
          <el-input-number v-model="startForm.bitrateKbps" :min="500" :max="8000" :step="500" />
          <span class="live-form__hint">kbps</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startDialog = false">取消</el-button>
        <el-button type="primary" :loading="starting" @click="submitStart">开始推流</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.live {
  height: 100%;
  overflow: hidden;
  background: var(--im-bg);
}

/* ------------------------------ 直播间 ------------------------------ */
.live-room {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.live-room__top {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--im-border, #e4e7ed);
  flex: none;
}

.live-room__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.live-room__anchor {
  max-width: 160px;
  font-weight: 600;
}

.live-room__online,
.live-room__like {
  font-size: 13px;
  color: #909399;
}

.live-room__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.live-room__stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #000;
}

.live-player {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.live-player__video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.live-player__mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: rgba(0, 0, 0, 0.6);
  font-size: 15px;
}

.live-room__title {
  flex: none;
  padding: 10px 14px;
  background: #fff;
  border-top: 1px solid var(--im-border, #e4e7ed);
}

.live-room__title h3 {
  margin: 0;
  font-size: 15px;
}

.live-room__notice {
  margin: 4px 0 0;
  font-size: 12px;
  color: #909399;
}

/* ------------------------------ 弹幕 ------------------------------ */
.live-danmaku {
  width: 320px;
  flex: none;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--im-border, #e4e7ed);
  background: #fff;
}

.live-danmaku__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  font-weight: 600;
  border-bottom: 1px solid var(--im-border, #e4e7ed);
}

.live-danmaku__ws {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}

.live-danmaku__ws--open {
  color: #67c23a;
}

.live-danmaku__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 12px;
}

.live-danmaku__item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 13px;
  line-height: 1.5;
}

.live-danmaku__item--system {
  justify-content: center;
}

.live-danmaku__sys {
  font-size: 12px;
  color: #e6a23c;
  background: #fdf6ec;
  padding: 2px 10px;
  border-radius: 10px;
}

.live-danmaku__nick {
  flex: none;
  color: #909399;
}

.live-danmaku__nick--anchor {
  color: #f56c6c;
  font-weight: 600;
}

.live-danmaku__text {
  color: #303133;
  word-break: break-word;
}

.live-danmaku__empty {
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
  margin-top: 24px;
}

.live-danmaku__input {
  display: flex;
  gap: 6px;
  padding: 10px 12px;
  border-top: 1px solid var(--im-border, #e4e7ed);
}

.live-danmaku__like {
  flex: none;
}

/* ------------------------------ 大厅 ------------------------------ */
.live-lobby {
  height: 100%;
  overflow-y: auto;
  padding: 16px;
}

.live-lobby__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.live-lobby__actions {
  display: flex;
  gap: 8px;
}

.live-anchor {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 16px;
}

.live-anchor__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.live-anchor__row--btns {
  margin-top: 10px;
}

.live-anchor__title {
  font-weight: 600;
  max-width: 320px;
}

.live-anchor__state {
  font-size: 12px;
  color: #67c23a;
}

.live-anchor__error {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.live-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  min-height: 120px;
}

.live-grid__empty {
  grid-column: 1 / -1;
}

.live-card {
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--im-border, #e4e7ed);
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}

.live-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.1);
}

.live-card--ended {
  cursor: default;
  opacity: 0.7;
}

.live-card--ended:hover {
  transform: none;
  box-shadow: none;
}

.live-card__cover {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #1f2329;
}

.live-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.live-card__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4b5563;
}

.live-card__status {
  position: absolute;
  top: 8px;
  left: 8px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}

.live-card__status--1 {
  background: #f56c6c;
}

.live-card__online {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}

.live-card__info {
  padding: 8px 10px 10px;
}

.live-card__title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 6px;
}

.live-card__anchor {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
}

.live-card__source {
  margin-left: auto;
  flex: none;
  font-size: 11px;
  color: #c0c4cc;
}

.live-lobby__pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

.live-form__hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

/* ------------------------------ 窄屏 ------------------------------ */
@media (max-width: 768px) {
  .live-room__body {
    flex-direction: column;
  }

  .live-danmaku {
    width: 100%;
    border-left: none;
    border-top: 1px solid var(--im-border, #e4e7ed);
    height: 40%;
  }

  .live-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 10px;
  }
}
</style>
