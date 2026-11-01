<template>
  <div class="articles">
    <div class="toolbar">
      <h2 class="page-title">文章管理</h2>
      <span class="grow" />
      <el-input
        v-model="query.keyword"
        :maxlength="50"
        clearable
        class="keyword"
        placeholder="标题关键词；纯数字也认作者 ID"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value" :label="opt.label" />
      </el-select>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新建文章</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="审核工作流：待审(1) → 通过(2) / 驳回(3)；已发布(2) ↔ 已下架(4)；编辑不改状态"
      description="列表含全部状态、按创建时间倒序。删除是逻辑删除且一期没有回收站，删掉的行整体隐身、无法自助找回。代写可选「直接发布」（免审）或落待审走审核。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" :row-class-name="rowClass">
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="article-cell">
              <img v-if="covers[row.id]" class="cover" :src="covers[row.id]" alt="" />
              <div v-else class="cover cover-empty"><el-icon><Document /></el-icon></div>
              <div class="article-text">
                <span class="ellipsis">{{ row.title || '（无标题）' }}</span>
                <span class="muted">#{{ row.id }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="作者" min-width="150">
          <template #default="{ row }">
            <div class="user-text">
              <span class="ellipsis">{{ row.authorName || '已注销用户' }}</span>
              <span class="muted">#{{ row.authorId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status]" effect="plain" size="small">{{ STATUS_TEXT[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column align="right" label="浏览" width="80">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.viewCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发布/创建时间" width="160">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.publishTime || row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="220">
          <template #default="{ row }">
            <el-button text type="primary" @click="openPreview(row)">预览</el-button>
            <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 1 || row.status === 0" text type="success" @click="confirmApprove(row)">通过</el-button>
            <el-button v-if="row.status === 1 || row.status === 0" text type="warning" @click="confirmReject(row)">驳回</el-button>
            <el-button v-if="row.status === 2" text type="danger" @click="confirmOffline(row)">下架</el-button>
            <el-button v-if="row.status === 4" text type="success" @click="confirmOnline(row)">上架</el-button>
            <el-button text type="danger" @click="confirmDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的文章" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @size-change="reload"
          @current-change="load"
        />
      </div>
    </el-card>

    <!-- 预览弹框：正文是 Markdown，渲成 HTML 后过 DOMPurify；受控图片经 fetchMedia 转 blob -->
    <el-dialog v-model="preview.visible" :title="preview.title" width="760px" top="6vh" destroy-on-close>
      <div v-loading="preview.loading" class="markdown-body" v-html="preview.html" ref="previewBody"></div>
    </el-dialog>

    <!-- 新建 / 编辑弹框：Markdown 编辑 + 实时预览 -->
    <el-dialog
      v-model="editor.visible"
      :title="editor.id ? '编辑文章' : '新建文章'"
      width="960px"
      top="5vh"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div class="editor">
        <el-input v-model="editor.title" maxlength="120" show-word-limit placeholder="标题（必填，最多 120 字）" />
        <el-input
          v-model="editor.summary"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          resize="none"
          placeholder="摘要（选填）"
          class="editor__summary"
        />
        <el-input v-model="editor.coverUrl" placeholder="封面受控地址（选填，如 /api/file/download/{fileId}）" class="editor__cover" />
        <div class="editor__panes">
          <div class="editor__pane">
            <div class="editor__pane-title">Markdown 正文</div>
            <el-input
              v-model="editor.content"
              type="textarea"
              :autosize="{ minRows: 14, maxRows: 22 }"
              placeholder="# 标题、**加粗**、- 列表、`代码`、![图片](/api/file/download/{fileId})…"
            />
          </div>
          <div class="editor__pane">
            <div class="editor__pane-title">实时预览</div>
            <div class="editor__preview markdown-body" v-html="editor.html" ref="editorBody"></div>
          </div>
        </div>
        <el-checkbox v-if="!editor.id" v-model="editor.publishNow">直接发布（免审，作为官方内容立即上架）</el-checkbox>
      </div>
      <template #footer>
        <el-button @click="editor.visible = false">取消</el-button>
        <el-button type="primary" :loading="editor.saving" @click="saveEditor">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Document } from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  fetchArticlePage,
  fetchArticleDetail,
  createArticle,
  updateArticle,
  approveArticle,
  rejectArticle,
  offlineArticle,
  onlineArticle,
  deleteArticle
} from '@/api/admin'
import { fetchMedia } from '@/api/request'
import { formatListTime, formatNumber } from '@/utils/format'

/**
 * 后台文章管理（Markdown + 审核工作流）。
 *
 * 列表含全部状态，操作按当前态分路口出现：待审/草稿给「通过 / 驳回」，已发布给「下架」，
 * 已下架给「上架」，任何状态都可预览、编辑与删除。审核只在待审(1)↔已发布(2)/驳回(3) 之间动，
 * 上下架只在已发布(2)↔已下架(4) 之间动——非法当前态点了会被后端拒。
 *
 * 待审行高亮，方便把它当成审核队列来清。
 */
const STATUS_TEXT = { 0: '草稿', 1: '待审核', 2: '已发布', 3: '已驳回', 4: '已下架' }
const STATUS_TAG = { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info' }
const STATUS_OPTIONS = [
  { value: 0, label: '草稿' },
  { value: 1, label: '待审核' },
  { value: 2, label: '已发布' },
  { value: 3, label: '已驳回' },
  { value: 4, label: '已下架' }
]

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', status: undefined, current: 1, size: 20 })

function rowClass({ row }) {
  return row.status === 1 ? 'row-pending' : ''
}

async function load() {
  loading.value = true
  try {
    const page = await fetchArticlePage({
      keyword: query.keyword || undefined,
      status: query.status,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
    loadCovers()
  } catch {
    // 提示已由 request.js 弹过；保留上一次结果
  } finally {
    loading.value = false
  }
}

function reload() {
  query.current = 1
  load()
}

/* 封面缩略：受控地址塞 <img> 带不了登录头，先取成 blob */
const covers = reactive({})
function loadCovers() {
  rows.value.forEach((row) => {
    if (row.coverUrl && !covers[row.id]) {
      fetchMedia(row.coverUrl).then((src) => {
        covers[row.id] = src
      })
    }
  })
}

/* ---------- Markdown 渲染 + 受控图片换 blob ---------- */
function toHtml(md) {
  if (!md) {
    return ''
  }
  return DOMPurify.sanitize(marked.parse(md, { breaks: true, gfm: true }))
}

/** 渲完后把容器里的受控 <img> 换成 blob 地址；失败不阻断 */
async function hydrateImages(el) {
  if (!el) {
    return
  }
  const imgs = el.querySelectorAll('img')
  imgs.forEach(async (img) => {
    const src = img.getAttribute('src') || ''
    if (src.includes('/api/file/download/')) {
      const blob = await fetchMedia(src)
      if (blob) {
        img.src = blob
      }
    }
  })
}

/* ---------- 预览 ---------- */
const preview = reactive({ visible: false, title: '', html: '', loading: false })
const previewBody = ref(null)

async function openPreview(row) {
  preview.title = `预览：${row.title || `#${row.id}`}`
  preview.html = ''
  preview.loading = true
  preview.visible = true
  try {
    const detail = await fetchArticleDetail(row.id)
    preview.html = toHtml(detail.content)
    await nextTick()
    hydrateImages(previewBody.value)
  } catch {
    // 接口层已弹提示
  } finally {
    preview.loading = false
  }
}

/* ---------- 新建 / 编辑 ---------- */
const editor = reactive({
  visible: false,
  saving: false,
  id: null,
  title: '',
  summary: '',
  content: '',
  coverUrl: '',
  publishNow: false,
  html: ''
})
const editorBody = ref(null)

function resetEditor() {
  editor.id = null
  editor.title = ''
  editor.summary = ''
  editor.content = ''
  editor.coverUrl = ''
  editor.publishNow = false
  editor.html = ''
}

function openCreate() {
  resetEditor()
  editor.visible = true
}

async function openEdit(row) {
  resetEditor()
  try {
    const detail = await fetchArticleDetail(row.id)
    editor.id = detail.id
    editor.title = detail.title || ''
    editor.summary = detail.summary || ''
    editor.content = detail.content || ''
    editor.coverUrl = detail.coverUrl || ''
    editor.html = toHtml(editor.content)
    editor.visible = true
    await nextTick()
    hydrateImages(editorBody.value)
  } catch {
    // 接口层已弹提示
  }
}

// 编辑正文时实时刷新预览（含图片换 blob）
watch(
  () => editor.content,
  async (val) => {
    editor.html = toHtml(val)
    await nextTick()
    hydrateImages(editorBody.value)
  }
)

async function saveEditor() {
  if (!editor.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  editor.saving = true
  try {
    const dto = {
      title: editor.title.trim(),
      summary: editor.summary.trim(),
      content: editor.content,
      coverUrl: editor.coverUrl.trim() || undefined
    }
    if (editor.id) {
      await updateArticle(editor.id, dto)
      ElMessage.success('文章已保存')
    } else {
      await createArticle(dto, editor.publishNow)
      ElMessage.success(editor.publishNow ? '文章已创建并发布' : '文章已创建，待审核')
    }
    editor.visible = false
    load()
  } catch {
    // 接口层已弹提示
  } finally {
    editor.saving = false
  }
}

/* ---------- 状态动作 ---------- */
function applyRow(row, updated) {
  if (updated) {
    Object.assign(row, updated)
  }
}

async function confirmApprove(row) {
  let reason
  try {
    const result = await ElMessageBox.prompt(
      `通过后「${row.title || `#${row.id}`}」将发布到广场，作者与所有人可见。`,
      '审核通过',
      { confirmButtonText: '确认通过', cancelButtonText: '取消', inputPlaceholder: '选填：审核备注' }
    )
    reason = result.value?.trim() || undefined
  } catch {
    return
  }
  try {
    const updated = await approveArticle(row.id, reason)
    applyRow(row, updated)
    ElMessage.success('文章已通过并发布')
  } catch {
    // 接口层已弹提示
  }
}

async function confirmReject(row) {
  let reason
  try {
    const result = await ElMessageBox.prompt(
      `驳回「${row.title || `#${row.id}`}」后作者可见事由并可修改后重新提交。`,
      '审核驳回',
      {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputPlaceholder: '请填写驳回事由',
        inputValidator: (value) => (value && value.trim() ? true : '请填写驳回事由'),
        type: 'warning'
      }
    )
    reason = result.value?.trim()
  } catch {
    return
  }
  try {
    const updated = await rejectArticle(row.id, reason)
    applyRow(row, updated)
    ElMessage.success('文章已驳回')
  } catch {
    // 同上
  }
}

async function confirmOffline(row) {
  let reason
  try {
    const result = await ElMessageBox.prompt(
      `下架后「${row.title || `#${row.id}`}」在广场不再展示，记录保留、可随时恢复上架。`,
      '下架文章',
      { confirmButtonText: '确认下架', cancelButtonText: '取消', inputPlaceholder: '选填：下架事由', type: 'warning' }
    )
    reason = result.value?.trim() || undefined
  } catch {
    return
  }
  try {
    const updated = await offlineArticle(row.id, reason)
    applyRow(row, updated)
    ElMessage.success('文章已下架')
  } catch {
    // 同上
  }
}

async function confirmOnline(row) {
  try {
    await ElMessageBox.confirm(
      `恢复上架「${row.title || `#${row.id}`}」，广场重新可见。`,
      '恢复上架',
      { confirmButtonText: '确认上架', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const updated = await onlineArticle(row.id)
    applyRow(row, updated)
    ElMessage.success('文章已恢复上架')
  } catch {
    // 同上
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.title || `#${row.id}`}」是逻辑删除，一期没有回收站，删除后无法自助恢复。`,
      '删除文章',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'error',
        inputPlaceholder: '请输入「删除」两个字确认',
        inputValidator: (value) => (value && value.trim() === '删除' ? true : '请精确输入：删除')
      }
    )
  } catch {
    return
  }
  try {
    await deleteArticle(row.id)
    ElMessage.success('文章已删除')
    load()
  } catch {
    // 同上
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.keyword {
  width: 250px;
}

.status {
  width: 130px;
}

.hint {
  margin-bottom: 16px;
}

.article-cell {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.cover {
  background: #f5f7fa;
  border-radius: 4px;
  flex: none;
  height: 40px;
  object-fit: cover;
  width: 64px;
}

.cover-empty {
  align-items: center;
  display: flex;
  justify-content: center;
  color: #c0c4cc;
}

.article-text,
.user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

:deep(.row-pending) {
  background: var(--el-color-warning-light-9);
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor__panes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.editor__pane {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.editor__pane-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.editor__preview {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 14px;
  height: 400px;
  overflow: auto;
  background: var(--el-bg-color);
}

@media (max-width: 820px) {
  .editor__panes {
    grid-template-columns: 1fr;
  }
}

/* markdown 排版（预览与编辑面板共用） */
.markdown-body {
  line-height: 1.7;
  word-break: break-word;
  min-height: 60px;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin: 14px 0 8px;
  font-weight: 600;
}

.markdown-body :deep(img) {
  max-width: 100%;
  border-radius: 6px;
}

.markdown-body :deep(pre) {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 6px;
  overflow: auto;
}

.markdown-body :deep(code) {
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 4px;
}

.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--el-border-color-lighter);
  padding: 5px 8px;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}
</style>
