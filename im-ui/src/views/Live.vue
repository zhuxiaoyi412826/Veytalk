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
import { createWebmPlayer, webmPlayerSupported } from '@/utils/webmPlayer'
import {
  QUALITY_MODE,
  dedupeTiers,
  buildQualityChoices,
  resolveQualityChoice,
  qualitySummary,
  pickPlayerMode
} from '@/utils/quality'
import {
  fetchLivePage,
  fetchLiveRoom,
  unlockLiveRoom,
  payLiveRoom,
  fetchMyLive,
  liveHeartbeat,
  startLive,
  stopLive,
  abortLive,
  fetchVideoPage,
  createVideo,
  deleteVideo,
  reportVideoPlay,
  fetchLinkmicList,
  fetchMuteList,
  muteUser,
  unmuteUser,
  fetchBlockWords,
  replaceBlockWords,
  startPk,
  votePk,
  stopPk,
  fetchCurrentPk,
  reportReplay,
  fetchReplay,
  fetchReplayRooms
} from '@/api/live'
import { uploadFileSmart } from '@/api/file'
import { fetchWallet, recharge, checkin, sendGift } from '@/api/wallet'
import { guardImage } from '@/utils/nsfw'
import { formatConvTime } from '@/utils/format'
import { mediaUrl, readVideoMetadata } from '@/utils/media'
import { getToken } from '@/utils/token'
import { wsBaseURL, isElectron, livePlayBase } from '@/utils/env'
import { useAuthStore } from '@/stores/auth'
import { useLinkmicStore } from '@/stores/linkmic'
import { useReportStore } from '@/stores/report'
import { useSettingsStore } from '@/stores/settings'
import UserAvatar from '@/components/UserAvatar.vue'
import AnchorStatsDialog from '@/components/AnchorStatsDialog.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const report = useReportStore()
const settings = useSettingsStore()

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

/* ==================== 大厅：可回看场次 ==================== */
/**
 * 大厅那条「可看回放」横条。为什么要单独一份列表：回放的入口标记能活 retention-days
 * （默认 1 天），而大厅对「已结束房间」只展示 1 小时（ended-room-visible-minutes），不进这一条就等于
 * 「关播一小时后回放再没人找得到」。卡片不直接带地址，点了才进房现签。
 */
const replays = ref([])

async function loadReplays() {
  try {
    replays.value = (await fetchReplayRooms({ limit: 12 })) || []
  } catch {
    replays.value = []
  }
}

function openReplayRoom(item) {
  router.push({ name: 'live-room', params: { roomId: String(item.id) }, query: { replay: '1' } })
}

/* ==================== 顶层双板块：视频 / 直播 ==================== */
/** 顶层板块：live 直播间（默认），video 短视频信息流 */
const activeTab = ref('live')

/* ---------- 视频板块：信息流列表 ---------- */
const videos = ref([])
const videoLoading = ref(false)
const videoQuery = reactive({ current: 1, size: 24, total: 0 })
let videoLoaded = false

async function loadVideos() {
  videoLoading.value = true
  try {
    const page = await fetchVideoPage({ current: videoQuery.current, size: videoQuery.size })
    videos.value = page?.records || []
    videoQuery.total = page?.total || 0
    videoLoaded = true
  } catch {
    // request.js 已弹过提示
  } finally {
    videoLoading.value = false
  }
}

/* ---------- 视频板块：播放区（点卡片在此内联播放） ---------- */
const playingVideo = ref(null)
const playerEl = ref(null)
// mediaUrl 是响应式的：首次返回空串并触发后台取 blob，取回后本 computed 自动变成 blob 地址
const playSrc = computed(() => (playingVideo.value ? mediaUrl(playingVideo.value.fileUrl) : ''))
function openVideo(v) {
  playingVideo.value = v
  reportVideoPlay(v.id)
  // 本地展示计数 +1，省一次重拉
  v.playCount = (v.playCount || 0) + 1
}
function closePlayer() {
  playingVideo.value = null
}
/**
 * 播放区那一行元数据，末尾拼上真实分辨率。
 * 点播视频是「一行一个原文件」（上传时不转码），没有多档可切，所以这里只报数不给选择器——
 * 前端放大像素那种「假 2K」只会变糊，与直播那种真多档不是一回事。
 */
const playingVideoMeta = computed(() => {
  const v = playingVideo.value
  if (!v) return ''
  const parts = [v.uploaderName, fmtDuration(v.duration), `${v.playCount || 0} 次播放`]
  if (v.width && v.height) parts.push(`${v.width}×${v.height}`)
  return parts.filter(Boolean).join(' · ')
})
// blob 地址到位后再驱动播放：mediaUrl 异步，src 还是空时 autoplay 不会重试
watch(playSrc, (src) => {
  if (src && playerEl.value) {
    playerEl.value.load?.()
    playerEl.value.play?.().catch(() => {})
  }
})

function coverSrc(v) {
  return v.coverUrl ? mediaUrl(v.coverUrl) : ''
}

function fmtDuration(sec) {
  if (!sec || sec <= 0) {
    return '0:00'
  }
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

async function onRemoveVideo(v) {
  try {
    await ElMessageBox.confirm(`确定删除视频「${v.title}」吗？`, '删除视频', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteVideo(v.id)
    videos.value = videos.value.filter((x) => x.id !== v.id)
    videoQuery.total = Math.max(0, videoQuery.total - 1)
    if (playingVideo.value && playingVideo.value.id === v.id) {
      closePlayer()
    }
    ElMessage.success('已删除')
  } catch {
    // request.js 已弹过提示
  }
}

/* ---------- 视频板块：上传对话框（复用 uploadFileSmart 传输链路） ---------- */
const uploadDialog = ref(false)
const videoInput = ref(null)
const coverInput = ref(null)
const videoFile = ref(null)
const videoMeta = reactive({ duration: null, width: null, height: null })
const coverFile = ref(null)
const coverPreview = ref('')
const uploadForm = reactive({ title: '' })
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadStage = ref('')
const uploadStageText = computed(() => {
  const map = { hash: '计算文件指纹', upload: '上传中', merge: '合并中', instant: '秒传命中', done: '完成' }
  return map[uploadStage.value] || '上传中'
})

function clearCoverPreview() {
  if (coverPreview.value) {
    URL.revokeObjectURL(coverPreview.value)
    coverPreview.value = ''
  }
}

function resetUploadForm() {
  videoFile.value = null
  coverFile.value = null
  videoMeta.duration = null
  videoMeta.width = null
  videoMeta.height = null
  uploadForm.title = ''
  uploadPercent.value = 0
  uploadStage.value = ''
  clearCoverPreview()
}

function openUpload() {
  resetUploadForm()
  uploadDialog.value = true
}

async function onVideoChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) {
    return
  }
  videoFile.value = file
  const meta = await readVideoMetadata(file)
  videoMeta.duration = meta ? meta.duration : null
  videoMeta.width = meta ? meta.width : null
  videoMeta.height = meta ? meta.height : null
  if (!uploadForm.title) {
    uploadForm.title = file.name.replace(/\.[^.]+$/, '').slice(0, 60)
  }
  // 默认取首帧作封面；用户可自行替换；抓帧失败则留空退化为占位图
  await grabFirstFrame(file)
}

async function grabFirstFrame(file) {
  clearCoverPreview()
  coverFile.value = null
  const url = URL.createObjectURL(file)
  try {
    const frame = await captureFrame(url)
    if (frame) {
      coverFile.value = frame
      coverPreview.value = URL.createObjectURL(frame)
    }
  } catch {
    // 抓帧失败不阻断：无封面时前端退化为占位图
  } finally {
    URL.revokeObjectURL(url)
  }
}

// 把视频首帧画进 canvas 导出 jpeg File；很多编码器 time 0 帧是空的，先 seek 一小段再画更稳
function captureFrame(videoUrl) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.playsInline = true
    video.src = videoUrl
    video.onerror = () => reject(new Error('video load error'))
    const drawFrame = () => {
      try {
        const canvas = document.createElement('canvas')
        canvas.width = video.videoWidth || 320
        canvas.height = video.videoHeight || 180
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height)
        canvas.toBlob((blob) => {
          if (!blob) {
            reject(new Error('no frame blob'))
            return
          }
          resolve(new File([blob], 'cover.jpg', { type: 'image/jpeg' }))
        }, 'image/jpeg', 0.85)
      } catch (err) {
        reject(err)
      }
    }
    video.onloadedmetadata = () => {
      const t = Math.min(0.1, (video.duration || 1) / 2)
      if (Math.abs(video.currentTime - t) > 0.01) {
        video.currentTime = t
      } else {
        drawFrame()
      }
    }
    video.onseeked = drawFrame
  })
}

function pickCover() {
  if (coverInput.value) {
    coverInput.value.click()
  }
}

function onCoverChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) {
    return
  }
  coverFile.value = file
  clearCoverPreview()
  coverPreview.value = URL.createObjectURL(file)
}

function resetCover() {
  if (videoFile.value) {
    grabFirstFrame(videoFile.value)
  } else {
    coverFile.value = null
    clearCoverPreview()
  }
}

async function submitUpload() {
  if (!videoFile.value) {
    ElMessage.warning('请先选择视频文件')
    return
  }
  if (!uploadForm.title.trim()) {
    ElMessage.warning('请填写视频标题')
    return
  }
  // 封面是信息流里人人可见的静态图，与头像同级，过同一道闸门。
  // 排在视频本体上传之前：封面被拦就没必要先把几百 MB 的原片传上去再放弃
  if (coverFile.value) {
    const guard = await guardImage(coverFile.value, { scene: 'video_cover' })
    if (!guard.pass) {
      ElMessage.error(guard.message)
      return
    }
  }
  uploading.value = true
  try {
    const vo = await uploadFileSmart(videoFile.value, 'video', videoMeta.duration, (p, stage) => {
      uploadPercent.value = p
      uploadStage.value = stage
    })
    let coverFileId = null
    if (coverFile.value) {
      const cvo = await uploadFileSmart(coverFile.value, 'video_cover', null, () => {})
      coverFileId = cvo && cvo.fileId
    }
    await createVideo({
      title: uploadForm.title.trim(),
      fileId: vo.fileId,
      coverFileId,
      duration: videoMeta.duration,
      width: videoMeta.width,
      height: videoMeta.height,
      size: vo.size,
      ext: vo.ext,
      contentType: vo.contentType
    })
    ElMessage.success('发布成功')
    uploadDialog.value = false
    videoQuery.current = 1
    await loadVideos()
  } catch (err) {
    ElMessage.error('发布失败：' + ((err && err.message) || '上传中断'))
  } finally {
    uploading.value = false
  }
}

// 首次切到视频板块才拉列表，避免进大厅就多打一次请求
watch(activeTab, (tab) => {
  if (tab === 'video' && !videoLoaded) {
    loadVideos()
  }
})

function enterRoom(id) {
  router.push({ name: 'live-room', params: { roomId: String(id) } })
}

/* ==================== 主播数据面板 ==================== */
const statsDialog = ref(false)

/**
 * 面板里点「进房」/「看回放」：先收面板再跳，否则路由变了对话框还浮在上面。
 * 面板是跨场次的列表，点的不是当前房间，所以走与卡片同一个 router 目标。
 */
function openStatsRoom(roomId) {
  statsDialog.value = false
  enterRoom(roomId)
}

function openStatsReplay(roomId) {
  statsDialog.value = false
  router.push({ name: 'live-room', params: { roomId: String(roomId) }, query: { replay: '1' } })
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
  displayId: null,
  // 门禁：password 非空即密码房；price>0 即付费房（两者都给按密码优先）。都留空为公开房
  password: '',
  price: ''
})
/** 本机显示器列表（多屏选择用）；单屏或浏览器端为空，不显示选择项 */
const displays = ref([])

/**
 * 原画档位 → 建议码率 / 码率上限（kbps）。
 * 上限只约束原画那一路（阶梯向下的几档按比例派生），所以 2K 要放宽到 16000。
 */
const RESOLUTION_DEFAULT_BITRATE = { '480p': 900, '720p': 2500, '1080p': 4000, '1440p': 8000 }
const RESOLUTION_MAX_BITRATE = { '480p': 2000, '720p': 4000, '1080p': 8000, '1440p': 16000 }
const bitrateMax = computed(() => RESOLUTION_MAX_BITRATE[startForm.resolution] || 8000)

// 换原画就顺手把码率拨到该档的建议值：两者本来强相关，留着一个跟分辨率不匹配的码率
// （1440p + 800k 那种）只会让观众看到一屏马赛克。拨完仍可手动改
watch(() => startForm.resolution, (r) => {
  const def = RESOLUTION_DEFAULT_BITRATE[r]
  if (def && startForm.bitrateKbps !== def) {
    startForm.bitrateKbps = Math.min(def, RESOLUTION_MAX_BITRATE[r] || def)
  }
})

/** 开播面板里的阶梯预告：原画向下派生，实际比采集源高的档会被主进程砍掉，所以只能说「最多」有哪几档 */
const ladderHint = computed(() => {
  const order = ['1440p', '1080p', '720p', '480p']
  const idx = order.indexOf(startForm.resolution)
  return order.slice(idx < 0 ? 1 : idx).join(' / ')
})

/** 本场实际已铺开的档位（主进程上报），主播面板里给主播看 */
const pushTiers = ref([])

/** 桌面端原生推流桥（preload 注入）；浏览器下为空，开播按钮据此禁用 */
const liveBridge = computed(() => (isElectron() ? window.__IM_LIVE__ : null))
const canBroadcast = computed(() => !!liveBridge.value && typeof liveBridge.value.startPush === 'function')

/** 我正在直播中的房间（用于开播后切到主播面板 / 刷新后恢复现场） */
const myLiving = ref(null)
const pushInfo = reactive({ roomId: '', streamKey: '', pushUrl: '', heartbeatSeconds: 15, replayEnabled: true })
const pushState = ref('idle') // idle | starting | pushing | stopped | error
const pushError = ref('')
/**
 * 主进程上报过的回放定版结果（来自主动 stopPush 之外的路径：ffmpeg 自己崩掉时
 * 前端不会再走 stopPush，只能从 stopped 事件里拿到）。endBroadcast 上报后清空。
 */
const lastReplay = ref(null)
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
  startForm.password = ''
  startForm.price = ''
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
      bitrateKbps: Number(startForm.bitrateKbps) || 2500,
      password: startForm.password.trim() || undefined,
      price: Number(startForm.price) > 0 ? Number(startForm.price) : undefined
    })
    pushInfo.roomId = String(info.roomId)
    pushInfo.streamKey = info.streamKey
    pushInfo.pushUrl = info.pushUrl
    pushInfo.heartbeatSeconds = info.heartbeatSeconds || 15
    // 后端没开的功能不值得推流端白写一份文件：旧后端不返这个字段，按「开」处理
    pushInfo.replayEnabled = info.replayEnabled !== false
    lastReplay.value = null
    startDialog.value = false

    // 2) 媒体面：让主进程拉起 ffmpeg 切 HLS 并 PUT 到 pushUrl
    pushState.value = 'starting'
    pushError.value = ''
    const res = await liveBridge.value.startPush({
      pushUrl: pushInfo.pushUrl,
      sourceType: startForm.sourceType,
      resolution: startForm.resolution,
      bitrateKbps: Number(startForm.bitrateKbps) || 2500,
      // 只传 displayId（纯数字）；displays.value.find(...) 得到的是 Vue 响应式 Proxy，
      // 直接过 IPC 会触发「An object could not be cloned」。主进程按 id 用 screen.getAllDisplays() 查 bounds。
      displayId: startForm.displayId ?? null
    })
    // 主进程按采集源真实高度把放大了也没用的档砍掉了，拿到实际铺开的档位才能知道主播选的 1440p 有没有真出去
    pushTiers.value = Array.isArray(res?.tiers) ? res.tiers : []

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
  let replay = null
  if (liveBridge.value && typeof liveBridge.value.stopPush === 'function') {
    try {
      // stopPush 会等主进程「排空上传队列 + PUT 一份 VOD 清单」才回来（百毫秒量级）
      const res = await liveBridge.value.stopPush({ replay: pushInfo.replayEnabled !== false })
      replay = res && res.replay
    } catch {
      // 停推失败无副作用
    }
  }
  // ffmpeg 意外退出走的是事件上报（那会儿 active 已被清，stopPush 拿不到东西）
  if (!replay) {
    replay = lastReplay.value
  }
  lastReplay.value = null
  pushState.value = 'stopped'
  pushTiers.value = []
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
  // 回放上报：清单已经由主进程 PUT 到媒体服务器，这一步只告诉后端「这一场有回放可看」。
  // 失败就静默（request 层 silent）：没有入口不影响直播本身，也不该弹一个干扰关播的错
  if (id && replay && replay.segmentCount > 0) {
    reportReplay(id, {
      segmentCount: replay.segmentCount,
      durationSeconds: replay.durationSeconds,
      bytes: replay.bytes
    })
      // 报成功就刷一下大厅那条横条：主播关播后当场就能看到自己的回放
      .then(() => loadReplays())
      .catch(() => {})
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
// 门禁解锁弹窗：观众进密码/付费房且未解锁时弹。gateBusy 防重复提交（尤其付费房防双扣）
const gateDialog = ref(false)
const gateBusy = ref(false)
const gatePassword = ref('')
const videoEl = ref(null)
const danmakuList = ref([])
const danmakuText = ref('')
const onlineCount = ref(0)
const likeCount = ref(0)
const wsStatus = ref('idle') // idle | connecting | open | closed
/** 直播已结束但聊天室保留（ENDED 聊天模式）：画面黑屏、弹幕仍可发 */
const streamEnded = ref(false)
/**
 * 回放态：按下「看回放」且真的拿到签名地址才为 true。
 * replayInfo 不挂在 room 上——签名地址有有效期，每次点都要现取一份。
 */
const replayOn = ref(false)
const replayInfo = ref(null)
const replayBusy = ref(false)
let hls = null
let danmakuWs = null
let pingTimer = null
// 连麦合成流的 MSE/WebM 播放器（与 hls 二选一，同一 <video> 上切换）
let webmPlayer = null
// 当前播放器形态：'' | 'hls' | 'webm'，避免重复重建
const playerMode = ref('')
/**
 * 本场流实际铺开的档位（由 hls.js 的 levels 去重而来），只用来判定「哪几档可以点」。
 * ★ 选择器不再因为档位少而整体隐藏：四个选项（自动/480p/720p/1080p/1440p）恒定出现，
 *   本场没有的那几档置灰并写明原因（本场最高 720p / 连麦合成流 / 本浏览器不支持切档）。
 *   整体藏掉会让人误以为「这个直播间压根不能切」，给一个点了没反应的假选项更糟——
 *   灰掉 + 一句原因才是既统一又属实的做法。
 */
const qualityTiers = ref([])
/** 当前播放器形态（QUALITY_MODE 之一，'' 表示还没起播放器）：决定这四档能不能真切 */
const qualitySource = ref('')
/** 四个固定选项 + 各自的可点性与原因 */
const qualityChoices = computed(() => buildQualityChoices(qualityTiers.value, qualitySource.value))
/** 选择器旁的一句话说明：本场到底出了哪几档 */
const qualitySummaryText = computed(() => qualitySummary(qualityTiers.value, qualitySource.value))
/** 实际生效的选择器值：'auto' 或档位高度字符串（'1440'/'1080'/'720'/'480'） */
const qualityValue = ref('auto')
/** 已下发给 hls.js 的期望档位：直播清单每秒重读一次，不记录就会反复重设 currentLevel 打断 ABR */
let qualityPushed = null

const isAnchor = computed(() => !!room.value && room.value.mine)

/**
 * 举报本场直播：room 类型的责任人是主播（后端 ReportRoomTargetSpiImpl 取 anchorId），
 * 主播举报自己的房间就是自举报，后端会拒，因此入口只在观众侧给。
 * targetId 用路由上的房间 ID（字符串形式的雪花 ID，后端 Jackson 能直接转 Long）。
 */
function onReportRoom() {
  report.openReport({
    targetType: 'room',
    targetId: roomId.value,
    title: room.value?.title || `直播 #${roomId.value}`
  })
}

/* ==================== 钱包与礼物（直播间内） ==================== */
// 余额/目录经 /api/wallet 拉取；送礼是 REST + 服务端经 /ws/live 广播 gift 帧，本端飘屏统一由广播驱动（含自己），
// 不做本地乐观飘屏以免同一笔礼出现两条特效；余额则用送礼响应即时回填。
const wallet = reactive({ enabled: false, balance: 0, gifts: [], signedToday: false, checkinBonus: 0 })
const giftPanelOpen = ref(false)
const selectedGiftId = ref(null)
const giftCount = ref(1)
const giftSubmitting = ref(false)
const signInBusy = ref(false)
const rechargeBusy = ref(false)
const rechargeAmount = ref(100)
const floatingGifts = ref([]) // 特效飘屏：{ id, giftIcon, giftName, count, senderName, kind }
const giftRank = ref([]) // 贡献榜（按送礼人累计价值降序）：{ senderId, senderName, avatar, totalPrice, count }

const selectedGift = computed(() => wallet.gifts.find((g) => g.giftId === selectedGiftId.value) || null)
const giftTotal = computed(() => (selectedGift.value ? (selectedGift.value.price || 0) * giftCount.value : 0))
const giftAffordable = computed(() => wallet.balance >= giftTotal.value)

async function loadWallet() {
  try {
    const vo = await fetchWallet()
    if (vo) {
      wallet.enabled = !!vo.enabled
      wallet.balance = vo.balance || 0
      wallet.gifts = vo.gifts || []
      wallet.signedToday = !!vo.signedToday
      wallet.checkinBonus = vo.checkinBonus || 0
      if (vo.enabled && !selectedGiftId.value && wallet.gifts.length) {
        selectedGiftId.value = wallet.gifts[0].giftId
      }
    }
  } catch {
    // 钱包未装配或被限流：静默，礼物面板显示为空目录
  }
}

function pickGift(gift) {
  selectedGiftId.value = gift.giftId
}

async function onSendGift() {
  // 主播不能给自己的直播送礼物（礼物入口对主播已隐藏，后端另有兼底）
  if (isAnchor.value) {
    ElMessage.warning('不能给自己的直播送礼物')
    return
  }
  const gift = selectedGift.value
  if (!gift) {
    ElMessage.warning('请选择礼物')
    return
  }
  if (!giftAffordable.value) {
    ElMessage.warning('积分不足，请先充值或签到')
    return
  }
  giftSubmitting.value = true
  try {
    const res = await sendGift(roomId.value, gift.giftId, giftCount.value)
    if (res && res.balance != null) {
      wallet.balance = res.balance
    } else {
      wallet.balance = Math.max(0, wallet.balance - giftTotal.value)
    }
    giftPanelOpen.value = false
  } catch {
    // 余额不足 / 房间已结束等由 request.js 统一提示
  } finally {
    giftSubmitting.value = false
  }
}

async function onCheckin() {
  if (wallet.signedToday) {
    ElMessage.info('今日已签到')
    return
  }
  signInBusy.value = true
  try {
    const vo = await checkin()
    if (vo) {
      wallet.balance = vo.balance || wallet.balance
      wallet.signedToday = true
    }
    ElMessage.success(`签到成功 +${wallet.checkinBonus} 积分`)
  } catch {
    // request.js 已提示
  } finally {
    signInBusy.value = false
  }
}

async function onRecharge() {
  const amount = Number(rechargeAmount.value)
  if (!amount || amount <= 0) {
    ElMessage.warning('请输入充值积分')
    return
  }
  rechargeBusy.value = true
  try {
    const vo = await recharge(amount)
    if (vo) {
      wallet.balance = vo.balance || wallet.balance
    }
    ElMessage.success('充值已到账（模拟）')
  } catch {
    // request.js 已提示
  } finally {
    rechargeBusy.value = false
  }
}

/** 收到 gift 帧：压入飘屏队列（数秒后自动出列）并累计贡献榜 */
function pushFloatingGift(frame) {
  const id = `g-${frame.ts || Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  floatingGifts.value.push({
    id,
    giftIcon: frame.giftIcon,
    giftName: frame.giftName,
    count: frame.count,
    senderName: frame.senderName,
    kind: frame.kind
  })
  setTimeout(() => {
    const i = floatingGifts.value.findIndex((g) => g.id === id)
    if (i >= 0) {
      floatingGifts.value.splice(i, 1)
    }
  }, 3200)
}

function bumpGiftRank(frame) {
  const sid = frame.senderId
  let entry = giftRank.value.find((r) => r.senderId === sid)
  if (!entry) {
    entry = { senderId: sid, senderName: frame.senderName, avatar: frame.senderAvatar, totalPrice: 0, count: 0 }
    giftRank.value.push(entry)
  }
  entry.totalPrice += frame.totalPrice || 0
  entry.count += frame.count || 0
  giftRank.value.sort((a, b) => b.totalPrice - a.totalPrice)
  if (giftRank.value.length > 20) {
    giftRank.value.splice(20)
  }
}

/* ==================== 连麦（嘉宾申请上麦 / 主播接受，主播侧合成推流） ==================== */
// 信令走主 /ws（linkmic / linkmic-signal），由 stores/linkmic 状态机裁决；本视图只做入口与展示：
// 观众看到「申请上麦」，主播看到「待处理申请 + 在麦嘉宾」；观众侧据 /ws/live 的 linkmic 帧切换播放器（mse-fe 片）。
const linkmic = useLinkmicStore()
const linkmicOn = ref(false) // 本场当前是否在连麦合成中（观众侧徽标与播放器分流用）

/**
 * 把 MediaStream 绑到 <video>.srcObject 的自定义指令（script setup 里 vSrcStream 即 v-src-stream）。
 * 不能用 :src：流对象不是 URL，写成属性会得到 "[object MediaStream]"；靠 ref 手工赋值又兜不住
 * v-for 格子与流到达时机不定的组合，updated 钩子才能跟着重绑。
 */
function attachStream(el, stream) {
  el.srcObject = stream || null
  if (stream) {
    el.play().catch(() => {})
  }
}
const vSrcStream = {
  mounted: (el, binding) => attachStream(el, binding.value),
  updated: (el, binding) => {
    if (el.srcObject !== binding.value) {
      attachStream(el, binding.value)
    }
  },
  beforeUnmount: (el) => {
    el.srcObject = null
  }
}

/** 进房后按身份初始化连麦：主播拉 ICE + 记合成流媒体目录；观众补齐在麦列表并判初始连麦态 */
async function setupLinkmic(vo) {
  linkmicOn.value = false
  try {
    if (vo.mine) {
      await linkmic.initAsAnchor(roomId.value, vo.playUrl, vo.anchorName)
    } else {
      await linkmic.initAsGuest(roomId.value)
      const list = await fetchLinkmicList(roomId.value)
      linkmicOn.value = Array.isArray(list) && list.length > 0
    }
  } catch {
    // 连麦配置拉取失败：静默，入口自然禁用
  }
}

/** 观众侧：空闲=申请，申请中=取消，已上麦=下麦 */
function onRequestMic() {
  if (linkmic.guestStatus === 'active' || linkmic.guestStatus === 'requesting') {
    linkmic.leaveMic()
  } else {
    linkmic.requestMic()
  }
}

/** 主播一键断开全部连麦（不关播，只是把嘉宾请下麦，观众自动回到普通直播画面） */
function onEndAllLinkmic() {
  linkmic.endAllLinkmic()
}

/** 监听静音：连麦双方都是外放时很容易啸叫，给一个一键关掉本地扬声器的入口（不影响观众听到的合成流） */
const monitorMuted = ref(false)

/**
 * 连麦分屏舞台的格子数据：固定「左为主播、右为嘉宾」，与观众端看到的合成画面同构。
 * 以前这一层完全不存在：嘉宾的远端流只被挂进 composer 内部的隐藏静音 <video>（只取帧、不接扬声器），
 * 而嘉宾端的 guestRemoteStream 根本没被任何元素绑定——所以主播听不到嘉宾、嘉宾也看不到听不到主播。
 * muted 只加在「自己的画面」上（否则自听回声），对端画面受 monitorMuted 控制。
 */
const stageCells = computed(() => {
  if (isAnchor.value && linkmic.isAnchor) {
    if (!linkmic.anchorGuestStreams.length) {
      return null
    }
    return {
      left: { key: 'self', stream: linkmic.anchorSelfStream, name: room.value?.anchorName || '主播（我）', muted: true },
      rights: linkmic.anchorGuestStreams.map((g) => ({
        key: g.callId,
        stream: g.stream,
        name: g.name || '嘉宾',
        muted: monitorMuted.value
      }))
    }
  }
  if (linkmic.guestStatus === 'active') {
    return {
      left: {
        key: 'anchor',
        stream: linkmic.guestRemoteStream,
        name: room.value?.anchorName || '主播',
        muted: monitorMuted.value
      },
      rights: [{ key: 'self', stream: linkmic.guestSelfStream, name: '我', muted: true }]
    }
  }
  return null
})
const stageActive = computed(() => !!stageCells.value)

// 分屏占位期间把背面的直播流暂停：不藏起来只靠 v-show 的话，hls.js/MSE 仍在背地解码缓冲，
// 与分屏那几路 WebRTC 叠在一起白耗一份 CPU，低端机上足以把连麦帧率拖下来
watch(stageActive, (on) => {
  const v = videoEl.value
  if (!v) {
    return
  }
  if (on) {
    v.pause()
  } else {
    v.play().catch(() => {})
  }
})

/** 自动播放被拦时（无手势/策略差异）至少能点一下补救，不致于黑屏无声 */
function onStageClick(evt) {
  const box = evt && evt.currentTarget
  if (!box) {
    return
  }
  box.querySelectorAll('video').forEach((v) => {
    if (v.paused) {
      v.play().catch(() => {})
    }
  })
}

function onAcceptRequest(req) {
  linkmic.acceptRequest(req.callId, { guestUserId: req.guestUserId })
}

function onRejectRequest(req) {
  linkmic.rejectRequest(req.callId)
}

function onKickGuest(guest) {
  linkmic.kickGuest(guest.callId)
}

async function openRoom(id) {
  roomLoading.value = true
  try {
    const vo = await fetchLiveRoom(id)
    room.value = vo
    // 换房先离场：上一次点的回放不能留在新房间的画面上（顺带把播放器一起收掉）
    closeReplay()
    // 门禁未解锁排在「已结束」判断之前：锁着的房间后端不下发 playUrl/danmakuWs，
    // 走解锁弹窗而不是当成「已结束」黑屏。保留房间外壳（标题/封面），只不拉播放器、不连弹幕。
    if (vo && vo.gate && vo.gate.type !== 'none' && vo.unlocked === false && !vo.mine) {
      closeDanmaku()
      danmakuList.value = []
      streamEnded.value = false
      onlineCount.value = vo.onlineCount || 0
      gatePassword.value = ''
      gateDialog.value = true
      return
    }
    // 已结束、聊天模式也超时（后端不再下发 danmakuWs）、且没有回放：只提示并黑屏
    if (!vo || (vo.status !== 1 && !vo.danmakuWs && !vo.hasReplay)) {
      ElMessage.info('该直播已结束')
      teardownRoom()
      return
    }
    likeCount.value = 0
    danmakuList.value = []
    floatingGifts.value = []
    giftRank.value = []
    // 切房间时先拆掉上场的 PK 面板：roomId 变了，那一帧的「我方/对方」认领也跟着失效
    teardownPk()
    // 已结束但处于聊天模式（status=2 且带 danmakuWs）：不起播放器（黑屏），只接聊天室
    streamEnded.value = vo.status !== 1
    await nextTick()
    if (vo.status === 1) {
      setupPlayer(vo.playUrl)
    } else {
      destroyPlayer()
    }
    connectDanmaku(vo.danmakuWs)
    loadWallet()
    await setupLinkmic(vo)
    // 进房即处于连麦（观众从在麦列表补齐）：从默认 hls 切到 webm 合成流
    ensurePlayerMode()
    // PK 兜底：进房补帧要等 WS 连上，这之间先拿一次快照，免得比到一半的房间看着像没在比
    if (!pk.value) {
      const pkVo = await fetchCurrentPk(roomId.value)
      if (pkVo && !pk.value) {
        applyPk(pkVo)
      }
    }
    // 从大厅「可回看」横条点进来的（query 带了 replay=1）：进房直接起回放，不让用户再点一次
    if (route.query.replay === '1' && vo.status !== 1 && vo.hasReplay) {
      await openReplay()
    }
  } catch {
    room.value = null
    streamEnded.value = false
    closeReplay()
  } finally {
    roomLoading.value = false
  }
}

/**
 * 提交解锁：密码房走 /unlock，付费房走 /pay。成功后重走一遍进房流程（再取一次 detail，这次带 playUrl）。
 *
 * <p>错误（错密码 11019 / 非对应房类型 / 余额不足）由请求层统一弹提示，这里不重复。
 * gateBusy 锁住整个提交，防付费房双击二次扣款（后端也已做幂等，两道相叠）。
 */
async function submitGateUnlock() {
  if (!room.value || gateBusy.value) {
    return
  }
  const id = room.value.id
  const paid = room.value.gate && room.value.gate.type === 'paid'
  if (!paid && !gatePassword.value.trim()) {
    ElMessage.warning('请输入房间密码')
    return
  }
  gateBusy.value = true
  try {
    if (paid) {
      await payLiveRoom(id)
    } else {
      await unlockLiveRoom(id, gatePassword.value.trim())
    }
    gateDialog.value = false
    // 解锁成功后 openRoom 重取详情：这次 unlocked=true、后端下发 playUrl/danmakuWs，正常起播
    await openRoom(id)
  } catch {
    // 失败保持弹窗，让用户重输/换价重试；请求层已提示具体原因
  } finally {
    gateBusy.value = false
  }
}

/** 暂不解锁：收弹窗并回大厅（房间外壳无播放能力，停在这里也没意义） */
function gateCancel() {
  gateDialog.value = false
  router.push({ name: 'live' })
}

/** 回放顶条上那一行摘要：时长 / 体积 / 分片数 */
const replayMetaText = computed(() => {
  const r = replayInfo.value
  if (!r) {
    return ''
  }
  const sec = Number(r.durationSeconds) || 0
  const mb = (Number(r.bytes) || 0) / 1024 / 1024
  const size = mb >= 1 ? `${mb.toFixed(1)} MB` : '不足 1 MB'
  return `${Math.floor(sec / 60)} 分 ${sec % 60} 秒 · ${size} · ${r.segmentCount || 0} 片`
})

/**
 * 看点播回放：每次点都现取一份签名地址（同直播地址那样会过期，不能缓存），拿到后按 VOD 起播放器。
 * 取回空说明入口标记已经到期（im.live.replay.retention-days），顺手抹掉本房间的
 * hasReplay，免得留一个点了没反应的按钮。清单里的分片地址不带签名，取到清单就取得了目录。
 */
async function openReplay() {
  if (!room.value || replayBusy.value) {
    return
  }
  replayBusy.value = true
  try {
    const vo = await fetchReplay(room.value.id)
    if (!vo || !vo.playUrl) {
      ElMessage.info('回放已过期')
      room.value.hasReplay = false
      return
    }
    replayInfo.value = vo
    replayOn.value = true
    await nextTick()
    setupPlayer(vo.playUrl, { vod: true })
  } catch {
    // request.js 已提示；回到黑屏态，聊天室照旧
    replayOn.value = false
  } finally {
    replayBusy.value = false
  }
}

/** 退出回放：停掉播放器回到「已结束」黑屏态，聊天室不受影响 */
function closeReplay() {
  replayOn.value = false
  replayInfo.value = null
  destroyPlayer()
}

/**
 * 关播瞬间还留在房间里的观众：入口标记是主播端「stopLive 之后」才上报的（前面还要等主进程
 * 排空上传队列并 PUT 一份 VOD 清单），所以收到 ended 帧那一刻它必定还不存在。延后探一次，
 * 探到了就把按钮挂上来，观众不必为了看回放退出去再进。只探一次，不做轮询。
 */
let replayProbeTimer = null
function probeReplay(id) {
  clearTimeout(replayProbeTimer)
  if (!id) {
    return
  }
  replayProbeTimer = setTimeout(async () => {
    // 期间可能已退房/换房：房间不对就别把上一场的回放挂到新房间上
    if (!room.value || String(room.value.id) !== String(id) || room.value.hasReplay) {
      return
    }
    try {
      const vo = await fetchReplay(id)
      if (vo && vo.playUrl && room.value && String(room.value.id) === String(id)) {
        room.value.hasReplay = true
      }
    } catch {
      // 探不到不提示：这一步本来就是锦上添花
    }
  }, 10000)
}

/** 上一次档位集的特征串：用它识别「新档刚上线」，那种情况下之前落不了地的偏好要重新试一次 */
let qualityTiersSig = ''

/**
 * 清单解析/刷新后重算档位表，并把偏好从头下发一次（新档上线时，之前落不了地的偏好可能现在能了）。
 * @param {string} mode 本端播放器形态，只有 QUALITY_MODE.MSE 才真的能锁档
 */
function syncQualityLevels(inst, mode) {
  qualitySource.value = mode
  const tiers = dedupeTiers(inst && inst.levels)
  qualityTiers.value = tiers
  // 档位集变了（开播头几秒逐档补齐、或主清单补上 RESOLUTION）就强制重下发一次；
  // 否则 applyQuality 会因为「偏好没改过」直接早退，观众锁的 1080p 永远停在自动上
  const sig = `${mode}:${tiers.map((t) => t.height).join(',')}`
  applyQuality(inst, buildQualityChoices(tiers, mode), sig !== qualityTiersSig)
  qualityTiersSig = sig
}

/**
 * 下发清晰度偏好。本场没那一档（主播只推到 720p）或本形态压根切不了档时回落自动：
 * 锁到一个不存在的 level 序号会让 hls.js 无流可拉，宁可走 ABR 也别黑屏。
 */
function applyQuality(inst, choices, force) {
  const want = String(settings.liveQuality || 'auto')
  if (!force && want === qualityPushed) return
  qualityPushed = want
  const { choice } = resolveQualityChoice(choices, want)
  try {
    // -1 就是交回 hls.js 的自适应；给具体 index 则是锁档（hls.js 在下一个分片边界切换）
    if (inst) inst.currentLevel = choice ? choice.index : -1
  } catch {
    /* 实例刚被 destroyPlayer 掉 */
  }
  // 落不了地就把显示值拨回自动：别让选择器停在一个灰着的选项上冒充已生效
  qualityValue.value = choice ? want : 'auto'
}

/** 观众在选择器里改档：存成默认偏好（下次进任何直播间都按它），并立刻作用于当前流 */
function onQualityChange(val) {
  settings.update({ liveQuality: String(val) })
  applyQuality(hls, qualityChoices.value, true)
}

/**
 * 起 HLS 播放器。opts.vod 为真时按点播处理：关低延迟、不做「等首片」重试
 * （回放清单是关播时定版落盘的，进得来就一定读得到），网络错直接提示。
 */
function setupPlayer(playUrl, opts = {}) {
  const isVod = !!opts.vod
  destroyPlayer()
  const video = videoEl.value
  if (!video || !playUrl) {
    return
  }
  playerMode.value = 'hls'
  // playUrl 后端下发：自测是相对路径（/hls/...，走前端同源代理），生产可能是绝对 CDN 地址。
  // 相对路径按环境补 host：Web 用同源（空串），Electron 用本机流媒体绝对地址（见 env.js livePlayBase）
  const src = /^https?:\/\//i.test(playUrl) ? playUrl : livePlayBase() + playUrl
  // ★ 先试 hls.js（MSE），只有它不支持才退回原生 HLS（判断口径与理由见 utils/quality.js 的 pickPlayerMode）。
  // 先判 canPlayType 的旧写法会让「能切档的浏览器」被推进没有档位 API 的原生分支（选项全标「不支持」）
  const mode = pickPlayerMode({
    mse: Hls.isSupported(),
    native: !!video.canPlayType('application/vnd.apple.mpegurl')
  })
  if (mode === QUALITY_MODE.NATIVE) {
    // 走到这里基本只剩 iOS（没 MSE）：能播但拿不到 levels、也设不了 currentLevel，四档全置灰 + 只留「自动」
    video.src = src
    qualitySource.value = QUALITY_MODE.NATIVE
    qualityTiers.value = []
    qualityValue.value = 'auto'
    video.play().catch(() => {})
    return
  }
  if (mode === QUALITY_MODE.MSE) {
    // liveSyncDurationCount 3→2：播放位距直播边缘少回退一个切片，延迟降约 1~2s（推流端切 1s 片后再降）。
    // 若卡顿明显可回调 3；网络稳定也可压到 1。回放（VOD）不吃这套：从清单开头播，要的是稳
    hls = isVod
      ? new Hls({ lowLatencyMode: false, liveSyncDurationCount: 4 })
      : new Hls({ lowLatencyMode: true, liveSyncDurationCount: 2 })
    const inst = hls
    let netRetries = 0
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      netRetries = 0
      // 拿到主清单才能枚举档位：按用户偏好锁定/走自动（档位不够时自动回落，不会黑屏）
      syncQualityLevels(inst, QUALITY_MODE.MSE)
      video.play().catch(() => {})
    })
    // 刚开播那几秒 ffmpeg 可能只写出了第一档，剩下的逐档补进主清单；档位集变了要跟着重算
    hls.on(Hls.Events.LEVELS_UPDATED, () => syncQualityLevels(inst, QUALITY_MODE.MSE))
    // 单档流或主清单没写 RESOLUTION 时，解析那一刻 level.height 还是 0（选项会全灰成一排「未标注」），
    // 真实尺寸要等首分片解出 track info 才补上；直播清单每秒重读一次，跟着 LEVEL_UPDATED 重算就能自愈
    hls.on(Hls.Events.LEVEL_UPDATED, () => syncQualityLevels(inst, QUALITY_MODE.MSE))
    hls.on(Hls.Events.ERROR, (_evt, data) => {
      if (!data?.fatal) {
        return
      }
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
        if (isVod) {
          // 回放没有「还没上传」的窗口，重试只是白等
          ElMessage.error('回放加载失败，请稍后重试')
        } else if (netRetries++ < 30) {
          // 开播头几秒 init.mp4/m3u8 还没 PUT 上来，首次加载必然 404。旧实现只 startLoad 一次，
          // 失败后无人接管——表现为「点开播看不到画面，必须回大厅再进」。改为每秒重试一次、最多 30 次，
          // 覆盖 ffmpeg 启动+首片上传窗口；manifest 成功解析后计数清零
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
  if (webmPlayer) {
    webmPlayer.stop()
    webmPlayer = null
  }
  playerMode.value = ''
  // 清晰度属于「这一场流」而不是全局：换场/退回黑屏态都得清掉，否则旧档位会挂在新流上
  qualityTiers.value = []
  qualitySource.value = ''
  qualityValue.value = 'auto'
  qualityPushed = null
  qualityTiersSig = ''
  if (videoEl.value) {
    videoEl.value.removeAttribute('src')
    videoEl.value.load?.()
  }
}

/** 连麦合成流（WebM/MSE）播放器：与 hls 互斥，先销 hls 再建 webm 流 */
function setupWebmPlayer(playUrl) {
  destroyPlayer()
  const video = videoEl.value
  if (!video || !playUrl || !webmPlayerSupported()) {
    return
  }
  playerMode.value = 'webm'
  // 连麦合成流只有一档：选择器仍出（四个选项全灰 + 原因），不能因为切不了就突然消失
  qualitySource.value = QUALITY_MODE.WEBM
  qualityTiers.value = []
  qualityValue.value = 'auto'
  qualityPushed = null
  qualityTiersSig = ''
  webmPlayer = createWebmPlayer({
    video,
    playUrl,
    onState: (s) => {
      if (s === 'error') {
        // MSE 建流失败（编码不支持等）：回退到 hls，不致于黑屏
        ElMessage.warning('连麦合成流播放失败，回退普通直播')
        if (room.value) {
          setupPlayer(room.value.playUrl)
        }
      }
    }
  })
  webmPlayer.start()
}

/** 据当前是否连麦选播放器：连麦→WebM/MSE，非连麦→HLS；已处目标形态则不重建 */
function ensurePlayerMode() {
  // 主播不自看合成流（本地有预览，回看自己上传的合成流无意义且多一路解码开销）
  if (isAnchor.value) {
    return
  }
  const living = room.value && room.value.status === 1 && !streamEnded.value
  if (!living) {
    return
  }
  const want = linkmicOn.value && webmPlayerSupported() ? 'webm' : 'hls'
  if (playerMode.value === want) {
    return
  }
  if (want === 'webm') {
    setupWebmPlayer(room.value.playUrl)
  } else {
    setupPlayer(room.value.playUrl)
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
    case 'gift':
      pushFloatingGift(frame)
      bumpGiftRank(frame)
      break
    case 'linkmic':
      // 主播侧合成流生效/失效：观众据此在 HLS 与 MSE/WebM 播放器间切换
      linkmicOn.value = !!frame.active
      ensurePlayerMode()
      appendSystem(frame.active ? '主播正在连麦' : '连麦已结束')
      break
    case 'pk':
      onPkFrame(frame)
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
      probeReplay(room.value && room.value.id)
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
    // userId 是主播「点谁禁言」的依据，服务端弹幕帧本就带了这个字段（见 broadcastDanmaku）
    userId: frame.userId ? String(frame.userId) : '',
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
  // 主播不能给自己的直播点赞（按钮对主播已隐藏，这里兼做防御）
  if (isAnchor.value) {
    return
  }
  sendFrame({ type: 'like', count: 1 })
  // 本地先给一点即时反馈，真实计数以服务端合并广播为准
  likeCount.value += 1
}

/* ==================== 弹幕房管（仅主播） ==================== */
// 禁言与屏蔽词都按主播 id 存服务端（跨场次生效），所以这份状态挂在页面级而不是房间级。
// 名单与词表都是「打开面板才拉」：主播面板不是高频入口，进房就拉两次 REST 不值当。
const moderationOpen = ref(false)
const muteList = ref([])
const blockWords = ref([])
const wordDraft = ref('')
/** 当前在弹幕气泡上展开禁言菜单的用户 id（一次只开一个，免得刷屏时满屏都是菜单） */
const muteMenuUserId = ref('')
const MUTE_PRESETS = [
  { label: '5 分钟', minutes: 5 },
  { label: '30 分钟', minutes: 30 },
  { label: '1 小时', minutes: 60 },
  { label: '永久', minutes: 0 }
]
const mutedIds = computed(() => new Set(muteList.value.map((m) => String(m.userId))))

async function loadModeration() {
  try {
    // 两份数据并行拉：面板打开后两边都要展示，串行会把等待时间叠加
    const [mutes, words] = await Promise.all([fetchMuteList(), fetchBlockWords()])
    muteList.value = mutes || []
    blockWords.value = words || []
  } catch {
    // 静默接口失败已由 request 拦截器兜住，这里不重复弹提示
  }
}

function openModeration() {
  moderationOpen.value = true
  loadModeration()
}

function toggleMuteMenu(item) {
  if (!isAnchor.value || item.anchor || !item.userId) {
    return
  }
  muteMenuUserId.value = muteMenuUserId.value === item.userId ? '' : item.userId
}

async function onMute(item, minutes) {
  muteMenuUserId.value = ''
  try {
    await muteUser({ userId: item.userId, minutes, roomId: roomId.value || undefined })
    ElMessage.success(minutes > 0 ? `已禁言 ${item.nickname} ${minutes} 分钟` : `已将 ${item.nickname} 永久禁言`)
    // 名单立即重拉：服务端带了 roomId 会给那人的在线连接推一帧提示，本地不必伪造一条记录
    loadModeration()
  } catch {
    // 错误文案（名单已满/不能禁自己）由拦截器提示
  }
}

async function onUnmute(m) {
  try {
    await unmuteUser(m.userId, roomId.value || undefined)
    muteList.value = muteList.value.filter((x) => String(x.userId) !== String(m.userId))
    ElMessage.success(`已解除对 ${m.nickname} 的禁言`)
  } catch {
    // 同上
  }
}

/** 增删一个词后整表 PUT：服务端只给了整表替换接口（理由见 LiveWordsRequest 注释），前端就不保留草稿态 */
async function commitWords(next) {
  try {
    const saved = await replaceBlockWords(next)
    blockWords.value = saved || []
  } catch {
    // 超长/超量会被服务端拒绝并给出原因，词表维持原样即可
  }
}

function onAddWord() {
  const word = wordDraft.value.trim()
  if (!word) {
    return
  }
  if (blockWords.value.includes(word)) {
    ElMessage.info('这个词已经在屏蔽列表里')
    wordDraft.value = ''
    return
  }
  commitWords([...blockWords.value, word])
  wordDraft.value = ''
}

function onRemoveWord(word) {
  commitWords(blockWords.value.filter((w) => w !== word))
}

/** 禁言条目上的人读时长：永久就写永久，否则写「还剩几分钟」好过抛一个时间戳 */
function muteExpireText(m) {
  if (m.permanent) {
    return '永久'
  }
  const left = Math.max(0, Math.round(((m.expireAt || 0) - Date.now()) / 60000))
  return left > 0 ? `还剩 ${left} 分钟` : '已到期'
}

/* ==================== 连麦 PK（两个在播房间比热度） ==================== */
// 进度不轮询：服务端每秒给两个房间各推一帧 pk（见 LivePkService.tick）。
// 倒计时也不靠「每收到一帧就减一秒」：弹幕通道抖动、页面切后台都会漏帧，那样会出现
// 「剩余 12 秒卡住然后突然归零」。改为记住最近一帧到达的时刻与它报的剩余秒数，本地每秒算差值，
// 帧到达时重设锚点——这样漏帧只是把锚点重新校准，不会把倒计时带偏。
const pk = ref(null)
const pkRemain = ref(0)
/** 本人这一票投给了会话里的 A 还是 B（服务端不在帧里回传个人票，本地记住即可） */
const pkMyVote = ref('')
const pkDialogOpen = ref(false)
const pkTargets = ref([])
const pkTargetsLoading = ref(false)
const pkBusy = ref(false)
const pkDuration = ref(300)
const PK_DURATIONS = [
  { label: '3 分钟', seconds: 180 },
  { label: '5 分钟', seconds: 300 },
  { label: '10 分钟', seconds: 600 }
]
/** endReason 是人看的文案，不是日志字段：room-ended 说「对方关播」比说「房间已结束」直观 */
const PK_END_TEXT = { time: '时间到', manual: '提前结束', 'room-ended': '对方关播', banned: '违规终止' }
let pkExpireAt = 0
let pkTicker = null
let pkClearTimer = null

const pkActive = computed(() => !!pk.value && pk.value.status === 0)

/**
 * 我这方是 A 还是 B：帧里的 A/B 是会话内部的固定编号，两个房间收到的是同一帧，
 * 前端必须按自己所在的 roomId 认领一边，否则进度条与胜负文案会左右镜像颠倒。
 */
const pkMySide = computed(() => {
  const p = pk.value
  if (!p) {
    return 'A'
  }
  return String(p.roomA) === String(roomId.value) ? 'A' : 'B'
})

/** 投票要传的是会话里的 A/B，不是「我方/对方」——按钮文字给人看，参数给服务端看 */
const pkOppSide = computed(() => (pkMySide.value === 'A' ? 'B' : 'A'))

/** 给人看的面板一律「我方在左、对方在右」，A/B 只在投票时作为参数出现 */
const pkView = computed(() => {
  const p = pk.value
  if (!p) {
    return null
  }
  const meFirst = pkMySide.value === 'A'
  const ratioA = Number(p.ratioA ?? 50)
  return {
    selfName: meFirst ? p.nameA : p.nameB,
    oppName: meFirst ? p.nameB : p.nameA,
    selfAvatar: meFirst ? p.avatarA : p.avatarB,
    oppAvatar: meFirst ? p.avatarB : p.avatarA,
    selfHeat: meFirst ? p.heatA : p.heatB,
    oppHeat: meFirst ? p.heatB : p.heatA,
    selfVotes: meFirst ? p.voteA : p.voteB,
    oppVotes: meFirst ? p.voteB : p.voteA,
    selfRatio: meFirst ? ratioA : 100 - ratioA,
    iWon: !!p.winner && p.winner !== 'DRAW' && String(p.winner) === pkMySide.value,
    draw: p.winner === 'DRAW' || !p.winner
  }
})

const pkRemainText = computed(() => {
  const s = Math.max(0, Number(pkRemain.value) || 0)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
})

function pkDurationText(sec) {
  const s = Number(sec) || 0
  return s >= 60 ? `${Math.round(s / 60)} 分钟` : `${s} 秒`
}

function pkResultText(p) {
  const reason = PK_END_TEXT[p.endReason] || '已结束'
  const score = `${p.heatA ?? 0} : ${p.heatB ?? 0}`
  if (!p.winner || p.winner === 'DRAW') {
    return `PK ${reason}：平局（${score}）`
  }
  const iWon = (p.winner === 'A') === (String(p.roomA) === String(roomId.value))
  return `PK ${reason}：${iWon ? '我方' : '对方'}胜（${score}）`
}

/**
 * 一帧 PK 状态落进界面。phase 只有 WS 帧带（start/running/end），REST 返回的对象不传，
 * 于是开场与结算的旁白都只由 WS 那一路说一次——自己发起时 REST 先落状态，随后收到的
 * start 帧 pkId 相同，不会再报一次「PK 开始」。
 */
function applyPk(obj, phase) {
  if (!obj || !obj.pkId) {
    return
  }
  const isNew = !pk.value || String(pk.value.pkId) !== String(obj.pkId)
  if (isNew) {
    pkMyVote.value = ''
    clearPkClear()
  }
  pk.value = obj
  if (phase === 'start') {
    appendSystem(`PK 开始：${obj.nameA} vs ${obj.nameB}，${pkDurationText(obj.durationSeconds)}`)
  }
  if (Number(obj.status) === 0) {
    pkExpireAt = Date.now() + Math.max(0, Number(obj.remainSeconds) || 0) * 1000
    startPkTicker()
  } else {
    stopPkTicker()
    pkRemain.value = 0
  }
  if (phase === 'end') {
    appendSystem(pkResultText(obj))
    // 结果面板留 15 秒：观众要看清谁赢了，但也不该在下一场直播里挂着一块上场的比分牌
    pkClearTimer = setTimeout(() => {
      pk.value = null
      pkMyVote.value = ''
    }, 15000)
  }
}

function clearPkClear() {
  if (pkClearTimer) {
    clearTimeout(pkClearTimer)
    pkClearTimer = null
  }
}

function startPkTicker() {
  // 先算一次再建表：否则面板要等第一秒过完才从 0:00 跳到真实剩余时间
  pkRemain.value = Math.max(0, Math.round((pkExpireAt - Date.now()) / 1000))
  if (pkTicker) {
    return
  }
  pkTicker = setInterval(() => {
    pkRemain.value = Math.max(0, Math.round((pkExpireAt - Date.now()) / 1000))
    // 走到 0 就停表，胜负等服务端的 end 帧：本地不擅自判平局，否则会出现「先显示平局再跳出真结果」
    if (pkRemain.value <= 0) {
      stopPkTicker()
    }
  }, 1000)
}

function stopPkTicker() {
  if (pkTicker) {
    clearInterval(pkTicker)
    pkTicker = null
  }
}

function onPkFrame(frame) {
  applyPk(frame, frame.phase)
}

async function onPkVote(side) {
  if (!pk.value || pk.value.status !== 0) {
    return
  }
  try {
    const vo = await votePk(pk.value.pkId, side)
    pkMyVote.value = side
    // 票数与热度就地回填，不等下一拍广播：投完立刻看见自己那一票生效才有反馈感
    if (pk.value && String(pk.value.pkId) === String(vo.pkId)) {
      pk.value = { ...pk.value, heatA: vo.heatA, heatB: vo.heatB, voteA: vo.voteA, voteB: vo.voteB, ratioA: vo.ratioA }
    }
  } catch {
    // PK 已结束、功能未开启等由拦截器给文案，面板保持原样等下一帧即可
  }
}

/** 发起入口只在主播侧，且对手必须此刻也在直播——服务端会拒不在播的目标，所以候选直接取自「直播中」列表 */
async function openPkDialog() {
  pkDialogOpen.value = true
  pkTargetsLoading.value = true
  try {
    const page = await fetchLivePage({ current: 1, size: 30, status: 1 })
    const mine = String(room.value?.anchorId || '')
    pkTargets.value = ((page && page.records) || []).filter((r) => r.status === 1 && String(r.anchorId) !== mine)
  } catch {
    pkTargets.value = []
  } finally {
    pkTargetsLoading.value = false
  }
}

async function onStartPk(target) {
  pkBusy.value = true
  try {
    const vo = await startPk({ targetUserId: target.anchorId, durationSeconds: pkDuration.value })
    applyPk(vo)
    pkDialogOpen.value = false
  } catch {
    // 「对方当前不在直播」「已有 PK 在进行」等保持弹窗打开，好换一个人再试
  } finally {
    pkBusy.value = false
  }
}

async function onPkStop() {
  if (!pk.value) {
    return
  }
  try {
    await stopPk(pk.value.pkId)
    // 不在此处拆面板：服务端会把 end 帧广播给两个房间，两边同时收到才有同一份结果
  } catch {
    // 非对阵主播喊停会被拒（LIVE_FORBIDDEN），拦截器已提示
  }
}

function teardownPk() {
  stopPkTicker()
  clearPkClear()
  pk.value = null
  pkRemain.value = 0
  pkMyVote.value = ''
  pkDialogOpen.value = false
  pkTargets.value = []
}

function teardownRoom() {
  // closeReplay 内含 destroyPlayer：回放态与播放器一起收掉
  clearTimeout(replayProbeTimer)
  closeReplay()
  closeDanmaku()
  room.value = null
  gateDialog.value = false
  danmakuList.value = []
  onlineCount.value = 0
  streamEnded.value = false
  floatingGifts.value = []
  giftRank.value = []
  giftPanelOpen.value = false
  linkmicOn.value = false
  moderationOpen.value = false
  muteMenuUserId.value = ''
  teardownPk()
  linkmic.reset()
}

/* ==================== 生命周期 / 路由联动 ==================== */
function syncRoute() {
  if (roomId.value) {
    openRoom(roomId.value)
  } else {
    teardownRoom()
    loadRooms()
    loadMyLive()
    loadReplays()
  }
}

onMounted(() => {
  if (liveBridge.value && typeof liveBridge.value.onState === 'function') {
    liveBridge.value.onState((state) => {
      const s = state?.state || 'idle'
      pushState.value = s
      // pushing 事件带着本场档位（硬编回落软编重开时也会再报一次），以此为准
      if (Array.isArray(state?.tiers) && state.tiers.length) {
        pushTiers.value = state.tiers
      }
      if (state?.error) {
        pushError.value = state.error
      }
      // 主进程生成的回放统计：只有 ffmpeg 自己崩掉（或被超时巡检关掉）这条路会带上来，
      // 存下来等 endBroadcast 上报；正常关播时 stopPush 的返回值更新鲜
      if (s === 'stopped' && state?.replay) {
        lastReplay.value = state.replay
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
        <!--
          举报入口放在顶栏右侧而不是弹幕按钮行：那一行已经有点赞/礼物/发送，
          再挤一个负向操作会把主操作顶下去；report.enabled 为假（服务端关了举报）时整个按钮不渲染。
        -->
        <el-button
          v-if="room && !isAnchor && report.enabled"
          class="live-room__report"
          size="small"
          type="warning"
          plain
          :icon="'Warning'"
          @click="onReportRoom"
        >
          举报直播间
        </el-button>
      </div>

      <div class="live-room__body">
        <div class="live-room__stage">
          <div class="live-player">
            <video v-show="!stageActive" ref="videoEl" class="live-player__video" controls playsinline></video>
            <!-- 连麦分屏舞台：主播与嘉宾本地都看到「左＝主播｜右＝嘉宾」的实时画面，与观众端看到的合成流同构。
                 未静音的对端 <video> 同时是本地监听的声音出口（走浏览器原生 AEC，比 WebAudio 直连 destination 更不易啸叫）。
                 连麦期间盖住 HLS 那路：那既是主播自己几秒前的延迟画面，又与分屏重复解码 -->
            <div v-if="stageCells" class="live-linkmic__stage" @click="onStageClick">
              <div class="live-linkmic__side">
                <video
                  class="live-linkmic__video"
                  autoplay
                  playsinline
                  :muted="stageCells.left.muted"
                  v-src-stream="stageCells.left.stream"
                ></video>
                <span class="live-linkmic__tag">{{ stageCells.left.name }}</span>
              </div>
              <div class="live-linkmic__side">
                <div v-for="cell in stageCells.rights" :key="cell.key" class="live-linkmic__cell">
                  <video
                    class="live-linkmic__video"
                    autoplay
                    playsinline
                    :muted="cell.muted"
                    v-src-stream="cell.stream"
                  ></video>
                  <span class="live-linkmic__tag">{{ cell.name }}</span>
                </div>
              </div>
              <div class="live-linkmic__bar">
                <span class="live-linkmic__bar-title">
                  🎤 连麦中· {{ isAnchor ? `${stageCells.rights.length} 位嘉宾` : '与主播同屏' }}
                </span>
                <el-button size="small" @click.stop="monitorMuted = !monitorMuted">
                  {{ monitorMuted ? '恢复声音' : '静音' }}
                </el-button>
                <el-button v-if="isAnchor" size="small" type="danger" @click.stop="onEndAllLinkmic">
                  断开连麦
                </el-button>
                <el-button v-else size="small" type="danger" @click.stop="onRequestMic">下麦</el-button>
              </div>
            </div>
            <div v-if="roomLoading" class="live-player__mask">加载中…</div>
            <div v-else-if="!replayOn && (!room || room.status !== 1 || streamEnded)" class="live-player__mask">
              <span>{{ streamEnded || (room && room.status === 2) ? '主播已结束直播，可继续聊天' : '直播已结束' }}</span>
              <!-- 回放入口：后端只在「已结束且 Redis 里有入口标记」时下发 hasReplay；
                   按钮不直接带地址，点了才现取一次签名 -->
              <el-button
                v-if="room && room.hasReplay"
                size="small"
                type="primary"
                :loading="replayBusy"
                @click.stop="openReplay"
              >
                ▶ 看回放
              </el-button>
            </div>
            <!-- 回放态顶条：与直播中的连麦/PK 面板错开位置，只管「在看回放」这件事 -->
            <div v-if="replayOn" class="live-replay">
              <span class="live-replay__tag">回放</span>
              <span class="live-replay__meta">{{ replayMetaText }}</span>
              <el-button size="small" text @click.stop="closeReplay">退出回放</el-button>
            </div>
            <!-- 礼物飘屏：服务端 gift 帧驱动，从右下向左上滑过 -->
            <div class="live-gift__floats">
              <div
                v-for="g in floatingGifts"
                :key="g.id"
                class="live-gift__float"
                :class="{ 'live-gift__float--effect': g.kind === 'effect' }"
              >
                <span class="live-gift__float-icon">{{ g.giftIcon }}</span>
                <span class="live-gift__float-text">{{ g.senderName }} 送出 {{ g.giftName }} ×{{ g.count }}</span>
              </div>
            </div>
            <!-- 连麦中徽标：观众侧据 /ws/live linkmic 帧显示，提示当前为合成连麦流；本端已在分屏舞台里就不重复挂标 -->
            <div v-if="linkmicOn && !stageActive" class="live-linkmic__badge">🎤 连麦中</div>
            <!-- 清晰度：只要画面在播就出现，选项固定四个（自动/480p/720p/1080p/1440p）且与开播面板同名字；
                 本场没铺到的档位置灰并在右侧标出原因（本场无 / 单档 / 不支持 / 未标注），
                 右侧那句汇总说清本场到底有哪几档——观众与主播面对同一套选项：
                 既不给出点了没反应的假控件，也不把本来能切的入口整体藏掉 -->
            <div
              v-if="qualitySource"
              class="live-quality"
              :class="{ 'live-quality--below': linkmicOn && !stageActive }"
              @click.stop
            >
              <span class="live-quality__label">清晰度</span>
              <el-select :model-value="qualityValue" size="small" @change="onQualityChange">
                <el-option label="自动" value="auto" />
                <el-option
                  v-for="q in qualityChoices"
                  :key="q.value"
                  :label="q.label"
                  :value="q.value"
                  :disabled="!q.enabled"
                >
                  <span>{{ q.label }}</span>
                  <span v-if="q.note" class="live-quality__note">{{ q.note }}</span>
                </el-option>
              </el-select>
              <span class="live-quality__meta" :title="qualitySummaryText">{{ qualitySummaryText }}</span>
            </div>
            <!-- PK 面板：挂在画面上沿居中，正好压在分屏那条中缝的上方；不论本端是不是分屏都在同一位置，
                 因为两个房间收到的帧内容相同，靠 roomId 认领左右 -->
            <div v-if="pkView" class="live-pk" :class="{ 'live-pk--ended': !pkActive }">
              <div class="live-pk__row">
                <span class="live-pk__name live-pk__name--self im-ellipsis">{{ pkView.selfName }}</span>
                <span class="live-pk__vs">VS</span>
                <span class="live-pk__name live-pk__name--opp im-ellipsis">{{ pkView.oppName }}</span>
                <button class="live-pk__close" title="关闭" @click="teardownPk">✕</button>
              </div>
              <div class="live-pk__track">
                <div class="live-pk__fill" :style="{ width: pkView.selfRatio + '%' }"></div>
                <span class="live-pk__heat live-pk__heat--self">{{ pkView.selfHeat }}</span>
                <span class="live-pk__heat live-pk__heat--opp">{{ pkView.oppHeat }}</span>
              </div>
              <div v-if="pkActive" class="live-pk__foot">
                <span class="live-pk__count">⏳ {{ pkRemainText }}</span>
                <span class="live-pk__votes">{{ pkView.selfVotes }} : {{ pkView.oppVotes }} 票</span>
                <template v-if="!isAnchor">
                  <el-button
                    size="small"
                    :type="pkMyVote === pkMySide ? 'primary' : 'default'"
                    @click="onPkVote(pkMySide)"
                  >
                    挺我方
                  </el-button>
                  <el-button
                    size="small"
                    :type="pkMyVote === pkOppSide ? 'primary' : 'default'"
                    @click="onPkVote(pkOppSide)"
                  >
                    挺对方
                  </el-button>
                </template>
                <el-button v-else size="small" text type="danger" @click="onPkStop">提前结束</el-button>
              </div>
              <div v-else class="live-pk__foot live-pk__foot--end">
                <span class="live-pk__result" :class="{ 'live-pk__result--win': pkView.iWon }">
                  {{ pkView.draw ? '平局' : pkView.iWon ? '我方胜' : '对方胜' }}
                </span>
                <span class="live-pk__reason">{{ PK_END_TEXT[pk.endReason] || '已结束' }}</span>
              </div>
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
                <span
                  class="live-danmaku__nick"
                  :class="{
                    'live-danmaku__nick--anchor': item.anchor,
                    'live-danmaku__nick--actionable': isAnchor && !item.anchor
                  }"
                  :title="isAnchor && !item.anchor ? '点击设为禁言对象' : ''"
                  @click="toggleMuteMenu(item)"
                >
                  {{ item.anchor ? '主播 · ' : '' }}{{ item.nickname }}
                </span>
                <el-tag v-if="mutedIds.has(item.userId)" class="live-danmaku__muted" size="small" type="warning">
                  已禁言
                </el-tag>
                <span class="live-danmaku__text">{{ item.content }}</span>
              </template>
              <!-- 点昵称就地展开的禁言菜单：比弹框快，也不必给 200 条流水各挂一个 popover -->
              <div v-if="muteMenuUserId === item.userId" class="live-danmaku__mute-menu">
                <el-button
                  v-for="p in MUTE_PRESETS"
                  :key="p.minutes"
                  size="small"
                  :type="p.minutes === 0 ? 'danger' : 'default'"
                  @click.stop="onMute(item, p.minutes)"
                >
                  {{ p.label }}
                </el-button>
                <el-button
                  v-if="mutedIds.has(item.userId)"
                  size="small"
                  text
                  type="success"
                  @click.stop="onUnmute({ userId: item.userId, nickname: item.nickname })"
                >
                  取消禁言
                </el-button>
              </div>
            </div>
            <div v-if="!danmakuList.length" class="live-danmaku__empty">还没有互动，来说点什么吧</div>
          </div>

          <!-- 贡献榜：本场按送礼人累计价值排序，有送礼才显 -->
          <div v-if="giftRank.length" class="live-gift__rank">
            <div class="live-gift__rank-title">🏆 本场贡献榜</div>
            <div v-for="(r, idx) in giftRank.slice(0, 5)" :key="r.senderId" class="live-gift__rank-item">
              <span class="live-gift__rank-no">{{ idx + 1 }}</span>
              <UserAvatar :src="r.avatar" :name="r.senderName" :size="22" />
              <span class="live-gift__rank-name im-ellipsis">{{ r.senderName }}</span>
              <span class="live-gift__rank-score">{{ r.totalPrice }}</span>
            </div>
          </div>

          <!-- 礼物面板已挪到 live-room__body 层的抖音式底部弹层（见 </aside> 之后）：
               旧版把它竖排在 320px 侧栏里，礼物一多就被窗口底边截断 -->

          <!-- 连麦区：主播看待处理申请 + 在麦嘉宾；观众看自己的连麦状态提示 -->
          <div v-if="linkmic.enabled" class="live-linkmic">
            <template v-if="isAnchor">
              <div v-if="linkmic.pendingRequests.length" class="live-linkmic__reqs">
                <div class="live-linkmic__title">🎤 上麦申请</div>
                <div v-for="req in linkmic.pendingRequests" :key="req.callId" class="live-linkmic__req">
                  <span class="live-linkmic__req-name">观众 {{ req.guestUserId }}</span>
                  <div class="live-linkmic__req-btns">
                    <el-button size="small" type="primary" @click="onAcceptRequest(req)">接受</el-button>
                    <el-button size="small" @click="onRejectRequest(req)">拒绝</el-button>
                  </div>
                </div>
              </div>
              <div v-if="linkmic.onMicGuests.length" class="live-linkmic__mic">
                <div class="live-linkmic__title">
                  在麦嘉宾（{{ linkmic.onMicGuests.length }}/{{ linkmic.maxSlots }}）
                  <el-button class="live-linkmic__end" size="small" text type="danger" @click="onEndAllLinkmic">
                    断开全部
                  </el-button>
                </div>
                <div v-for="g in linkmic.onMicGuests" :key="g.callId" class="live-linkmic__guest">
                  <UserAvatar :src="g.avatar" :name="g.name" :size="22" />
                  <span class="live-linkmic__guest-name im-ellipsis">{{ g.name }}</span>
                  <el-button size="small" text type="danger" @click="onKickGuest(g)">下麦</el-button>
                </div>
              </div>
            </template>
            <template v-else>
              <div v-if="linkmic.guestHint" class="live-linkmic__hint">{{ linkmic.guestHint }}</div>
            </template>
          </div>

          <!-- 输入栏分两行：旧版把输入框 + 发送/点赞/礼物/上麦 硬排在一行，320px 宽下右侧按钮必被裁掉 -->
          <div class="live-danmaku__input">
            <div class="live-danmaku__send">
              <el-input
                v-model="danmakuText"
                placeholder="发条弹幕…"
                maxlength="100"
                size="default"
                @keyup.enter="sendDanmaku"
              />
              <el-button type="primary" :disabled="wsStatus !== 'open'" @click="sendDanmaku">发送</el-button>
            </div>
            <div class="live-danmaku__acts">
              <el-button v-if="!isAnchor" class="live-danmaku__like" @click="sendLike">❤ 点赞</el-button>
              <el-button
                v-if="wallet.enabled && !isAnchor"
                class="live-danmaku__gift"
                :class="{ 'live-danmaku__gift--on': giftPanelOpen }"
                @click="giftPanelOpen = !giftPanelOpen"
              >
                🎁 礼物
              </el-button>
              <el-button
                v-if="linkmic.enabled && !isAnchor"
                class="live-danmaku__mic"
                :type="linkmic.guestStatus === 'active' ? 'success' : 'default'"
                :loading="linkmic.guestStatus === 'requesting'"
                @click="onRequestMic"
              >
                {{ linkmic.guestStatus === 'active' ? '下麦' : linkmic.guestStatus === 'requesting' ? '取消申请' : '申请上麦' }}
              </el-button>
              <el-button v-if="isAnchor" class="live-danmaku__mod" @click="openModeration">🛡 房管</el-button>
              <el-button
                v-if="isAnchor && !pkActive"
                class="live-danmaku__pk"
                :disabled="!room || room.status !== 1"
                @click="openPkDialog"
              >
                ⚔ PK
              </el-button>
              <span v-if="isAnchor" class="live-danmaku__tip">不能给自己点赞/送礼；点观众昵称可禁言</span>
            </div>
          </div>
        </aside>

        <!-- 礼物弹层（抖音式）：挂在 body 层覆盖画面+侧栏底部，整幅宽展示礼物，不再被侧栏宽度限死 -->
        <transition name="im-gift-up">
          <div v-if="giftPanelOpen && wallet.enabled" class="live-gift__sheet">
            <div class="live-gift__sheet-mask" @click="giftPanelOpen = false"></div>
            <div class="live-gift__sheet-body">
              <!-- 顶栏不再换行：标题 + 余额 + 关闭。旧版把「收起 ✕」和签到/充值一起塞进 ops 弹性簇，
                   窄屏下 margin-left:auto 把它们整簇顶出可视区，手机上既没返回入口也看不到签到/充值 -->
              <div class="live-gift__sheet-head">
                <span class="live-gift__sheet-title">🎁 送礼物</span>
                <span class="live-gift__sheet-balance">余额 <b>{{ wallet.balance }}</b> 积分</span>
                <el-button class="live-gift__sheet-close" size="small" @click="giftPanelOpen = false">
                  ✕ 关闭
                </el-button>
              </div>
              <div class="live-gift__grid">
                <button
                  v-for="gift in wallet.gifts"
                  :key="gift.giftId"
                  class="live-gift__item"
                  :class="{ 'live-gift__item--active': gift.giftId === selectedGiftId }"
                  @click="pickGift(gift)"
                >
                  <span class="live-gift__item-icon">{{ gift.icon }}</span>
                  <span class="live-gift__item-name">{{ gift.name }}</span>
                  <span class="live-gift__item-price">{{ gift.price }}</span>
                </button>
                <div v-if="!wallet.gifts.length" class="live-gift__empty">
                  暂无可送礼物：im_gift 表没有数据，需导入 sql/im_data.sql 第 10 节礼物种子
                </div>
              </div>
              <div class="live-gift__sheet-foot">
                <span class="live-gift__label">数量</span>
                <el-input-number v-model="giftCount" :min="1" :max="99" size="small" controls-position="right" />
                <span class="live-gift__total">合计 {{ giftTotal }}</span>
                <el-button
                  class="live-gift__send-btn"
                  type="primary"
                  :loading="giftSubmitting"
                  :disabled="!selectedGift || !giftAffordable"
                  @click="onSendGift"
                >
                  立即送礼
                </el-button>
                <span v-if="!giftAffordable" class="live-gift__lack">积分不足</span>
              </div>
              <!-- 签到/充值：面板内独立一行，窄屏自动换行，不会被顶出屏幕 -->
              <div class="live-gift__wallet">
                <el-button size="small" :loading="signInBusy" :disabled="wallet.signedToday" @click="onCheckin">
                  {{ wallet.signedToday ? '已签到' : `签到 +${wallet.checkinBonus}` }}
                </el-button>
                <el-input v-model="rechargeAmount" size="small" class="live-gift__recharge" placeholder="充值积分" />
                <el-button size="small" type="primary" plain :loading="rechargeBusy" @click="onRecharge">充值</el-button>
              </div>
            </div>
          </div>
        </transition>

        <!-- 房管面板（仅主播）：禁言名单 + 屏蔽词。用 dialog 而不是塞进 320px 侧栏，
             因为这两份列表都需要横向空间（昵称 + 剩余时长 + 操作 / 词标签 + 输入框） -->
        <el-dialog v-model="moderationOpen" class="live-mod__dialog" title="🛡 弹幕房管" width="520px" append-to-body>
          <el-tabs>
            <el-tab-pane :label="muteList.length ? `禁言名单（${muteList.length}）` : '禁言名单'">
              <div v-if="!muteList.length" class="live-mod__empty">还没有人被打过静音——点观众昵称即可禁言</div>
              <div v-for="m in muteList" :key="m.userId" class="live-mod__row">
                <UserAvatar :src="m.avatar" :name="m.nickname" :size="26" />
                <span class="live-mod__name im-ellipsis">{{ m.nickname }}</span>
                <span class="live-mod__expire">{{ muteExpireText(m) }}</span>
                <el-button size="small" text type="success" @click="onUnmute(m)">取消禁言</el-button>
              </div>
            </el-tab-pane>
            <el-tab-pane :label="blockWords.length ? `屏蔽词（${blockWords.length}）` : '屏蔽词'">
              <div class="live-mod__words-input">
                <el-input
                  v-model="wordDraft"
                  size="small"
                  placeholder="加一个词，回车确认"
                  @keyup.enter="onAddWord"
                />
                <el-button size="small" type="primary" @click="onAddWord">添加</el-button>
              </div>
              <div class="live-mod__tags">
                <el-tag
                  v-for="w in blockWords"
                  :key="w"
                  class="live-mod__tag"
                  type="danger"
                  effect="plain"
                  closable
                  @close="onRemoveWord(w)"
                >
                  {{ w }}
                </el-tag>
                <span v-if="!blockWords.length" class="live-mod__empty">还没有屏蔽词</span>
              </div>
              <p class="live-mod__hint">
                命中屏蔽词的弹幕直接拒发；全局敏感词由服务端词库统一遮罩，不需要在这里重复配置。
              </p>
            </el-tab-pane>
          </el-tabs>
        </el-dialog>

        <!-- 发起 PK：对手必须此刻也在直播（服务端会拒不在播的目标），所以候选直接取自直播中列表，
             不让人手填用户 ID -->
        <el-dialog v-model="pkDialogOpen" class="live-pk__dialog" title="⚔ 发起连麦 PK" width="480px" append-to-body>
          <div class="live-pk__dur">
            <span class="live-pk__dur-label">时长</span>
            <el-radio-group v-model="pkDuration" size="small">
              <el-radio-button v-for="d in PK_DURATIONS" :key="d.seconds" :value="d.seconds">
                {{ d.label }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <div v-loading="pkTargetsLoading" class="live-pk__targets">
            <div v-for="t in pkTargets" :key="t.id" class="live-pk__target">
              <UserAvatar :src="t.anchorAvatar" :name="t.anchorName" :size="28" />
              <div class="live-pk__target-info">
                <span class="live-pk__target-name im-ellipsis">{{ t.anchorName }}</span>
                <span class="live-pk__target-title im-ellipsis">{{ t.title }}</span>
              </div>
              <span class="live-pk__target-online">👁 {{ t.onlineCount || 0 }}</span>
              <el-button size="small" type="primary" :loading="pkBusy" @click="onStartPk(t)">发起挑战</el-button>
            </div>
            <div v-if="!pkTargetsLoading && !pkTargets.length" class="live-pk__empty">
              此刻没有别的主播在播：PK 要对方也开着播才有得比，稍后再试
            </div>
          </div>
          <p class="live-pk__hint">
            热度 = 期间礼物 + 点赞 + 观众投票按权重相加，只算两个房间各自的增量（开场前攒下的不算）；投票一人一次，改票即覆盖。
          </p>
        </el-dialog>
      </div>
    </div>

    <!-- ==================== 大厅形态 ==================== -->
    <div v-else class="live-lobby">
      <!-- 顶层双板块切换：视频 / 直播 -->
      <div class="live-lobby__tabs">
        <el-radio-group v-model="activeTab" size="large">
          <el-radio-button value="live">直播</el-radio-button>
          <el-radio-button value="video">视频</el-radio-button>
        </el-radio-group>
      </div>

      <!-- ===== 直播板块（原大厅）===== -->
      <template v-if="activeTab === 'live'">
      <div class="live-lobby__head">
        <div class="live-lobby__filters">
          <el-radio-group :model-value="listQuery.status" size="default" @change="onFilterStatus">
            <el-radio-button :value="null">全部</el-radio-button>
            <el-radio-button :value="1">直播中</el-radio-button>
            <el-radio-button :value="2">已结束</el-radio-button>
          </el-radio-group>
        </div>
        <div class="live-lobby__actions">
          <!-- 数据面板人人可开（没播过就是空态）：要播一场才能看到数，不如让入口一直在那 -->
          <el-button plain @click="statsDialog = true">📊 我的数据</el-button>
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
          <!-- 阶梯实际铺开的档位（主进程上报）：比采集源高的档会被砍掉，主播看这里就知道 1440p 有没有真出 -->
          <span v-if="pushTiers.length" class="live-anchor__tiers">档位 {{ pushTiers.join(' / ') }}</span>
        </div>
        <div v-if="pushError" class="live-anchor__error">{{ pushError }}</div>
        <div class="live-anchor__row live-anchor__row--btns">
          <el-button size="small" @click="enterRoom(myLiving.id)">进入我的直播间</el-button>
          <el-button size="small" type="danger" @click="confirmEndBroadcast">结束直播</el-button>
        </div>
      </div>

      <!-- 可回看场次：入口标记活 retention-days（默认 1 天），而上面那些「已结束」卡片
           只留 ended-room-visible-minutes（默认 1 小时），这条横条负责中间这一天的窗口；
           到期后入口消失，媒体服务器上的分片也由 live-retention.js 一并删掉（见 md/直播.md §10.7） -->
      <div v-if="replays.length" class="live-replays">
        <div class="live-replays__head">🎬 可看回放</div>
        <div class="live-replays__row">
          <div
            v-for="item in replays"
            :key="item.id"
            class="live-replays__card"
            @click="openReplayRoom(item)"
          >
            <div class="live-replays__cover">
              <img v-if="item.cover" :src="item.cover" alt="" />
              <div v-else class="live-replays__placeholder">
                <el-icon :size="22"><VideoCamera /></el-icon>
              </div>
              <span class="live-replays__play">▶</span>
            </div>
            <div class="live-replays__title im-ellipsis">{{ item.title }}</div>
            <div class="live-replays__meta im-ellipsis">
              {{ item.anchorName }} · {{ formatConvTime(item.endTime) }}
            </div>
          </div>
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
            <!-- 已结束且能回看：标在右上角（那里正好不被在线人数占） -->
            <span v-else-if="item.hasReplay" class="live-card__replay">▶ 回放</span>
            <!-- 门禁徽标：直播中的密码房/付费房，左下角提示需解锁才能进（gate 仅 status===1 时有值） -->
            <span
              v-if="item.status === 1 && item.gate && item.gate.type !== 'none'"
              class="live-card__gate"
            >🔒 {{ item.gate.type === 'paid' ? item.gate.price + '积分' : '密码' }}</span>
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
      </template>

      <!-- ===== 视频板块：短视频信息流 ===== -->
      <template v-else>
        <div class="live-video__head">
          <span class="live-video__count">共 {{ videoQuery.total }} 个视频</span>
          <el-button type="primary" @click="openUpload">上传视频</el-button>
        </div>

        <!-- 播放区：点下方卡片在此内联播放 -->
        <div v-if="playingVideo" class="live-video__player">
          <div class="live-video__player-bar">
            <UserAvatar :src="playingVideo.uploaderAvatar" :name="playingVideo.uploaderName" :size="26" />
            <div class="live-video__player-info">
              <div class="live-video__player-title im-ellipsis">{{ playingVideo.title }}</div>
              <div class="live-video__player-meta">{{ playingVideoMeta }}</div>
            </div>
            <el-button text @click="closePlayer">关闭 ✕</el-button>
          </div>
          <video ref="playerEl" class="live-video__video" :src="playSrc" controls autoplay playsinline></video>
        </div>

        <div v-loading="videoLoading" class="live-grid live-grid--video">
          <div v-for="v in videos" :key="v.id" class="video-card" @click="openVideo(v)">
            <div class="video-card__cover">
              <img v-if="coverSrc(v)" :src="coverSrc(v)" alt="" />
              <div v-else class="video-card__placeholder">
                <el-icon :size="34"><VideoCamera /></el-icon>
              </div>
              <span class="video-card__play">▶ {{ v.playCount || 0 }}</span>
              <span class="video-card__duration">{{ fmtDuration(v.duration) }}</span>
              <span v-if="playingVideo && playingVideo.id === v.id" class="video-card__playing">播放中</span>
              <span v-if="v.mine" class="video-card__del" @click.stop="onRemoveVideo(v)">删除</span>
            </div>
            <div class="video-card__info">
              <div class="video-card__title">{{ v.title }}</div>
              <div class="video-card__uploader">
                <UserAvatar :src="v.uploaderAvatar" :name="v.uploaderName" :size="20" />
                <span class="im-ellipsis video-card__name">{{ v.uploaderName }}</span>
              </div>
            </div>
          </div>
          <el-empty v-if="!videoLoading && !videos.length" description="还没有视频，点右上角上传" class="live-grid__empty" />
        </div>

        <div v-if="videoQuery.total > videoQuery.size" class="live-lobby__pager">
          <el-pagination
            layout="prev, pager, next"
            :total="videoQuery.total"
            :page-size="videoQuery.size"
            :current-page="videoQuery.current"
            @current-change="(p) => { videoQuery.current = p; loadVideos() }"
          />
        </div>
      </template>
    </div>

    <!-- ==================== 主播数据面板 ==================== -->
    <AnchorStatsDialog
      v-model="statsDialog"
      @open-room="openStatsRoom"
      @open-replay="openStatsReplay"
    />

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
            <el-option label="1440p (2K)" value="1440p" />
          </el-select>
          <span class="live-form__hint">选的是原画</span>
        </el-form-item>
        <el-form-item label="码率">
          <el-input-number v-model="startForm.bitrateKbps" :min="500" :max="bitrateMax" :step="500" />
          <span class="live-form__hint">kbps，上限 {{ bitrateMax }}</span>
        </el-form-item>
        <el-form-item label="清晰度">
          <span class="live-form__hint live-form__hint--wide">
            自动同时转出多档（最多 {{ ladderHint }}）；观众端始终能看到自动/480p/720p/1080p/1440p 五个选项，
            比采集源高的档会被跳过，本场没铺到的档在观众那边置灰
          </span>
        </el-form-item>
        <el-form-item label="门禁">
          <el-input v-model="startForm.password" maxlength="32" show-password placeholder="设房间密码（可空）" style="width: 200px" />
          <span class="live-form__hint">填了即密码直播</span>
        </el-form-item>
        <el-form-item label="入场价">
          <el-input-number v-model="startForm.price" :min="0" :max="1000000" :step="10" :disabled="!!startForm.password" />
          <span class="live-form__hint">积分，&gt;0 即付费直播（与密码互斥）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="startDialog = false">取消</el-button>
        <el-button type="primary" :loading="starting" @click="submitStart">开始推流</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 门禁解锁弹窗（观众进密码/付费房且未解锁） ==================== -->
    <!-- :show-close=false 且不绑 @close：避免解锁成功时程序关框误触发「回大厅」，离房只走「暂不观看」按钮 -->
    <el-dialog
      v-model="gateDialog"
      :title="room && room.gate && room.gate.type === 'paid' ? '付费直播' : '密码直播'"
      width="360px"
      :close-on-click-modal="false"
      :show-close="false"
    >
      <div v-if="room && room.gate" class="live-gate">
        <p class="live-gate__title im-ellipsis">{{ room.title }}</p>
        <template v-if="room.gate.type === 'paid'">
          <p>本场为付费直播，解锁需支付 <b>{{ room.gate.price }}</b> 积分。</p>
          <p class="live-gate__hint">积分为站内虚拟币，支付后不可退回（同送礼口径）。</p>
        </template>
        <template v-else>
          <p>本场为密码直播，请输入主播设定的房间密码。</p>
          <el-input
            v-model="gatePassword"
            type="password"
            show-password
            maxlength="32"
            placeholder="房间密码"
            @keyup.enter="submitGateUnlock"
          />
        </template>
      </div>
      <template #footer>
        <el-button @click="gateCancel">暂不观看</el-button>
        <el-button type="primary" :loading="gateBusy" @click="submitGateUnlock">
          {{ room && room.gate && room.gate.type === 'paid' ? '支付并解锁' : '解锁' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 上传视频对话框 ==================== -->
    <el-dialog
      v-model="uploadDialog"
      title="上传视频"
      width="520px"
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
    >
      <el-form label-width="72px">
        <el-form-item label="视频">
          <div class="live-up__row">
            <input ref="videoInput" type="file" accept="video/*" hidden @change="onVideoChange" />
            <el-button @click="videoInput && videoInput.click()">{{ videoFile ? '重新选择' : '选择视频文件' }}</el-button>
            <span v-if="videoFile" class="live-up__name im-ellipsis">{{ videoFile.name }}</span>
          </div>
          <div v-if="videoFile" class="live-up__meta">
            <span v-if="videoMeta.duration">时长 {{ fmtDuration(videoMeta.duration) }}</span>
            <span v-if="videoMeta.width"> · {{ videoMeta.width }}×{{ videoMeta.height }}</span>
          </div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="uploadForm.title" maxlength="60" show-word-limit placeholder="给视频起个标题" />
        </el-form-item>
        <el-form-item label="封面">
          <div class="live-up__cover">
            <input ref="coverInput" type="file" accept="image/*" hidden @change="onCoverChange" />
            <div class="live-up__cover-preview">
              <img v-if="coverPreview" :src="coverPreview" alt="" />
              <div v-else class="live-up__cover-ph">默认首帧</div>
            </div>
            <div class="live-up__cover-btns">
              <el-button size="small" @click="pickCover">选择封面</el-button>
              <el-button v-if="coverPreview" size="small" text @click="resetCover">恢复首帧</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="uploading" label="进度">
          <el-progress :percentage="uploadPercent" :status="uploadPercent >= 100 ? 'success' : ''" style="width: 100%" />
          <span class="live-up__stage">{{ uploadStageText }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadDialog = false" :disabled="uploading">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">发布</el-button>
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

.live-room__report {
  margin-left: auto;
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
  /* 礼物弹层是绝对定位的底部抽屉，锚在这一层才能横跨画面+侧栏整幅宽 */
  position: relative;
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
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #fff;
  background: rgba(0, 0, 0, 0.6);
  font-size: 15px;
}

/* 回放态顶条：左上角一枚「回放」标 + 一行元数据 + 退出按钮 */
.live-replay {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border-radius: 14px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}

.live-replay__tag {
  padding: 1px 6px;
  border-radius: 8px;
  background: rgba(255, 120, 30, 0.9);
}

.live-replay__meta {
  opacity: 0.85;
}

/* 清晰度选择器：右上角浮层，与连麦徽标同层位但可交互（徽标是 pointer-events:none） */
.live-quality {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border-radius: 14px;
  background: rgba(0, 0, 0, 0.55);
  max-width: calc(100% - 24px);
}

/* 本场档位汇总：空间不够就裁掉，不让它把右上角浮层撑到遮住画面 */
.live-quality__meta {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.75);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 150px;
}

/* 下拉项右侧的不可用原因（本场无 / 单档 / 不支持） */
.live-quality__note {
  margin-left: 10px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

/* 连麦徽标占着右上角时往下挪一格，别叠在一起 */
.live-quality--below {
  top: 46px;
}

.live-quality__label {
  font-size: 12px;
  color: #fff;
  white-space: nowrap;
}

.live-quality :deep(.el-select) {
  width: 96px;
}

/* 连麦：观众侧徽标 + 主播/嘉宾面板 */
.live-linkmic__badge {
  position: absolute;
  top: 12px;
  right: 12px;
  padding: 4px 10px;
  border-radius: 14px;
  font-size: 13px;
  color: #fff;
  background: rgba(64, 158, 255, 0.85);
  pointer-events: none;
}

/* 连麦分屏舞台：左主播、右嘉宾（多人时右侧纵向堆叠），与 composer 画给观众的分屏同构 */
.live-linkmic__stage {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: stretch;
  background: #000;
}

.live-linkmic__side {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* 中缝：一条白线把两栏分开，PK 感更明确 */
.live-linkmic__side + .live-linkmic__side {
  border-left: 2px solid rgba(255, 255, 255, 0.18);
}

.live-linkmic__cell {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  background: #111;
}

.live-linkmic__video {
  width: 100%;
  height: 100%;
  /* contain 而非 cover：摄像头多为 16:9，分屏每格是竖长条，cover 会只裁中间一条、把人脸切掉 */
  object-fit: contain;
  background: #000;
}

.live-linkmic__tag {
  position: absolute;
  left: 8px;
  bottom: 8px;
  max-width: calc(100% - 16px);
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  pointer-events: none;
}

/* 顶部工具条：连麦状态 + 本地静音 + 断开连麦（主播）/ 下麦（嘉宾） */
.live-linkmic__bar {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.6), rgba(0, 0, 0, 0));
}

.live-linkmic__bar-title {
  flex: 1;
  min-width: 0;
  color: #fff;
  font-size: 13px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.live-linkmic {
  flex: none;
  /* 申请/在麦人数多时内部滚动，不把输入栏顶出侧栏 */
  max-height: 168px;
  overflow-y: auto;
  border-top: 1px solid #ebeef5;
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.live-linkmic__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

/* 「断开全部」贴右，与计数同一行，不致于多占一行把麦位列表往下顶 */
.live-linkmic__end {
  margin-left: auto;
}

.live-linkmic__req,
.live-linkmic__guest {
  display: flex;
  align-items: center;
  gap: 8px;
}

.live-linkmic__req-name,
.live-linkmic__guest-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}

.live-linkmic__req-btns {
  display: flex;
  gap: 6px;
}

.live-linkmic__hint {
  font-size: 12px;
  color: #e6a23c;
}

/* 礼物飘屏：覆盖在画面上，不拦截鼠标 */
.live-gift__floats {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 60px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  pointer-events: none;
  overflow: hidden;
}

.live-gift__float {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 20px;
  background: rgba(0, 0, 0, 0.45);
  color: #ffd76a;
  font-size: 14px;
  align-self: flex-start;
  animation: im-gift-float 3.2s ease-out forwards;
}

.live-gift__float--effect {
  background: linear-gradient(90deg, rgba(255, 94, 158, 0.55), rgba(120, 80, 255, 0.45));
  color: #fff;
  font-weight: 600;
}

.live-gift__float-icon {
  font-size: 22px;
}

@keyframes im-gift-float {
  0% {
    opacity: 0;
    transform: translateX(-40px);
  }
  12% {
    opacity: 1;
  }
  80% {
    opacity: 1;
  }
  100% {
    opacity: 0;
    transform: translateX(30%);
  }
}

/* 贡献榜 */
.live-gift__rank {
  flex: none;
  max-height: 132px;
  overflow-y: auto;
  padding: 8px 12px;
  border-top: 1px solid var(--im-border, #e4e7ed);
  background: #fafafa;
}

.live-gift__rank-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}

.live-gift__rank-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
  font-size: 13px;
}

.live-gift__rank-no {
  width: 18px;
  text-align: center;
  color: #909399;
}

.live-gift__rank-name {
  flex: 1;
  min-width: 0;
}

.live-gift__rank-score {
  color: #ff7a45;
  font-weight: 600;
}

/* 礼物弹层（抖音式底部抽屉）：整幅宽盖在画面+侧栏下沿，礼物再多也绝不截断 */
.live-gift__sheet {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: flex-end;
}

.live-gift__sheet-mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
}

.live-gift__sheet-body {
  position: relative;
  width: 100%;
  max-height: 62%;
  overflow-y: auto;
  /* 只允许纵向滚动：否则子项一超宽就会产生横向偏移，把右侧按钮静默裁掉 */
  overflow-x: hidden;
  padding: 12px 18px 14px;
  border-top: 1px solid var(--im-border, #e4e7ed);
  border-radius: 14px 14px 0 0;
  background: #fff;
  box-shadow: 0 -8px 28px rgba(0, 0, 0, 0.18);
}

.live-gift__sheet-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.live-gift__sheet-title {
  flex: none;
  font-size: 14px;
  font-weight: 600;
}

.live-gift__sheet-balance {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.live-gift__sheet-balance b {
  color: #ff7a45;
}

/* 关闭（返回）按钮：靠右且 flex:none，手机上永远可见可点 */
.live-gift__sheet-close {
  margin-left: auto;
  flex: none;
}

/* 签到 / 充值行 */
.live-gift__wallet {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--im-border, #e4e7ed);
}

.live-gift__grid {
  display: grid;
  /* 自适应列数：宽屏一排能放 7~8 个，窄屏自动折行；旧版固定 4 列被 320px 侧栏挤扁 */
  grid-template-columns: repeat(auto-fill, minmax(86px, 1fr));
  gap: 10px;
}

.live-gift__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 8px 4px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 10px;
  background: #f7f8fa;
  cursor: pointer;
  transition: transform 0.12s ease, border-color 0.12s ease;
}

.live-gift__item:hover {
  transform: translateY(-2px);
}

.live-gift__item--active {
  border-color: #ff7a45;
  background: #fff5f0;
  box-shadow: 0 0 0 1px #ff7a45 inset;
}

.live-gift__item-icon {
  font-size: 28px;
  line-height: 1.15;
}

.live-gift__item-name {
  font-size: 12px;
  color: #606266;
}

.live-gift__item-price {
  font-size: 12px;
  font-weight: 600;
  color: #ff7a45;
}

.live-gift__empty {
  grid-column: 1 / -1;
  padding: 18px 0;
  text-align: center;
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
}

.live-gift__sheet-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--im-border, #e4e7ed);
}

.live-gift__label {
  font-size: 13px;
  color: #606266;
}

.live-gift__total {
  font-size: 13px;
  color: #ff7a45;
}

.live-gift__send-btn {
  margin-left: auto;
  min-width: 108px;
}

.live-gift__lack {
  font-size: 12px;
  color: #f56c6c;
}

.live-gift__recharge {
  width: 96px;
}

/* 弹层动效：遮罩淡入，面板从底部推上来 */
.im-gift-up-enter-active,
.im-gift-up-leave-active {
  transition: opacity 0.2s ease;
}

.im-gift-up-enter-active .live-gift__sheet-body,
.im-gift-up-leave-active .live-gift__sheet-body {
  transition: transform 0.24s ease;
}

.im-gift-up-enter-from,
.im-gift-up-leave-to {
  opacity: 0;
}

.im-gift-up-enter-from .live-gift__sheet-body,
.im-gift-up-leave-to .live-gift__sheet-body {
  transform: translateY(100%);
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
  /* 侧栏自身不得溢出：内容多时交给 list / rank / linkmic 各自滚动，
     否则礼物、连麦、输入栏会被窗口底边直接截掉（用户反馈的「显示不完全」） */
  min-height: 0;
  overflow: hidden;
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
  /* wrap 是为禁言菜单准备的：菜单靠 flex:1 0 100% 换行占满一条，否则会被当成第二个气泡挤在右侧 */
  flex-wrap: wrap;
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

/* 主播侧的观众昵称其实是个按钮：不给出 pointer 与 hover，它看起来就只是段文字 */
.live-danmaku__nick--actionable {
  cursor: pointer;
}

.live-danmaku__nick--actionable:hover {
  color: var(--el-color-primary);
  text-decoration: underline;
}

.live-danmaku__muted {
  flex: none;
}

.live-danmaku__mute-menu {
  flex: 1 0 100%;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 6px;
  background: var(--im-panel, #f5f7fa);
}

/* 房管面板（el-dialog 内容靠 slot 编译到本组件作用域，所以 scoped 样式照常生效） */
.live-mod__row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--im-border, #ebeef5);
}

.live-mod__name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
}

.live-mod__expire {
  flex: none;
  color: #e6a23c;
  font-size: 12px;
}

.live-mod__empty {
  padding: 12px 0;
  color: #909399;
  font-size: 13px;
  text-align: center;
}

.live-mod__words-input {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.live-mod__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 32px;
}

.live-mod__hint {
  margin: 10px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}

/* --- 连麦 PK：进度面板挂在画面上沿居中 --- */
/* 半透黑底 + 毛玻璃：底下是任意颜色的直播画面，用主题色反而会在浅色主题上被视频淹掉 */
.live-pk {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3;
  width: min(420px, calc(100% - 110px));
  padding: 8px 10px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
  color: #fff;
  font-size: 12px;
}

.live-pk--ended {
  border-color: rgba(255, 214, 102, 0.55);
}

.live-pk__row {
  display: flex;
  align-items: center;
  gap: 6px;
  /* 右上角的关闭是绝对定位的，不给这行留白就会压住对方昵称的尾字 */
  padding-right: 16px;
}

.live-pk__name {
  flex: 1;
  min-width: 0;
  font-weight: 600;
  text-align: center;
}

.live-pk__vs {
  flex: none;
  font-size: 11px;
  letter-spacing: 1px;
  color: #ffd666;
}

.live-pk__close {
  position: absolute;
  top: 4px;
  right: 6px;
  border: 0;
  padding: 2px 4px;
  background: transparent;
  color: rgba(255, 255, 255, 0.75);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.live-pk__track {
  position: relative;
  margin-top: 8px;
  height: 14px;
  border-radius: 7px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.18);
}

/* 热度条从左侧（我方）向右推进；双方都是 0 时服务端给 50%，看着是均分而不是空条 */
.live-pk__fill {
  height: 100%;
  background: linear-gradient(90deg, #409eff, #67c23a);
  transition: width 0.4s ease;
}

.live-pk__heat {
  position: absolute;
  top: 0;
  line-height: 14px;
  font-size: 10px;
  color: #fff;
  text-shadow: 0 0 3px rgba(0, 0, 0, 0.75);
}

.live-pk__heat--self {
  left: 6px;
}

.live-pk__heat--opp {
  right: 6px;
}

.live-pk__foot {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 7px;
}

.live-pk__foot--end {
  justify-content: center;
}

/* 倒计时用等宽数字：不然每一位都在抖宽度，整条 foot 跟着颤 */
.live-pk__count {
  font-variant-numeric: tabular-nums;
}

.live-pk__votes {
  color: rgba(255, 255, 255, 0.82);
}

.live-pk__foot .el-button + .el-button {
  margin-left: 0;
}

.live-pk__result {
  font-weight: 600;
}

.live-pk__result--win {
  color: #ffd666;
}

.live-pk__reason {
  color: rgba(255, 255, 255, 0.72);
}

/* 发起弹窗：候选列表要滚动，30 个在播房间不可能一屏列出 */
.live-pk__dur {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.live-pk__dur-label {
  color: #909399;
  font-size: 13px;
}

.live-pk__targets {
  max-height: 260px;
  overflow-y: auto;
}

.live-pk__target {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--im-border);
}

.live-pk__target-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.live-pk__target-name {
  font-size: 13px;
  font-weight: 600;
}

.live-pk__target-title,
.live-pk__target-online {
  font-size: 12px;
  color: #909399;
}

.live-pk__empty {
  padding: 18px 0;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.live-pk__hint {
  margin: 10px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
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
  flex: none;
  /* 两行布局：第一行输入+发送，第二行点赞/礼物/上麦。旧版单行在 320px 下右侧按钮被裁 */
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-top: 1px solid var(--im-border, #e4e7ed);
}

.live-danmaku__send {
  display: flex;
  gap: 6px;
}

.live-danmaku__acts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.live-danmaku__like,
.live-danmaku__gift {
  flex: none;
}

.live-danmaku__gift--on {
  border-color: #ff7a45;
  color: #ff7a45;
}

.live-danmaku__tip {
  font-size: 12px;
  color: #909399;
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

/* 本场实际铺开的阶梯档位 */
.live-anchor__tiers {
  font-size: 12px;
  color: #909399;
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

.live-card__replay {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  color: #fff;
  background: rgba(255, 120, 30, 0.85);
}

/* 门禁徽标：直播中的密码房/付费房，放左下角（避开状态标签与在线人数的上部） */
.live-card__gate {
  position: absolute;
  bottom: 8px;
  left: 8px;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  color: #fff;
  background: rgba(0, 0, 0, 0.6);
}

/* 大厅「可看回放」横条：小尺寸封面 + 标题/主播，横向滑动 */
.live-replays {
  margin-bottom: 14px;
}

.live-replays__head {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}

.live-replays__row {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.live-replays__card {
  flex: 0 0 148px;
  cursor: pointer;
}

.live-replays__cover {
  position: relative;
  height: 84px;
  border-radius: 8px;
  overflow: hidden;
  background: #111;
}

.live-replays__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.live-replays__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #666;
}

.live-replays__play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: #fff;
  background: rgba(0, 0, 0, 0.35);
  opacity: 0.9;
}

.live-replays__title {
  font-size: 13px;
  margin-top: 6px;
}

.live-replays__meta {
  font-size: 12px;
  color: #999;
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

/* 整行都是提示（阶梯说明）：不需要左边那段间距，长句子要能折行 */
.live-form__hint--wide {
  margin-left: 0;
  line-height: 1.6;
}

/* 门禁解锁弹窗 */
.live-gate__title {
  margin: 0 0 12px;
  font-weight: 600;
  font-size: 15px;
}
.live-gate__hint {
  margin: 8px 0 0;
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

  /* 礼物弹层手机端适配：面板占高拉开，否则网格+送礼行+签到充值挤不进一屏 */
  .live-gift__sheet-body {
    max-height: 80%;
    padding: 10px 12px 16px;
    border-radius: 12px 12px 0 0;
  }

  .live-gift__grid {
    grid-template-columns: repeat(auto-fill, minmax(66px, 1fr));
    gap: 8px;
  }

  .live-gift__item {
    padding: 6px 2px;
  }

  .live-gift__item-icon {
    font-size: 24px;
  }

  .live-gift__item-name,
  .live-gift__item-price {
    font-size: 11px;
  }

  .live-gift__sheet-foot {
    gap: 8px;
  }

  /* 手机上不让 margin-left:auto 把按钮顶到右侧边缘外，改成满宽主操作 */
  .live-gift__send-btn {
    margin-left: 0;
    flex: 1 1 100%;
    min-width: 0;
  }

  .live-gift__wallet {
    gap: 6px;
  }

  .live-gift__recharge {
    width: auto;
    flex: 1;
    min-width: 76px;
  }

  .live-gift__wallet .el-button {
    flex: 1;
    margin-left: 0;
  }
}

/* ------------------------------ 顶层视频/直播双板块 ------------------------------ */
.live-lobby__tabs {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

/* ------------------------------ 视频板块 ------------------------------ */
.live-video__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.live-video__count {
  font-size: 13px;
  color: #909399;
}

.live-video__player {
  margin-bottom: 16px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 8px;
  overflow: hidden;
  background: #000;
}

.live-video__player-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: #fff;
}

.live-video__player-info {
  flex: 1;
  min-width: 0;
}

.live-video__player-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.live-video__player-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.live-video__video {
  display: block;
  width: 100%;
  max-height: 60vh;
  background: #000;
}

/* B 站风：无框卡片，封面 16:9 裁切，标题两行 */
.video-card {
  display: flex;
  flex-direction: column;
  cursor: pointer;
}

.video-card__cover {
  position: relative;
  width: 100%;
  /* 兼容写法：padding-top 撑出 16:9，避免旧内核 aspect-ratio 失效被竖图撑高 */
  padding-top: 56.25%;
  border-radius: 6px;
  overflow: hidden;
  background: #1f2329;
  transition: box-shadow 0.2s;
}

.video-card:hover .video-card__cover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.video-card__cover img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.video-card__placeholder {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4b5563;
}

.video-card__duration {
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 2px 6px;
  border-radius: 0 0 6px 0;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  line-height: 16px;
}

.video-card__play {
  position: absolute;
  left: 6px;
  top: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 12px;
}

.video-card__playing {
  position: absolute;
  left: 6px;
  bottom: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #fb7299;
  color: #fff;
  font-size: 12px;
}

.video-card__del {
  position: absolute;
  right: 6px;
  top: 6px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.2s;
}

.video-card:hover .video-card__del {
  opacity: 1;
}

.video-card__info {
  padding: 8px 2px 0;
}

.video-card__title {
  font-size: 14px;
  line-height: 20px;
  color: #18191c;
  margin-bottom: 6px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  word-break: break-all;
}

.video-card:hover .video-card__title {
  color: var(--el-color-primary, #409eff);
}

.video-card__uploader {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
}

.video-card__name {
  flex: 1;
  min-width: 0;
}

/* 视频卡片更密一些，接近 B 站首页多列瀑布流观感 */
.live-grid--video {
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px 12px;
}

/* ------------------------------ 上传视频对话框 ------------------------------ */
.live-up__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.live-up__name {
  flex: 1;
  min-width: 0;
  color: #606266;
  font-size: 13px;
}

.live-up__meta {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.live-up__cover {
  display: flex;
  align-items: center;
  gap: 12px;
}

.live-up__cover-preview {
  width: 128px;
  height: 72px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 6px;
  overflow: hidden;
  background: #1f2329;
  flex: none;
}

.live-up__cover-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.live-up__cover-ph {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
  font-size: 12px;
}

.live-up__cover-btns {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.live-up__stage {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
