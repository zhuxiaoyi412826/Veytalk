import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
import { getToken } from '@/utils/token'
import { setSectionTitle } from '@/utils/title'
import { isElectron } from '@/utils/env'

/**
 * 路由表。
 *
 * 本文件刻意只依赖 utils/token，不 import 任何 api 或 store：
 * api/request.js 需要在登录态失效时把用户送回登录页，它 import 了本文件，
 * 这里再反向依赖 api 就会形成循环（Vite 下表现为其中之一拿到 undefined）。
 *
 * 页面全部用动态 import 懒加载，登录页因此不必等 element-plus 之外的
 * 聊天、好友相关代码下载完就能显示。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    children: [
      { path: '', redirect: { name: 'chat' } },
      {
        // 可选参数让「未选中会话」与「选中某个会话」共用一个组件实例：
        // 切换会话时 ChatHome 不会重新挂载，左侧列表的滚动位置与已加载数据都得以保留。
        path: 'chat/:conversationId?',
        name: 'chat',
        component: () => import('@/views/ChatHome.vue'),
        meta: { title: '消息' }
      },
      {
        path: 'friends',
        name: 'friends',
        component: () => import('@/views/FriendList.vue'),
        meta: { title: '好友' }
      },
      {
        path: 'friends/requests',
        name: 'friend-requests',
        component: () => import('@/views/FriendRequest.vue'),
        meta: { title: '好友申请' }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/Profile.vue'),
        meta: { title: '个人信息' }
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/Settings.vue'),
        meta: { title: '设置' }
      },
      {
        path: 'interview',
        name: 'interview',
        component: () => import('@/views/Interview.vue'),
        meta: { title: '后端 Java 全栈面试' }
      },
      {
        path: 'user/:id',
        name: 'user-profile',
        component: () => import('@/views/UserProfile.vue'),
        meta: { title: '用户资料' }
      }
    ]
  },
  // 未匹配到的地址一律回主界面：手输错地址比看到一个空白 404 页更有用
  { path: '/:pathMatch(.*)*', redirect: { name: 'chat' } }
]

const router = createRouter({
  // Electron 从 file:// 加载页面，没有服务器做 URL 重写，HTML5 history 模式会让刷新/深链
  // 落到不存在的路径而白屏，必须退回 hash 模式；浏览器 Web 部署仍用 history（URL 更干净）。
  history: isElectron() ? createWebHashHistory() : createWebHistory(),
  routes,
  // 切换会话时不要把滚动位置带过去
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  const authenticated = !!getToken()
  if (to.meta.public) {
    // 已登录还访问登录页（后退按钮、收藏夹）时送回主界面
    return authenticated && to.name === 'login' ? { name: 'chat' } : true
  }
  if (!authenticated) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  // 未读数那半边标题由 conversation store 维护，这里只换栏目名
  setSectionTitle(to.meta.title)
})

export default router
