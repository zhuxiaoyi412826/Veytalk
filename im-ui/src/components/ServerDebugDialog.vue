<template>
  <el-dialog
    v-model="visible"
    title="后端地址（开发调试）"
    width="min(560px, calc(100vw - 32px))"
    append-to-body
    :close-on-click-modal="false"
    @open="onOpen"
  >
    <!-- 当前生效值 + 来源：只显地址不显来源是不够的——环境变量能盖住内置默认，
         用户改了界面却发现值没变，不知道是被谁压着的 -->
    <div class="dbg__row">
      <span class="dbg__k">当前生效</span>
      <el-tag size="small" :type="sourceTagType(info.source)">{{ sourceLabel(info.source) }}</el-tag>
      <code class="dbg__v">{{ info.baseUrl || '未设置' }}</code>
    </div>

    <p class="dbg__note">
      这个地址决定所有 HTTP 与 WebSocket 请求发往哪里。改完<b>不用重启程序</b>：保存后页面自动重载即生效。
      Web 浏览器部署用不到它（同源，由 Nginx / vite 代理转发）。
    </p>

    <el-form class="dbg__form" @submit.prevent>
      <el-form-item label="后端地址">
        <el-input
          v-model.trim="draft"
          placeholder="例如 http://192.168.1.20:8080，也可只填 192.168.1.20:8080"
          clearable
          :disabled="busy"
          @keyup.enter="test"
        />
      </el-form-item>
    </el-form>

    <!-- 探测结论：级别不同下一步动作完全不同，所以标题、正文、配色一起分档，
         不能统一成一句「网络错误」——那正是这个入口要消灭的东西 -->
    <div v-if="probe" class="dbg__probe" :class="`dbg__probe--${probe.level}`">
      <div class="dbg__probe-title">
        <el-icon><component :is="probeIcon(probe.level)" /></el-icon>
        <span>{{ probe.title }}</span>
        <span v-if="elapsedMs" class="dbg__probe-ms">{{ elapsedMs }} ms</span>
      </div>
      <p class="dbg__probe-detail">{{ probe.detail }}</p>
    </div>
    <div v-else-if="lastError" class="dbg__probe dbg__probe--fail">
      <div class="dbg__probe-title"><el-icon><CircleCloseFilled /></el-icon><span>操作失败</span></div>
      <p class="dbg__probe-detail">{{ lastError }}</p>
    </div>

    <div v-if="info.configPath" class="dbg__path">
      配置文件：<code>{{ info.configPath }}</code>
      <el-link type="primary" :underline="false" :icon="FolderOpened" @click="reveal">打开所在目录</el-link>
    </div>

    <template #footer>
      <div class="dbg__footer">
        <el-button v-if="info.hasConfig" text :icon="RefreshLeft" :disabled="busy" @click="resetDefault">
          恢复内置默认
        </el-button>
        <span class="dbg__spacer" />
        <el-button :loading="probing" :disabled="busy || !draft" @click="test">测试连接</el-button>
        <el-button type="primary" :loading="saving" :disabled="busy || !draft" @click="save">保存并重载</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheckFilled, CircleCloseFilled, FolderOpened, RefreshLeft, WarningFilled } from '@element-plus/icons-vue'
import { PROBE_LEVEL, classifyProbe, probeUrl, sourceLabel, sourceTagType } from '@/utils/servercfg'

/**
 * 后端地址调试弹窗（仅桌面端）。
 *
 * 为什么放在登录页也挂一个入口：连不上后端的时候，登录页是唯一能打开的界面，
 * 所以改地址的能力必须出现在「还没有任何登录态」的地方，而且要完全不依赖网络请求
 * ——读写都走主进程 IPC（见 electron/server-config.js），后端瘫了也照样能改。
 *
 * 地址校验只有一份，在主进程。前端要点「测试连接」前先让它规整一次（bridge.normalize），
 * 拿同一个口径去探，避免出现「界面说合法、保存却被拒」这种两边规则漂移。
 *
 * 探测走渲染进程 fetch：要验的就是真实请求将要经过的那条网络路径
 * （含 webSecurity:false 的跨源设定与证书信任），换主进程去探等于换了条路，通了不算数。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const bridge = () => (typeof window !== 'undefined' && window.__IM_NATIVE__ && window.__IM_NATIVE__.server) || null

const info = ref({ source: '', baseUrl: '', configPath: '', hasConfig: false })
const draft = ref('')
const probe = ref(null)
const lastError = ref('')
const probing = ref(false)
const saving = ref(false)
const elapsedMs = ref(0)

const busy = computed(() => probing.value || saving.value)

function probeIcon(level) {
  if (level === PROBE_LEVEL.OK) return CircleCheckFilled
  if (level === PROBE_LEVEL.SOFT) return WarningFilled
  return CircleCloseFilled
}

async function onOpen() {
  probe.value = null
  lastError.value = ''
  elapsedMs.value = 0
  const api = bridge()
  if (!api) {
    lastError.value = '当前环境拿不到主进程的配置通道（这个入口只在桌面版可用）'
    return
  }
  try {
    const data = await api.get()
    info.value = data || info.value
    draft.value = data.baseUrl || ''
  } catch (e) {
    lastError.value = String((e && e.message) || e)
  }
}

/** 探测一次 GET /api/auth/me：它带 @SaCheckLogin，未登录也回 HTTP 200 + code 1002，正好证明「这是我们的后端」 */
async function test() {
  const api = bridge()
  if (!api || busy.value) return
  probing.value = true
  probe.value = null
  lastError.value = ''
  const startedAt = Date.now()
  try {
    const { baseUrl } = await api.normalize(draft.value)
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 6000)
    let input
    try {
      const res = await fetch(probeUrl(baseUrl), { signal: controller.signal, cache: 'no-store' })
      let body = null
      try {
        body = await res.json()
      } catch {
        body = null // 不是 JSON 就按「不像本后端」处理，让 classifyProbe 说清楚
      }
      input = { status: res.status, body }
    } catch (e) {
      const aborted = e && (e.name === 'AbortError' || String(e.message || '').includes('abort'))
      input = aborted ? { timedOut: true } : { threw: true, message: String((e && (e.message || e.name)) || e) }
    } finally {
      clearTimeout(timer)
    }
    elapsedMs.value = Date.now() - startedAt
    probe.value = classifyProbe(input)
  } catch (e) {
    elapsedMs.value = 0
    lastError.value = String((e && e.message) || e)
  } finally {
    probing.value = false
  }
}

async function save() {
  const api = bridge()
  if (!api || busy.value) return
  saving.value = true
  try {
    const data = await api.set(draft.value)
    ElMessage.success(`后端地址已保存为 ${data.baseUrl}，正在重载页面…`)
    info.value = { ...info.value, baseUrl: data.baseUrl, source: 'file', hasConfig: true }
    // 重载是必须的：axios 的 baseURL 在模块加载时就算定了（api/request.js），
    // 不重载会出现「地址存了、请求还往老地方发」的诡异状态
    setTimeout(() => window.location.reload(), 600)
  } catch (e) {
    lastError.value = String((e && e.message) || e)
  } finally {
    saving.value = false
  }
}

async function resetDefault() {
  const api = bridge()
  if (!api || busy.value) return
  try {
    await ElMessageBox.confirm(
      '将删除配置文件里的地址，回落到环境变量或安装包内置默认（http://localhost:8080）。确认？',
      '恢复内置默认',
      { type: 'warning', confirmButtonText: '恢复并重载', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  saving.value = true
  try {
    const data = await api.reset()
    ElMessage.success(`已恢复为 ${data.baseUrl}（来源：${sourceLabel(data.source)}），正在重载页面…`)
    setTimeout(() => window.location.reload(), 600)
  } catch (e) {
    lastError.value = String((e && e.message) || e)
    saving.value = false
  }
}

async function reveal() {
  const api = bridge()
  if (!api) return
  try {
    await api.reveal()
  } catch (e) {
    lastError.value = String((e && e.message) || e)
  }
}
</script>

<style scoped>
.dbg__row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dbg__k {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.dbg__v {
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  word-break: break-all;
}

.dbg__note {
  margin: 10px 0 14px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.dbg__probe {
  margin-top: 4px;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
}

.dbg__probe--ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.dbg__probe--soft {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

.dbg__probe--fail {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.dbg__probe-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.dbg__probe-ms {
  margin-left: auto;
  font-weight: 400;
  font-size: 12px;
  opacity: 0.8;
}

.dbg__probe-detail {
  margin: 6px 0 0;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.dbg__path {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dbg__path code {
  font-family: Consolas, Menlo, monospace;
  word-break: break-all;
}

.dbg__footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dbg__spacer {
  flex: 1;
}
</style>
