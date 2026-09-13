<template>
  <el-dialog
    v-model="visible"
    :title="fileName"
    width="70vw"
    top="5vh"
    :close-on-click-modal="true"
    destroy-on-close
    class="file-viewer"
  >
    <div v-if="loading" class="file-viewer__loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在加载文件内容…</span>
    </div>

    <div v-else-if="error" class="file-viewer__error">
      <el-icon :size="24"><WarningFilled /></el-icon>
      <span>{{ error }}</span>
    </div>

    <div v-else class="file-viewer__body im-scroll">
      <pre class="file-viewer__pre">{{ content }}</pre>
    </div>

    <template #footer>
      <div class="file-viewer__footer">
        <span v-if="!loading && !error" class="file-viewer__info">
          {{ lineCount }} 行 · {{ sizeLabel }}
        </span>
        <el-button @click="visible = false">关闭</el-button>
        <el-button :disabled="loading || !!error" @click="onCopy">
          {{ copied ? '已复制' : '复制' }}
        </el-button>
        <el-button type="primary" :disabled="loading || !!error" @click="onSave">另存为…</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 文本文件预览弹窗。
 *
 * 点击可预览的文本文件（json / xml / yml / py / ps1 / md / txt 等）时打开，
 * 取文件内容以等宽字体原样展示。不提供语法高亮——这些格式用 pre 原样展示就有可读性，
 * 引入 highlight.js 之类的库增加的体积与收益不成比例。
 *
 * 「另存为」通过 <a download> 触发浏览器原生保存对话框，
 * 用户可以在对话框里自由选择保存路径与文件名。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { fetchBlob } from '@/api/request'
import { downloadFile } from '@/utils/media'

const props = defineProps({
  fileUrl: { type: String, default: '' },
  fileName: { type: String, default: '文件预览' }
})

const visible = defineModel('visible', { type: Boolean, default: false })

const loading = ref(false)
const error = ref('')
const content = ref('')
const copied = ref(false)

const lineCount = computed(() => {
  if (!content.value) {
    return 0
  }
  return content.value.split('\n').length
})

const sizeLabel = computed(() => {
  const bytes = new Blob([content.value]).size
  if (bytes < 1024) {
    return bytes + ' B'
  }
  if (bytes < 1024 * 1024) {
    return (bytes / 1024).toFixed(1) + ' KB'
  }
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
})

/**
 * 弹窗打开时取文件内容。
 *
 * 用 fetchBlob 取二进制再转文本，而不是直接 http.get 取字符串：
 * 受控下载地址返回的 Content-Type 可能是 application/octet-stream，
 * axios 的 text 响应类型在遇到非文本 Content-Type 时行为不一致，
 * 走 blob 再 text() 解码最可靠。
 */
watch(visible, async (show) => {
  if (!show || !props.fileUrl) {
    return
  }
  loading.value = true
  error.value = ''
  content.value = ''
  copied.value = false
  try {
    const blob = await fetchBlob(props.fileUrl, { baseURL: '' })
    content.value = await blob.text()
  } catch (e) {
    error.value = e?.message || '文件内容获取失败'
  } finally {
    loading.value = false
  }
})

async function onCopy() {
  try {
    await navigator.clipboard.writeText(content.value)
    copied.value = true
    ElMessage.success('已复制到剪贴板')
    setTimeout(() => { copied.value = false }, 2000)
  } catch {
    const area = document.createElement('textarea')
    area.value = content.value
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(area)
    if (ok) {
      copied.value = true
      ElMessage.success('已复制到剪贴板')
      setTimeout(() => { copied.value = false }, 2000)
    } else {
      ElMessage.warning('自动复制失败，请手动选择文本')
    }
  }
}

/**
 * 另存为。
 *
 * 用 downloadFile 触发 <a download>，浏览器会弹出原生保存对话框，
 * 用户可以在对话框里自由选择保存路径和文件名。
 * 这是 Web 应用唯一能做到的方式——浏览器安全模型不允许网页直接指定磁盘路径。
 */
async function onSave() {
  try {
    await downloadFile(props.fileUrl, props.fileName)
  } catch {
    // downloadFile 内部已弹过错误提示
  }
}
</script>

<style scoped>
.file-viewer__loading,
.file-viewer__error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 200px;
  font-size: 14px;
  color: var(--im-text-secondary);
}

.file-viewer__error {
  color: #f56c6c;
}

.file-viewer__body {
  max-height: 70vh;
  overflow: auto;
  background: #f8f8f8;
  border: 1px solid var(--im-border);
  border-radius: 6px;
}

.file-viewer__pre {
  margin: 0;
  padding: 16px;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
  word-break: normal;
  color: var(--im-text);
  tab-size: 4;
}

.file-viewer__footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.file-viewer__info {
  margin-right: auto;
  font-size: 12px;
  color: var(--im-text-secondary);
}
</style>
