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
        <el-button type="primary" :loading="streaming" @click="send()">开始面试</el-button>
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
        :disabled="streaming"
        placeholder="输入你的回答，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="onSendKey"
      />
      <el-button
        v-if="!streaming"
        type="primary"
        :disabled="!draft.trim() || !started"
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
import { fetchInterviewStatus, streamInterviewChat } from '@/api/ai'

defineOptions({ name: 'Interview' })

/**
 * 页面持有的对话状态就是「会话」本身：后端无状态，每轮把完整历史带过去。
 * role 有三种：ai（面试官）、user（候选人）、error（本地错误提示，不参与历史）。
 */
const messages = ref([])
const draft = ref('')
const streaming = ref(false)
const status = ref({})
const listEl = ref(null)
let abortController = null

/** 面试是否已经开始：未开始时输入框不可直接发（先点「开始面试」让面试官开场） */
const started = computed(() => messages.value.some((item) => item.role !== 'error'))

onMounted(async () => {
  try {
    status.value = (await fetchInterviewStatus()) || {}
  } catch {
    // 状态条只是辅助信息，拉取失败不打扰用户
    status.value = {}
  }
})

onBeforeUnmount(() => {
  abortController?.abort()
})

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
  if (streaming.value) {
    return
  }
  if (text) {
    messages.value.push({ role: 'user', content: text })
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
  ElMessage.success('已清空，点击「开始面试」重新进行')
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
