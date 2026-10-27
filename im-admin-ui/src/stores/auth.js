import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { getToken, setToken, clearToken } from '@/utils/token'
import { resetRedirectFlag } from '@/api/request'

/**
 * 与后端 ImConstants 里的后台权限点一一对应。
 *
 * <b>system:manage</b> 是二期之前的伞权限，仍保留作向后兼容：持有它的人（admin）能进所有屏。
 * 二期把四个控制器拆到细粒度点后，一个审核员可能只持有 system:dashboard + system:report:handle
 * 而<b>没有</b>伞权限——所以「能不能进后台」不能再只认 system:manage，得认下面这一组。
 * 三期又加了四个点（视频/直播/监控/日志）：只持有新点的人也该让他进后台，
 * 而不是登录时就报「没有管理后台权限」。
 * 四期再加 system:config（系统配置）：同理，一个只被配了配置页权限的账号
 * 也应该能进后台，只是进去后只看到那一屏。
 * 四期第二批再加 system:remote（远程控制审计）：同一处理方式，不再重复解释。
 * 四期第四批再加 system:announcement（全服公告）：同理，但它是本批<b>唯一</b>的新权限点
 * （在线敏感词不另设点，仍走 system:config），所以跑完 SQL 必须清一次 im:auth:* 缓存。
 * 四期第五批再加四个点（钱包与礼物 / AI 面试记录 / 群组管理 / 消息管理）：这批一次加了四个，
 * 而不是前几批的一个——四屏各认一个点，谁被给了哪屏就只进得去那一屏，同样不能顺带。
 * 这些串是<b>唯一</b>一份：im-admin 那些 Controller 类上的 {@code @SaCheckPermission(...)} 才是真正的门，
 * 前端拿它只做「要不要显示某个菜单 / 要不要放行某个路由」这件事，藏掉按钮从来不是安全边界。
 */
const PERM_SYSTEM_MANAGE = 'system:manage'
const ADMIN_PERMS = [
  'system:dashboard',
  'system:user:manage',
  'system:report:handle',
  'system:role:manage',
  'system:video:manage',
  'system:live:manage',
  'system:monitor',
  'system:log',
  'system:config',
  'system:remote',
  'system:announcement',
  'system:wallet',
  'system:interview',
  'system:group:manage',
  'system:message:manage'
]

function permissionList(userInfo) {
  return userInfo?.permissions || []
}

function hasAdminPermission(userInfo) {
  const permissions = permissionList(userInfo)
  // 额外认一下 '*'：Sa-Token 服务端把 '*' 当通配，前端若比后端更严，
  // 给角色配了通权的人只会看到一个「没有后台权限」的错误提示
  if (permissions.includes('*') || permissions.includes(PERM_SYSTEM_MANAGE)) {
    return true
  }
  // 只持有一个细粒度点（如只有看板的审核员）也该让他进后台，而不是挡在登录页外
  return ADMIN_PERMS.some((perm) => permissions.includes(perm))
}

/**
 * 正在进行中的 /auth/me。
 *
 * 放在模块作用域而不是 state 里：Pinia 的 state 期望可序列化，塞一个 Promise 进去
 * 会让 devtools 与时间旅行调试变得奇怪。整个应用只有一个 auth store 实例，
 * 这个变量也就只有一个主人。
 */
let loadPromise = null

/**
 * 后台的登录态。
 *
 * 它和 im-ui 那份 stores/auth 最大的区别是<b>多了一道权限闸门</b>：
 * 聊天端只要登录成功就能进，后台还要求这个账号持有 system:manage。
 * 这道检查放在这里而不是只放在路由守卫里，是因为守卫只看得见 localStorage，
 * 而「有没有权限」这个事实只有 /auth/login 与 /auth/me 的响应里才有。
 *
 * 两道口子是成对的：
 *  1. 本文件在登录成功与 /auth/me 恢复时各验一次，不合格就不留凭证；
 *  2. api/request.js 兜住「会话中途权限被收回」——那时接口回 1003，
 *     由它清凭证并退回登录页。只留第 1 道的话，被撤权的人还能继续在
 *     已经打开的页面上点到处报错的按钮。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    userInfo: null,
    /** /auth/me 是否已经问过，用来区分「还没加载」与「确实没登录」 */
    resolved: false
  }),

  getters: {
    isLoggedIn: () => !!getToken(),
    userId: (state) => state.userInfo?.userId ?? null,
    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '',
    username: (state) => state.userInfo?.username || '',
    avatarRaw: (state) => state.userInfo?.avatar || '',
    roles: (state) => state.userInfo?.roles || [],
    permissions: (state) => permissionList(state.userInfo),
    /** 已解析出资料且确认持有后台权限点（伞权限或任意细粒度点） */
    allowed: (state) => !!state.userInfo && hasAdminPermission(state.userInfo)
  },

  actions: {
    /**
     * 逐屏权限判定：菜单显隐与路由放行都走它。
     *
     * 三条规则：持有 '*' 直接放行；持有伞权限 system:manage 时放行任意细粒度屏
     * （但反过来「专门问有没有伞权限」仍要求真的有，不能因为有几个细粒度点就谎报）；
     * 否则精确匹配。与后端 {@code @SaCheckPermission} 的判定口径对齐，前端只做显隐不做鉴权。
     */
    can(code) {
      const perms = this.permissions
      if (perms.includes('*')) {
        return true
      }
      if (code !== PERM_SYSTEM_MANAGE && perms.includes(PERM_SYSTEM_MANAGE)) {
        return true
      }
      return perms.includes(code)
    },

    /**
     * 账号密码登录。
     *
     * 权限不合格时<b>不写 token</b>直接抛错：如果先把凭证存进 localStorage 再清，
     * 中间那一次跳转里的拦截器会带着一个「有身份但没权限」的 token 去打管理接口，
     * 页面会在登录页上闪出一串 1003 红条。
     */
    async login(form) {
      const vo = await authApi.login(form)
      if (!vo?.token) {
        throw new Error('登录响应异常：没有拿到 token')
      }
      if (!hasAdminPermission(vo.userInfo)) {
        // 不设凭证，只把原因抛给登录页显示；措辞与 request.js 里的 1003 保持一致
        throw new Error('该账号没有管理后台权限')
      }
      setToken(vo.token, vo.tokenName)
      this.userInfo = vo.userInfo
      this.resolved = true
      resetRedirectFlag()
      return vo
    },

    /**
     * 刷新页面后恢复身份，供路由守卫 await。
     *
     * 失败（token 过期、被踢、权限已被收回）就静默清凭证并返回 null，
     * 由守卫把人送去登录页。错误提示交给 request.js 统一处理，这里不重复弹。
     *
     * <b>并发共用同一个请求</b>：首次进入时路由守卫与布局组件都可能调它，
     * 各自发一次 /auth/me 不只是多一次请求——两个响应交错写 state 会让
     * 「拿到资料却仍被判为没登录」这种偶发问题很难复现。
     */
    async ensureLoaded() {
      if (this.resolved) {
        return this.userInfo
      }
      if (!getToken()) {
        this.resolved = true
        return null
      }
      if (loadPromise) {
        return loadPromise
      }
      loadPromise = this.resolveIdentity()
      try {
        return await loadPromise
      } finally {
        loadPromise = null
      }
    },

    /** ensureLoaded 的实际执行体，单独拆出来只为让上面那段并发合流的逻辑好读 */
    async resolveIdentity() {
      let info
      try {
        info = await authApi.fetchMe()
      } catch {
        // 接口层已经按 1002/1003 做过清凭证与跳转，这里只把状态对齐到「没登录」
        this.teardown()
        return null
      }
      this.resolved = true
      if (!hasAdminPermission(info)) {
        // 权限是在这次会话里被收回的：不留凭证，否则会停在一个每格都 1003 的看板上
        this.teardown()
        return null
      }
      this.userInfo = info
      resetRedirectFlag()
      return info
    },

    /** 主动登出：后端调用失败也要完成本地清理，否则用户会卡在「退不出去」的界面 */
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 忽略：登录态可能早已失效
      }
      this.teardown()
    },

    /** 只做本地清理，不回调后端（被撤权、被踢线时走这里） */
    teardown() {
      clearToken()
      this.userInfo = null
      this.resolved = true
    }
  }
})
