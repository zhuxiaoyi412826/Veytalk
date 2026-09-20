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

    <div v-else class="file-viewer__body-host">
      <!-- PDF：交给浏览器内置查看器渲染，无需引入 pdf.js -->
      <iframe
        v-if="isPdfFile"
        :src="pdfUrl"
        class="file-viewer__pdf"
        frameborder="0"
        title="PDF 预览"
      />
      <!-- 文本：等宽字体原样展示 -->
      <div v-else class="file-viewer__body im-scroll">
        <pre class="file-viewer__pre">{{ content }}</pre>
      </div>
      <!-- 发送者水印：随附件传递，接收端预览时叠在内容上 -->
      <WatermarkOverlay v-if="watermark" :text="watermark" />
    </div>

    <template #footer>
      <div class="file-viewer__footer">
        <span v-if="!loading && !error" class="file-viewer__info">
          <template v-if="isPdfFile">PDF · {{ sizeLabel }}</template>
          <template v-else>{{ lineCount }} 行 · {{ sizeLabel }}</template>
        </span>
        <el-button @click="visible = false">关闭</el-button>
        <el-button v-if="!isPdfFile" :disabled="loading || !!error" @click="onCopy">
          {{ copied ? '已复制' : '复制' }}
        </el-button>
        <!-- allowDownload=false 时（设置里开启「预览时禁止下载」）不提供原始文件下载入口 -->
        <el-button
          v-if="allowDownload"
          type="primary"
          :disabled="loading || !!error"
          @click="onSave"
        >另存为…</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 文件预览弹窗：文本 + PDF。
 *
 * 文本类文件（json / xml / yml / py / ps1 / md / txt 等）取内容以等宽字体原样展示，
 * 不做语法高亮——这些格式用 pre 原样展示就有可读性，引入 highlight.js 收益不成比例。
 * PDF 取成 blob 后交 <iframe>，由浏览器内置查看器渲染，同样不引入额外库。
 *
 * 进入前按 fileSize 卡一道大小上限：预览是把整份文件读进内存，
 * 超大文件（几百 MB）会拖崩标签页，超限直接提示下载后查看而不发起拉取。
 *
 * 「另存为」通过 <a download> 触发浏览器原生保存对话框；当 allowDownload 为 false
 * （设置里开启「预览时禁止下载原文件」）时隐藏该入口，只做在线预览。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import WatermarkOverlay from './WatermarkOverlay.vue'
import { fetchBlob } from '@/api/request'
import { downloadFile, isPdf, MAX_PREVIEW_BYTES } from '@/utils/media'
import { mediaBaseURL } from '@/utils/env'

const props = defineProps({
  fileUrl: { type: String, default: '' },
  fileName: { type: String, default: '文件预览' },
  /** 文件字节数，用于进入前的大小上限校验；取不到时（0）跳过校验照常预览 */
  fileSize: { type: Number, default: 0 },
  /** 是否允许在预览里下载原文件，false 时隐藏「另存为」 */
  allowDownload: { type: Boolean, default: true },
  /** 非空时在预览内容上叠加该文字水印（发送者昵称） */
  watermark: { type: String, default: '' }
})

const visible = defineModel('visible', { type: Boolean, default: false })

const loading = ref(false)
const error = ref('')
const content = ref('')
const copied = ref(false)
const pdfUrl = ref('')

const isPdfFile = computed(() => isPdf(props.fileName))

const lineCount = computed(() => {
  if (!content.value) {
    return 0
  }
  return content.value.split('\n').length
})

const sizeLabel = computed(() => {
  const bytes = isPdfFile.value ? (props.fileSize || 0) : new Blob([content.value]).size
  if (bytes < 1024) {
    return bytes + ' B'
  }
  if (bytes < 1024 * 1024) {
    return (bytes / 1024).toFixed(1) + ' KB'
  }
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
})

/** 释放上一次的 PDF object URL，避免反复预览攒下内存泄漏 */
function revokePdfUrl() {
  if (pdfUrl.value) {
    URL.revokeObjectURL(pdfUrl.value)
    pdfUrl.value = ''
  }
}

/**
 * 弹窗打开时取文件内容。
 *
 * 先按大小上限拦截，再把内容读成 blob：文本走 text() 解码，
 * PDF 转 object URL 交 iframe。用 fetchBlob 而非 http.get 取字符串，是因为受控
 * 下载地址的 Content-Type 可能是 octet-stream，axios 的 text 响应类型行为不一致。
 */
watch(visible, async (show) => {
  revokePdfUrl()
  if (!show || !props.fileUrl) {
    content.value = ''
    error.value = ''
    return
  }
  if (props.fileSize && props.fileSize > MAX_PREVIEW_BYTES) {
    error.value = `文件较大（${sizeLabel.value}），超出在线预览上限，请下载后查看`
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  content.value = ''
  copied.value = false
  try {
    // fileUrl 是自带 /api 前缀的受控地址：Web 同源下前缀置空即可，Electron 必须补后端绝对地址
    const blob = await fetchBlob(props.fileUrl, { baseURL: mediaBaseURL() })
    if (isPdfFile.value) {
      const typed = blob.type === 'application/pdf' ? blob : new Blob([blob], { type: 'application/pdf' })
      pdfUrl.value = URL.createObjectURL(typed)
    } else {
      content.value = await blob.text()
    }
  } catch (e) {
    error.value = e?.message || '文件内容获取失败'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(revokePdfUrl)

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
 * 用 downloadFile 触发 <a download>，浏览器弹出原生保存对话框，
 * 用户可在其中自由选择保存路径和文件名——浏览器安全模型不允许网页直接指定磁盘路径。
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

.file-viewer__body-host {
  position: relative;
}

.file-viewer__body {
  max-height: 70vh;
  overflow: auto;
  background: #f8f8f8;
  border: 1px solid var(--im-border);
  border-radius: 6px;
}

.file-viewer__pdf {
  display: block;
  width: 100%;
  height: 72vh;
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
