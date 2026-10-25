import { defineStore } from 'pinia'
import { fetchReportConfig, submitReport } from '@/api/report'

/**
 * 举报入口的开关、字典与那一个共享弹窗的状态。
 *
 * 为什么要有这个 store：举报入口散在四个地方（消息右键、资料卡、直播间、群设置），
 * 每处各自打一次 /api/report/config 就是四次同样的请求，而且四处对「没拿到配置时该不该显示入口」
 * 的判断很容易写出不一致的答案。字典取一次、开关判一处，入口只管调 openReport()。
 *
 * 弹窗本体只挂一份（layout/MainLayout.vue），由这里的 target + visible 驱动：
 * 四个入口各引一个对话框组件会让「同时开着两个举报窗」这种事成为可能。
 */
export const useReportStore = defineStore('report', {
  state: () => ({
    /** /api/report/config 的原样响应，null 表示还没取到 */
    config: null,
    loaded: false,
    loading: false,
    /** 弹窗是否可见 */
    visible: false,
    /** 当前要举报的对象：{ targetType, targetId, title } */
    target: null,
    /** 提交进行中标记，防双击重复提交 */
    submitting: false
  }),

  getters: {
    /**
     * 举报入口是否显示。
     *
     * <b>拿不到配置时按「开」处理</b>：只有服务端明确回了 enabled=false 才藏入口。
     * 反过来做的话，网络抖一下四个入口就同时消失，看起来像功能被撤了，
     * 而真正的闸在后端（submit 第一行就 throwUnless(enabled, REPORT_DISABLED)）。
     */
    enabled: (state) => !state.config || state.config.enabled !== false,
    reasons: (state) => state.config?.reasons || [],
    targetTypes: (state) => state.config?.targetTypes || [],
    reasonTextMax: (state) => state.config?.reasonTextMax || 500
  },

  actions: {
    /** 进主界面时拉一次；force 用于处置开关被改后不想刷新页面的场合 */
    async ensureConfig(force = false) {
      // 守卫只看「成功拿到过没有」：只看 loaded 的话，首次失败（后端没起、网络抖）
      // 会把 loaded 置真再短路掉所有重试，弹窗里的理由下拉框从此永远空着，
      // 非得刷新页面才能恢复——失败必须留着下次重开弹窗时自动再试的机会
      if (this.loading || (this.loaded && this.config && !force)) {
        return this.config
      }
      this.loading = true
      try {
        this.config = await fetchReportConfig()
        this.loaded = true
      } catch {
        // silent 请求，失败不弹提示；config 留 null、loaded 留 false，下次打开弹窗重试；
        // enabled 那道 getter 会按「开」处理
      } finally {
        this.loading = false
      }
      return this.config
    },

    /**
     * 打开举报弹窗。
     *
     * @param {{targetType: string, targetId: (string|number), title?: string}} target
     *        targetType 用后端的码（user / message / room / group），不要传中文名
     * @returns {boolean} 没开举报功能时返回 false，调用方据此什么都不用做
     */
    openReport(target) {
      if (!this.enabled || !target?.targetType || target.targetId == null) {
        return false
      }
      this.target = { title: '', ...target }
      this.visible = true
      // 入口可能在配置还没到位时被点到（首屏并发），此时字典是空的，补一次
      this.ensureConfig()
      return true
    },

    closeReport() {
      this.visible = false
      this.target = null
    },

    /** 提交一次举报，成功返回工单 ID；失败由 request.js 弹提示，这里只把异常抛回弹窗 */
    async submit({ reasonCode, reasonText }) {
      if (!this.target) {
        return null
      }
      this.submitting = true
      try {
        return await submitReport({
          targetType: this.target.targetType,
          targetId: this.target.targetId,
          reasonCode,
          reasonText: reasonText || undefined
        })
      } finally {
        this.submitting = false
      }
    },

    /** 退出登录时清掉，避免换账号后短暂看到上一个账号拿到的字典 */
    reset() {
      this.config = null
      this.loaded = false
      this.loading = false
      this.visible = false
      this.target = null
      this.submitting = false
    }
  }
})
