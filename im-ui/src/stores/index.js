import { useAuthStore } from './auth'
import { useChatStore } from './chat'
import { useConversationStore } from './conversation'
import { useFriendStore } from './friend'

/**
 * 退出登录的完整流程。
 *
 * 抽出来是因为导航条与「我的」页面各有一个退出入口，两边的清理步骤必须完全一致：
 * 少清一个 store，换账号登录后就会短暂看到上一个人的列表或聊天记录。
 *
 * 单独放在这个文件而不是塞进 auth store：chat store 已经 import 了 auth store，
 * 反向再引会形成循环依赖（Vite 下的表现是拿到 undefined）。
 * 本文件不被任何 store 引用，是依赖链的末端，天然安全。
 */
export async function signOut() {
  const auth = useAuthStore()
  // auth.logout 内部会关 WebSocket、清 token、清媒体缓存并复位未读角标；
  // 后端调用失败它也会完成本地清理，不会把人卡在「退不出去」的界面
  await auth.logout()
  useChatStore().reset()
  useConversationStore().reset()
  useFriendStore().reset()
}
