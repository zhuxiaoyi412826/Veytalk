import { defineStore } from 'pinia'
import { ref, shallowRef, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { send as wsSend } from '@/ws/socket'
import { fetchLinkmicConfig, fetchLinkmicList } from '@/api/live'
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
  // 分屏舞台要直接绑的裸流引用。MediaStream 一旦被响应式代理就会破坏 WebRTC，故一律用 shallowRef；
  // 数组也只在整体替换时触发更新（不就地 push），保证元素里的 stream 仍是原始对象
  const anchorSelfStream = shallowRef(null)
  const anchorGuestStreams = shallowRef([]) // [{ callId, stream, name }]
  const guestSelfStream = shallowRef(null)

  /* ------------------------------ 非响应式内部态 ------------------------------ */
  let config = null // { enabled, maxSlots, iceServers }
  let configLoading = null
  let iceServers = []
  /** callId → { engine, guestUserId, remoteStream, role } 一路连麦一个引擎 */
  const sessions = new Map()
  /** 主播侧共享的合成推流器（首路连麦建立，最后一路下麦销毁） */
  let composer = null
  let composerAnchorSourceId = null // 记录已挂进 composer 的主播本地流 id，避免多路重复挂
  /** 当前给 composer 供主播画面的那一路连麦 callId：它被踢时摄像头随引擎一起停，必须改挂另一路的本地流 */
  let anchorSourceOwner = null
  let guestCallId = ''
  /** 主播自己的展示名（合成画面左上角名牌），由 initAsAnchor 从房间详情带入 */
  let anchorName = '主播'
  /** 上行被服务端拒时只回 error 帧、不进本状态机，没定时器就会永久卡在 requesting */
  let requestTimer = null
  /** 略大于 im.linkmic.request-timeout-seconds（默认 60s，那是服务端清主播待处理列表的节拍），保证正常的 accept/reject 总是先赢 */
  const REQUEST_TIMEOUT_MS = 65_000
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
    // roomId 必须原样传字符串，不能 Number()：房间 id 是 19 位雪花 ID，超出 JS 安全整数会被四舍五入
    // （2111276027704037378 → 2111276027704037400），服务端按坏 id 查房间必然「未在直播」，
    // request 在写连麦状态之前就被拒，表现为观众永久卡住、主播收不到任何上麦提示。
    // 下行侧 SafeLongSerializer 本就把超范围的 Long 写成字符串，服务端反序列化对 string/number 都接受。
    const payload = { linkAction, roomId: roomId.value, ...extra }
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
   * @param {string} [name] 主播展示名，只用于合成画面上的名牌
   */
  async function initAsAnchor(id, playUrl, name) {
    roomId.value = asId(id)
    role.value = 'anchor'
    mediaBase.value = webmBaseFromPlayUrl(playUrl)
    if (name) {
      anchorName = name
    }
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
    const guestUserId = asId(meta.guestUserId)
    const label = meta.name || `嘉宾${String(guestUserId || '').slice(-4)}`
    const entry = { callId, role: 'anchor', guestUserId, remoteStream: null, engine: null, label }
    sessions.set(callId, entry)
    onMicGuests.value.push({ callId, guestUserId, name: label, avatar: meta.avatar || '' })
    ensureComposer()
  }

  /** 从服务端在麦列表补齐嘉宾昵称与头像（接受后与每次麦位变化都可调，失败静默保留占位名） */
  async function refreshGuestProfiles() {
    if (!roomId.value) {
      return
    }
    try {
      const list = await fetchLinkmicList(roomId.value)
      if (!Array.isArray(list) || !list.length) {
        return
      }
      const byUser = new Map(list.map((g) => [asId(g.userId), g]))
      onMicGuests.value.forEach((g) => {
        const hit = byUser.get(asId(g.guestUserId))
        if (!hit) {
          return
        }
        if (hit.name) {
          g.name = hit.name
          const entry = sessions.get(g.callId)
          if (entry) {
            entry.label = hit.name
          }
          if (composer) {
            composer.setLabel(g.callId, hit.name)
          }
        }
        if (hit.avatar) {
          g.avatar = hit.avatar
        }
      })
      anchorGuestStreams.value = anchorGuestStreams.value.map((s) => {
        const hit = byUser.get(asId((sessions.get(s.callId) || {}).guestUserId))
        return hit && hit.name ? { ...s, name: hit.name } : s
      })
    } catch {
      // 昵称补齐失败不影响连麦本身，继续用占位名
    }
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

  /**
   * 主播一键断开全部连麦：逐路发 leave 并本地拆引擎，服务端收到后会向房间广播 linkmic active=false，
   * 观众自动从合成流切回普通 HLS。待处理申请不在此处拒（那是「拒绝」按钮的语义）。
   */
  function endAllLinkmic() {
    const ids = [...sessions.keys()]
    if (!ids.length) {
      return
    }
    ids.forEach((callId) => {
      linkSend('leave', { callId, reason: 'anchor-end' })
      teardownSession(callId)
    })
    ElMessage.success('已断开连麦')
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
    // 信令帧里只有 fromUserId、没有昵称（WsCallPayload 不为其建模），补一次 REST 把昵称/头像回填到麦位与画面名牌。
    // 放在 offer 而不放在 accept 之后：服务端是先 sAdd 麦位集合再把 offer 转给主播，到这里必然已能查到；
    // 而 accept 刚发出就查会跟服务端写集合同步抢跑，时灵时不灵
    refreshGuestProfiles()
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

  /** 主播侧某路引擎：本地流既是主播画面（合成 + 本地预览），嘉宾远端流既进合成也要能听到 */
  async function startAnchorEngine(entry) {
    const engine = buildEngine(
      entry.callId,
      (stream) => {
        // 主播本地流只需挂一次（多路连麦共享同一路主播摄像头）
        attachAnchorLocalStream(entry.callId, stream)
      },
      (stream) => {
        entry.remoteStream = stream
        if (composer) {
          composer.addSource(entry.callId, stream, 'guest', entry.label)
        }
        bindGuestStream(entry.callId, stream, entry.label)
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

  /**
   * 把嘉宾远端流登记给界面层。主播之前听不到嘉宾，就是因为远端流只被挂进了 composer 的隐藏静音
   * <video>（只取帧与参与混音、不接扬声器）；现在界面层拿它绑一个未静音的 <video> 即可听到。
   * ontrack 对同一路流会按轨各触发一次，stream 引用相同时直接返回，避免反复重建数组与重挂源。
   */
  function bindGuestStream(callId, stream, name) {
    const list = anchorGuestStreams.value
    const idx = list.findIndex((g) => g.callId === callId)
    if (idx >= 0) {
      if (list[idx].stream === stream) {
        return
      }
      const next = list.slice()
      next[idx] = { ...next[idx], stream, name }
      anchorGuestStreams.value = next
      return
    }
    anchorGuestStreams.value = [...list, { callId, stream, name: name || '嘉宾' }]
  }

  function unbindGuestStream(callId) {
    anchorGuestStreams.value = anchorGuestStreams.value.filter((g) => g.callId !== callId)
  }

  /** 把某路引擎的本地流登记为主播画面源（预览与合成共用同一对象，不另开一次 getUserMedia） */
  function attachAnchorLocalStream(ownerCallId, stream) {
    anchorSelfStream.value = stream
    if (composer && composerAnchorSourceId == null) {
      composerAnchorSourceId = 'anchor'
      anchorSourceOwner = ownerCallId
      composer.addSource('anchor', stream, 'anchor', anchorName)
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

  function clearRequestTimeout() {
    if (requestTimer) {
      clearTimeout(requestTimer)
      requestTimer = null
    }
  }

  /** 给「申请中」上兜底时限：主播不在线之外的所有无响应场景，都能把 UI 拉回 idle */
  function armRequestTimeout() {
    clearRequestTimeout()
    requestTimer = setTimeout(() => {
      requestTimer = null
      if (guestStatus.value !== 'requesting') {
        return
      }
      resetGuest(true)
      ElMessage.warning('上麦申请超时，主播未响应，请重试')
    }, REQUEST_TIMEOUT_MS)
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
    armRequestTimeout()
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
        // 自己的摄像头也要看得到（分屏右格），否则上麦后画面全黑、无从判断麦是否正常
        guestSelfStream.value = stream
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
    clearRequestTimeout()
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
    clearRequestTimeout()
    if (closeEngine && guestEngine) {
      guestEngine.close()
    }
    guestEngine = null
    guestRemoteStream.value = null
    guestSelfStream.value = null
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
    unbindGuestStream(callId)
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
    // 主播画面由「先建起来的那一路引擎」的本地流供给，而引擎 close 会连自己的摄像头轨一起停。
    // 不把它改挂到另一路仍活着的本地流上，观众就会看到主播那一格永久黑屏
    if (anchorSourceOwner === callId) {
      anchorSourceOwner = null
      composerAnchorSourceId = null
      if (composer) {
        composer.removeSource('anchor')
      }
      const alive = [...sessions.values()].find((e) => e.engine && e.engine.localStream)
      if (alive) {
        attachAnchorLocalStream(alive.callId, alive.engine.localStream)
      } else {
        anchorSelfStream.value = null
      }
    }
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
    clearRequestTimeout()
    // 主播：关掉全部连麦路 + 合成器
    sessions.forEach((entry) => entry.engine && entry.engine.close())
    sessions.clear()
    if (composer) {
      composer.stop()
      composer = null
    }
    composerAnchorSourceId = null
    anchorSourceOwner = null
    anchorSelfStream.value = null
    anchorGuestStreams.value = []
    composerActive.value = false
    pendingRequests.value = []
    onMicGuests.value = []
    // 嘉宾：关本人这路
    if (guestEngine) {
      guestEngine.close()
    }
    guestEngine = null
    guestRemoteStream.value = null
    guestSelfStream.value = null
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
    guestSelfStream,
    anchorSelfStream,
    anchorGuestStreams,
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
    endAllLinkmic,
    requestMic,
    leaveMic,
    onSignal,
    reset
  }
})
