import type { PropsWithChildren } from 'react'
import { useEffect } from 'react'
import Taro, { useDidHide, useDidShow } from '@tarojs/taro'
import { installIncomingCall } from '@/services/incoming-call'
import { installRealtime } from '@/services/realtime'
import { connect, disconnect } from '@/services/websocket'
import { getToken } from '@/utils/session'
import './app.scss'

function App({ children }: PropsWithChildren) {
  useEffect(() => installRealtime(), [])
  useEffect(() => installIncomingCall(), [])

  useDidShow(() => {
    console.error('[App] useDidShow 触发, token:', getToken() ? '已存在' : '为空')
    if (getToken()) void connect()
  })

  useDidHide(() => disconnect())

  return children
}

export default App
