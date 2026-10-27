import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import router from '@/router'
import { asId, sameId } from '@/utils/id'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useSettingsStore } from '@/stores/settings'
import { useFriendStore } from '@/stores/friend'
import { useGroupStore } from '@/stores/group'
import { useCallStore } from '@/stores/call'
import { useGroupcallStore } from '@/stores/groupcall'
import { useLinkmicStore } from '@/stores/linkmic'
import { useAnnounceStore } from '@/stores/announce'
import { on, connect, send as wsSend } from './socket'
import { summarize, useConversationStore } from '@/stores/conversation'
import {
  notifyIncoming,
  notifyOfflineBatch,
  notifySystem,
  setOpenConversationHandler
} from '@/utils/notify'

/**
 * WebSocket 下行报文到各 store 的分发。
 *
 * 单独成一个模块而不是写在 MainLayout 里：这里是协议知识（每种 type 的 data 长什么样、
 * 该更新哪几个 store）的集中地，布局组件只该负责挂载与卸载。
 *
 * 事件名与后端 WsMessageType 的取值完全一致，中间不做翻译层。
 */

/** 已安装则返回注销函数，重复调用不会重复注册 */
let installed = null

/** ElNotification 支持的四种样式；服务端给的 tone 不在这个表里就按 info 兜底 */
const NOTIFY_TONES = ['success', 'warning', 'info', 'error']

/**
 * 断线期间的未读数轮询间隔。
 *
 * WS 掉了不等于站点挂了：后端重启、代理断连、笔记本休眠醒来这些场景下，
 * HTTP 接口一直是通的，只有推送断了。不兜这一层，用户就只能靠刷新页面发现新消息。
 * 15 秒比心跳长一个量级但短于退避上限，标签页在后台时浏览器会自己把定时器压到分钟级，
 * 不必我们再做可见性判断。
 */
const OFFLINE_POLL_MS = 15000

let offlinePollTimer = null
/** 上一次轮询到的未读总数：拿它做差才能知道「断线这段时间又来了几条」 */
let offlineBaselineUnread = null

export function installWsDispatch() {
  if (installed) {
    return installed
  }

  const auth = useAuthStore()
  const chat = useChatStore()
  const conversation = useConversationStore()
  const friend = useFriendStore()
  const group = useGroupStore()
  const call = useCallStore()
  const groupcall = useGroupcallStore()
  const linkmic = useLinkmicStore()
  const announce = useAnnounceStore()

  // 点系统通知要跳到对应会话。notify 那层不认识 router（它也不该认识），
  // 跳转在这里接回来；重复 install 会覆盖上一个回调，不会叠加
  setOpenConversationHandler((conversationId) => {
    router.push({ name: 'chat', params: { conversationId } }).catch(() => {
      // 已经在该会话的路由上：push 会被 vue-router 以 NavigationDuplicated reject，属正常
    })
  })

  const disposers = []

  disposers.push(
    on('open', ({ reconnected }) => {
      stopOfflineUnreadPoll()
      // 连上就是「网络可用」的最强信号：先重提交离线待发送队列（内部空队列时几乎零成本），
      // 断网期间攒下的消息按入队顺序补发，clientMsgId 幂等保证不会重复入库
      chat.flushPending()
      // 公告补拉：首次连接与重连都要打一次。实时那一帧只覆盖发布瞬间在线的连接，
      // 离线期间发的公告只有这一条路能拿到；store 内部按 ID 判重且只在用户确认后落盘，
      // 所以重复拉只会多一个静默请求，不会重复弹同一条、也不会把没读过的公告悄悄记成已读
      announce.pull()
      if (!reconnected) {
        // 首次连接：登录流程里已经拉过一遍列表，不重复请求
        return
      }
      // 重连后补拉断线期间的消息。后端在返回的同时推进确认位点，
      // 但不清未读，所以拉完还要刷一次会话列表把角标对上。
      chat
        .pullOffline()
        .then((list) => {
          if (list && list.length > 0) {
            conversation.fetchList()
            // 这批消息是静默上屏的（走 appendMessage，不经 message 帧），用户切回来只看得到
            // 气泡多了几条、什么声音都没有 —— 汇成一条通知，而不是逐条弹
            notifyOfflineBatch(list, { info: (item) => conversationInfo(conversation, item, auth.userId) })
          } else {
            conversation.refreshTotalUnread()
          }
        })
        .catch(() => {
          conversation.refreshTotalUnread()
        })
    })
  )

  disposers.push(
    on('message', (packet) => {
      const dto = packet.data
      if (!dto) {
        return
      }
      chat.applyIncoming(dto)

      // 正打开着这个会话且页面在前台时不算未读：否则角标会先 +1 再被 markRead 清零，闪一下。
      // 页面在后台时即使会话是打开的也要计未读 —— 用户根本没看到。
      const viewing =
        sameId(dto.conversationId, conversation.activeId) && document.visibilityState === 'visible'

      conversation.applyIncoming(dto, viewing)

      // 提醒判定全部交给 notify 那一层（前台静默 / 后台补通知 / 免打扰 / 节流都在里面），
      // 这里只负责把「哪来的、叫什么、正文摘要、是否点名」四件事说清楚
      const info = conversationInfo(conversation, dto, auth.userId)
      notifyIncoming({
        conversationId: dto.conversationId,
        name: info.name,
        summary: notifyBody(dto),
        muted: info.muted,
        important: info.important,
        viewing
      })

      // 送达回执与是否在看无关：消息已经到端了
      chat.reportDelivered([dto.messageId])
      if (viewing) {
        chat.reportRead(dto.conversationId, dto.seq)
        conversation.markRead(dto.conversationId, dto.seq)
      }
    })
  )

  disposers.push(
    on('ack', (packet) => {
      // clientMsgId 在报文外层，data 才是消息体
      const dto = packet.data
      if (!dto) {
        return
      }
      chat.applyAck(packet.clientMsgId, dto)
      conversation.applyAck(dto)
    })
  )

  disposers.push(
    on('recall-notify', (packet) => {
      chat.applyRecall(packet.data)
      conversation.applyRecall(packet.data)
    })
  )

  // 闪照被任一成员拆开销毁后，后端推给全员：本端气泡立即变「已销毁」占位（含发送端）
  disposers.push(on('flash-notify', (packet) => chat.applyFlashViewed(packet.data)))

  // 发送者改了自己已发出的文本消息，后端把新正文推给全员：各端气泡实时刷新正文并标「已编辑」
  disposers.push(on('edit-notify', (packet) => chat.applyEdit(packet.data)))

  // 某人给一条消息贴/取消了一个表情，后端推增量给全员（含操作者本端）：各端气泡表情栏据此加减
  disposers.push(on('reaction-notify', (packet) => chat.applyReaction(packet.data)))

  // 有人投/改票或发起人截止，后端推权威票数给全员：各端投票卡片据此刷新选项条
  disposers.push(on('vote-notify', (packet) => chat.applyVote(packet.data)))

  // 有人接龙或发起人截止，后端推全量条目快照给全员（含操作者其他设备）：各端接龙卡片整份替换 extra.chainItems
  disposers.push(on('chain-notify', (packet) => chat.applyChain(packet.data)))

  // 群主/管理员标记或取消精华，后端推 {messageId, conversationId, essence} 给群成员：各端气泡精华标签实时增减
  disposers.push(on('essence-notify', (packet) => chat.applyEssence(packet.data)))

  // 收藏只推给操作者本人（私人标记，不去打扰全群），触达的是同一账号的其他端：手机收藏、电脑气泡也跟着亮
  disposers.push(on('favorite-notify', (packet) => chat.applyFavorite(packet.data)))

  // 送达 / 已读回执都推给原发送者，差别只是把状态升到哪一级
  disposers.push(on('delivered-notify', (packet) => chat.applyReceipt(packet.data && packet.data.messageIds, 2)))
  disposers.push(on('read-notify', (packet) => chat.applyReceipt(packet.data && packet.data.messageIds, 3)))

  disposers.push(on('unread', (packet) => conversation.applyUnread(packet.data)))

  disposers.push(
    on('online-state', (packet) => {
      conversation.applyOnlineState(packet.data)
      friend.applyOnlineState(packet.data)
    })
  )

  disposers.push(on('notify', (packet) => handleNotify(packet.data, { conversation, friend, group, auth })))

  // 音视频通话信令：整帧交给通话 store 的状态机裁决（invite/accept/offer/answer/ice/hangup/failed 等）
  //
  // 这里的「跨类型拦截」是必要的：1:1 通话、群通话、连麦各用一套 Redis 忙线键，
  // 服务端互不相认（已知取舍，收敛成一个「媒体会话注册表」要跨三个模块重构）。
  // 不拦就会两个全屏浮层叠在一起、三套本地采集抢同一个摄像头；在这一层做而不写进 store，
  // 是因为两个 store 互引会变成循环依赖，而 dispatch 本就同时持有它们。
  // 拒回的那一帧走对方的协议，目的是让呼叫方早点知道结果，而不是干等 45 秒振铃超时
  disposers.push(
    on('call-signal', (packet) => {
      const data = packet.data
      if (data && data.action === 'invite' && groupcall.busy) {
        wsSend('call', { action: 'reject', callId: data.callId, reason: 'busy-group-call' })
        return
      }
      call.onSignal(data)
    })
  )

  // 群通话信令：整帧交给 groupcall store（create/invite/join/roster/member-join/member-leave/offer/answer/ice/state/end/failed）
  disposers.push(
    on('groupcall-signal', (packet) => {
      const data = packet.data
      if (data && data.action === 'invite' && call.visible) {
        wsSend('groupcall', { action: 'reject', callId: data.callId, reason: 'busy-call' })
        return
      }
      groupcall.onSignal(data)
    })
  )

  // 直播连麦信令：整帧交给连麦 store（request/accept/offer/answer/ice/leave/reject/failed，按 linkAction 裁决）
  disposers.push(on('linkmic-signal', (packet) => linkmic.onSignal(packet.data)))

  disposers.push(
    on('kickout', (packet) => {
      const data = packet.data || {}
      // socket 收到 kickout 时已经置位「不再重连」，这里只负责收尾
      auth.teardown()
      chat.reset()
      conversation.reset()
      friend.reset()
      // 没确认完的公告也要清：否则下一个账号登录会在自己的界面上看到上一个账号的公告栏
      announce.reset()
      ElMessageBox.alert(data.reason || '账号已在其他设备登录', '下线通知', {
        confirmButtonText: '重新登录',
        type: 'warning'
      })
        .catch(() => {})
        .finally(() => {
          router.replace({ name: 'login' })
        })
    })
  )

  disposers.push(
    on('error', (packet) => {
      // 服务端把上行报文的异常翻译成了 error 帧但没有断开连接，
      // 所以这里只提示，不做任何重连动作
      ElMessage.error(packet.message || '操作未生效')
    })
  )

  disposers.push(
    on('close', ({ willReconnect }) => {
      // 只有「会重连」才轮询：登出与被踢时继续探只会把 401 刷成一排
      if (willReconnect) {
        startOfflineUnreadPoll(conversation)
      }
    })
  )

  // 刷新页面后没有走过登录流程，token 在 localStorage 里而连接还没建立
  connect()

  installed = () => {
    disposers.forEach((dispose) => dispose())
    installed = null
    stopOfflineUnreadPoll()
    setOpenConversationHandler(null)
  }
  return installed
}

export function uninstallWsDispatch() {
  if (installed) {
    installed()
  }
}

/**
 * WS 断线期间的未读兜底轮询。
 *
 * 刻意只拉 `/unread/total` 一个数：不拉正文（离线消息得靠重连后的 pullOffline 一次性补齐，
 * 轮询里逐次拉历史会和那条路撞车）、不推已读位点（人在离线状态里读不到东西，推了反而是错的信息）。
 * 数量变大时补一条系统通知，因为光有角标在后台标签页上根本看不见。
 */
function startOfflineUnreadPoll(conversation) {
  if (offlinePollTimer) {
    return
  }
  if (useSettingsStore().notifyUnreadPoll === false) {
    return
  }
  offlineBaselineUnread = conversation.totalUnread || 0
  offlinePollTimer = setInterval(async () => {
    // 轮询期间用户可能把它关了：下一跳就收掉，不让一个标着「已关闭」的开关还在背后台发请求
    if (useSettingsStore().notifyUnreadPoll === false) {
      stopOfflineUnreadPoll()
      return
    }
    try {
      const before = offlineBaselineUnread || 0
      await conversation.refreshTotalUnread()
      const after = conversation.totalUnread || 0
      offlineBaselineUnread = after
      if (after > before) {
        notifySystem(`有 ${after - before} 条新消息`, '连接已中断，消息可能不完整，请回到页面刷新', {
          tag: 'offline-poll'
        })
      }
    } catch {
      // 站点整体不可达时这里会一直失败：不提示、不重试控制，重连那一路由 socket 自己管
    }
  }, OFFLINE_POLL_MS)
}

function stopOfflineUnreadPoll() {
  if (offlinePollTimer) {
    clearInterval(offlinePollTimer)
    offlinePollTimer = null
  }
  offlineBaselineUnread = null
}

/**
 * 一条消息对应的会话展示信息：名字 + 是否免打扰 + 有没有点名找我。
 * 同时服务「实时推送」与「离线补拉」两路，所以不取到本地列表里找不到就算了：
 * 新会话（对方刚通过好友申请）本地还没建列表，拿发送者昵称兜底总比只写「新消息」好。
 */
function conversationInfo(conversation, dto, me) {
  const target = conversation.find(dto && dto.conversationId)
  return {
    name:
      (target && target.name) ||
      (dto && dto.fromNickname) ||
      '新消息',
    muted: !!(target && target.muted),
    important: mentionMe(dto, me)
  }
}

/**
 * 是否点名提醒了我：与后端 MessageExtra.hasMention() 同口径（@全体，或 atUserIds 里有我）。
 * atUserIds 是雪花 ID，JSON 过来可能是字符串，所以用 sameId 比大小而不是 includes。
 */
function mentionMe(dto, me) {
  const extra = dto && dto.extra
  if (!extra) {
    return false
  }
  if (extra.atAll) {
    return true
  }
  return Array.isArray(extra.atUserIds) && extra.atUserIds.some((id) => sameId(id, me))
}

/**
 * 通知正文：群聊带上谁说的，单聊不带（标题已经是那个人了）。
 * 附件类走 summarize 转成「[图片]」这种占位 —— 把文件 ID 抄进通知正文是最刺眼的穿帮。
 */
function notifyBody(dto) {
  const text = summarize(dto)
  const isGroup = Number(dto.conversationType) === 2 || Number(dto.msgType) === 5
  if (isGroup && dto.fromNickname) {
    return `${dto.fromNickname}: ${text}`
  }
  return text
}

/**
 * 处理 notify 帧。
 *
 * 同一个 type 下有三种形态，且区分字段并不统一：
 * 握手欢迎、举报回执、管理端警告与全服公告用 action，好友相关用 bizType，群组相关用 biz。
 * 这是后端逐步演进留下的现状，前端必须几种都认，只按其中一种判断会静默漏掉另一半通知。
 */
function handleNotify(data, { conversation, friend, group, auth }) {
  if (!data) {
    return
  }
  // 握手欢迎帧：心跳间隔已由 socket 自己消费，这里不需要再做任何事
  if (data.action === 'connected') {
    return
  }

  if (data.bizType === 'friend-request') {
    friend.onFriendRequestNotify()
    ElNotification({
      title: '新的好友申请',
      message: `${data.fromNickname || '有人'} 请求加你为好友${data.verifyMessage ? `：${data.verifyMessage}` : ''}`,
      type: 'info',
      // 点击通知直接跳到申请页，比让用户自己找入口省事
      onClick: () => router.push({ name: 'friend-requests' })
    })
    notifySystem('新的好友申请', `${data.fromNickname || '有人'} 请求加你为好友`, {
      tag: 'friend-request',
      onOpen: () => router.push({ name: 'friend-requests' })
    })
    return
  }

  if (data.bizType === 'friend-accepted') {
    friend.onFriendAcceptedNotify()
    // 对方同意后会后端会建好单聊会话，本地列表要跟着补上
    conversation.fetchList()
    ElNotification({
      title: '好友申请已通过',
      message: `${data.nickname || '对方'} 已同意你的好友申请`,
      type: 'success',
      onClick: () => {
        if (data.conversationId) {
          router.push({ name: 'chat', params: { conversationId: asId(data.conversationId) } })
        }
      }
    })
    notifySystem('好友申请已通过', `${data.nickname || '对方'} 已同意你的好友申请`, {
      tag: 'friend-accepted',
      onOpen: () => {
        if (data.conversationId) {
          router.push({ name: 'chat', params: { conversationId: asId(data.conversationId) } })
        }
      }
    })
    return
  }

  // 全服公告：判重、通知、待确认列表都在 stores/announce 里（补拉那一路共用同一个集合），
  // 这一层只负责把 WS 那一帧交过去，不在这里自己记「弹过没有」——两处各记一份必然漂移
  if (data.action === 'announce') {
    announce.ingest([data])
    return
  }

  // 举报的两条回执：结论推给举报人（report-result），违规警告推给当事人（admin-warn）。
  // 两者都是纯提醒：不刷新列表也不跳路由——工单页在管理后台那边，聊天端没有可跳的目标。
  // 样式取服务端的 tone，不让前端拿中文标题去猜（改一句文案就把通知颜色改乱了）。
  if (data.action === 'report-result' || data.action === 'admin-warn') {
    const title = data.title || (data.action === 'report-result' ? '举报处理结果' : '内容提醒')
    ElNotification({
      title,
      message: data.message || '',
      type: NOTIFY_TONES.includes(data.tone) ? data.tone : 'info'
    })
    notifySystem(title, data.message || '', { tag: data.action })
    return
  }

  if (data.biz === 'group') {
    // 群会话会出现在会话列表里，成员变动、群名修改需要同步；
    // 同时刷新群列表，保持群资料与后端一致
    conversation.fetchList()
    group.onGroupNotify(data)
    // 入群相关的子事件额外给用户一条可操作的系统通知
    handleGroupJoinNotify(data, group)
    return
  }
}

/**
 * 入群审批 / 邀请相关的提醒。
 *
 * <p>只处理三种新 action，其余群事件（members-added 等）维持原有的“静默刷新”行为，
 * 不弹窗打扰。join-request 推给管理层、join-approved/join-rejected 推给申请人本端。
 */
function handleGroupJoinNotify(data, group) {
  const action = data.action
  if (!action) {
    return
  }
  if (action === 'join-request') {
    ElNotification({
      title: '新的入群申请',
      message: `${data.nickname || '有人'} 申请加入群聊，请前往群设置审批`,
      type: 'info'
    })
    notifySystem('新的入群申请', `${data.nickname || '有人'} 申请加入群聊，请前往群设置审批`, {
      tag: 'join-request'
    })
    return
  }
  if (action === 'join-approved') {
    ElNotification({
      title: '入群申请已通过',
      message: `你已加入群聊${data.groupName ? `「${data.groupName}」` : ''}`,
      type: 'success',
      onClick: () => {
        if (data.conversationId) {
          router.push({ name: 'chat', params: { conversationId: asId(data.conversationId) } })
        }
      }
    })
    notifySystem('入群申请已通过', `你已加入群聊${data.groupName ? `「${data.groupName}」` : ''}`, {
      tag: 'join-approved',
      onOpen: () => {
        if (data.conversationId) {
          router.push({ name: 'chat', params: { conversationId: asId(data.conversationId) } })
        }
      }
    })
    return
  }
  if (action === 'join-rejected') {
    const message = data.groupName
      ? '管理员未通过你加入「' + data.groupName + '」的申请'
      : '管理员未通过你的入群申请'
    ElNotification({
      title: '入群申请被拒绝',
      message,
      type: 'warning'
    })
    notifySystem('入群申请被拒绝', message, { tag: 'join-rejected' })
  }
}
