import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import * as userApi from '@/api/user'
import { getToken, setToken, clearToken, getDeviceId } from '@/utils/token'
import { resetRedirectFlag } from '@/api/request'
import { clearMediaCache, mediaUrl } from '@/utils/media'
import { initLocalDb } from '@/utils/localdb'
import { setUnreadCount } from '@/utils/title'
import { connect, disconnect } from '@/ws/socket'

/**
 * 登录态与当前用户资料。
 *
 * token 的真身在 localStorage（见 utils/token），store 里只留 userInfo。
 * 这样刷新页面后不必等 /auth/me 返回就能让路由守卫放行，
 * 界面也不会因为 store 被重置而闪一下登录页。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    userInfo: null,
    /** /auth/me 是否已经问过一次，用来区分「还没加载」与「确实没登录」 */
    resolved: false
  }),

  getters: {
    isLoggedIn: () => !!getToken(),
    userId: (state) => state.userInfo?.userId ?? null,
    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '',
    username: (state) => state.userInfo?.username || '',
    signature: (state) => state.userInfo?.signature || '',
    gender: (state) => state.userInfo?.gender ?? 0,
    phone: (state) => state.userInfo?.phone || '',
    email: (state) => state.userInfo?.email || '',
    roles: (state) => state.userInfo?.roles || [],
    permissions: (state) => state.userInfo?.permissions || [],
    /** 是否已设置密码；验证码登录自动建号的账号为 false，需引导首次设置 */
    passwordSet: (state) => state.userInfo?.passwordSet !== false,
    /** 头像的可渲染地址，空值时组件回退到昵称首字母 */
    avatarUrl: (state) => mediaUrl(state.userInfo?.avatar),
    avatarRaw: (state) => state.userInfo?.avatar || ''
  },

  actions: {
    /**
     * 是否持有某个权限串。
     *
     * 清单由后端 PermissionProviderImpl 从 im_permission 联表查出，种子数据里
     * admin 拿到的是 7 个权限码的完整枚举而不是通配符。
     * 这里额外认一下 '*' 是为了和 Sa-Token 服务端的通配语义保持一致：
     * 将来若在角色上配了 '*'，前端不能比后端更严而把按钮隐藏掉。
     */
    hasPermission(permission) {
      if (!permission) {
        return true
      }
      const owned = this.permissions
      return owned.includes('*') || owned.includes(permission)
    },

    hasRole(role) {
      return this.roles.includes(role)
    },

    /** 账号密码登录，form: { account, password, captchaKey, captchaCode, loginType } */
    async login(form) {
      const vo = await authApi.login({ ...form, deviceId: getDeviceId() })
      this.applyLoginResult(vo)
      return vo
    },

    /** 手机号 + 短信验证码登录 */
    async loginBySms(form) {
      const vo = await authApi.loginBySms({ ...form, deviceId: getDeviceId() })
      this.applyLoginResult(vo)
      return vo
    },

    /** 邮箱 + 邮箱验证码登录，form: { email, emailCode } */
    async loginByEmail(form) {
      const vo = await authApi.loginByEmail({ ...form, deviceId: getDeviceId() })
      this.applyLoginResult(vo)
      return vo
    },

    /** 注册。后端注册成功即返回登录态，不必再调一次 login */
    async register(form) {
      const vo = await authApi.register({ ...form, deviceId: getDeviceId() })
      this.applyLoginResult(vo)
      return vo
    },

    applyLoginResult(vo) {
      if (!vo || !vo.token) {
        return
      }
      setToken(vo.token, vo.tokenName)
      this.userInfo = vo.userInfo || null
      this.resolved = true
      // 上一次因为登录态失效而置位的跳转抑制标记必须复位，
      // 否则本次登录后再遇到任何 1002 都不会跳登录页了
      resetRedirectFlag()
      connect()
      // 登录成功后打开当前用户的本地消息库（切换账号时会先关掉上一个用户的库）
      initLocalDb(this.userId)
    },

    /**
     * 刷新页面后恢复资料。
     *
     * 拿不到（token 过期、被踢）就静默清掉本地凭证，让路由守卫把人送去登录页；
     * 这里的失败提示交给 request.js 的统一处理，本方法不再弹一次。
     */
    async loadCurrentUser() {
      if (!getToken()) {
        this.resolved = true
        return null
      }
      try {
        this.userInfo = await userApi.fetchProfile()
        // 刷新页面后没走登录流程，这里补齐本地库的初始化（内部幂等）
        initLocalDb(this.userId)
      } catch {
        this.teardown()
        return null
      } finally {
        this.resolved = true
      }
      return this.userInfo
    },

    async updateProfile(data) {
      this.userInfo = await userApi.updateProfile(data)
      return this.userInfo
    },

    /** 绑定 / 换绑手机号，后端返回绑定后的最新资料，直接覆盖本地缓存 */
    async bindPhone(data) {
      this.userInfo = await userApi.bindPhone(data)
      return this.userInfo
    },

    /** 头像走专用上传接口，后端会顺手写进资料，这里只需刷新本地缓存 */
    applyAvatar(fileVo) {
      if (!this.userInfo || !fileVo) {
        return
      }
      this.userInfo = { ...this.userInfo, avatar: fileVo.url }
    },

    async changePassword(data) {
      await userApi.changePassword(data)
      // 后端的判定是 !Boolean.FALSE.equals(logoutAll)，而 ChangePasswordRequest 里该字段默认 TRUE：
      // 也就是说除非显式传 false，改完密码就会踢掉包括本端在内的全部登录态。
      // 前端必须跟着清，否则界面还停在内页而后续每个请求都在返回 1002。
      if (!data || data.logoutAll !== false) {
        this.teardown()
        return false
      }
      return true
    },

    /** 退出登录。后端调用失败也要完成本地清理，否则用户会被卡在一个「退不出去」的界面 */
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 忽略：登录态可能早已失效
      }
      this.teardown()
    },

    /** 被其他设备顶下线时的本地清理，不回调后端 */
    teardown() {
      disconnect()
      clearToken()
      clearMediaCache()
      setUnreadCount(0)
      this.userInfo = null
      this.resolved = true
    }
  }
})
