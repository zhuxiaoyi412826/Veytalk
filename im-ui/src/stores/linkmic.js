import { defineStore } from 'pinia'
import { ref, shallowRef, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { send as wsSend } from '@/ws/socket'
import { fetchLinkmicConfig } from '@/api/live'
import { createCallPeer } from '@/utils/webrtc'
import { createLiveComposer, composerSupported, webmBaseFromPlayUrl } from '@/utils/liveComposer'
import { asId } from '@/utils/id'
import { dlog } from '@/utils/logger'

/**
 * 直播连麦状态机（Pinia）：在「观众（嘉宾）」与「该场主播」之间编排 1:1 WebRTC 连麦。
 *
 * 与通话 store 同源复用 createCallPeer 引擎与 ws 信令骨架，差别在三处：
 * - 信令类型是 linkmic（linkAction：request/accept/reject/offer/answer/ice/leave），走主 /ws；
 * - 一个主播可同时接多名嘉宾（每路一个 callId、一路独立 PeerConnection），故用 sessions Map 管理；
 * - 主播侧多一路「合成推流」：把主播本地流 + 各嘉宾远端流交给 liveComposer 合成成 WebM 上传，
 *   观众端再从 HLS 切到 MSE/WebM 播放器（播放器切换在 Live.vue + utils/webmPlayer.js，不在本 store）。
 *
 * 角色：role='anchor' 我在这场的麦克风位接受别人上麦；role='guest' 我是申请上麦的观众。同一页面只会是一种。
 * 引擎句柄、MediaStream、composer 都是裸引用（响应式代理会破坏 WebRTC），只把界面要读的标量放进响应式 state。
 */

function newCallId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `link-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const INSECURE_HINT =
  '当前访问地址不是 HTTPS/localhost，浏览器禁止使用麦克风/摄像头：手机请用 https://<电脑IP>:5173 访问（npm run gen:cert 生成证书）'

function failureText(reason) {
  switch (reason) {
    case 'not-viewer':
      return '需要先在这间直播间里才能申请上麦'
    case 'anchor-offline':
      return '主播当前不在线'
    case 'self-busy':
      return '你已在一路连麦中'
    case 'full':
      return '连麦位已满'
    case 'timeout':
      return '上麦申请无应答'
    case 'gone':
      return '连麦已结束'
    case 'forbidden':
      return '无权操作该连麦'
    case 'max-duration':
      return '连麦已达时长上限'
    default:
      return '连麦失败'
  }
}

export const useLinkmicStore = defineStore('linkmic', () => {
  /* ------------------------------ 响应式状态 ------------------------------ */
  const enabled = ref(false)
  const maxSlots = ref(1)
  const role = ref('') // anchor | guest
  const roomId = ref(null)
  const mediaBase = ref('')
  // 嘉宾侧：本人这路
  const guestStatus = ref('idle') // idle | requesting | active
  const guestHint = ref('')
  // 主播侧：待处理申请 + 在麦嘉宾列表（供界面渲染麦位/接受按钮）
  const pendingRequests = ref([]) // { callId, guestUserId }
  const onMicGuests = ref([]) // { callId, guestUserId, name, avatar }
  const composerActive = ref(false)

  /* ------------------------------ 非响应式内部态 ------------------------------ */
  let config = null // { enabled, maxSlots, iceServers }
  let configLoading = null
  let iceServers = []
  /** callId → { engine, guestUserId, remoteStream, role } 一路连麦一个引擎 */
  const sessions = new Map()
  /** 主播侧共享的合成推流器（首路连麦建立，最后一路下麦销毁） */
  let composer = null
  let composerAnchorSourceId = null // 记录已挂进 composer 的主播本地流 id，避免多路重复挂
  let guestCallId = ''
  let guestEngine = null
  const guestRemoteStream = shallowRef(null)

  const isAnchor = computed(() => role.value === 'anchor')
  const isGuest = computed(() => role.value === 'guest')
  const activeCount = computed(() => onMicGuests.value.length)

  /* ------------------------------ 配置 ------------------------------ */

  async function ensureConfig(force = false) {
    if (config && !force) {
      return config
    }
    if (configLoading) {
      return configLoading
    }
    const id = roomId.value
    configLoading = fetchLinkmicConfig(id)
      .then((vo) => {
        config = {
          enabled: !!(vo && vo.enabled),
          maxSlots: (vo && vo.maxSlots) || 1,
          iceServers: (vo && vo.iceServers) || []
        }
        enabled.value = config.enabled
        maxSlots.value = config.maxSlots
        iceServers = config.iceServers
        return config
      })
      .catch(() => {
        config = { enabled: false, maxSlots: 1, iceServers: [] }
        enabled.value = false
        return config
      })
      .finally(() => {
        configLoading = null
      })
    return configLoading
  }

  function hasMediaApi() {
    return !!(typeof navigator !== 'undefined' && navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
  }

  /* ------------------------------ 组帧上行 ------------------------------ */

  function linkSend(linkAction, extra = {}) {
    const payload = { linkAction, roomId: Number(roomId.value), ...extra }
    return wsSend('linkmic', payload)
  }

  /* ------------------------------ 引擎工厂 ------------------------------ */

  /** 建一路连麦引擎：本地采集 + 远端流回调 + 本地产生的 offer/answer/ice 转成 linkmic 上行 */
  function buildEngine(callId, onLocalStream, onRemoteStream) {
    return createCallPeer({
      iceServers,
      mediaType: 'video',
      onLocalSignal: (action, data) => {
        linkSend(action, { callId, ...data })
      },
      onLocalStream,
      onRemoteStream,
      onConnectionState: (state) => dlog('[linkmic] pc state', callId, state)
    })
  }

  /* ============================== 主播侧 ============================== */

  /**
   * 以主播身份初始化本场连麦：记下房间与合成流媒体目录基址，拉 ICE 配置。
   * @param {string|number} id 房间 ID
   * @param {string} playUrl 后端下发的播放地址（用于推导 webm 合成流的写入/读取目录）
   */
  async function initAsAnchor(id, playUrl) {
    roomId.value = asId(id)
    role.value = 'anchor'
    mediaBase.value = webmBaseFromPlayUrl(playUrl)
    await ensureConfig()
  }

  /** 收到观众上麦申请：入待处理列表（界面弹出「接受/拒绝」） */
  function noteRequest(data) {
    if (!data || !data.callId) {
      return
    }
    if (pendingRequests.value.some((r) => r.callId === data.callId)) {
      return
    }
    pendingRequests.value.push({ callId: data.callId, guestUserId: asId(data.fromUserId) })
    ElMessage.info('有观众申请上麦')
  }

  /** 接受某条申请：建主播侧引擎、准备合成推流、回 accept（嘉宾侧收到后发 offer） */
  async function acceptRequest(callId, meta = {}) {
    if (sessions.has(callId)) {
      return
    }
    if (!composerSupported() && !composer) {
      ElMessage.error('当前浏览器不支持 WebM 合成推流，无法连麦')
      linkSend('reject', { callId, reason: 'unsupported' })
      return
    }
    await ensureConfig()
    const ok = linkSend('accept', { callId })
    if (!ok) {
      ElMessage.error('连麦信令发送失败，请重试')
      return
    }
    const entry = { callId, role: 'anchor', guestUserId: asId(meta.guestUserId), remoteStream: null, engine: null }
    sessions.set(callId, entry)
    onMicGuests.value.push({ callId, guestUserId: asId(meta.guestUserId), name: meta.name || '嘉宾', avatar: meta.avatar || '' })
    ensureComposer()
  }

  /** 拒绝某条申请 */
  function rejectRequest(callId) {
    linkSend('reject', { callId, reason: 'anchor-reject' })
    pendingRequests.value = pendingRequests.value.filter((r) => r.callId !== callId)
  }

  /** 主播踢下麦 / 主动结束某一路 */
  function kickGuest(callId) {
    linkSend('leave', { callId, reason: 'anchor-end' })
    teardownSession(callId)
  }

  /** 收到嘉宾 offer：建/取引擎，落 remoteDescription（引擎自动回 answer） */
  async function handleOffer(callId, sdpType, sdp) {
    let entry = sessions.get(callId)
    if (!entry) {
      // 极端时序：offer 早于本地 accept 记账，补建一个
      entry = { callId, role: 'anchor', guestUserId: null, remoteStream: null, engine: null }
      sessions.set(callId, entry)
      ensureComposer()
    }
    if (!entry.engine) {
      await startAnchorEngine(entry)
    }
    try {
      await entry.engine.handleDescription(sdpType, sdp)
    } catch (e) {
      dlog('[linkmic] handleDescription 失败', e)
    }
  }

  async function handleIce(callId, candidate) {
    const entry = sessions.get(callId)
    if (entry && entry.engine && candidate) {
      await entry.engine.handleCandidate(candidate)
    }
  }

  /** 主播侧某路引擎：本地流挂进 composer 当主播画面，远端嘉宾流挂进 composer 当画中画 */
  async function startAnchorEngine(entry) {
    const engine = buildEngine(
      entry.callId,
      (stream) => {
        // 主播本地流只需挂一次（多路连麦共享同一路主播摄像头）
        if (composer && composerAnchorSourceId == null) {
          composerAnchorSourceId = 'anchor'
          composer.addSource('anchor', stream, 'anchor')
        }
      },
      (stream) => {
        entry.remoteStream = stream
        if (composer) {
          composer.addSource(entry.callId, stream, 'guest')
        }
      }
    )
    entry.engine = engine
    try {
      await engine.initMedia()
    } catch (e) {
      ElMessage.error(`无法获取麦克风/摄像头：${(e && e.name) || ''}`)
      teardownSession(entry.callId)
    }
  }

  /** 首路连麦时建合成推流器并开始；已在则复用 */
  function ensureComposer() {
    if (composer || !mediaBase.value) {
      return
    }
    composer = createLiveComposer({
      mediaBase: mediaBase.value,
      onState: (state) => {
        composerActive.value = state === 'recording' || state === 'starting'
        if (state === 'error') {
          ElMessage.error('合成推流中断')
        }
      },
      onWarn: (msg) => dlog('[linkmic] composer warn', msg)
    })
    composer.start()
  }

  /* ============================== 嘉宾侧 ============================== */

  /** 以嘉宾（观众）身份初始化：只拉 ICE 配置，本人一路 */
  async function initAsGuest(id) {
    roomId.value = asId(id)
    role.value = 'guest'
    await ensureConfig()
  }

  /** 申请上麦 */
  async function requestMic() {
    if (guestStatus.value !== 'idle') {
      return
    }
    if (!enabled.value) {
      ElMessage.warning('本场未开启连麦')
      return
    }
    if (!hasMediaApi()) {
      ElMessage.error(INSECURE_HINT)
      return
    }
    await ensureConfig()
    const id = newCallId()
    guestCallId = id
    const ok = linkSend('request', { callId: id, mediaType: 'video' })
    if (!ok) {
      ElMessage.error('连麦信令发送失败，请重试')
      return
    }
    guestStatus.value = 'requesting'
    guestHint.value = '正在申请上麦…'
  }

  /** 收到主播 accept：建嘉宾引擎并发 offer */
  async function onAccepted() {
    if (guestStatus.value !== 'requesting') {
      return
    }
    if (!hasMediaApi()) {
      ElMessage.error(INSECURE_HINT)
      linkSend('leave', { callId: guestCallId, reason: 'insecure' })
      resetGuest()
      return
    }
    const engine = buildEngine(
      guestCallId,
      (stream) => {
        guestEngine = engine
        void stream
      },
      (stream) => {
        guestRemoteStream.value = stream
      }
    )
    guestEngine = engine
    try {
      await engine.initMedia()
    } catch (e) {
      guestHint.value = `无法获取麦克风/摄像头：${(e && e.name) || ''}`
      ElMessage.error(guestHint.value)
      linkSend('leave', { callId: guestCallId, reason: 'media-error' })
      resetGuest()
      return
    }
    guestStatus.value = 'active'
    guestHint.value = '已上麦'
    try {
      await engine.createOffer()
    } catch {
      guestHint.value = '协商失败'
    }
  }

  async function guestHandleDescription(sdpType, sdp) {
    if (guestEngine) {
      await guestEngine.handleDescription(sdpType, sdp)
    }
  }

  async function guestHandleIce(candidate) {
    if (guestEngine && candidate) {
      await guestEngine.handleCandidate(candidate)
    }
  }

  /** 嘉宾主动下麦 */
  function leaveMic() {
    if (guestCallId) {
      linkSend('leave', { callId: guestCallId, reason: 'guest-leave' })
    }
    resetGuest(true)
  }

  function resetGuest(closeEngine = false) {
    if (closeEngine && guestEngine) {
      guestEngine.close()
    }
    guestEngine = null
    guestRemoteStream.value = null
    guestCallId = ''
    guestStatus.value = 'idle'
    guestHint.value = ''
  }

  /* ------------------------------ 下行信令统一入口（由 dispatch 转交） ------------------------------ */

  async function onSignal(data) {
    if (!data || !data.linkAction) {
      return
    }
    const action = data.linkAction
    const cid = data.callId

    // 主播视角才会收到的：request
    if (action === 'request') {
      if (role.value === 'anchor') {
        noteRequest(data)
      }
      return
    }

    // 其余按 callId 归属裁决
    if (isAnchor.value) {
      switch (action) {
        case 'offer':
          await handleOffer(cid, data.sdpType, data.sdp)
          break
        case 'answer': {
          const entry = sessions.get(cid)
          if (entry && entry.engine) {
            await entry.engine.handleDescription(data.sdpType, data.sdp)
          }
          break
        }
        case 'ice':
          await handleIce(cid, data.candidate)
          break
        case 'leave':
        case 'reject':
          teardownSession(cid)
          break
        case 'failed':
          if (data.reason) {
            ElMessage.warning(failureText(data.reason))
          }
          teardownSession(cid)
          break
        default:
          break
      }
      return
    }

    if (isGuest.value) {
      if (cid && guestCallId && asId(cid) !== asId(guestCallId)) {
        return
      }
      switch (action) {
        case 'accept':
          await onAccepted()
          break
        case 'answer':
          await guestHandleDescription(data.sdpType, data.sdp)
          break
        case 'ice':
          await guestHandleIce(data.candidate)
          break
        case 'reject':
          resetGuest(true)
          ElMessage.warning(data.reason ? failureText(data.reason) : '上麦申请被拒绝')
          break
        case 'leave':
          resetGuest(true)
          guestHint.value = ''
          break
        case 'failed':
          resetGuest(true)
          ElMessage.warning(failureText(data.reason))
          break
        default:
          break
      }
    }
  }

  /** 销毁主播侧某一路连麦：关引擎、从 composer 摘源、若无人在麦则停 composer */
  function teardownSession(callId) {
    const entry = sessions.get(callId)
    pendingRequests.value = pendingRequests.value.filter((r) => r.callId !== callId)
    onMicGuests.value = onMicGuests.value.filter((g) => g.callId !== callId)
    if (!entry) {
      maybeStopComposer()
      return
    }
    if (entry.engine) {
      entry.engine.close()
    }
    if (composer) {
      composer.removeSource(callId)
    }
    sessions.delete(callId)
    maybeStopComposer()
  }

  function maybeStopComposer() {
    if (composer && sessions.size === 0) {
      composer.stop()
      composer = null
      composerAnchorSourceId = null
      composerActive.value = false
    }
  }

  /* ------------------------------ 退房/关播统一收尾 ------------------------------ */

  function reset() {
    // 主播：关掉全部连麦路 + 合成器
    sessions.forEach((entry) => entry.engine && entry.engine.close())
    sessions.clear()
    if (composer) {
      composer.stop()
      composer = null
    }
    composerAnchorSourceId = null
    composerActive.value = false
    pendingRequests.value = []
    onMicGuests.value = []
    // 嘉宾：关本人这路
    if (guestEngine) {
      guestEngine.close()
    }
    guestEngine = null
    guestRemoteStream.value = null
    guestCallId = ''
    guestStatus.value = 'idle'
    guestHint.value = ''
    role.value = ''
    roomId.value = null
    mediaBase.value = ''
    config = null
    configLoading = null
    enabled.value = false
    maxSlots.value = 1
    iceServers = []
  }

  /** 供界面绑定：嘉宾本端远端流（预览对端），主播侧不从这里取（走 composer） */
  return {
    // state
    enabled,
    maxSlots,
    role,
    guestStatus,
    guestHint,
    pendingRequests,
    onMicGuests,
    composerActive,
    guestRemoteStream,
    // getters
    isAnchor,
    isGuest,
    activeCount,
    // actions
    initAsAnchor,
    initAsGuest,
    ensureConfig,
    acceptRequest,
    rejectRequest,
    kickGuest,
    requestMic,
    leaveMic,
    onSignal,
    reset
  }
})
