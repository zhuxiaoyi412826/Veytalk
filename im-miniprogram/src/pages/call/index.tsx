import { useEffect, useRef, useState } from 'react'
import { Button, LivePlayer, LivePusher, Text, View } from '@tarojs/components'
import Taro, { useRouter } from '@tarojs/taro'
import Avatar from '@/components/Avatar'
import { callApi } from '@/services/api'
import { ApiError } from '@/services/http'
import { send as sendSignal, subscribe } from '@/services/websocket'
import type { TrtcUserSig, WsEvent } from '@/types/im'
import { showError } from '@/utils/ui'
import styles from './index.module.scss'

interface CallSignalData {
  action?: string
  callId?: string
  fromUserId?: string | number
  mediaType?: string
  reason?: string
}

/**
 * TRTC 房间号必须是 1~4294967295 的整数。
 * 由 callId 派生而非信令透传：主被叫各自算一遍即可得到同一个房间号，
 * 不需要改动后端 WsCallPayload，也不会和 Web/App 端的信令字段产生歧义。
 */
function roomIdFromCallId(callId: string): number {
  let hash = 0
  for (let i = 0; i < callId.length; i++) {
    hash = (hash * 31 + callId.charCodeAt(i)) >>> 0
  }
  return (hash % 2000000000) + 1
}

/** 标准 TRTC 推拉流地址；userId 为「流归属方」，拉流时填对端 ID */
function trtcUrl(sig: TrtcUserSig, roomId: number, userId: string): string {
  return `room://cloud.tencent.com/rtc?sdkappid=${sig.sdkAppId}&roomId=${roomId}`
    + `&userId=${encodeURIComponent(userId)}&userSig=${encodeURIComponent(sig.userSig)}`
}

export default function CallPage() {
  const router = useRouter()
  const callId = router.params.callId || ''
  const mediaType = router.params.mediaType === 'video' ? 'video' : 'audio'
  const peerUserId = router.params.peerUserId || ''
  const peerName = router.params.peerName || '对方'
  const peerAvatar = router.params.peerAvatar || ''
  const role = router.params.role === 'callee' ? 'callee' : 'caller'
  const isVideo = mediaType === 'video'

  const [sig, setSig] = useState<TrtcUserSig | null>(null)
  const [accepted, setAccepted] = useState(false)
  const [statusText, setStatusText] = useState(role === 'caller' ? '正在呼叫，等待对方接听…' : '正在接通…')
  const [muted, setMuted] = useState(false)
  const [speakerOff, setSpeakerOff] = useState(false)
  const endedRef = useRef(false)
  const acceptedRef = useRef(false)

  const roomId = roomIdFromCallId(callId)

  useEffect(() => { acceptedRef.current = accepted }, [accepted])

  const finish = (text: string): void => {
    if (endedRef.current) return
    endedRef.current = true
    setStatusText(text)
    setTimeout(() => { Taro.navigateBack().catch(() => Taro.switchTab({ url: '/pages/conversations/index' })) }, 1200)
  }

  // 拉签名；被叫进页面即视为接受，回一帧 accept 让主叫开始拉流
  useEffect(() => {
    let alive = true
    const load = async (): Promise<void> => {
      try {
        const result = await callApi.userSig()
        if (!alive) return
        if (!result || result.sdkAppId <= 0 || !result.userSig) {
          showError(new Error('后端未配置 TRTC'), '通话不可用')
          finish('通话服务未配置')
          return
        }
        setSig(result)
        if (role === 'callee') {
          sendSignal({ type: 'call', action: 'accept', callId })
          setAccepted(true)
          setStatusText('通话中')
        }
      } catch (error) {
        console.error('[Call] usersig failed', error)
        // 1004 = 路由不存在：线上后端还是旧版本，没部署 /api/call/trtc-user-sig
        const staleBackend = error instanceof ApiError && error.code === 1004
        if (staleBackend) {
          showError(new Error('服务器端未部署通话接口，请更新后端'), '通话不可用')
          finish('服务器版本过旧')
          return
        }
        showError(error, '通话初始化失败')
        finish('初始化失败')
      }
    }
    void load()
    return () => { alive = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 订阅下行 call-signal：只关心本 callId 的帧
  useEffect(() => {
    const off = subscribe((event: WsEvent) => {
      if (event.type !== 'call-signal') return
      const data = event.data as CallSignalData | undefined
      if (!data || data.callId !== callId) return
      switch (data.action) {
        case 'accept':
          setAccepted(true)
          setStatusText('通话中')
          break
        case 'reject':
          finish('对方已拒绝')
          break
        case 'cancel':
          finish('对方已取消')
          break
        case 'timeout':
          finish('无人接听')
          break
        case 'hangup':
          finish('通话已结束')
          break
        case 'busy':
          finish('对方忙线中')
          break
        default:
          break
      }
    })
    return off
  }, [callId])

  const hangup = (): void => {
    const action = acceptedRef.current ? 'hangup' : role === 'callee' ? 'reject' : 'cancel'
    sendSignal({ type: 'call', action, callId })
    finish('通话已结束')
  }

  const pushUrl = sig ? trtcUrl(sig, roomId, sig.userId) : ''
  const playUrl = sig && accepted && peerUserId ? trtcUrl(sig, roomId, peerUserId) : ''

  return <View className={styles.page}>
    <View className={styles.stage}>
      {isVideo && playUrl
        ? <LivePlayer className={styles.remote} src={playUrl} mode='RTC' autoplay objectFit='fillCrop' soundMode={speakerOff ? 'ear' : 'speaker'} />
        : <View className={styles.audioStage}>
            <Avatar name={peerName} url={peerAvatar} size={160} />
            <Text className={styles.peerName}>{peerName}</Text>
          </View>}
      <Text className={styles.status}>{statusText}</Text>
      {pushUrl
        ? <LivePusher
            className={isVideo ? styles.local : styles.audioPusher}
            url={pushUrl}
            mode='RTC'
            autopush
            enableCamera={isVideo}
            enableMic
            muted={muted}
            beauty={0}
          />
        : null}
    </View>

    <View className={styles.controls}>
      <Button className={styles.controlButton} onClick={() => setMuted(!muted)}>{muted ? '取消静音' : '静音'}</Button>
      <Button className={styles.hangup} onClick={hangup}>挂断</Button>
      <Button className={styles.controlButton} onClick={() => setSpeakerOff(!speakerOff)}>{speakerOff ? '扬声器' : '听筒'}</Button>
    </View>
    <Text className={styles.hint}>小程序通话经腾讯云 TRTC 转发，仅支持与另一台小程序互通</Text>
  </View>
}
