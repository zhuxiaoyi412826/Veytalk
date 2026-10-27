import { defineStore } from 'pinia'
import { ElNotification } from 'element-plus'
import { fetchActiveAnnouncements } from '@/api/announcement'
import { useAuthStore } from '@/stores/auth'
import { asId } from '@/utils/id'
import { notifySystem } from '@/utils/notify'

/**
 * 全服公告在用户端的唯一持有者。
 *
 * 为什么要从 `utils/announce.js` 升成一个 store：公告此前只有「一条 12 秒的 ElNotification」
 * 这一个可见面，而它在到达的那一刻就把 ID 写进了本地已读集合。管理员在后台发完公告、
 * 切回聊天页时那 12 秒早就过去了 —— 这条公告在这台设备上再也不会出现第二次。
 * 「发布了但前端没收到」的真相不是没送达，而是<b>送达即销声</b>。
 *
 * 于是本批改了两件事：
 * - 「已读」改叫<b>确认（ack）</b>：只有用户在公告栏或公告列表里点过「知道了」才落盘，
 *   弹过通知不再等于用户看过；
 * - 未确认的公告常驻在聊天界面顶部的公告栏（`components/AnnouncementBar.vue`），
 *   通知只是顺带的一下，错过通知不再等于错过公告。
 *
 * 已知边界，都是刻意的取舍：
 * - 没有服务端已读回执表，确认只记在客户端本地 —— <b>换设备会重新看到</b>，清过浏览器数据同理；
 * - 后台撤回一条公告收不回已经弹出去的通知，但公告栏会跟着补拉消失（撤回后接口不再返回它）；
 * - 通知那一档仍不受「关闭提醒」开关约束：那个开关管的是消息音与系统通知，
 *   全站性公告若可退订，会出现「关着提醒的人永远看不到停机公告」这种更糟的口径。
 */

/** 确认集合按用户分键：换账号登录不该继承上一个人的确认记录，也因此退出登录时不必清它 */
const ACK_PREFIX = 'im.announce.acked.'

/**
 * 上一版「送达即已读」留下的键，本批起废弃。
 *
 * <b>故意不迁移</b>：那些 ID 是「弹过一次通知」的机器记录，没有任何一次是用户确认来的，
 * 把它们搬进新键等于把这条 bug 的效果永久保留下来（当前正好被吞掉的那条公告会永远不再出现）。
 * 读确认集合时顺手删掉旧键，用户会重新看到最近生效的公告 —— 这正是想要的行为。
 */
const LEGACY_READ_PREFIX = 'im.announce.read.'

/**
 * 本地最多记多少个公告 ID。
 *
 * 只增不减的集合会一直长大，而一次补拉只回最新 5 条：留 200 条足够覆盖
 * 「停机几天后回来」，再多就是没有必要的历史包袱。超出时按插入顺序剪掉最老的。
 */
const ACK_MAX = 200

/** ElNotification 的四种样式；服务端的 tone 不在表里就按 info 兜底，与 dispatch 同一条规矩 */
const NOTIFY_TONES = ['success', 'warning', 'info', 'error']

/** 通知与公告栏里只显示这么长一段全文，剩下的点开看 */
const BRIEF_MAX = 120

/**
 * 通知停留时间：比消息通知长一档、又不至于要手动关。
 *
 * 不用 {@code duration: 0}（常驻）：一次补拉最多 5 条，五条关不掉的通知叠在屏幕上，
 * 用户的下一步动作是把它们全部叉掉而不是读内容。常驻的那一份交给公告栏，它不占屏幕角落。
 */
const NOTIFY_MS = 12000

/**
 * userId 还没到位时补拉的重试节奏。
 *
 * WS 的 open 事件可能早于 `/api/user/profile` 返回（实测两个请求相差 50ms 级并发），
 * 上一版这时直接 return，整趟补拉静默丢掉且<b>永不重试</b>——用户这一整场会话都拿不到公告。
 * 延后重试比「指望 bootstrap 那一路也拉一次」稳：那两路本就是各管各的触发点。
 */
const PULL_RETRY_MS = 2000
const PULL_MAX_RETRY = 5

/** 窗口重新获得焦点时补拉的节流窗口：公告是低频事，每次切回标签页都打一次接口没必要 */
const FOCUS_PULL_MS = 60000

/**
 * 已确认集合与「本次会话弹过通知」的集合都不放进 state：
 * 它们只参与判重，不参与渲染，做成响应式只会让 Pinia 每次比对都拷一遍数组。
 * 换账号时在 ensureUser 里整体重置，因此不需要按用户维度建成 Map。
 */
const acked = new Set()
const sessionToasted = new Set()
let ackedUserId = ''
let pullTimer = null
let pullRetries = 0
let lastPullAt = 0

export const useAnnounceStore = defineStore('announce', {
  state: () => ({
    /** 未确认的公告，新的在前；公告栏与公告列表都渲染这一个数组 */
    items: [],
    /** 公告列表弹窗（components/AnnouncementDialog.vue）是否可见 */
    dialogVisible: false,
    /** 补拉进行中标记，避免多个触发点各打一次同样的请求 */
    pulling: false
  }),

  getters: {
    pendingCount: (state) => state.items.length,
    /** 最新一条：公告栏只放一条，其余靠「查看 N 条」进列表 */
    latest: (state) => state.items[0] || null
  },

  actions: {
    /**
     * 让本地确认集合对准当前账号。
     * @returns {boolean} 拿不到 userId（资料还没回来）时返回 false，调用方据此放弃本次动作
     */
    ensureUser() {
      const userId = asId(useAuthStore().userId)
      if (!userId) {
        return false
      }
      if (ackedUserId === userId) {
        return true
      }
      // 换账号（含登录后第一次）：内存与待确认列表整体重来，
      // 否则上一个人没确认完的公告会挂在这个人的公告栏上，点「知道了」还会写错键
      ackedUserId = userId
      acked.clear()
      sessionToasted.clear()
      this.items = []
      this.dialogVisible = false
      loadAcked(userId)
      return true
    },

    /**
     * 收一批公告进来：过滤已确认与已在列表里的，剩下的挂到待确认列表并弹通知。
     *
     * 入参同时接受两种形状：补拉回来的 {@code AnnouncementVO}（正文字段是 content）
     * 与 WS 那一帧（正文字段是 message）。不在这里做字段归一的话，调用方就得知道
     * 自己走的是哪条路——而 dispatch 里那两条路挨得很近，迟早有人传错。
     */
    ingest(list) {
      if (!Array.isArray(list) || list.length === 0) {
        return
      }
      if (!this.ensureUser()) {
        return
      }
      const known = new Set(this.items.map((item) => item.id))
      const fresh = []
      list.forEach((raw) => {
        const item = normalize(raw)
        // 没有 ID 的行不去记：记了就是一个空键，之后所有同类行都会被当成已确认
        if (item.id === '' || acked.has(item.id) || known.has(item.id)) {
          return
        }
        known.add(item.id)
        fresh.push(item)
      })
      if (fresh.length === 0) {
        return
      }
      // 补拉返回的是发布时刻倒序，直接整段插到前面：最新的仍然在最前
      this.items = [...fresh, ...this.items]
      fresh.forEach((item) => {
        // 通知只在本会话内记一次：公告在用户确认之前会反复出现在补拉结果里，
        // 每拉一次都响一声是骚扰；而它没被确认，所以仍留在公告栏上
        if (sessionToasted.has(item.id)) {
          return
        }
        sessionToasted.add(item.id)
        popOne(item, () => this.openDialog())
      })
    },

    /**
     * 补拉一次当前生效的公告。
     *
     * 三个触发点都走这里：WS 连接建立（首连与重连）、首屏 bootstrap 之后、窗口重新获得焦点。
     * 多打几次没有副作用——请求带 `silent`，判重在 `ingest`，并发由 `pulling` 拦住。
     */
    async pull() {
      if (!useAuthStore().userId) {
        schedulePull()
        return
      }
      if (this.pulling || !this.ensureUser()) {
        return
      }
      this.pulling = true
      lastPullAt = Date.now()
      pullRetries = 0
      try {
        this.ingest(await fetchActiveAnnouncements())
      } catch {
        // 接口本身已是 silent：这一趟没补到就算了，下一个触发点还会再拉
      } finally {
        this.pulling = false
      }
    },

    /**
     * 用户确认某一条：只有这里会落盘。
     *
     * 由公告栏的「知道了」、列表里每条的「知道了」与「全部知道了」调用。
     * @param id 公告 ID，字符串或数字都行（内部过 asId）
     */
    ack(id) {
      const key = asId(id)
      if (key === '' || !this.ensureUser()) {
        return
      }
      if (!acked.has(key)) {
        acked.add(key)
        persistAcked()
      }
      this.items = this.items.filter((item) => item.id !== key)
      if (this.items.length === 0) {
        this.dialogVisible = false
      }
    },

    /** 列表底部的「全部知道了」：一次把当前待确认的全部落盘 */
    ackAll() {
      if (!this.ensureUser() || this.items.length === 0) {
        return
      }
      this.items.forEach((item) => acked.add(item.id))
      this.items = []
      this.dialogVisible = false
      persistAcked()
    },

    openDialog() {
      if (this.items.length === 0) {
        return
      }
      this.dialogVisible = true
    },

    closeDialog() {
      this.dialogVisible = false
    },

    /**
     * 窗口重新回到前台时补一次。
     *
     * 「管理员发公告时这个标签页在后台」是最常见的一种错过：浏览器会把后台页的定时器压到
     * 分钟级甚至冻结，重连也不一定发生。回到前台补一下，最多 FOCUS_PULL_MS 一次。
     */
    syncOnVisible() {
      if (typeof document !== 'undefined' && document.visibilityState !== 'visible') {
        return
      }
      if (Date.now() - lastPullAt < FOCUS_PULL_MS) {
        return
      }
      this.pull()
    },

    /** 退出登录时被 stores/index.js 调用：清掉待确认列表，避免换账号短暂看到上一个人的公告栏 */
    reset() {
      if (pullTimer) {
        clearTimeout(pullTimer)
        pullTimer = null
      }
      pullRetries = 0
      lastPullAt = 0
      ackedUserId = ''
      acked.clear()
      sessionToasted.clear()
      this.items = []
      this.dialogVisible = false
      this.pulling = false
    }
  }
})

/** userId 未就绪时的延后重试：只留一个挂起的定时器，重试次数有上限 */
function schedulePull() {
  if (pullTimer || pullRetries >= PULL_MAX_RETRY) {
    return
  }
  pullRetries += 1
  pullTimer = setTimeout(() => {
    pullTimer = null
    useAnnounceStore().pull()
  }, PULL_RETRY_MS)
}

/** 两种来源的正文字段名不同，在这里一次抹平 */
function normalize(raw) {
  const content = String((raw && (raw.message ?? raw.content)) || '')
  const oneLine = content.replace(/\s+/g, ' ').trim()
  return {
    id: asId(raw && raw.id),
    title: (raw && raw.title) || '系统公告',
    content,
    // 长正文交给列表弹窗：让公告栏自己撑高会把首屏挤掉半屏，
    // 而它的作用只是「让人知道有公告」，全文本来就有点开这条路
    brief: oneLine.length <= BRIEF_MAX ? oneLine : `${oneLine.slice(0, BRIEF_MAX)}…`,
    tone: NOTIFY_TONES.includes(raw && raw.tone) ? raw.tone : 'info',
    publishTime: (raw && (raw.publishTime || raw.createTime)) || ''
  }
}

function popOne(item, openDialog) {
  ElNotification({
    title: item.title,
    message: item.brief,
    type: item.tone,
    duration: NOTIFY_MS,
    onClick: openDialog
  })
  // 页面在后台时 ElNotification 根本看不见，补一条系统通知；tag 按公告 ID 分，
  // 免得两条不同公告在同一系统通知位上互相覆盖
  notifySystem(item.title, item.brief, { tag: `announce-${item.id}`, onOpen: openDialog })
}

function loadAcked(userId) {
  try {
    localStorage.removeItem(LEGACY_READ_PREFIX + userId)
  } catch {
    // 无痕模式下 removeItem 也可能抛，旧键留着不影响新键的读写
  }
  try {
    const raw = localStorage.getItem(ACK_PREFIX + userId)
    const parsed = raw ? JSON.parse(raw) : null
    // 坏数据（手改过的、半截的 JSON）按「本机没确认过任何公告」处理：
    // 宁可重弹一条，也不能因为读不到确认集合就把公告整条丢掉
    ;(Array.isArray(parsed) ? parsed : []).forEach((id) => {
      const key = asId(id)
      if (key !== '') {
        acked.add(key)
      }
    })
  } catch {
    // 同上
  }
}

/**
 * 落盘确认集合，顺手剪到 {@link ACK_MAX} 条。
 *
 * 剪枝会同步改内存里的那个 Set：内存与存储留同一份口径，
 * 否则下次启动只剩 200 条而本次会话还在按更大的集合判重。
 */
function persistAcked() {
  const ids = Array.from(acked)
  if (ids.length === 0) {
    return
  }
  const kept = ids.slice(-ACK_MAX)
  if (kept.length !== ids.length) {
    acked.clear()
    kept.forEach((id) => acked.add(id))
  }
  try {
    localStorage.setItem(ACK_PREFIX + ackedUserId, JSON.stringify(kept))
  } catch {
    // 配额满 / 无痕模式：这次记不住，下次进来同一条会再出现一次，不影响任何功能
  }
}
