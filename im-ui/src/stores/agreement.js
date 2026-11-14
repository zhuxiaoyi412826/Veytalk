import { defineStore } from 'pinia'
import { fetchAgreement, fetchAgreements, fetchPendingAgreements, submitAgreementConsent } from '@/api/agreement'

/**
 * 法定文本在前端唯一的持有者：清单、待签弹窗、正文缓存。
 *
 * 为什么要做成 store 而不是各页面各自调接口：同一份「当前生效版本号」会被三个地方用到
 * （注册页要把它原样回传、再同意弹窗要显示它、设置页的链接要指向它）。各拉各的就会出现
 * 「注册页拿着 v1 提交而弹窗已经看到 v2」这种自己跟自己不一致的最难查的错。
 *
 * <b>本文件不 import '@/stores'</b>：那个文件的 signOut() 会反过来调本 store 的 reset()，
 * 双向引用在 Vite 下会拿到 undefined。登出这个动作交给弹窗组件去做（组件是依赖链末端）。
 */

/** 后端 ResultCode.AGREEMENT_VERSION_MISMATCH：文本在他阅读期间被换过 */
const CODE_VERSION_MISMATCH = 2023

/**
 * 「这个版本我在这台设备上选过暂不处理」的本地记录。
 *
 * 只在弹窗可跳过（待签项全是可选条款）时才查它，也只在这种时候写它：
 * 必签项的「不同意」在服务端一条留痕都不给过，靠本地标记把它藏起来等于自欺欺人，
 * 下次启动必须照弹。可选条款反过来，反复骚扰一个已经说过不要的人才是问题。
 */
const DISMISS_KEY = 'im.agreement.dismissed'

/**
 * 正文缓存，按 {@code code:version} 存。
 *
 * 缓存可以无限长且永远不用失效：发布后的正文不可改（后台没有改已发布版本的入口，
 * 改文案等于发新版本），同一个版本的内容因此是常量。公示页与弹窗因此共用一份下载。
 * 不做成 state：正文是几十 KB 的字符串，塞进响应式对象会让 Pinia 每次比对都拷一遍全文。
 */
const contentCache = new Map()

export const useAgreementStore = defineStore('agreement', {
  state: () => ({
    /** 三份当前生效版（不含正文），顺序由后端定死 */
    list: [],
    /** 清单是否已经成功拉过，避免重复请求；失败时保持 false，下次进来会再试 */
    listLoaded: false,
    /**
     * 清单上一次加载失败的原因文案，成功一次就清空。
     *
     * 为什么要把吞掉的错误留成状态：注册页那几行勾选是 `v-if="列表非空"` 渲染的，
     * 「后端没起」与「后台一份文本都没发布」在页面上长得一模一样（都是什么都不显示）。
     * 2026-11-08 那次报错就是撞在这上面：ImApplication 重启的 68 秒里 /api 全部 500，
     * 注册弹窗因此没有协议行，用户只看到一个莫名其妙的失败。
     */
    listError: '',
    /** 待签项（弹窗正在展示的那些），空数组表示不需要弹 */
    pending: [],
    /** 弹窗是否可跳过：服务端给的，前端不自己判「哪些算必签」 */
    dismissible: true,
    dialogVisible: false,
    submitting: false
  }),

  getters: {
    /** 按 code 取当前生效版，公示页与注册页都靠它把 code 换成中文名与版本号 */
    byCode: (state) => (code) => state.list.find((item) => item.code === code) || null
  },

  actions: {
    /**
     * 拉一次清单。
     * @param force 强制重拉：注册页每次打开弹窗都调这个，因为可能有人刚好在填表时后台发了新版
     */
    async refreshList(force = false) {
      if (this.listLoaded && !force) {
        return this.list
      }
      try {
        const list = await fetchAgreements()
        this.list = Array.isArray(list) ? list : []
        this.listLoaded = true
        this.listError = ''
      } catch (e) {
        // 接口是 silent 的：清单拿不到就什么都不显示，注册页的勾选行会随之隐藏（见 Login.vue）。
        // 但失败原因要留在 listError 里，由页面摆出「加载失败 + 重试」——
        // 静默吞掉是这一整套链路里唯一会让「点了没反应」重现的地方。
        // 旧的 list 故意不清空：拉到过一版就用那一版提交，比把已读文本抹掉更接近正确行为
        this.listError = (e && e.message) || '条款清单加载失败'
      }
      return this.list
    },

    /**
     * 取某一份的正文，命中缓存就不发请求。
     * @returns 失败时返回 null，调用方自己决定是显示错误还是保留旧内容
     */
    async loadContent(code, version) {
      const key = `${code}:${version || 'current'}`
      if (contentCache.has(key)) {
        return contentCache.get(key)
      }
      try {
        const vo = await fetchAgreement(code, version)
        if (vo && vo.content) {
          contentCache.set(key, vo)
        }
        return vo || null
      } catch {
        return null
      }
    },

    /**
     * 登录后问一次「还欠哪几份」，需要就把弹窗立起来。
     *
     * 由 MainLayout 的 bootstrap 调用（首屏与换账号进来都会问）。放在 bootstrap 而不是
     * 登录接口返回之后：验证码登录、扫码登录、自动建号这些路径都不经过注册页那个勾选框，
     * 而它们拿到的登录态和账号密码登录是完全等价的，必须走同一道确认。
     */
    async check() {
      if (this.submitting) {
        return
      }
      let vo
      try {
        vo = await fetchPendingAgreements()
      } catch {
        return
      }
      const items = vo && Array.isArray(vo.agreements) ? vo.agreements : []
      if (!vo || !vo.need || items.length === 0) {
        this.pending = []
        this.dialogVisible = false
        return
      }
      const dismissible = vo.dismissible !== false
      const target = dismissible ? items.filter((item) => !dismissedLocally(item)) : items
      if (target.length === 0) {
        this.dialogVisible = false
        return
      }
      this.pending = target
      this.dismissible = dismissible
      this.dialogVisible = true
    },

    /**
     * 提交表态。
     * @param items {@code [{ code, version, accept }]}
     * @returns {@code 'ok'} 已留痕并关窗；{@code 'mismatch'} 文本已换版，弹窗内容已刷新需重读；
     *          {@code 'error'} 其他失败，弹窗原样留着可重试
     */
    async submit(items) {
      this.submitting = true
      try {
        await submitAgreementConsent(items)
        this.pending = []
        this.dialogVisible = false
        clearDismissed(items)
        return 'ok'
      } catch (e) {
        if (e && e.code === CODE_VERSION_MISMATCH) {
          // 重新问一次：弹窗里换成新版内容，让他重读一遍再表态。
          // 绝不能拿旧版本号原地重试一次——那会把他对上一版文本的勾选记成对这一版的同意，
          // 而这一整套设计存在的唯一理由就是「同意只对具体某一版有效」
          await this.check()
          return 'mismatch'
        }
        return 'error'
      } finally {
        this.submitting = false
      }
    },

    /**
     * 关掉弹窗（只有可跳过那一条路走到这里）。
     *
     * 不提交任何表态：没做过的决定不能留痕，留了就是伪造。改为在本地记下
     * 「这一版这台设备上他选过暂不处理」，同一版不再重复弹。
     */
    dismiss() {
      if (this.dismissible) {
        rememberDismissed(this.pending)
      }
      this.dialogVisible = false
    },

    /** 退出登录时被 '@/stores' 的 signOut 调用 */
    reset() {
      this.pending = []
      this.dismissible = true
      this.dialogVisible = false
      this.submitting = false
    }
  }
})

/** 待签项是否已被本机「暂不处理」过 */
function dismissedLocally(item) {
  return readDismissed().has(dismissKey(item))
}

/** 提交成功后清掉这些 code 的本地跳过标记：已经表过态了，标记留着只会挡住下一次改版提醒 */
function clearDismissed(items) {
  const set = readDismissed()
  let changed = false
  items.forEach((item) => {
    const key = dismissKey(item)
    if (set.delete(key)) {
      changed = true
    }
  })
  if (changed) {
    writeDismissed(set)
  }
}

function rememberDismissed(items) {
  const set = readDismissed()
  let changed = false
  items.forEach((item) => {
    const key = dismissKey(item)
    if (!set.has(key)) {
      set.add(key)
      changed = true
    }
  })
  if (changed) {
    writeDismissed(set)
  }
}

/** 键里带版本号：只挡「这一版」的重复弹，后台一发新版，标记自然失效，弹窗照旧出现 */
function dismissKey(item) {
  return `${item.code}:${item.version}`
}

function readDismissed() {
  try {
    const raw = localStorage.getItem(DISMISS_KEY)
    const parsed = raw ? JSON.parse(raw) : null
    return new Set(Array.isArray(parsed) ? parsed : [])
  } catch {
    // 无痕模式或坏数据：按「本机没跳过任何东西」处理，宁可多弹一次
    return new Set()
  }
}

function writeDismissed(set) {
  try {
    localStorage.setItem(DISMISS_KEY, JSON.stringify(Array.from(set)))
  } catch {
    // 写不进去只是下次会再弹一次，不影响任何功能
  }
}
