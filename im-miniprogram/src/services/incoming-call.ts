import Taro from '@tarojs/taro'
import { send as sendSignal, subscribe } from './websocket'
import { useAppStore } from '@/store/useAppStore'
import type { WsEvent } from '@/types/im'

interface CallSignalData {
  action?: string
  callId?: string
  fromUserId?: string | number
  mediaType?: string
}

/** 正在振铃的 callId；同一通电话只弹一次，避免 invite 重发时堆叠多个弹窗 */
let ringingCallId = ''

function resolveName(fromUserId: string): string {
  const friends = useAppStore.getState().friends as Array<Record<string, unknown>> | undefined
  const friend = friends?.find((f) => String(f.userId ?? f.friendId ?? '') === fromUserId)
  if (!friend) return '对方'
  return String(friend.remark || friend.nickname || friend.name || '对方')
}

/**
 * 全局来电监听：任何页面收到 call-signal/invite 都弹接听框。
 *
 * 用 Taro.showModal 而不是自定义来电页，是为了让来电在任意页面都能覆盖当前栈顶；
 * 代价是主叫 cancel 时无法编程收起这个系统弹窗，被叫此时点接听会立刻收到结束帧退出，
 * 这一版接受这个体验折中。
 */
export function installIncomingCall(): () => void {
  return subscribe((event: WsEvent) => {
    if (event.type !== 'call-signal') return
    const data = event.data as CallSignalData | undefined
    if (!data?.callId) return

    if (data.action === 'cancel' || data.action === 'timeout') {
      if (ringingCallId === data.callId) ringingCallId = ''
      return
    }
    if (data.action !== 'invite') return
    if (ringingCallId === data.callId) return
    ringingCallId = data.callId

    const callId = data.callId
    const fromUserId = String(data.fromUserId || '')
    const mediaType = data.mediaType === 'video' ? 'video' : 'audio'
    const name = resolveName(fromUserId)

    Taro.vibrateLong().catch(() => undefined)

    void Taro.showModal({
      title: mediaType === 'video' ? '视频通话邀请' : '语音通话邀请',
      content: `${name} 邀请你通话`,
      confirmText: '接听',
      cancelText: '拒绝'
    }).then((res) => {
      if (res.confirm) {
        const query = `role=callee&callId=${callId}&mediaType=${mediaType}&peerUserId=${fromUserId}`
          + `&peerName=${encodeURIComponent(name)}&peerAvatar=`
        void Taro.navigateTo({ url: `/pages/call/index?${query}` })
      } else {
        sendSignal({ type: 'call', action: 'reject', callId })
      }
    }).catch(() => undefined).finally(() => {
      if (ringingCallId === callId) ringingCallId = ''
    })
  })
}
