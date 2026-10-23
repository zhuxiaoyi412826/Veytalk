import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import { send as wsSend } from '@/ws/socket'
import { getGroupActiveCall } from '@/api/call'
import { createCallPeer, hasMediaApi, INSECURE_HINT } from '@/utils/webrtc'
import { useCallStore } from '@/stores/call'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { asId, sameId } from '@/utils/id'
import { dlog } from '@/utils/logger'

/**
 * 群通话（多人）状态机（Pinia）。
 *
 * 与 1:1 通话的 store 分开而不是复用：1:1 是「一个对端、一个 engine、一条 PeerConnection」，
 * 这里是「N-1 个对端、N-1 个 engine、共享同一路本地采集」。把两套形状塞进一个 store，
 * 每个 if 都要先问「现在是单聊还是群聊」，比多开一个文件更难读。
 *
 * 三件事必须记住：
 * 1. **本地只采集一次**。mediaStream 由本 store 持有，作为 localStream 传给每一路
 *    {@code createCallPeer}（引擎因此不会自己 getUserMedia，也不会在某一路关闭时停轨）。
 *    Windows 上同一摄像头被重复打开并不安全，N 路各采一次还可能直接把设备占死。
 * 2. **谁后进房谁发 offer**。收到 roster（我刚进房）→ 我对每个已有成员 createOffer；
 *    收到 member-join（别人进房）→ 我只建 engine 等对方 offer。规则反了就是两侧同时 offer 撞车。
 * 3. **静音/关摄像头动的是共享轨**，一按就是对全房静音，这正确，不是副作用。
 *
 * 已知边界（不假装没有）：
 * - 群通话不落盘录制：mesh 下要录的是 N-1 路分立的远端流，得先做混音/混流才有意义，
 *   本期只落一条不含录音的通话记录。
 * - 未进房的人看不到实时人数：服务端不往全群广播「开了一路通话」（im-websocket 不持成员名单），
 *   提示条只在打开群聊时查一次 REST，之后靠进/出房帧在本机已知的范围内更新。
 * - 与 1:1 通话、直播连麦的忙线键是三套，服务端互不相认；跨类型不并发靠前端拦截（见 ws/dispatch.js）。
 */

/** 生成通话 ID：发起人创建，同一次群通话所有信令共享 */
function newCallId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `gcall-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export const useGroupcallStore = defineStore('groupcall', () => {
  /* ------------------------------ 响应式状态 ------------------------------ */
  // idle | incoming(收到邀约未进房) | dialing(已建房等人接) | active | ended
  const status = ref('idle')
  const callId = ref('')
  const groupId = ref(null)
  const conversationId = ref(null)
  const mediaType = ref('audio')
  /** incoming 时邀约我的人（可能是发起人，也可能是房里的任意成员） */
  const inviterUserId = ref(null)
  /** 已在房里的其他成员（不含自己）：[{ userId, muted, cameraOff, joinedAt }] */
  const members = ref([])
  /** 我方发出、还没人接的邀请：[{ userId, at }]。只有发起/追加邀请的那一端看得见 */
  const pending = ref([])
  const muted = ref(false)
  const cameraOff = ref(false)
  const seconds = ref(0)
  const hint = ref('')
  /** 每一路的连接状态，界面用它区分「连接中 / 已连通 / 连不上」 */
  const peerStates = ref({})
  const localStream = shallowRef(null)
  /** uid → MediaStream。用 shallowRef + 整体替换：MediaStream 被 Vue 代理会破坏 srcObject */
  const remoteStreams = shallowRef({})
  /**
   * 本群正在进行、但本机没参与的通话（进群聊时查一次 REST 得到）。
   * 形状：{ callId, groupId, mediaType, count }；null 表示没有。
   * 带 groupId 是因为提示条只在「查过的那一个群」上该出现：从 A 群切到 B 群时
   * 请求还在路上，不比对就会把 A 群的通话条画到 B 群头上。
   */
  const roomActive = ref(null)

  /* ------------------------------ 非响应式内部态 ------------------------------ */
  /** uid → 引擎句柄。响应式化会让 Vue 深度代理 RTCPeerConnection，WebRTC 会坏 */
  let peers = {}
  let tickTimer = null
  let startedAtTs = 0
  /** 至少有一路真正连通过，才算「这通电话接通过」，否则不落通话记录 */
  let anyConnected = false
  /** 我是发起人：只有我落那条通话记录，避免 N 个人各发一条 */
  let isCreator = false

  const call = useCallStore()
  const auth = useAuthStore()

  const busy = computed(() => status.value !== 'idle')
  const visible = computed(() => status.value !== 'idle' && status.value !== 'ended')
  const isActive = computed(() => status.value === 'active')
  /** 房间里的人数（含自己），头像墙与通话记录都用它 */
  const participantCount = computed(() => members.value.length + 1)

  /* ------------------------------ 信令收发 ------------------------------ */

  function signal(action, extra = {}) {
    return wsSend('groupcall', { action, callId: callId.value, ...extra })
  }

  /* ------------------------------ 媒体 ------------------------------ */

  /** 采集一次本地流，供全部 N-1 路共享；失败（无权限/无设备/非安全上下文）时抛错 */
  async function ensureLocalStream() {
    if (localStream.value) {
      return localStream.value
    }
    if (!hasMediaApi()) {
      throw new Error(INSECURE_HINT)
    }
    const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
    const constraints =
      mediaType.value === 'video'
        ? { audio, video: { width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30 } } }
        : { audio, video: false }
    localStream.value = await navigator.mediaDevices.getUserMedia(constraints)
    applyMediaState()
    return localStream.value
  }

  /** 把本端的静音/摄像头开关落到共享轨上（采集完成后与切换开关时都要调） */
  function applyMediaState() {
    const stream = localStream.value
    if (!stream) {
      return
    }
    stream.getAudioTracks().forEach((track) => {
      track.enabled = !muted.value
    })
    stream.getVideoTracks().forEach((track) => {
      track.enabled = !cameraOff.value
    })
  }

  /**
   * 取某一位成员的引擎，没有就建一个。
   * @param {string} uid 对端用户 ID（已 asId 的字符串）
   */
  function ensurePeer(uid) {
    if (peers[uid]) {
      return peers[uid]
    }
    const engine = createCallPeer({
      iceServers: call.iceServers || [],
      mediaType: mediaType.value,
      // 关键：传共享流，引擎便不再采集、也不会在这路关闭时把轨停掉
      localStream: localStream.value,
      onLocalSignal: (action, data) => signal(action, { toUserId: uid, ...data }),
      onRemoteStream: (stream) => attachRemoteStream(uid, stream),
      onConnectionState: (state) => onPeerState(uid, state)
    })
    peers[uid] = engine
    // initMedia 在共享流下是同步的（只是 addTrack），await 一次以兼容将来可能的重采集
    engine.initMedia().catch((error) => dlog('[groupcall] initMedia 失败', error))
    setPeerState(uid, 'connecting')
    return engine
  }

  function attachRemoteStream(uid, stream) {
    remoteStreams.value = { ...remoteStreams.value, [uid]: stream }
  }

  function detachRemoteStream(uid) {
    if (!remoteStreams.value[uid]) {
      return
    }
    const next = { ...remoteStreams.value }
    delete next[uid]
    remoteStreams.value = next
  }

  function setPeerState(uid, state) {
    peerStates.value = { ...peerStates.value, [uid]: state }
  }

  function onPeerState(uid, state) {
    dlog('[groupcall] pc', uid, state)
    if (state === 'connected') {
      anyConnected = true
      setPeerState(uid, 'connected')
      startTimer()
    } else if (state === 'failed') {
      // 单路失败不影响整场：只把这一格标成连不上，其余人继续通话（mesh 的隔离性）
      setPeerState(uid, 'failed')
      hint.value = '与部分成员连接失败：确认两端互通 UDP，或改用 TURN 中继'
    } else if (state === 'closed') {
      setPeerState(uid, 'closed')
    }
  }

  function closePeer(uid) {
    const engine = peers[uid]
    if (!engine) {
      return
    }
    try {
      engine.close()
    } catch {
      /* 已经断了就没什么可收尾的 */
    }
    delete peers[uid]
    detachRemoteStream(uid)
    const next = { ...peerStates.value }
    delete next[uid]
    peerStates.value = next
  }

  function closeAllPeers() {
    Object.keys(peers).forEach(closePeer)
    peers = {}
    remoteStreams.value = {}
    peerStates.value = {}
  }

  /* ------------------------------ 计时 ------------------------------ */

  function startTimer() {
    if (tickTimer || !startedAtTs) {
      return
    }
    seconds.value = Math.floor((Date.now() - startedAtTs) / 1000)
    tickTimer = setInterval(() => {
      seconds.value = Math.floor((Date.now() - startedAtTs) / 1000)
    }, 1000)
  }

  function stopTimer() {
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = null
    }
  }

  /* ------------------------------ 发起 / 加入 / 退出 ------------------------------ */

  /**
   * 发起一场群通话。
   * @param {number|string} gid 群 ID
   * @param {string} type 'audio' | 'video'
   * @param {Array<string|number>} calleeUserIds 首批邀请的成员
   * @param {object} ctx { conversationId } 落通话记录用的会话
   */
  async function start(gid, type, calleeUserIds, ctx = {}) {
    if (status.value !== 'idle') {
      return false
    }
    await call.ensureConfig()
    if (!call.enabled || !call.groupEnabled) {
      ElMessage.warning('群通话功能已关闭')
      return false
    }
    const id = newCallId()
    callId.value = id
    groupId.value = asId(gid)
    conversationId.value = ctx.conversationId != null ? asId(ctx.conversationId) : null
    mediaType.value = type === 'video' ? 'video' : 'audio'
    isCreator = true
    try {
      await ensureLocalStream()
    } catch (error) {
      ElMessage.error(error && error.message ? error.message : '无法获取麦克风/摄像头')
      reset()
      return false
    }
    status.value = 'dialing'
    hint.value = '正在呼叫…'
    startedAtTs = Date.now()
    members.value = []
    pending.value = (calleeUserIds || []).map((uid) => ({ userId: asId(uid), at: Date.now() }))
    const ok = wsSend('groupcall', {
      action: 'create',
      callId: id,
      // 雪花 ID 一律按字符串上送：Number() 会把末尾几位舍掉，邀请就发给一个不存在的人
      groupId: asId(gid),
      mediaType: mediaType.value,
      calleeUserIds: pending.value.map((p) => p.userId)
    })
    if (!ok) {
      hint.value = '连接未就绪，稍后再试'
      ElMessage.warning(hint.value)
      reset()
    }
    return ok
  }

  /** 通话进行中再拉人（房里的任何人都能拉，不只是发起人） */
  function invite(userIds) {
    if (status.value !== 'active' && status.value !== 'dialing') {
      return false
    }
    const ids = (userIds || []).map((uid) => asId(uid))
    const known = new Set([
      ...members.value.map((m) => m.userId),
      ...pending.value.map((p) => p.userId),
      asId(auth.userId)
    ])
    const fresh = ids.filter((uid) => !known.has(uid))
    if (!fresh.length) {
      return false
    }
    if (members.value.length + pending.value.length + fresh.length >= (call.groupMaxSlots || 4)) {
      ElMessage.warning(`群通话最多 ${call.groupMaxSlots || 4} 人（含你自己）`)
    }
    pending.value = [...pending.value, ...fresh.map((uid) => ({ userId: uid, at: Date.now() }))]
    return signal('invite', { calleeUserIds: fresh })
  }

  /** 进房后对每个先到者发起 offer（后进房者恒为 caller） */
  async function offerToEach(list) {
    for (const m of list) {
      const uid = asId(m.userId)
      const engine = ensurePeer(uid)
      try {
        await engine.createOffer()
      } catch (error) {
        dlog('[groupcall] createOffer 失败', uid, error)
      }
    }
  }

  /** 接听邀约 / 主动加入群里已开的通话 */
  async function join() {
    if (status.value !== 'incoming' && status.value !== 'idle') {
      return false
    }
    const wasIncoming = status.value === 'incoming'
    await call.ensureConfig()
    if (!call.enabled || !call.groupEnabled) {
      ElMessage.warning('群通话功能已关闭')
      return false
    }
    try {
      await ensureLocalStream()
    } catch (error) {
      ElMessage.error(error && error.message ? error.message : '无法获取麦克风/摄像头')
      if (wasIncoming) {
        signal('reject', { reason: 'media-unavailable' })
      }
      reset()
      return false
    }
    startedAtTs = Date.now()
    status.value = 'active'
    hint.value = '正在连接…'
    return signal('join')
  }

  /** 从顶部提示条点「加入」：本机空闲，先带上查到的 callId */
  async function joinActive(callIdFromQuery, gid, type) {
    if (status.value !== 'idle') {
      return false
    }
    callId.value = callIdFromQuery
    groupId.value = asId(gid)
    mediaType.value = type === 'video' ? 'video' : 'audio'
    isCreator = false
    roomActive.value = null
    return join()
  }

  function rejectInvite() {
    if (status.value !== 'incoming') {
      return
    }
    signal('reject', { reason: 'user-reject' })
    reset()
  }

  function hangup() {
    signal('leave', { reason: 'user-leave' })
    teardown(anyConnected)
  }

  /* ------------------------------ 控制 ------------------------------ */

  function toggleMute() {
    muted.value = !muted.value
    applyMediaState()
    signal('state', { muted: muted.value })
  }

  function toggleCamera() {
    if (mediaType.value !== 'video') {
      return
    }
    cameraOff.value = !cameraOff.value
    applyMediaState()
    signal('state', { cameraOff: cameraOff.value })
  }

  /* ------------------------------ 下行信令 ------------------------------ */

  /**
   * 处理一帧下行 groupcall-signal。
   * @param {object} data 后端 WsGroupCallPayload 形状
   */
  async function onSignal(data) {
    if (!data || !data.action) {
      return
    }
    const action = data.action
    // invite 是唯一「本机空闲时到达」的信令
    if (action === 'invite') {
      if (status.value !== 'idle') {
        // 已经在一场群通话里：直接替用户拒掉，别让对端干等 45 秒振铃超时
        wsSend('groupcall', { action: 'reject', callId: data.callId, reason: 'self-busy' })
        return
      }
      callId.value = data.callId
      groupId.value = asId(data.groupId)
      mediaType.value = data.mediaType === 'video' ? 'video' : 'audio'
      inviterUserId.value = asId(data.fromUserId)
      isCreator = false
      status.value = 'incoming'
      hint.value = '邀请你加入群通话'
      return
    }

    if (data.callId && callId.value && asId(data.callId) !== asId(callId.value)) {
      return
    }

    switch (action) {
      case 'roster': {
        // 我刚进房：拿到先到者名单，对他们逐个发起 offer
        members.value = mergeMembers(data.members)
        status.value = 'active'
        startedAtTs = startedAtTs || Date.now()
        startTimer()
        await offerToEach(members.value)
        break
      }
      case 'member-join': {
        // 别人进房：我建好引擎等着，offer 由对方发（规则反了会两侧同时 offer 撞车）
        members.value = mergeMembers(data.members)
        ;(data.members || []).forEach((m) => ensurePeer(asId(m.userId)))
        if (status.value === 'dialing') {
          status.value = 'active'
        }
        break
      }
      case 'member-leave': {
        const uid = asId(data.fromUserId)
        removeMember(uid)
        closePeer(uid)
        pending.value = pending.value.filter((p) => p.userId !== uid)
        if (data.reason === 'reject' || data.reason === 'timeout') {
          hint.value = data.reason === 'reject' ? '有人拒绝了通话' : '有人未接听'
        }
        break
      }
      case 'state': {
        const uid = asId(data.fromUserId)
        members.value = members.value.map((m) =>
          m.userId === uid ? { ...m, muted: data.muted != null ? data.muted : m.muted, cameraOff: data.cameraOff != null ? data.cameraOff : m.cameraOff } : m
        )
        break
      }
      case 'offer':
      case 'answer': {
        const engine = ensurePeer(asId(data.fromUserId))
        await engine.handleDescription(data.sdpType, data.sdp)
        break
      }
      case 'ice': {
        const engine = peers[asId(data.fromUserId)]
        if (engine && data.candidate) {
          await engine.handleCandidate(data.candidate)
        }
        break
      }
      case 'end':
        teardown(anyConnected, endText(data.reason))
        break
      case 'failed':
        // 点「加入」时那路通话可能刚好收了：服务端回 gone，顺手把顶部的陈旧提示条清掉
        if (data.reason === 'gone') {
          roomActive.value = null
        }
        teardown(false, failureText(data.reason))
        break
      default:
        break
    }
  }

  /** 花名册合并：一律以服务端给的 ACTIVE 列表为准（它会回收掉被摘除的人），本地只补 state */
  function mergeMembers(list) {
    const byId = new Map(members.value.map((m) => [m.userId, m]))
    return (list || []).map((m) => {
      const uid = asId(m.userId)
      const local = byId.get(uid)
      return {
        userId: uid,
        muted: !!m.muted,
        cameraOff: !!m.cameraOff,
        joinedAt: m.joinedAt || (local ? local.joinedAt : Date.now())
      }
    })
  }

  function removeMember(uid) {
    members.value = members.value.filter((m) => m.userId !== uid)
  }

  function endText(reason) {
    return reason === 'max-duration' ? '通话已达时长上限' : ''
  }

  function failureText(reason) {
    switch (reason) {
      case 'exists':
        return '本群已有一路群通话在进行'
      case 'full':
        return `群通话已满 ${call.groupMaxSlots || 4} 人`
      case 'self-busy':
        return '你已在另一路群通话中'
      case 'not-member':
        return '只有本群成员才能加入群通话'
      case 'gone':
        return '通话已结束'
      case 'forbidden':
        return '无权操作该通话'
      default:
        return '群通话发起失败'
    }
  }

  /* ------------------------------ 收尾与通话记录 ------------------------------ */

  async function teardown(finalize, endHint = '') {
    const recordConv = conversationId.value
    const recordMediaType = mediaType.value
    const recordCount = participantCount.value
    const recordSeconds = seconds.value
    const recordIsCreator = isCreator

    stopTimer()
    closeAllPeers()
    // 本群的「进行中的通话」提示条此时必定陈旧（我们刚把那一路送走），不清会残留一个点上去吃 gone 的入口
    if (roomActive.value && sameId(roomActive.value.groupId, groupId.value)) {
      roomActive.value = null
    }
    if (localStream.value) {
      // 共享流归本 store，所有路都关了才停，摄像头指示灯这时才该灭
      localStream.value.getTracks().forEach((track) => track.stop())
      localStream.value = null
    }
    status.value = 'ended'
    if (endHint) {
      hint.value = endHint
      ElMessage.warning(endHint)
    }
    // 只有发起人落一条群通话记录：N 个人各发一条，群聊里就会出现 N 条同样的气泡
    if (finalize && recordIsCreator && recordConv) {
      await writeCallRecord({
        conversationId: recordConv,
        mediaType: recordMediaType,
        durationSeconds: recordSeconds,
        participantCount: recordCount
      })
    }
    setTimeout(() => {
      if (status.value === 'ended') {
        reset()
      }
    }, 800)
  }

  async function writeCallRecord({ conversationId: convId, mediaType: type, durationSeconds, participantCount: count }) {
    const chat = useChatStore()
    try {
      await chat.send(convId, {
        msgType: 6,
        content: '',
        extra: {
          callMediaType: type,
          duration: durationSeconds,
          // 只有这一个字段是群通话新增的：人数比参与者 ID 清单有用且不腐坏（成员退群后 ID 清单会变成假名单）
          callParticipantCount: count
        }
      })
    } catch {
      /* 发送失败由 chat store 的占位气泡与离线队列处理 */
    }
  }

  function reset() {
    status.value = 'idle'
    stopTimer()
    callId.value = ''
    groupId.value = null
    conversationId.value = null
    mediaType.value = 'audio'
    inviterUserId.value = null
    members.value = []
    pending.value = []
    muted.value = false
    cameraOff.value = false
    seconds.value = 0
    hint.value = ''
    anyConnected = false
    isCreator = false
    startedAtTs = 0
  }

  /* ------------------------------ 本群通话提示条 ------------------------------ */

  /** 最后一次查询的群 ID：用来丢弃「切走之后才到」的响应，不把它当成当前群的通话 */
  let activeQueried = ''

  /** 由 ChatWindow 在打开群聊时调用：查一次本群是否有进行中的通话 */
  async function refreshActive(gid) {
    activeQueried = asId(gid)
    // 本机正在通话里，提示条就该隐身（那路通话已经被浮层接管了）
    if (busy.value) {
      roomActive.value = null
      return
    }
    if (!gid) {
      roomActive.value = null
      return
    }
    try {
      const vo = await getGroupActiveCall(asId(gid))
      if (activeQueried !== asId(gid)) {
        return
      }
      if (vo && vo.callId && asId(vo.groupId) === asId(gid)) {
        roomActive.value = {
          callId: vo.callId,
          groupId: asId(gid),
          mediaType: vo.mediaType === 'video' ? 'video' : 'audio',
          // 服务端这里返回的是「全量 ACTIVE 成员」（含我自己），所以人数直接取长度不再 +1
          count: (vo.members || []).length
        }
        return
      }
      roomActive.value = null
    } catch {
      // 查询失败就什么都不显示：提示条是锦上添花，不能因为它弹错误框打断聊天
      if (activeQueried === asId(gid)) {
        roomActive.value = null
      }
    }
  }

  /** 通话结束或离开群聊时清掉提示条 */
  function clearActive() {
    roomActive.value = null
  }

  return {
    // state
    status,
    callId,
    groupId,
    conversationId,
    mediaType,
    inviterUserId,
    members,
    pending,
    muted,
    cameraOff,
    seconds,
    hint,
    peerStates,
    localStream,
    remoteStreams,
    roomActive,
    // getters
    busy,
    visible,
    isActive,
    participantCount,
    // actions
    start,
    invite,
    join,
    joinActive,
    rejectInvite,
    hangup,
    toggleMute,
    toggleCamera,
    onSignal,
    refreshActive,
    clearActive,
    reset
  }
})
