import { defineStore } from 'pinia'
import { ref, shallowRef, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { send as wsSend } from '@/ws/socket'
import { getCallConfig } from '@/api/call'
import { uploadFileSmart } from '@/api/file'
import { createCallPeer } from '@/utils/webrtc'
import { createCallRecorder, callRecordSupported } from '@/utils/callRecorder'
import { openRecordDir, fetchDefaultRecordDir } from '@/utils/recorder'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import { asId } from '@/utils/id'
import { dlog } from '@/utils/logger'

/**
 * 一对一语音视频通话状态机（Pinia）。
 *
 * 职责边界：
 * - 本 store 只维护「通话实时态 + 信令编解码」，媒体细节在 utils/webrtc.js，录制细节在 utils/callRecorder.js；
 * - 信令走已认证的 WebSocket 长连接（ws/socket 的 send('call', ...)），下行 call-signal 由 ws/dispatch.js
 *   转交本 store 的 onSignal；前端不感知 deviceId，服务端按连接身份处理多端振铃 / 首接胜出；
 * - 通话结束只有主叫落一条 MsgType.CALL 记录消息（避免被叫也发导致两条气泡），时长以接通后计时为准。
 *
 * 状态机：idle → dialing(主叫已发 invite) / ringing(被叫收到 invite) → active(已接通) → ended → idle。
 * 引擎句柄、MediaStream、录制器、计时器都是非响应式的裸引用（MediaStream 被 Vue 代理会破坏 WebRTC），
 * 仅把界面要读的标量放进响应式 state，流用 shallowRef 交出裸对象引用供 <video>.srcObject 绑定。
 */

/** 生成通话 ID：主叫创建，同一次通话所有信令共享 */
function newCallId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID()
  }
  return `call-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export const useCallStore = defineStore('call', () => {
  /* ------------------------------ 响应式状态 ------------------------------ */
  const status = ref('idle') // idle | dialing | ringing | active | ended
  const role = ref('') // caller | callee
  const callId = ref('')
  const mediaType = ref('audio') // audio | video
  const peerUserId = ref(null)
  const conversationId = ref(null)
  const muted = ref(false)
  const cameraOff = ref(false)
  const sharing = ref(false)
  const recording = ref(false)
  const seconds = ref(0)
  const hint = ref('')
  const recordSupported = ref(false)
  // 服务端通话开关：ensureConfig 拉到后置真，未拉到前默认不可用（避免误开入口）
  const enabled = ref(false)
  const localStream = shallowRef(null)
  const remoteStream = shallowRef(null)

  /* ------------------------------ 非响应式内部态 ------------------------------ */
  let engine = null
  let recorder = null
  let tickTimer = null
  let failTimer = null
  let startedAtTs = 0
  let wasConnected = false
  let recordedFilePath = ''
  let config = null // { enabled, iceServers }
  let configLoading = null

  const isActive = computed(() => status.value === 'active')
  const visible = computed(() => status.value !== 'idle' && status.value !== 'ended')

  /* ------------------------------ 配置 ------------------------------ */

  /** 拉一次 ICE 配置并缓存；enabled=false 时整体禁用通话入口 */
  async function ensureConfig(force = false) {
    if (config && !force) {
      return config
    }
    if (configLoading) {
      return configLoading
    }
    configLoading = getCallConfig()
      .then((vo) => {
        config = {
          enabled: !!(vo && vo.enabled),
          iceServers: (vo && vo.iceServers) || []
        }
        enabled.value = config.enabled
        recordSupported.value = callRecordSupported()
        return config
      })
      .catch(() => {
        config = { enabled: false, iceServers: [] }
        enabled.value = false
        return config
      })
      .finally(() => {
        configLoading = null
      })
    return configLoading
  }

  /* ------------------------------ 信令收发 ------------------------------ */

  /** 组一帧上行 call 信令；返回 false 表示连接未就绪（发送失败由调用方提示） */
  function signal(action, extra = {}) {
    const payload = { action, callId: callId.value, ...extra }
    return wsSend('call', payload)
  }

  /* ------------------------------ 媒体引擎 ------------------------------ */

  /**
   * 本环境能不能拿到麦/摄像头。浏览器只在安全上下文（https 或 localhost/127.0.0.1）
   * 暴露 navigator.mediaDevices：手机用 http://局域网IP 访问时它是 undefined，
   * 不预检直接调 getUserMedia 会撞一个天书一样的 TypeError（navigator.mediaDevices 不存在），
   * 用户只看到「接通后就关闭」——这正是那个 bug 的主要现场。
   */
  function hasMediaApi() {
    return !!(typeof navigator !== 'undefined' && navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
  }

  const INSECURE_HINT = '当前访问地址不是 HTTPS/localhost，浏览器禁止使用麦克风/摄像头：手机请用 https://<电脑IP>:5173 访问（npm run gen:cert 生成证书）'

  async function buildEngine() {
    engine = createCallPeer({
      iceServers: (config && config.iceServers) || [],
      mediaType: mediaType.value,
      onLocalSignal: (action, data) => signal(action, data),
      onLocalStream: (stream) => {
        localStream.value = stream
      },
      onRemoteStream: (stream) => {
        remoteStream.value = stream
      },
      onConnectionState: (state) => {
        dlog('[call] pc connectionState', state)
        if (state === 'connected') {
          clearFailTimer()
          if (status.value === 'active') {
            hint.value = ''
          }
          startTimer()
        } else if (state === 'failed') {
          // ICE 彻底失败（同网段但防火墙拦了 UDP、两端不同网段等）：不自动收口的话
          // 本端卡在一个无声无像的浮层里，对端也不知道你挂了 —— 给 5s 重连窗口，
          // 没救回来就主动挂断（hangup 会同时通知对端 teardown）
          hint.value = 'P2P 连接失败：确认两端在同一局域网，且电脑防火墙未拦截 UDP'
          clearFailTimer()
          failTimer = setTimeout(() => {
            failTimer = null
            if (status.value === 'active') {
              ElMessage.error('通话无法建立，已挂断（检查同网段 / 防火墙 UDP）')
              hangup()
            }
          }, 5000)
        }
      }
    })
    await engine.initMedia()
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
    // 媒体真正连通后，若开了自动录制且本端支持，自动开始录制
    maybeAutoRecord()
  }

  async function maybeAutoRecord() {
    if (recording.value || !callRecordSupported()) {
      return
    }
    const settings = useSettingsStore()
    if (settings.callAutoRecord) {
      await startRecording()
    }
  }

  function stopTimer() {
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = null
    }
  }

  function clearFailTimer() {
    if (failTimer) {
      clearTimeout(failTimer)
      failTimer = null
    }
  }

  /* ------------------------------ 主叫：拨出 ------------------------------ */

  /**
   * 发起通话。
   * @param {number|string} userId 对端用户 ID（取自单聊会话 peerUserId）
   * @param {string} type 'audio' | 'video'
   * @param {object} ctx { conversationId } 落通话记录消息用的会话
   */
  async function dialCall(userId, type, ctx = {}) {
    if (status.value !== 'idle') {
      return
    }
    await ensureConfig()
    if (config && !config.enabled) {
      hint.value = '通话功能已关闭'
      return
    }
    const id = newCallId()
    callId.value = id
    role.value = 'caller'
    mediaType.value = type === 'video' ? 'video' : 'audio'
    peerUserId.value = asId(userId)
    conversationId.value = ctx.conversationId != null ? asId(ctx.conversationId) : null
    // 取流前先验安全上下文：非 https/localhost 下浏览器根本不给麦照，
    // 拨出去只会在接通环节默默失败；这里直接拦下并告诉用户原因，不进 dialing
    if (!hasMediaApi()) {
      ElMessage.error(INSECURE_HINT)
      return
    }
    status.value = 'dialing'
    hint.value = '正在呼叫…'
    try {
      await buildEngine()
    } catch (error) {
      hint.value = `无法获取麦克风/摄像头：${(error && error.name) || ''}`
      ElMessage.error(hint.value)
      teardown(false)
      return
    }
    const ok = wsSend('call', {
      action: 'invite',
      callId: id,
      // 同 linkmic 的 roomId：雪花用户 ID 超出 JS 安全整数，Number() 会把末尾几位舍掉
      // （2098796080763510785 → 2098796080763510800）Invite 就发给一个不存在的人。小 id（种子账号）
      // 暂时看不出来，属于定时炸弹；统一按 asId 传字符串，服务端 Long 字段两种都收
      calleeUserId: asId(userId),
      mediaType: mediaType.value
    })
    if (!ok) {
      hint.value = '连接未就绪，稍后再试'
      teardown(false)
    }
  }

  /* ------------------------------ 被叫：接听 / 拒绝 ------------------------------ */

  async function accept() {
    if (status.value !== 'ringing') {
      return
    }
    if (!hasMediaApi()) {
      // 同样先验安全上下文：拿不到麦照就早点拒接并说明原因，
      // 不要让用户点了接听、界面瞬间自己关闭（看起来像通话坏了）
      ElMessage.error(INSECURE_HINT)
      signal('reject')
      teardown(false)
      return
    }
    try {
      await buildEngine()
    } catch (error) {
      hint.value = `无法获取麦克风/摄像头：${(error && error.name) || ''}`
      ElMessage.error(hint.value)
      signal('reject')
      teardown(false)
      return
    }
    status.value = 'active'
    hint.value = '已接听，等待对方连接…'
    startedAtTs = Date.now()
    wasConnected = true
    signal('accept')
  }

  function reject() {
    if (status.value !== 'ringing') {
      return
    }
    signal('reject')
    teardown(false)
  }

  /** 主叫在对方接听前取消 */
  function cancel() {
    if (status.value === 'dialing') {
      signal('cancel', { reason: 'caller-cancel' })
    }
    teardown(wasConnected)
  }

  /** 挂断（通话中） */
  function hangup() {
    signal('hangup', { reason: 'user-hangup' })
    teardown(wasConnected)
  }

  /* ------------------------------ 控制动作 ------------------------------ */

  function toggleMute() {
    muted.value = !muted.value
    if (engine) {
      engine.setMuted(muted.value)
    }
  }

  async function toggleCamera() {
    if (mediaType.value !== 'video' || !engine) {
      return
    }
    cameraOff.value = !cameraOff.value
    engine.setCameraEnabled(!cameraOff.value)
  }

  async function toggleScreen() {
    if (mediaType.value !== 'video' || !engine) {
      return
    }
    try {
      const ok = await engine.setScreenShare(!sharing.value)
      if (ok) {
        sharing.value = !sharing.value
        hint.value = sharing.value ? '正在共享屏幕' : ''
      }
    } catch {
      hint.value = '屏幕共享已取消'
    }
  }

  async function toggleRecord() {
    if (!callRecordSupported()) {
      hint.value = '仅桌面端支持录制通话'
      return
    }
    if (recording.value) {
      await stopRecording()
    } else {
      await startRecording()
    }
  }

  async function startRecording() {
    const settings = useSettingsStore()
    recorder = createCallRecorder({
      onError: (msg) => {
        hint.value = `录制中断：${msg}`
        recording.value = false
      }
    })
    const meta = {
      type: 'im-call-recording',
      fileName: '通话录音',
      deviceName: '通话',
      callId: callId.value,
      mediaType: mediaType.value,
      peerUserId: peerUserId.value
    }
    const dir = settings.callRecordDir || settings.remoteRecordDir || (await fetchDefaultRecordDir().catch(() => ''))
    const ok = await recorder.start({
      localStream: localStream.value,
      remoteStream: remoteStream.value,
      hasVideo: mediaType.value === 'video',
      dir,
      meta
    })
    recording.value = ok
    if (ok) {
      hint.value = '正在录制本次通话'
    }
  }

  async function stopRecording() {
    if (!recorder) {
      recording.value = false
      return
    }
    const result = await recorder.stop({ meta: { type: 'im-call-recording', callId: callId.value } })
    recording.value = false
    if (result && result.filePath) {
      recordedFilePath = result.filePath
    }
    recorder = null
  }

  /* ------------------------------ 下行信令处理（由 dispatch 转交） ------------------------------ */

  /**
   * 处理一帧下行 call-signal。
   * @param {object} data 后端 WsCallPayload 形状：{ action, callId, mediaType, fromUserId, sdpType, sdp, candidate, reason }
   */
  async function onSignal(data) {
    if (!data || !data.action) {
      return
    }
    const action = data.action
    // invite 是唯一「在本机空闲时到达」的信令：置为被叫振铃
    if (action === 'invite') {
      if (status.value !== 'idle') {
        return // 本机已在通话中，服务端忙线逻辑已回主叫，这里忽略
      }
      callId.value = data.callId
      role.value = 'callee'
      mediaType.value = data.mediaType === 'video' ? 'video' : 'audio'
      peerUserId.value = asId(data.fromUserId)
      status.value = 'ringing'
      hint.value = '邀请你通话'
      return
    }

    // 其余信令必须属于当前这通电话，否则丢弃（迟到的 / 别的通话的）
    if (data.callId && callId.value && asId(data.callId) !== asId(callId.value)) {
      return
    }

    switch (action) {
      case 'accept':
        // 主叫收到：对方已接听 → 由主叫发起 offer
        if (role.value === 'caller') {
          status.value = 'active'
          hint.value = '对方已接听，正在连接…'
          startedAtTs = Date.now()
          wasConnected = true
          try {
            await engine.createOffer()
          } catch (error) {
            hint.value = '协商失败'
          }
        }
        break
      case 'offer':
        if (engine) {
          await engine.handleDescription(data.sdpType, data.sdp)
        }
        break
      case 'answer':
        if (engine) {
          await engine.handleDescription(data.sdpType, data.sdp)
        }
        break
      case 'ice':
        if (engine && data.candidate) {
          await engine.handleCandidate(data.candidate)
        }
        break
      case 'reject':
        // 主叫收到被叫拒绝
        if (role.value === 'caller') {
          hint.value = '对方拒绝了通话'
          teardown(false)
        }
        break
      case 'cancel':
        // 被叫收到主叫取消
        if (role.value === 'callee') {
          hint.value = '对方已取消'
          teardown(false)
        }
        break
      case 'hangup':
        // 任一端收到对方挂断 / 服务端超长挂断
        teardown(wasConnected, data.reason === 'max-duration' ? '通话已达时长上限' : '')
        break
      case 'canceled':
        // 被叫其余设备：已在他端接听 / 振铃超时
        if (role.value === 'callee' && status.value === 'ringing') {
          hint.value = data.reason === 'timeout' ? '对方无应答' : '已在他端接听'
          teardown(false)
        }
        break
      case 'failed':
        teardown(false, failureText(data.reason))
        break
      default:
        break
    }
  }

  function failureText(reason) {
    switch (reason) {
      case 'offline':
        return '对方不在线'
      case 'busy':
        return '对方正忙'
      case 'self-busy':
        return '你已在通话中'
      case 'not-friend':
        return '需为好友才能通话'
      case 'timeout':
        return '对方无应答'
      case 'gone':
        return '通话已结束'
      case 'forbidden':
        return '无权操作该通话'
      default:
        return '呼叫失败'
    }
  }

  /* ------------------------------ 收尾与通话记录 ------------------------------ */

  /**
   * 结束通话，清理所有资源。
   * @param {boolean} finalize 是否落一条通话记录（仅真正接通过、且本人是主叫时）
   * @param {string} endHint 结束时展示的一次性提示（覆盖默认）
   */
  async function teardown(finalize, endHint = '') {
    const durationSeconds = seconds.value
    const recordRole = role.value
    const recordConv = conversationId.value
    const recordMediaType = mediaType.value
    const recordWasConnected = wasConnected

    stopTimer()
    clearFailTimer()
    if (recorder) {
      await stopRecording()
    }
    if (engine) {
      engine.close()
      engine = null
    }

    localStream.value = null
    remoteStream.value = null
    muted.value = false
    cameraOff.value = false
    sharing.value = false
    recording.value = false
    startedAtTs = 0
    wasConnected = false
    status.value = 'ended'
    if (endHint) {
      hint.value = endHint
      // hint 会随 800ms 后的 reset 一起被清，弹框才能让人看清「为什么刚点就关了」：
      // 最常见就是 failed(offline)——对端设备根本没连上服务器（多半是网络不通 / 非 HTTPS）
      ElMessage.warning(endHint)
    }

    // 主叫、且这通电话真正接通过，才落一条通话记录，避免两端各发一条 / 未接通的噪音
    if (finalize && recordWasConnected && recordRole === 'caller' && recordConv) {
      await writeCallRecord({
        conversationId: recordConv,
        mediaType: recordMediaType,
        durationSeconds,
        recordedFilePath
      })
    }

    recordedFilePath = ''
    // 短暂停留 ended 让浮层收尾动画播完，再回 idle
    setTimeout(() => {
      if (status.value === 'ended') {
        reset()
      }
    }, 800)
  }

  async function writeCallRecord({ conversationId: convId, mediaType: type, durationSeconds, recordedFilePath: path }) {
    const chat = useChatStore()
    const settings = useSettingsStore()
    let fileId = null
    let fileUrl = ''
    // 有录制文件：读回 → 上传 → 拿 fileId 挂到消息上
    if (path && callRecordSupported()) {
      try {
        const api = window.__IM_NATIVE__ && window.__IM_NATIVE__.record
        if (api && api.read) {
          const result = await api.read(path, (settings.callRecordMaxMb || 200) * 1024 * 1024)
          const name = path.replace(/^.*[\\/]/, '')
          const file = new File([result.data], name, { type: result.contentType || 'video/webm' })
          const vo = await uploadFileSmart(file, 'call_record', durationSeconds)
          if (vo) {
            fileId = vo.fileId || vo.id
            fileUrl = vo.url || ''
          }
        }
      } catch (error) {
        hint.value = '通话录音上传失败，仅记录本次通话'
      }
    }

    const extra = {
      callMediaType: type,
      duration: durationSeconds,
      callScreenRecorded: !!fileId
    }
    if (fileId) {
      extra.fileId = fileId
      if (fileUrl) {
        extra.fileUrl = fileUrl
      }
    }
    try {
      await chat.send(convId, {
        msgType: 6,
        content: fileId ? String(fileId) : '',
        extra
      })
    } catch {
      /* 发送失败由 chat store 的占位气泡与离线队列处理 */
    }
  }

  function reset() {
    status.value = 'idle'
    clearFailTimer()
    role.value = ''
    callId.value = ''
    mediaType.value = 'audio'
    peerUserId.value = null
    conversationId.value = null
    seconds.value = 0
    hint.value = ''
    muted.value = false
    cameraOff.value = false
    sharing.value = false
    recording.value = false
    localStream.value = null
    remoteStream.value = null
  }

  /** 在资源管理器打开录制目录（浮层「查看录像」用） */
  function revealRecording(filePath) {
    return openRecordDir(filePath || '')
  }

  return {
    // state
    status,
    role,
    callId,
    mediaType,
    peerUserId,
    conversationId,
    muted,
    cameraOff,
    sharing,
    recording,
    seconds,
    hint,
    recordSupported,
    enabled,
    localStream,
    remoteStream,
    // getters
    isActive,
    visible,
    // actions
    ensureConfig,
    dial: dialCall,
    accept,
    reject,
    cancel,
    hangup,
    toggleMute,
    toggleCamera,
    toggleScreen,
    toggleRecord,
    onSignal,
    reset,
    revealRecording
  }
})
