<template>
  <div class="interview">
    <header class="interview__header">
      <div class="interview__title">
        <el-icon :size="20"><Microphone /></el-icon>
        <span>后端 Java 全栈面试</span>
        <el-tag v-if="status.model" size="small" type="info">{{ status.model }}</el-tag>
      </div>
      <div class="interview__meta">
        <span v-if="status.dirExists === false" class="interview__warn">知识库目录不存在</span>
        <span v-else>知识库 {{ status.fileCount ?? 0 }} 个文件 / {{ status.chunkCount ?? 0 }} 个片段</span>
        <span v-if="status.apiKeyConfigured === false" class="interview__warn">API Key 未配置</span>
        <span v-if="proctor.enabled" class="interview__meta-sep">|</span>
        <el-tag v-if="proctor.enabled && sessionId" :type="violationTag" size="small" effect="plain">
          违规 {{ violation.total }}{{ violationLimit ? ` / ${violationLimit}` : '' }}
        </el-tag>
        <el-button
          v-if="proctor.enabled && sessionId && !isFullscreen"
          size="small"
          text
          @click="toggleFullscreen"
        >
          全屏监考
        </el-button>
        <el-button v-else-if="proctor.enabled && sessionId" size="small" text @click="toggleFullscreen">
          退出全屏
        </el-button>
        <el-button size="small" :disabled="!started || finished || streaming" @click="finish">
          结束面试
        </el-button>
        <el-button
          size="small"
          :disabled="streaming && messages.length === 0"
          @click="restart"
        >
          重新开始面试
        </el-button>
      </div>
    </header>

    <div ref="listEl" class="interview__body">
      <!-- 空态：面试由面试官先开场，点击后请求体带空历史 -->
      <div v-if="messages.length === 0" class="interview__empty">
        <el-icon :size="46" color="var(--im-primary)"><Microphone /></el-icon>
        <p class="interview__empty-title">后端 Java 全栈面试</p>
        <p class="interview__empty-desc">
          面试官将围绕后端 Java 全栈知识体系循序渐进地面试：
          先了解项目经历，再深挖 Java/JVM、并发、Spring、MySQL、Redis、分布式与架构设计，
          知识库中有相关内容时优先结合命题，最后给出打分总结。
        </p>
        <p v-if="proctorNotice" class="interview__notice">{{ proctorNotice }}</p>
        <el-button type="primary" :loading="streaming" :disabled="finished" @click="send()">开始面试</el-button>
      </div>

      <div
        v-for="(item, index) in messages"
        :key="index"
        class="interview__row"
        :class="`interview__row--${item.role}`"
      >
        <div v-if="item.role === 'ai'" class="interview__avatar interview__avatar--ai">
          <el-icon :size="18"><Microphone /></el-icon>
        </div>
        <div class="interview__bubble" :class="{ 'interview__bubble--error': item.role === 'error' }">
          <span class="interview__text">{{ item.content }}</span>
          <span v-if="item.role === 'ai' && streaming && index === messages.length - 1" class="interview__cursor">▍</span>
        </div>
        <div v-if="item.role === 'user'" class="interview__avatar interview__avatar--user">
          <el-icon :size="18"><User /></el-icon>
        </div>
      </div>
    </div>

    <footer class="interview__footer">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        resize="none"
        :disabled="streaming || finished"
        :placeholder="finished ? '本次面试已结束，可点「重新开始面试」再来一场' : '输入你的回答，Enter 发送，Shift+Enter 换行'"
        @keydown.enter.exact.prevent="onSendKey"
      />
      <el-button
        v-if="!streaming"
        type="primary"
        :disabled="!draft.trim() || !started || finished"
        @click="onSendKey"
      >
        发送
      </el-button>
      <el-button v-else type="danger" plain @click="stop">停止</el-button>
    </footer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Microphone, User } from '@element-plus/icons-vue'
import {
  endInterviewSession,
  fetchInterviewStatus,
  interviewLeaveBeacon,
  reportInterviewEvents,
  saveInterviewTurn,
  startInterviewSession,
  streamInterviewChat
} from '@/api/ai'

defineOptions({ name: 'Interview' })

/**
 * 页面持有的对话状态就是「会话」本身：每轮把完整历史带过去。
 * 服务端另存一份问答与事件副本用于事后复盘，不反过来参与对话组装。
 * role 有三种：ai（面试官）、user（候选人）、error（本地错误提示，不参与历史）。
 */
const messages = ref([])
const draft = ref('')
const streaming = ref(false)
const status = ref({})
const listEl = ref(null)
let abortController = null

/* ---------- 监考状态 ---------- */
/** 审计会话 ID：null 表示当前没有进行中的面试 */
const sessionId = ref(null)
/** 面试是否已终止（正常结束或被违规强制结束），终止后不再作答也不再接受上报结果 */
const finished = ref(false)
const violation = ref({ total: 0, blur: 0, copy: 0, paste: 0, fullscreenExit: 0 })
const isFullscreen = ref(false)
/** 待上报事件队列：攒一批或到时间窗才发，页面隐藏时强制带走 */
const pendingEvents = ref([])
let flushTimer = null
let blurProbeTimer = null
/** 面试官出完上一题的时刻，用于算本题作答耗时 */
let questionAt = 0
/** 已出到第几题，随事件一起上报，让审计能回答「他是在答第几题时切屏的」 */
let turnNo = 0
/** 上一题的作答文本与耗时，等面试官出完下一题时一起落库（顺序才对得上真实对话） */
let pendingAnswer = null

/** sessionStorage 里标记「有一个没收尾的会话」，刷新回来时据此补记结束原因 */
const LEFTOVER_KEY = 'im-interview-left-session'

/** 面试是否已经开始：未开始时输入框不可直接发（先点「开始面试」让面试官开场） */
const started = computed(() => messages.value.some((item) => item.role !== 'error'))

const proctor = computed(() => status.value.proctor || {})
const violationLimit = computed(() => Number(proctor.value.violationLimit) || 0)

const violationTag = computed(() => {
  const limit = violationLimit.value
  if (limit > 0 && violation.value.total >= limit) {
    return 'danger'
  }
  return violation.value.total > 0 ? 'warning' : 'success'
})

/** 开场前的监考告知：事后说「你怎么知道我切屏」是最难看的，必须先讲清楚 */
const proctorNotice = computed(() => {
  if (!proctor.value.enabled) {
    return ''
  }
  const watched = []
  if (proctor.value.watchVisibilityHidden || proctor.value.watchBlur) {
    watched.push('切屏与窗口失焦')
  }
  if (proctor.value.watchCopy) {
    watched.push('复制')
  }
  if (proctor.value.watchPaste) {
    watched.push('粘贴')
  }
  if (proctor.value.watchContextmenu) {
    watched.push('右键菜单')
  }
  if (proctor.value.watchFullscreenExit) {
    watched.push('退出全屏')
  }
  if (!watched.length) {
    return ''
  }
  const limit = violationLimit.value
  return `本场面试开启监考，将记录并上报：${watched.join('、')}`
    + (limit > 0 ? `；累计违规达 ${limit} 次将被强制结束` : '')
})

onMounted(async () => {
  try {
    status.value = (await fetchInterviewStatus()) || {}
  } catch {
    // 状态条只是辅助信息，拉取失败不打扰用户
    status.value = {}
  }
  await recoverLeftover()
})

onBeforeUnmount(() => {
  abortController?.abort()
  detachProctor()
  flushEvents(true)
})

/**
 * 刷新回来时补记上一场：只有新页面加载时才分得清「刷新」还是「关页」。
 * 关页的情况这里根本不会执行，那条会话由服务端在下次开始时补记为未完成。
 */
async function recoverLeftover() {
  const leftover = sessionStorage.getItem(LEFTOVER_KEY)
  if (!leftover) {
    return
  }
  sessionStorage.removeItem(LEFTOVER_KEY)
  const reloaded = (performance.getEntriesByType('navigation') || [])[0]?.type === 'reload'
  try {
    await endInterviewSession(leftover, reloaded ? 'reload' : 'close')
  } catch {
    // 收尾失败不影响这一场面试
  }
}

/** 组装发送给后端的历史：只保留 ai/user 两种角色，ai 映射回 assistant */
function buildHistory() {
  return messages.value
    .filter((item) => item.role === 'ai' || item.role === 'user')
    .map((item) => ({ role: item.role === 'ai' ? 'assistant' : 'user', content: item.content }))
}

function onSendKey() {
  const text = draft.value.trim()
  // 未开始时不允许直接发：首轮必须走「开始面试」让面试官先开场
  if (!text || streaming.value || !started.value) {
    return
  }
  draft.value = ''
  send(text)
}

/**
 * 发送一轮对话。text 为空表示「开始面试」——历史为空数组，
 * 后端会让面试官直接输出阶段 1 的开场问题。
 */
async function send(text) {
  if (streaming.value || finished.value) {
    return
  }
  if (text) {
    // 作答先攒着：面试官下一题出来时两个一起落库，入库顺序才与真实对话一致
    pendingAnswer = { text, elapsedMs: questionAt ? Date.now() - questionAt : 0 }
    messages.value.push({ role: 'user', content: text })
  } else {
    await ensureSession()
  }
  const history = buildHistory()
  const aiMessage = { role: 'ai', content: '' }
  messages.value.push(aiMessage)
  scrollToBottom()

  streaming.value = true
  abortController = new AbortController()
  try {
    await streamInterviewChat(history, {
      onDelta: (delta) => {
        aiMessage.content += delta
        scrollToBottom()
      },
      signal: abortController.signal
    })
    if (!finished.value && aiMessage.content.trim()) {
      await persistTurn(aiMessage.content.trim())
    }
  } catch (error) {
    if (error?.name === 'AbortError') {
      // 用户点了停止：保留已生成的部分并标注
      aiMessage.content += aiMessage.content ? '\n（已停止）' : '（已停止）'
    } else {
      // 失败时把空的 ai 占位气泡撤掉，换成错误提示条
      const index = messages.value.indexOf(aiMessage)
      if (index >= 0 && !aiMessage.content) {
        messages.value.splice(index, 1)
      }
      messages.value.push({ role: 'error', content: error?.message || 'AI 服务异常，请稍后重试' })
    }
  } finally {
    streaming.value = false
    abortController = null
    // 本题从面试官说完这一刻开始计时，不是从前端发起请求算：
    // 模型流式输出本身要几秒，那部分不该算到候选人头上
    questionAt = Date.now()
    scrollToBottom()
  }
}

function stop() {
  abortController?.abort()
}

async function restart() {
  if (messages.value.length === 0) {
    return
  }
  try {
    await ElMessageBox.confirm('重新开始将清空当前面试对话记录，确定继续？', '重新开始面试', {
      confirmButtonText: '重新开始',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  abortController?.abort()
  streaming.value = false
  messages.value = []
  draft.value = ''
  // 旧会话要真结束掉而不是抹了就当没发生：它没答完，审计上就是「未完成」
  await closeSession('close')
  resetProctorState()
  ElMessage.success('已清空，点击「开始面试」重新进行')
}

/** 主动结束：先让面试官给出总结，再关会话并把结论弹给候选人看 */
async function finish() {
  if (!started.value || finished.value) {
    return
  }
  try {
    await ElMessageBox.confirm('结束后将请面试官给出评分与总结，本场面试不能再继续作答，确定结束？', '结束面试', {
      confirmButtonText: '结束并出总结',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await send('本次面试到此结束，请对我的整体表现给出各维度评分、总分与简要总结。')
  await closeSession('user-end')
}

/**
 * 收尾会话：先把积压事件带走再 end，否则最后几秒的切屏恰好落在空里。
 * 顺序不能倒：end 之后会话已不算进行中，上报会被服务端拒收。
 */
async function closeSession(reason) {
  if (!sessionId.value) {
    return
  }
  finished.value = true
  detachProctor()
  const id = sessionId.value
  sessionStorage.removeItem(LEFTOVER_KEY)
  // 先把积压事件带走再关会话：顺序倒了会被服务端拒收（会话已不算进行中）
  await flushEvents(true)
  sessionId.value = null
  let summary = null
  try {
    summary = await endInterviewSession(id, reason)
  } catch {
    // 结束失败不弹错：面试页已经走完，报错只会多一个没人管的弹窗
  }
  if (reason === 'user-end') {
    ElMessageBox.alert(summarizeText(summary), '面试结束', { confirmButtonText: '知道了' }).catch(() => {})
  }
}

function summarizeText(summary) {
  if (!summary) {
    return '面试已结束。'
  }
  const seconds = Number(summary.durationSeconds) || 0
  const minutes = Math.floor(seconds / 60)
  const detail = `切屏 ${summary.blurCount ?? 0} 次、复制 ${summary.copyCount ?? 0} 次、粘贴 ${summary.pasteCount ?? 0} 次、退出全屏 ${summary.fullscreenExitCount ?? 0} 次`
  return `共 ${summary.turnCount ?? 0} 轮问答，用时 ${minutes} 分 ${seconds % 60} 秒；违规合计 ${summary.violationCount ?? 0} 次（${detail}）。\n完整的问答与事件时间线可在「面试记录」中查看。`
}

/** 违规达上限：服务端已经把这个会话置为强制结束，本地只需停下 */
function forceFinish() {
  finished.value = true
  sessionId.value = null
  sessionStorage.removeItem(LEFTOVER_KEY)
  detachProctor()
  abortController?.abort()
  streaming.value = false
  messages.value.push({ role: 'error', content: '违规次数已达上限，本次面试已被强制结束。' })
  ElMessageBox.alert(`违规累计 ${violation.value.total} 次，已达上限 ${violationLimit.value} 次，本次面试被强制结束。`,
    '面试已终止', { type: 'error', confirmButtonText: '知道了' }).catch(() => {})
}

function resetProctorState() {
  pendingEvents.value = []
  turnNo = 0
  pendingAnswer = null
  questionAt = 0
  finished.value = false
  isFullscreen.value = !!document.fullscreenElement
  violation.value = { total: 0, blur: 0, copy: 0, paste: 0, fullscreenExit: 0 }
}

/* ==================== 监考：检测与上报 ==================== */

/**
 * 建审计会话。
 *
 * 失败不拦面试：监考是附加能力，不能因为它把主流程卡住。
 * 建不成会话时后续一切 pushEvent 都会因为 sessionId 为空而自然空转。
 */
async function ensureSession() {
  if (sessionId.value || finished.value) {
    return
  }
  resetProctorState()
  try {
    const result = await startInterviewSession({
      title: '后端 Java 全栈面试',
      screen: `${window.screen.width}x${window.screen.height}`
    })
    sessionId.value = result?.sessionId ?? null
    if (sessionId.value) {
      attachProctor()
    }
  } catch {
    ElMessage.warning('监考会话创建失败，本场面试不记录切屏等事件')
  }
}

/** 把「上一答 + 本新题」一次性落库，失败只记日志：审计不能打断答题 */
async function persistTurn(question) {
  if (!sessionId.value) {
    return
  }
  const answer = pendingAnswer
  pendingAnswer = null
  turnNo += 1
  try {
    await saveInterviewTurn({
      sessionId: sessionId.value,
      // 带上题号：服务端拿它做幂等键，重试才不会多插一行（作答行归上一题）
      turnNo,
      question,
      answer: answer?.text || '',
      elapsedMs: answer?.elapsedMs || 0
    })
  } catch {
    // 落库失败只影响事后复盘，本题仍在屏幕上，候选人可以接着答
  }
}

/** 本地墙上时间：后端收的是 LocalDateTime，换成 UTC 会把时间对不上 */
function localDateTime() {
  const d = new Date()
  const p = (n, w = 2) => String(n).padStart(w, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
    + `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`
}

/**
 * 记一个事件：只报「发生了什么」，算不算违规由服务端按配置判定。
 * 前端不携带 violation 标志：否则改一下控制台就能把粘贴上报成非违规。
 */
function pushEvent(eventType, detail) {
  if (!proctor.value.enabled || !sessionId.value || finished.value) {
    return
  }
  pendingEvents.value.push({ eventType, eventTime: localDateTime(), turnNo, detail: detail || null })
  if (!flushTimer) {
    flushTimer = setTimeout(() => flushEvents(), 3000)
  }
}

async function flushEvents(silent = false) {
  if (flushTimer) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  if (!sessionId.value || !pendingEvents.value.length) {
    return
  }
  const batch = pendingEvents.value.splice(0, pendingEvents.value.length)
  try {
    applyProctorState(await reportInterviewEvents(sessionId.value, batch))
  } catch (error) {
    // 失败的回放：丢在网络抖动里的那几条，正是让违规计数偏低的原因
    pendingEvents.value.unshift(...batch)
    if (pendingEvents.value.length > 200) {
      pendingEvents.value.splice(0, pendingEvents.value.length - 200)
    }
    if (!silent && !flushTimer) {
      flushTimer = setTimeout(() => flushEvents(true), 8000)
    }
  }
}

function applyProctorState(state) {
  if (!state) {
    return
  }
  violation.value = {
    total: state.violationCount ?? 0,
    blur: state.blurCount ?? 0,
    copy: state.copyCount ?? 0,
    paste: state.pasteCount ?? 0,
    fullscreenExit: state.fullscreenExitCount ?? 0
  }
  if (state.ended) {
    forceFinish()
  } else if (state.reachedLimit) {
    // 阈値到了但服务端没强制结束（enforceLimit=false）时，至少要让候选人知道在被记
    ElMessage.warning(`已记录 ${state.violationCount} 次违规`)
  }
}

function onVisibilityChange() {
  if (document.visibilityState !== 'hidden') {
    return
  }
  pushEvent('visibility-hidden', '切到其他标签页或最小化')
  // 切走的这一刻是最后一批记录的机会：pagehide 不一定来，visibilitychange 一定来
  flushEvents(true)
}

function onWindowBlur() {
  // 切标签页会连着给 blur 与 visibility-hidden，只留后者会重复计一次；
  // 延迟判定：250ms 后仍未聚焦且页面还可见，才是切到别的程序/窗口
  clearTimeout(blurProbeTimer)
  blurProbeTimer = setTimeout(() => {
    if (!document.hasFocus() && document.visibilityState === 'visible') {
      pushEvent('blur', '窗口失焦')
    }
  }, 250)
}

function clipboardSummary(event, action) {
  const text = event.clipboardData?.getData('text') || ''
  return `${action} ${text.length} 字符`
}

function onCopy(event) {
  pushEvent('copy', clipboardSummary(event, '复制'))
}

function onCut(event) {
  pushEvent('cut', clipboardSummary(event, '剪切'))
}

function onPaste(event) {
  pushEvent('paste', clipboardSummary(event, '粘贴'))
}

function onContextMenu() {
  // 只记录不拦截：右键也是合法的系统菜单入口，禁掉反而干扰正常输入
  pushEvent('contextmenu', '打开右键菜单')
}

function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement
  if (!isFullscreen.value) {
    pushEvent('fullscreen-exit', '退出全屏监考')
  }
}

function onPageHide() {
  if (!sessionId.value || finished.value) {
    return
  }
  sessionStorage.setItem(LEFTOVER_KEY, String(sessionId.value))
  // 只带走事件不下结论：刷新还是关页要等下次加载才分得出
  interviewLeaveBeacon(sessionId.value, pendingEvents.value.splice(0, pendingEvents.value.length))
}

/** 按配置只挂需要的那几个：不监控的项连监都省，也不会弹无意义的警告 */
function attachProctor() {
  if (!proctor.value.enabled) {
    return
  }
  if (proctor.value.watchVisibilityHidden) {
    document.addEventListener('visibilitychange', onVisibilityChange)
  }
  if (proctor.value.watchBlur) {
    window.addEventListener('blur', onWindowBlur)
  }
  if (proctor.value.watchCopy) {
    document.addEventListener('copy', onCopy)
  }
  if (proctor.value.watchCut) {
    document.addEventListener('cut', onCut)
  }
  if (proctor.value.watchPaste) {
    document.addEventListener('paste', onPaste)
  }
  if (proctor.value.watchContextmenu) {
    document.addEventListener('contextmenu', onContextMenu)
  }
  if (proctor.value.watchFullscreenExit) {
    document.addEventListener('fullscreenchange', onFullscreenChange)
  }
  // 卸载补发与监考开关无关：只要可能有积压事件就得挂
  window.addEventListener('pagehide', onPageHide)
}

function detachProctor() {
  if (flushTimer) {
    clearTimeout(flushTimer)
    flushTimer = null
  }
  if (blurProbeTimer) {
    clearTimeout(blurProbeTimer)
    blurProbeTimer = null
  }
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('blur', onWindowBlur)
  document.removeEventListener('copy', onCopy)
  document.removeEventListener('cut', onCut)
  document.removeEventListener('paste', onPaste)
  document.removeEventListener('contextmenu', onContextMenu)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  window.removeEventListener('pagehide', onPageHide)
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await document.documentElement.requestFullscreen()
      ElMessage.info('已进入全屏监考，中途退出会被记录')
    }
  } catch {
    ElMessage.warning('浏览器拒绝了全屏请求，切屏仍会被记录')
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listEl.value) {
      listEl.value.scrollTop = listEl.value.scrollHeight
    }
  })
}
</script>

<style scoped>
.interview {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--im-bg);
}

.interview__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex: none;
  padding: 12px 20px;
  background: #ffffff;
  border-bottom: 1px solid var(--im-border, #e4e7ed);
}

.interview__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.interview__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #909399;
}

.interview__warn {
  color: #e6a23c;
}

/* 头部两组信息之间的分隔，避免「知识库 …」与「违规 …」黏成一行 */
.interview__meta-sep {
  color: var(--im-border, #dcdfe6);
}

.interview__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}

.interview__empty {
  max-width: 460px;
  margin: 8vh auto 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}

.interview__empty-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.interview__empty-desc {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.7;
  color: #909399;
}

/* 监考告知：开面前就把「记什么、记到多少次会结束」写在脸上 */
.interview__notice {
  max-width: 420px;
  margin: 0;
  padding: 8px 12px;
  border-radius: 6px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  font-size: 12px;
  line-height: 1.6;
  color: #b88230;
  text-align: left;
}

.interview__row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 16px;
}

.interview__row--user {
  justify-content: flex-end;
}

.interview__row--error {
  justify-content: center;
}

.interview__avatar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 6px;
  color: #ffffff;
}

.interview__avatar--ai {
  background: var(--im-primary, #409eff);
}

.interview__avatar--user {
  background: #909399;
}

.interview__bubble {
  max-width: min(680px, 78%);
  padding: 10px 14px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid var(--im-border, #e4e7ed);
  font-size: 14px;
  line-height: 1.7;
  color: #303133;
}

.interview__row--user .interview__bubble {
  background: var(--im-primary, #409eff);
  border-color: transparent;
  color: #ffffff;
}

.interview__bubble--error {
  background: #fef0f0;
  border-color: #fde2e2;
  color: #f56c6c;
  font-size: 13px;
}

/* AI 输出保留换行与缩进；不做 Markdown 渲染，纯文本展示即可读 */
.interview__text {
  white-space: pre-wrap;
  word-break: break-word;
}

.interview__cursor {
  animation: interview-blink 1s step-start infinite;
  color: var(--im-primary, #409eff);
}

@keyframes interview-blink {
  50% {
    opacity: 0;
  }
}

.interview__footer {
  flex: none;
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 12px 20px;
  background: #ffffff;
  border-top: 1px solid var(--im-border, #e4e7ed);
}

.interview__footer .el-textarea {
  flex: 1;
}

/* 窄屏：气泡放宽、头部信息换行 */
@media (max-width: 768px) {
  .interview__header {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }

  .interview__bubble {
    max-width: 86%;
  }
}
</style>
