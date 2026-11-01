<script setup>
/**
 * 直播页「文章」板块（作者侧全部 UI）。
 *
 * 从 Live.vue 抽出来独立成组件——Live.vue 已逾 4000 行，把文章的列表 / 阅读 / 撰写
 * 三块 UI 再塞进去只会让它更难维护；这个组件自持状态、自带分页，Live.vue 只需在
 * tab 切到 article 时挂上它。
 *
 * 两个子视图：「广场」看已发布（status=2），「我的」看自己全部状态（含草稿/待审/驳回）。
 * 撰写走 Markdown，右侧实时预览；封面与正文图片都经 im-file 上传（article_cover / article_img），
 * 落库的是受控地址，阅读时由 MarkdownView 统一转 blob 渲染。
 */
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EditPen, Picture, Document, Delete } from '@element-plus/icons-vue'
import {
  fetchArticlePage,
  fetchMyArticles,
  fetchArticleDetail,
  createArticle,
  updateArticle,
  submitArticle,
  deleteArticle
} from '@/api/article'
import { uploadFileSmart } from '@/api/file'
import { mediaUrl } from '@/utils/media'
import UserAvatar from '@/components/UserAvatar.vue'
import MarkdownView from '@/components/MarkdownView.vue'

/* ---------- 状态字典 ---------- */
const STATUS_TEXT = { 0: '草稿', 1: '待审核', 2: '已发布', 3: '已驳回', 4: '已下架' }
const STATUS_TAG = { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info' }

/* ---------- 列表：广场 / 我的 ---------- */
const subTab = ref('square')
const list = ref([])
const loading = ref(false)
const query = reactive({ current: 1, size: 12, total: 0 })

async function loadList() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    const page = subTab.value === 'square' ? await fetchArticlePage(params) : await fetchMyArticles(params)
    list.value = page?.records || []
    query.total = page?.total || 0
  } catch {
    // request.js 已弹过提示
  } finally {
    loading.value = false
  }
}

function switchSubTab(tab) {
  if (subTab.value === tab) {
    return
  }
  subTab.value = tab
  query.current = 1
  loadList()
}

function onPageChange(p) {
  query.current = p
  loadList()
}

function coverSrc(item) {
  return item.coverUrl ? mediaUrl(item.coverUrl) : ''
}

/* ---------- 阅读 ---------- */
const readVisible = ref(false)
const reading = ref(null)
const readLoading = ref(false)

async function openRead(item) {
  readVisible.value = true
  readLoading.value = true
  reading.value = null
  try {
    reading.value = await fetchArticleDetail(item.id)
  } catch {
    readVisible.value = false
  } finally {
    readLoading.value = false
  }
}

/* ---------- 撰写 / 编辑 ---------- */
const editVisible = ref(false)
const editSaving = ref(false)
const editingId = ref(null)
const coverUploading = ref(false)
const imgUploading = ref(false)
const form = reactive({ title: '', summary: '', content: '', coverFileId: null, coverRaw: '' })

function resetForm() {
  editingId.value = null
  form.title = ''
  form.summary = ''
  form.content = ''
  form.coverFileId = null
  form.coverRaw = ''
}

function openCreate() {
  resetForm()
  editVisible.value = true
}

async function openEdit(item) {
  resetForm()
  try {
    const detail = await fetchArticleDetail(item.id)
    editingId.value = detail.id
    form.title = detail.title || ''
    form.summary = detail.summary || ''
    form.content = detail.content || ''
    form.coverFileId = detail.coverFileId || null
    form.coverRaw = detail.coverUrl || ''
    editVisible.value = true
  } catch {
    // 拉不到详情（如已发布非本人不可编辑），错误提示已由拦截器弹出
  }
}

/** 封面上传：走 article_cover，成功后落 coverFileId 并即时预览受控地址 */
const coverInput = ref(null)
function pickCover() {
  coverInput.value && coverInput.value.click()
}
async function onCoverChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) {
    return
  }
  coverUploading.value = true
  try {
    const vo = await uploadFileSmart(file, 'article_cover')
    if (vo && vo.id) {
      form.coverFileId = vo.id
      form.coverRaw = `/api/file/download/${vo.id}`
    }
  } catch {
    ElMessage.error('封面上传失败')
  } finally {
    coverUploading.value = false
  }
}

/** 正文插图：走 article_img，拿到 fileId 后把受控地址按 Markdown 图片语法插进光标处 */
const imgInput = ref(null)
const contentArea = ref(null)
function pickImage() {
  imgInput.value && imgInput.value.click()
}
async function onImageChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) {
    return
  }
  imgUploading.value = true
  try {
    const vo = await uploadFileSmart(file, 'article_img')
    if (vo && vo.id) {
      insertAtCursor(`![图片](/api/file/download/${vo.id})`)
    }
  } catch {
    ElMessage.error('图片上传失败')
  } finally {
    imgUploading.value = false
  }
}

function insertAtCursor(text) {
  const el = contentArea.value
  const snippet = `\n\n${text}\n`
  if (el && typeof el.selectionStart === 'number') {
    const start = el.selectionStart
    form.content = form.content.slice(0, start) + snippet + form.content.slice(el.selectionEnd)
    requestAnimationFrame(() => {
      el.focus()
      const pos = start + snippet.length
      el.setSelectionRange(pos, pos)
    })
  } else {
    form.content += snippet
  }
}

/** 存草稿（action=draft）或提交审核（action=submit） */
async function saveArticle(action) {
  if (!form.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请填写正文')
    return
  }
  editSaving.value = true
  try {
    const body = {
      title: form.title.trim(),
      summary: form.summary.trim() || undefined,
      content: form.content,
      coverFileId: form.coverFileId || undefined,
      action
    }
    if (editingId.value) {
      await updateArticle(editingId.value, body)
    } else {
      await createArticle(body)
    }
    ElMessage.success(action === 'submit' ? '已提交审核' : '已存草稿')
    editVisible.value = false
    loadList()
  } catch {
    // 错误提示已由拦截器弹出
  } finally {
    editSaving.value = false
  }
}

/* ---------- 提交草稿 / 删除（我的列表） ---------- */
async function onSubmitDraft(item) {
  try {
    await submitArticle(item.id)
    ElMessage.success('已提交审核')
    loadList()
  } catch {
    // 错误提示已由拦截器弹出
  }
}

async function onRemove(item) {
  try {
    await ElMessageBox.confirm(`确定删除文章「${item.title}」吗？删除后不可恢复。`, '删除文章', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  try {
    await deleteArticle(item.id)
    ElMessage.success('已删除')
    loadList()
  } catch {
    // 错误提示已由拦截器弹出
  }
}

/* ---------- 首次挂载 ---------- */
loadList()
</script>

<template>
  <div class="article-panel">
    <!-- 子视图切换 + 撰写入口 -->
    <div class="article-panel__head">
      <div class="article-panel__tabs">
        <el-radio-group :model-value="subTab" size="default" @change="switchSubTab">
          <el-radio-button value="square">广场</el-radio-button>
          <el-radio-button value="mine">我的</el-radio-button>
        </el-radio-group>
      </div>
      <div class="article-panel__actions">
        <span class="article-panel__count">共 {{ query.total }} 篇</span>
        <el-button type="primary" :icon="EditPen" @click="openCreate">写文章</el-button>
      </div>
    </div>

    <!-- 列表卡片 -->
    <div v-loading="loading" class="article-grid">
      <div v-for="item in list" :key="item.id" class="article-card" @click="openRead(item)">
        <div class="article-card__cover">
          <img v-if="coverSrc(item)" :src="coverSrc(item)" alt="" />
          <div v-else class="article-card__placeholder">
            <el-icon :size="30"><Document /></el-icon>
          </div>
          <el-tag
            v-if="subTab === 'mine'"
            class="article-card__status"
            :type="STATUS_TAG[item.status]"
            size="small"
            effect="dark"
          >
            {{ STATUS_TEXT[item.status] }}
          </el-tag>
        </div>
        <div class="article-card__body">
          <div class="article-card__title im-two-line">{{ item.title }}</div>
          <div class="article-card__summary im-two-line">{{ item.summary || '暂无摘要' }}</div>
          <div class="article-card__meta">
            <UserAvatar :src="item.authorAvatar" :name="item.authorName" :size="20" />
            <span class="im-ellipsis article-card__author">{{ item.authorName }}</span>
            <span class="article-card__views">👁 {{ item.viewCount || 0 }}</span>
          </div>
          <div v-if="item.status === 3 && item.rejectReason" class="article-card__reject">
            驳回：{{ item.rejectReason }}
          </div>
          <!-- 我的：可操作区 -->
          <div v-if="subTab === 'mine'" class="article-card__ops" @click.stop>
            <el-button
              v-if="item.status === 0 || item.status === 3"
              text
              size="small"
              type="primary"
              :icon="EditPen"
              @click="openEdit(item)"
            >编辑</el-button>
            <el-button
              v-if="item.mine && (item.status === 0 || item.status === 3)"
              text
              size="small"
              type="warning"
              @click="onSubmitDraft(item)"
            >提交审核</el-button>
            <el-button text size="small" type="danger" :icon="Delete" @click="onRemove(item)">删除</el-button>
          </div>
        </div>
      </div>
      <el-empty
        v-if="!loading && !list.length"
        :description="subTab === 'square' ? '还没有已发布的文章' : '你还没有写过文章'"
        class="article-grid__empty"
      />
    </div>

    <div v-if="query.total > query.size" class="article-panel__pager">
      <el-pagination
        layout="prev, pager, next"
        :total="query.total"
        :page-size="query.size"
        :current-page="query.current"
        @current-change="onPageChange"
      />
    </div>

    <!-- 阅读弹窗 -->
    <el-dialog v-model="readVisible" title="正文" width="760px" top="6vh" class="article-read__dialog">
      <div v-loading="readLoading" class="article-read">
        <template v-if="reading">
          <h2 class="article-read__title">{{ reading.title }}</h2>
          <div class="article-read__meta">
            <UserAvatar :src="reading.authorAvatar" :name="reading.authorName" :size="26" />
            <span class="article-read__author">{{ reading.authorName }}</span>
            <span class="article-read__views">👁 {{ reading.viewCount || 0 }}</span>
            <el-tag :type="STATUS_TAG[reading.status]" size="small">{{ STATUS_TEXT[reading.status] }}</el-tag>
          </div>
          <img v-if="coverSrc(reading)" :src="coverSrc(reading)" class="article-read__cover" alt="" />
          <MarkdownView :content="reading.content" class="article-read__content" />
        </template>
      </div>
    </el-dialog>

    <!-- 撰写 / 编辑弹窗 -->
    <el-dialog
      v-model="editVisible"
      :title="editingId ? '编辑文章' : '写文章'"
      width="920px"
      top="5vh"
      :close-on-click-modal="false"
      class="article-edit__dialog"
    >
      <div class="article-edit">
        <el-input v-model="form.title" maxlength="120" show-word-limit placeholder="标题（必填，最多 120 字）" />
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="2"
          maxlength="255"
          show-word-limit
          resize="none"
          placeholder="摘要（选填，空则由服务端从正文截取）"
          class="article-edit__summary"
        />
        <!-- 封面 -->
        <div class="article-edit__cover">
          <el-button :loading="coverUploading" :icon="Picture" @click="pickCover">
            {{ form.coverFileId ? '更换封面' : '上传封面' }}
          </el-button>
          <img v-if="form.coverRaw" :src="mediaUrl(form.coverRaw)" class="article-edit__cover-img" alt="" />
          <el-button v-if="form.coverFileId" text type="danger" @click="form.coverFileId = null; form.coverRaw = ''">
            移除封面
          </el-button>
          <input ref="coverInput" type="file" accept="image/*" hidden @change="onCoverChange" />
          <input ref="imgInput" type="file" accept="image/*" hidden @change="onImageChange" />
        </div>
        <!-- 正文 + 实时预览 -->
        <div class="article-edit__editor">
          <div class="article-edit__pane">
            <div class="article-edit__pane-bar">
              <span>Markdown 正文</span>
              <el-button size="small" :loading="imgUploading" :icon="Picture" @click="pickImage">插入图片</el-button>
            </div>
            <el-input
              ref="contentArea"
              v-model="form.content"
              type="textarea"
              :autosize="{ minRows: 16, maxRows: 24 }"
              placeholder="支持 Markdown：# 标题、**加粗**、- 列表、`代码`、![图片](地址)…"
            />
          </div>
          <div class="article-edit__pane">
            <div class="article-edit__pane-bar"><span>实时预览</span></div>
            <div class="article-edit__preview">
              <MarkdownView :content="form.content" />
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button :loading="editSaving" @click="saveArticle('draft')">存草稿</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveArticle('submit')">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.article-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 10px;
}

.article-panel__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.article-panel__count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.article-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
  min-height: 120px;
}

.article-grid__empty {
  grid-column: 1 / -1;
}

.article-card {
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  display: flex;
  flex-direction: column;
}

.article-card:hover {
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.12);
  transform: translateY(-2px);
}

.article-card__cover {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--el-fill-color-light);
}

.article-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.article-card__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
}

.article-card__status {
  position: absolute;
  top: 8px;
  left: 8px;
}

.article-card__body {
  padding: 10px 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.article-card__title {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.article-card__summary {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  min-height: 38px;
}

.article-card__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.article-card__author {
  max-width: 90px;
}

.article-card__views {
  margin-left: auto;
}

.article-card__reject {
  font-size: 12px;
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  padding: 4px 8px;
  border-radius: 6px;
}

.article-card__ops {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 6px;
}

.article-panel__pager {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}

/* 阅读 */
.article-read {
  min-height: 120px;
  max-height: 72vh;
  overflow: auto;
}

.article-read__title {
  margin: 0 0 12px;
  font-size: 22px;
}

.article-read__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}

.article-read__views {
  margin-left: auto;
}

.article-read__cover {
  width: 100%;
  border-radius: 8px;
  margin-bottom: 14px;
}

/* 编辑 */
.article-edit {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.article-edit__cover {
  display: flex;
  align-items: center;
  gap: 10px;
}

.article-edit__cover-img {
  width: 84px;
  height: 48px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
}

.article-edit__editor {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.article-edit__pane {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.article-edit__pane-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.article-edit__preview {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 14px;
  height: 420px;
  overflow: auto;
  background: var(--el-bg-color);
}

@media (max-width: 720px) {
  .article-edit__editor {
    grid-template-columns: 1fr;
  }
}
</style>
