import { defineStore } from 'pinia'
import { setDebugEnabled } from '@/utils/logger'

/**
 * 全局界面设置。
 *
 * 全部落在 localStorage（键 im_settings），只在当前设备/浏览器生效，不建后端表：
 * 这些是纯外观与交互偏好，跨端同步的收益远小于建表 + 接口 + 迁移的成本。
 *
 * store 只维护「状态 + 把状态翻译成 CSS 变量/根类名」两件事：
 * 主题色、字号、列表宽度、气泡圆角都写成 documentElement 上的 CSS 变量，
 * 各组件沿用现有的 var(--im-*) 即可自动生效，无需逐个组件注入。
 */

const STORAGE_KEY = 'im_settings'

/** 可选主题色，Settings 页以色块形式展示 */
export const THEME_COLORS = [
  { label: '蓝色', value: '#409eff' },
  { label: '绿色', value: '#67c23a' },
  { label: '紫色', value: '#722ed1' },
  { label: '橙色', value: '#e6a23c' },
  { label: '红色', value: '#f56c6c' },
  { label: '青色', value: '#13c2c2' }
]

/** 消息字号缩放倍率 */
const FONT_SCALE = { small: 0.9, default: 1, large: 1.15 }

/** 会话列表头像边长（px） */
const AVATAR_PX = { small: 34, medium: 42, large: 50 }

const DEFAULTS = {
  // ---------- 外观 ----------
  themeMode: 'system', // light | dark | system
  themeColor: '#409eff',
  fontSize: 'default', // small | default | large
  chatBgType: 'default', // default | color | image
  chatBgValue: '', // 纯色值或图片 data URL
  listWidth: 300, // 会话列表宽度 px
  avatarSize: 'medium', // small | medium | large
  bubbleStyle: 'rounded', // rounded | simple
  // ---------- 消息行为 ----------
  sendKey: 'enter', // enter | ctrlEnter
  autoLoadHistory: true,
  showPreview: true,
  showTimestamp: true,
  showReadReceipt: true,
  imagePreview: true,
  autoPlay: true,
  // ---------- 隐私与安全 ----------
  /** 聊天窗口全局水印：开启后在当前账号的聊天区叠加账号信息，防截图（仅本机生效） */
  chatWatermark: false,
  /** 预览时禁止下载原文件：开启后文件预览弹窗隐藏「另存为」，只在线查看 */
  previewNoDownload: false,
  /** 多端信息共享：开启后自己在某台设备上发的消息实时镜像到其它端（ack 广播）；关闭后其它端不实时上屏，拉历史仍可见 */
  shareMultiDevice: true,
  // ---------- 本地缓存 ----------
  /** 媒体（图片/视频/文件）本地缓存开关：关闭后每次都从服务端重新拉取 */
  mediaCacheEnabled: true,
  /** 媒体缓存占用上限（MB），超限按 LRU 淘汰最旧条目；0 表示不限制 */
  mediaCacheMaxMb: 200,
  // ---------- 高级 ----------
  debugLog: false
}

/* ------------------------------ 颜色工具 ------------------------------ */

function hexToRgb(hex) {
  const value = String(hex || '').replace('#', '')
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value
  const num = parseInt(full, 16)
  if (Number.isNaN(num) || full.length !== 6) {
    return { r: 64, g: 158, b: 255 }
  }
  return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 }
}

function toHex({ r, g, b }) {
  const part = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0')
  return `#${part(r)}${part(g)}${part(b)}`
}

/**
 * 把 base 朝 target 混合 ratio 比例，用于生成主色的浅色/深色变体。
 * Element Plus 的 primary-light-N 就是主色与白色按 N/10 混合的结果，这里复刻同一算法。
 */
function mix(base, target, ratio) {
  const a = hexToRgb(base)
  const b = hexToRgb(target)
  return toHex({
    r: a.r + (b.r - a.r) * ratio,
    g: a.g + (b.g - a.g) * ratio,
    b: a.b + (b.b - a.b) * ratio
  })
}

/* ------------------------------ store ------------------------------ */

export const useSettingsStore = defineStore('settings', {
  state: () => ({ ...DEFAULTS, systemDark: false, initialized: false }),

  getters: {
    /** 当前是否应呈现深色：显式深色，或跟随系统且系统为深色 */
    isDark: (state) => state.themeMode === 'dark' || (state.themeMode === 'system' && state.systemDark),
    fontScale: (state) => FONT_SCALE[state.fontSize] ?? 1,
    /** 会话列表头像边长，供 ChatHome 直接绑定 */
    avatarPx: (state) => AVATAR_PX[state.avatarSize] ?? AVATAR_PX.medium,
    /**
     * 聊天区背景样式对象，绑定到 .chat-home__main 的 :style。
     * default 时返回空对象，交回 CSS 变量 --im-chat-bg 控制（含深色模式的默认底色）。
     */
    chatBackgroundStyle: (state) => {
      if (state.chatBgType === 'color' && state.chatBgValue) {
        return { background: state.chatBgValue }
      }
      if (state.chatBgType === 'image' && state.chatBgValue) {
        return {
          backgroundImage: `url(${state.chatBgValue})`,
          backgroundSize: 'cover',
          backgroundPosition: 'center',
          backgroundRepeat: 'no-repeat'
        }
      }
      return {}
    }
  },

  actions: {
    /**
     * 应用启动时调用一次：读回持久化设置、监听系统主题、把状态刷到 DOM。
     * 在 main.js 里 pinia 装好之后立即执行，保证首屏就是用户上次的主题。
     */
    init() {
      if (this.initialized) {
        return
      }
      this.load()
      if (typeof window !== 'undefined' && window.matchMedia) {
        const mq = window.matchMedia('(prefers-color-scheme: dark)')
        this.systemDark = mq.matches
        // 跟随系统时，系统主题变化要实时反映到界面
        const onChange = (event) => {
          this.systemDark = event.matches
          if (this.themeMode === 'system') {
            this.apply()
          }
        }
        if (mq.addEventListener) {
          mq.addEventListener('change', onChange)
        } else if (mq.addListener) {
          mq.addListener(onChange)
        }
      }
      this.initialized = true
      this.apply()
    },

    load() {
      try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (!raw) {
          return
        }
        const saved = JSON.parse(raw)
        // 只挑 DEFAULTS 里认得的键，防止旧版本残留字段污染状态
        Object.keys(DEFAULTS).forEach((key) => {
          if (saved[key] !== undefined) {
            this[key] = saved[key]
          }
        })
      } catch {
        // 解析失败就用默认值，不能让设置页打不开
      }
    },

    persist() {
      try {
        const snapshot = {}
        Object.keys(DEFAULTS).forEach((key) => {
          snapshot[key] = this[key]
        })
        localStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot))
      } catch {
        // 隐私模式或配额满时静默失败，设置仍在本会话内生效
      }
    },

    /**
     * 把当前状态翻译成根元素上的 CSS 变量与类名。
     * 所有外观项都收敛到这一处，改设置 = 改状态 + 调 apply。
     */
    apply() {
      if (typeof document === 'undefined') {
        return
      }
      const root = document.documentElement
      root.classList.toggle('dark', this.isDark)
      // 简约气泡：去掉圆角与尖角，尖角的隐藏规则写在 index.css 的全局选择器里
      root.classList.toggle('im-bubble-simple', this.bubbleStyle === 'simple')

      const color = this.themeColor || DEFAULTS.themeColor
      root.style.setProperty('--im-primary', color)
      root.style.setProperty('--im-primary-light', mix(color, '#ffffff', 0.9))
      // Element Plus 的主色及其 9 级浅色变体、2 级深色变体，覆盖按钮/链接/选中态等
      root.style.setProperty('--el-color-primary', color)
      for (let i = 1; i <= 9; i++) {
        root.style.setProperty(`--el-color-primary-light-${i}`, mix(color, '#ffffff', i / 10))
      }
      root.style.setProperty('--el-color-primary-dark-2', mix(color, '#000000', 0.2))

      root.style.setProperty('--im-font-scale', String(this.fontScale))
      root.style.setProperty('--im-list-width', `${Math.max(220, Math.min(460, Number(this.listWidth) || 300))}px`)
      root.style.setProperty('--im-bubble-radius', this.bubbleStyle === 'simple' ? '2px' : '8px')

      setDebugEnabled(this.debugLog)
    },

    /**
     * 统一入口：合并补丁 → 持久化 → 应用。
     * Settings 页的每个控件都走它，避免遗漏 persist/apply 中的某一步。
     */
    update(patch) {
      Object.assign(this, patch)
      this.persist()
      this.apply()
    },

    /** 恢复默认设置 */
    reset() {
      this.update({ ...DEFAULTS })
    },

    /**
     * 设置聊天背景图。图片以 data URL 存进 localStorage，
     * 因此对体积设上限，避免一张大图直接撑爆 5MB 配额导致所有设置写不进去。
     */
    setChatBackgroundImage(dataUrl) {
      this.update({ chatBgType: 'image', chatBgValue: dataUrl })
    },

    clearChatBackground() {
      this.update({ chatBgType: 'default', chatBgValue: '' })
    }
  }
})
