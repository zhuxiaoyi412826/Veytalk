<script setup>
/**
 * Markdown 只读渲染。
 *
 * marked 转 HTML 后必过 DOMPurify 再交给 v-html（与 FileViewer.vue 同一口径，
 * breaks:true 让单换行也成段、gfm:true 支持表格/任务列表等扩展语法）。
 *
 * 正文里的图片写的是受控地址 /api/file/download/{fileId}，这个地址不带凭证，
 * 而 v-html 渲出的 <img> 带不了登录头，直接放上去必然 401。所以渲染完成后遍历
 * 容器内的 <img>，把受控 src 经 utils/media.js 的 mediaUrlAsync 换成可渲染的 blob
 * 地址再回填（与全站头像/封面走同一条取内容路径）。
 */
import { nextTick, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { mediaUrlAsync } from '@/utils/media'

const props = defineProps({
  content: { type: String, default: '' }
})

const container = ref(null)

const html = ref('')

/** 受控下载地址特征：命中才需要换成 blob，外链（http/data/blob）原样放行 */
const CONTROLLED_SRC = /^\/api\/file\/download\/|\/api\/file\/download\//

function render() {
  if (!props.content) {
    html.value = ''
    return
  }
  const raw = marked.parse(props.content, { breaks: true, gfm: true })
  html.value = DOMPurify.sanitize(raw)
}

/**
 * 渲完后把受控 <img> 换成 blob 地址。异步、失败不阻断：取不到的图留原 src，
 * 加载失败浏览器自会显示破损占位，正文其余部分照常。
 */
async function hydrateImages() {
  await nextTick()
  const root = container.value
  if (!root) {
    return
  }
  const imgs = root.querySelectorAll('img')
  imgs.forEach(async (img) => {
    const src = img.getAttribute('src') || ''
    if (CONTROLLED_SRC.test(src)) {
      const blob = await mediaUrlAsync(src)
      if (blob) {
        img.src = blob
      }
    }
  })
}

watch(
  () => props.content,
  () => {
    render()
    hydrateImages()
  }
)

onMounted(() => {
  render()
  hydrateImages()
})
</script>

<template>
  <div ref="container" class="markdown-view" v-html="html"></div>
</template>

<style scoped>
.markdown-view {
  line-height: 1.7;
  word-break: break-word;
  color: var(--el-text-color-primary);
}

.markdown-view :deep(h1),
.markdown-view :deep(h2),
.markdown-view :deep(h3) {
  margin: 16px 0 10px;
  font-weight: 600;
  line-height: 1.4;
}

.markdown-view :deep(h1) {
  font-size: 1.6em;
}

.markdown-view :deep(h2) {
  font-size: 1.35em;
}

.markdown-view :deep(h3) {
  font-size: 1.15em;
}

.markdown-view :deep(p) {
  margin: 8px 0;
}

.markdown-view :deep(img) {
  max-width: 100%;
  border-radius: 8px;
  display: block;
  margin: 10px 0;
}

.markdown-view :deep(pre) {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 8px;
  overflow: auto;
  font-size: 0.9em;
}

.markdown-view :deep(code) {
  background: var(--el-fill-color-light);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: Consolas, Monaco, monospace;
}

.markdown-view :deep(pre code) {
  background: transparent;
  padding: 0;
}

.markdown-view :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
}

.markdown-view :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 10px 0;
}

.markdown-view :deep(th),
.markdown-view :deep(td) {
  border: 1px solid var(--el-border-color-lighter);
  padding: 6px 10px;
  text-align: left;
}

.markdown-view :deep(ul),
.markdown-view :deep(ol) {
  padding-left: 22px;
  margin: 8px 0;
}

.markdown-view :deep(a) {
  color: var(--el-color-primary);
  text-decoration: none;
}

.markdown-view :deep(hr) {
  border: none;
  border-top: 1px solid var(--el-border-color-lighter);
  margin: 14px 0;
}
</style>
