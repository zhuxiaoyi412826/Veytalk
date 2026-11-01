import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/token'

/**
 * 后台路由表。
 *
 * 顶部只 import utils/token，不 import api 与 store，和 im-ui 的路由是同一条规矩：
 * api/request.js 要在登录态失效时把用户送回登录页，它 import 了本文件，
 * 这里反向依赖 api 就会形成循环（Vite 下表现为其中之一拿到 undefined）。
 * 守卫里确实需要 auth store，用的是<b>函数内动态 import</b>——模块求值阶段不存在这条边，
 * 循环就组不起来。
 *
 * 内页全部懒加载：登录后首屏只要看板那一个 chunk，
 * 其余页面的代码等点到菜单再下。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('@/layout/AdminLayout.vue'),
    children: [
      { path: '', redirect: { name: 'dashboard' } },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '数据看板', perm: 'system:dashboard' }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UsersView.vue'),
        meta: { title: '用户管理', perm: 'system:user:manage' }
      },
      {
        path: 'reports',
        name: 'reports',
        component: () => import('@/views/ReportsView.vue'),
        meta: { title: '举报工单', perm: 'system:report:handle' }
      },
      {
        path: 'roles',
        name: 'roles',
        component: () => import('@/views/RolesView.vue'),
        meta: { title: '角色管理', perm: 'system:role:manage' }
      },
      {
        path: 'videos',
        name: 'videos',
        component: () => import('@/views/VideosView.vue'),
        meta: { title: '视频管理', perm: 'system:video:manage' }
      },
      {
        path: 'articles',
        name: 'articles',
        component: () => import('@/views/ArticlesView.vue'),
        meta: { title: '文章管理', perm: 'system:article:manage' }
      },
      {
        path: 'lives',
        name: 'lives',
        component: () => import('@/views/LivesView.vue'),
        meta: { title: '直播管理', perm: 'system:live:manage' }
      },
      {
        path: 'groups',
        name: 'groups',
        component: () => import('@/views/GroupView.vue'),
        meta: { title: '群组管理', perm: 'system:group:manage' }
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/MessageView.vue'),
        meta: { title: '消息管理', perm: 'system:message:manage' }
      },
      {
        path: 'wallet',
        name: 'wallet',
        component: () => import('@/views/WalletView.vue'),
        meta: { title: '钱包与礼物', perm: 'system:wallet' }
      },
      {
        path: 'interviews',
        name: 'interviews',
        component: () => import('@/views/InterviewView.vue'),
        meta: { title: 'AI 面试记录', perm: 'system:interview' }
      },
      {
        path: 'monitor',
        name: 'monitor',
        component: () => import('@/views/MonitorView.vue'),
        meta: { title: '监控告警', perm: 'system:monitor' }
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('@/views/LogsView.vue'),
        meta: { title: '日志管理', perm: 'system:log' }
      },
      {
        path: 'config',
        name: 'config',
        component: () => import('@/views/ConfigView.vue'),
        meta: { title: '系统配置', perm: 'system:config' }
      },
      {
        path: 'remote',
        name: 'remote',
        component: () => import('@/views/RemoteView.vue'),
        meta: { title: '远程控制', perm: 'system:remote' }
      },
      {
        path: 'announcements',
        name: 'announcements',
        component: () => import('@/views/AnnounceView.vue'),
        meta: { title: '全服公告', perm: 'system:announcement' }
      }
    ]
  },
  // 手输错地址一律回看板：给一个 404 空壳不如把人送回有数字的地方
  { path: '/:pathMatch(.*)*', redirect: { name: 'dashboard' } }
]

const router = createRouter({
  // 后台只在浏览器里跑（不做 Electron 打包），history 模式足够；
  // 但部署时子路径要在 nginx 上配 try_files，否则刷新内页会 404，细节见 README
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    // 已登录还访问登录页（后退按钮、收藏夹）时送回看板
    return getToken() && to.name === 'login' ? { name: 'dashboard' } : true
  }
  if (!getToken()) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  // 有凭证不代表有权限：刷新页面时必须先问一次 /auth/me 才能确认这个账号仍持有
  // 至少一个后台权限点（伞权限或任意细粒度点）。这一步是 await 的，代价是首屏多等一个请求，
  // 换来的是不会先进看板刷一屏 1003 再被踢回登录页
  const { useAuthStore } = await import('@/stores/auth')
  const auth = useAuthStore()
  const info = await auth.ensureLoaded()
  if (!info || !auth.allowed) {
    auth.teardown()
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  // 进了后台不代表能看每一屏：二三四期按细粒度权限点逐路由放行。
  // 缺这一屏权限时送回他能访问的第一屏（而不是弹回登录页——他确实是合法的后台用户，只是不该看到这一页）。
  // 没有 meta.perm 的路由（如根路径 redirect）不拦。
  //
  // 下面这个兜底顺序是有意义的：把「只看数字」的看板放最前，把能动手的屏放后面，
  // 于是只持低风险点的账号不会刚进后台就落在一个带危险按钮的页面上。
  // 四期第五批的四项插在直播之后、监控之前：群组与消息是「治理」，钱包与面试是「经营与招聘」，
  // 它们都比那四张运维表更接近业务现场，也更接近需要被单独授权的那一档。
  if (to.meta.perm && !auth.can(to.meta.perm)) {
    const fallback = [
      'dashboard',
      'reports',
      'users',
      'roles',
      'videos',
      'articles',
      'lives',
      'groups',
      'messages',
      'wallet',
      'interviews',
      'monitor',
      'logs',
      'config',
      'remote',
      'announcements'
    ].find((name) => {
      const target = routes[1].children.find((child) => child.name === name)
      return target?.meta?.perm && auth.can(target.meta.perm)
    })
    // 兜底：理论上 allowed 已保证至少命中一个点，找不到就退回登录，避免原地打转
    return fallback && fallback !== to.name ? { name: fallback } : { name: 'login' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 管理后台` : '管理后台'
})

export default router
