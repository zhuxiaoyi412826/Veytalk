import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import router from '@/router'
import { asId, sameId } from '@/utils/id'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import { useConversationStore } from '@/stores/conversation'
import { useFriendStore } from '@/stores/friend'
import { useGroupStore } from '@/stores/group'
import { useCallStore } from '@/stores/call'
import { useLinkmicStore } from '@/stores/linkmic'
import { on, connect } from './socket'

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
  const linkmic = useLinkmicStore()

  const disposers = []

  disposers.push(
    on('open', ({ reconnected }) => {
      // 连上就是「网络可用」的最强信号：先重提交离线待发送队列（内部空队列时几乎零成本），
      // 断网期间攒下的消息按入队顺序补发，clientMsgId 幂等保证不会重复入库
      chat.flushPending()
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

  // 群主/管理员标记或取消精华，后端推 {messageId, conversationId, essence} 给群成员：各端气泡精华标签实时增减
  disposers.push(on('essence-notify', (packet) => chat.applyEssence(packet.data)))

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

  disposers.push(on('notify', (packet) => handleNotify(packet.data, { conversation, friend, group })))

  // 音视频通话信令：整帧交给通话 store 的状态机裁决（invite/accept/offer/answer/ice/hangup/failed 等）
  disposers.push(on('call-signal', (packet) => call.onSignal(packet.data)))

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

  // 刷新页面后没有走过登录流程，token 在 localStorage 里而连接还没建立
  connect()

  installed = () => {
    disposers.forEach((dispose) => dispose())
    installed = null
  }
  return installed
}

export function uninstallWsDispatch() {
  if (installed) {
    installed()
  }
}

/**
 * 处理 notify 帧。
 *
 * 同一个 type 下有三种形态，且区分字段并不统一：
 * 握手欢迎与好友相关用 action / bizType，群组相关用 biz。
 * 这是后端逐步演进留下的现状，前端必须两种都认，只按其中一种判断会静默漏掉另一半通知。
 */
function handleNotify(data, { conversation, friend, group }) {
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
    return
  }
  if (action === 'join-rejected') {
    ElNotification({
      title: '入群申请被拒绝',
      message: data.groupName ? `管理员未通过你加入「${data.groupName}」的申请` : '管理员未通过你的入群申请',
      type: 'warning'
    })
  }
}
