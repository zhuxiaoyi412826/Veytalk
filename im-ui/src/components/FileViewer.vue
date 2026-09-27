<template>
  <el-dialog
    v-model="visible"
    :title="fileName"
    :width="narrow ? '100%' : '70vw'"
    :top="narrow ? '0' : '5vh'"
    :close-on-click-modal="true"
    destroy-on-close
    class="file-viewer"
    :class="{ 'file-viewer--narrow': narrow }"
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
      <!-- 文本：等宽字体原样展示；md 在渲染模式下走客户端渲染（需求 9） -->
      <div v-else-if="isMarkdownFile && mdRendered" class="file-viewer__body im-scroll">
        <div class="file-viewer__markdown" v-html="markdownHtml" />
      </div>
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
        <!-- md 文件提供渲染/源码切换：传输的始终是原始语法，渲染只发生在本机（需求 9） -->
        <el-button
          v-if="isMarkdownFile && !loading && !error"
          size="small"
          @click="mdRendered = !mdRendered"
        >{{ mdRendered ? '查看源码' : '渲染预览' }}</el-button>
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
 * Markdown（需求 9）：网络传输的仍是原始语法，接收端在本机用 marked 渲染成
 * HTML 展示，渲染不出这个客户端。附件内容不可信，v-html 前必过 DOMPurify 消毒，
 * 否则一份带 <script>/onerror 的 .md 就是存储型 XSS。
 *
 * JSON（需求 8 附带）：合法的 .json 附件取回后自动美化为缩进 2 空格的格式化文本，
 * 解析失败（本身是坏 json）则原样展示。
 *
 * 进入前按 fileSize 卡一道大小上限：预览是把整份文件读进内存，
 * 超大文件（几百 MB）会拖崩标签页，超限直接提示下载后查看而不发起拉取。
 *
 * 「另存为」通过 <a download> 触发浏览器原生保存对话框；当 allowDownload 为 false
 * （设置里开启「预览时禁止下载原文件」）时隐藏该入口，只做在线预览。
 *
 * 尺寸（需求 1）：窄屏（手机浏览器）下整个弹窗铺满屏幕、内容区占满剩余高度，
 * PC 浏览器与 Electron 桌面端维持 70vw 弹窗不变。判定走 utils/device.js，
 * 里面已排除 Electron，所以桌面窗口拉窄也不会误判成手机。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import WatermarkOverlay from './WatermarkOverlay.vue'
import { fetchBlob } from '@/api/request'
import { downloadFile, isPdf, MAX_PREVIEW_BYTES } from '@/utils/media'
import { useNarrowViewport } from '@/utils/device'
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

/** 窄屏（手机浏览器）：弹窗铺满整屏 */
const narrow = useNarrowViewport()

const loading = ref(false)
const error = ref('')
const content = ref('')
const copied = ref(false)
const pdfUrl = ref('')

const isPdfFile = computed(() => isPdf(props.fileName))

/** md / markdown 后缀：预览默认渲染成排版后的 HTML，可一键切回源码 */
const isMarkdownFile = computed(() => {
  const name = props.fileName || ''
  const dot = name.lastIndexOf('.')
  if (dot < 0) {
    return false
  }
  const ext = name.slice(dot + 1).toLowerCase()
  return ext === 'md' || ext === 'markdown'
})

/** 渲染/源码切换开关（弹窗重新打开时回到默认的渲染态） */
const mdRendered = ref(true)

/**
 * markdown 渲染结果：marked 转 HTML 后必过 DOMPurify 再交给 v-html。
 * breaks:true 让单换行也成段（聊天场景里大家习惯敲一个回车就换行）。
 */
const markdownHtml = computed(() => {
  if (!isMarkdownFile.value || !content.value) {
    return ''
  }
  const raw = marked.parse(content.value, { breaks: true, gfm: true })
  return DOMPurify.sanitize(raw)
})

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
  mdRendered.value = true
  try {
    // fileUrl 是自带 /api 前缀的受控地址：Web 同源下前缀置空即可，Electron 必须补后端绝对地址
    const blob = await fetchBlob(props.fileUrl, { baseURL: mediaBaseURL() })
    if (isPdfFile.value) {
      const typed = blob.type === 'application/pdf' ? blob : new Blob([blob], { type: 'application/pdf' })
      pdfUrl.value = URL.createObjectURL(typed)
    } else {
      let text = await blob.text()
      // .json 附件自动美化：能解析就重新序列化成缩进 2 空格，坏 json 原样展示
      if (/\.json$/i.test(props.fileName || '')) {
        try {
          text = JSON.stringify(JSON.parse(text), null, 2)
        } catch {
          // 解析失败说明内容本身不是合法 json，不替用户改文件内容
        }
      }
      content.value = text
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

/* markdown 渲染态排版：限制在预览宽度内，表格/代码块横向滚动而不是撑破布局 */
.file-viewer__markdown {
  padding: 16px 20px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--im-text);
  word-break: break-word;
}
.file-viewer__markdown :deep(h1),
.file-viewer__markdown :deep(h2),
.file-viewer__markdown :deep(h3),
.file-viewer__markdown :deep(h4) {
  margin: 18px 0 10px;
  font-weight: 600;
  line-height: 1.4;
}
.file-viewer__markdown :deep(h1) { font-size: 22px; }
.file-viewer__markdown :deep(h2) { font-size: 19px; }
.file-viewer__markdown :deep(h3) { font-size: 16px; }
.file-viewer__markdown :deep(p) { margin: 8px 0; }
.file-viewer__markdown :deep(ul),
.file-viewer__markdown :deep(ol) { padding-left: 24px; margin: 8px 0; }
.file-viewer__markdown :deep(code) {
  padding: 2px 5px;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 13px;
  background: rgba(127, 127, 127, 0.12);
  border-radius: 3px;
}
.file-viewer__markdown :deep(pre) {
  padding: 12px;
  overflow-x: auto;
  background: #f5f5f5;
  border-radius: 6px;
}
.file-viewer__markdown :deep(pre code) { padding: 0; background: none; }
.file-viewer__markdown :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  color: var(--im-text-secondary);
  border-left: 3px solid var(--im-primary, #409eff);
  background: rgba(127, 127, 127, 0.06);
}
.file-viewer__markdown :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 10px 0;
}
.file-viewer__markdown :deep(th),
.file-viewer__markdown :deep(td) { padding: 6px 10px; border: 1px solid var(--im-border); }
.file-viewer__markdown :deep(img) { max-width: 100%; }
.file-viewer__markdown :deep(a) { color: var(--im-primary, #409eff); }

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

<style>
/* 窄屏（手机浏览器）下预览弹窗铺满整屏：需求 1。
   写在非 scoped 块里，因为 .el-dialog__header/body/footer 是 Element Plus 内部元素，
   scoped 选择器带不上它们的 data-v；作用范围由 file-viewer--narrow 这个类名限定，
   而这个类只在 useNarrowViewport() 为真（非 Electron 且视口够窄）时才挂上去，
   所以 PC 浏览器和桌面端完全不受影响。 */
.el-dialog.file-viewer--narrow {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  width: 100vw;
  max-width: 100vw;
  height: 100vh;
  height: 100dvh;
  margin: 0 auto !important;
  border-radius: 0;
}

.el-dialog.file-viewer--narrow .el-dialog__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.el-dialog.file-viewer--narrow .file-viewer__body-host {
  flex: 1;
  min-height: 0;
  height: 100%;
}

/* 内容区撑满剩余高度，去掉左右描边——铺到屏幕边缘后边框反而像没对齐 */
.el-dialog.file-viewer--narrow .file-viewer__body {
  height: 100%;
  max-height: none;
  border-right: 0;
  border-left: 0;
  border-radius: 0;
}

.el-dialog.file-viewer--narrow .file-viewer__pdf {
  height: 100%;
  border-right: 0;
  border-left: 0;
  border-radius: 0;
}
</style>
