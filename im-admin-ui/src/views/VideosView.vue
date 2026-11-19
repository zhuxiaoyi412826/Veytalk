<template>
  <div class="videos">
    <el-tabs v-model="activeTab" class="videos__tabs">
      <el-tab-pane label="视频列表" name="videos">
    <div class="toolbar">
      <h2 class="page-title">视频管理</h2>
      <span class="grow" />
      <el-input
        v-model="query.keyword"
        :maxlength="30"
        clearable
        class="keyword"
        placeholder="标题关键词；纯数字也认上传者 ID"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="1" label="正常" />
        <el-option :value="2" label="已下架" />
      </el-select>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="这里只做「下架 / 恢复上架 / 删除」三个动作，不代删文件本体"
      description="下架后信息流不再展示但记录保留、随时可恢复；删除是逻辑删除且一期没有回收站，删掉的行整体隐身、无法自助找回。列表含已下架，按发布时间倒序。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="视频" min-width="260">
          <template #default="{ row }">
            <div class="video-cell">
              <img v-if="covers[row.id]" class="cover" :src="covers[row.id]" alt="" />
              <div v-else class="cover cover-empty"><el-icon><Film /></el-icon></div>
              <div class="video-text">
                <span class="ellipsis">{{ row.title || '（无标题）' }}</span>
                <span class="muted">#{{ row.id }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="上传者" min-width="150">
          <template #default="{ row }">
            <div class="user-text">
              <span class="ellipsis">{{ row.uploaderName || '已注销用户' }}</span>
              <span class="muted">#{{ row.uploaderId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="80">
          <template #default="{ row }">
            <span class="num">{{ formatDuration(row.duration) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分辨率" width="110">
          <template #default="{ row }">
            <span class="num">{{ row.width && row.height ? `${row.width}×${row.height}` : EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="90">
          <template #default="{ row }">
            <span class="num">{{ formatBytes(row.size) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="播放" width="90">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.playCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'warning'" effect="plain" size="small">
              {{ row.status === 1 ? '正常' : '已下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" width="120">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="180">
          <template #default="{ row }">
            <el-button text type="primary" @click="openPreview(row)">预览</el-button>
            <el-button v-if="row.status === 1" text type="danger" @click="confirmOffline(row)">下架</el-button>
            <el-button v-else text type="success" @click="confirmOnline(row)">上架</el-button>
            <el-button text type="danger" @click="confirmDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的视频" :image-size="80" />
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
      </el-tab-pane>

      <el-tab-pane label="分类管理" name="categories">
        <div class="toolbar">
          <span class="muted">自定义视频板块顶部分类栏：增删改、排序（改 sort）、启停。停用/删除只影响新的展示，不动历史视频已落库的分类文本。</span>
          <span class="grow" />
          <el-button type="primary" @click="openCategoryCreate">
            <el-icon><Plus /></el-icon>
            <span>新增分类</span>
          </el-button>
          <el-button :loading="catLoading" @click="loadCategories">
            <el-icon><Refresh /></el-icon>
            <span>重新读取</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="catLoading" :data="categories">
            <el-table-column align="center" label="排序" prop="sort" width="90" />
            <el-table-column label="分类名称" prop="name" min-width="160" />
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-switch :model-value="row.status === 1" @change="(v) => toggleCategoryStatus(row, v)" />
                <span class="muted cat-status-text">{{ row.status === 1 ? '启用' : '停用' }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="160">
              <template #default="{ row }">
                <el-button text type="primary" @click="openCategoryEdit(row)">编辑</el-button>
                <el-button text type="danger" @click="confirmDeleteCategory(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="还没有分类" :image-size="80" />
            </template>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 分类新增/编辑弹框：sort 留空则后端排到最后 -->
    <el-dialog v-model="catDialog.visible" :title="catDialog.id ? '编辑分类' : '新增分类'" width="420px">
      <el-form label-width="72px">
        <el-form-item label="名称">
          <el-input v-model="catDialog.name" :maxlength="32" placeholder="分类名称（不超过 32 字）" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="catDialog.sort" :min="0" :max="9999" />
          <span class="muted sort-hint">升序展示，留空排最后</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="catDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="catSaving" @click="submitCategory">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预览弹框：fileUrl 是服务端拼好的受控地址（非预签名短链，翻页停留再久也不 404），
         但 <video src> 带不了登录头，必须先经 fetchMedia 取成 blob 才能播 -->
    <el-dialog v-model="preview.visible" :title="preview.title" width="640px" destroy-on-close>
      <video v-if="preview.url" class="player" :src="preview.url" controls preload="metadata" />
      <p v-else-if="preview.loading" class="muted">视频加载中…</p>
      <p v-else-if="preview.failed" class="muted">视频加载失败（文件被删或无权访问），可关闭后重试</p>
      <p v-else class="muted">该记录没有可播放的文件地址</p>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createVideoCategory,
  deleteVideo,
  deleteVideoCategory,
  fetchVideoCategories,
  fetchVideoPage,
  offlineVideo,
  onlineVideo,
  updateVideoCategory
} from '@/api/admin'
import { fetchMedia } from '@/api/request'
import { EMPTY_TEXT, formatBytes, formatDuration, formatListTime, formatNumber } from '@/utils/format'

/**
 * 视频管理列表（三期）。
 *
 * 列表口径由后端钉死为发布时间倒序，这里不放排序表头（与用户管理页同一条规矩：
 * 放一个点了没反应的排序箭头比不放更糟）。
 *
 * 关键词的匹配范围与用户页不同：按标题模糊，<b>纯数字时额外认上传者 ID</b>——
 * 这个语义是 im-live 的 VideoAdminSpiImpl 定的，占位提示照它写。
 *
 * 删除做成需要手敲确认的高危动作：逻辑删除后行整体隐身，一期没有回收站，
 * 想恢复只能进数据库，所以这里比「禁用账号」还多加一道 inputValidator。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ keyword: '', status: undefined, current: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const page = await fetchVideoPage({
      keyword: query.keyword || undefined,
      status: query.status,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
    loadCovers()
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果，不把列表清成空白
  } finally {
    loading.value = false
  }
}

function reload() {
  query.current = 1
  load()
}

const preview = reactive({ visible: false, title: '', url: '', loading: false, failed: false })
let previewSeq = 0

async function openPreview(row) {
  // 自增序号：快速连开两条时，先返回的那条不能覆盖后打开的那条
  const seq = ++previewSeq
  preview.title = `预览：${row.title || `#${row.id}`}`
  preview.url = ''
  preview.failed = false
  preview.visible = true
  if (!row.fileUrl) {
    return
  }
  preview.loading = true
  try {
    // 受控地址直接塞 <video src> 必 401（资源请求带不了登录头），先取成 blob；
    // 几十 MB 的视频不能用 axios 默认的 20s 超时，这里显式取消超时
    const src = await fetchMedia(row.fileUrl, { timeout: 0 })
    if (seq === previewSeq) {
      preview.url = src
      preview.failed = !src
    }
  } finally {
    if (seq === previewSeq) {
      preview.loading = false
    }
  }
}

/**
 * 封面地址映射（row.id -> object URL）。
 *
 * 后端回的是 /api/file/download/{fileId} 受控地址，<img src> 同样带不了登录头，
 * 直接塞只会裂图；与头像同一套路用 fetchMedia 取 blob，取回后响应式填充。
 * 失败不缓存（fetchMedia 内部保证），翻页回来还会重试。
 */
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

/** 用响应里的最新那一行就地替换（与用户页同一做法），避免整页重查丢滚动位置 */
function applyRow(row, updated) {
  if (updated) {
    Object.assign(row, updated)
  }
}

async function confirmOffline(row) {
  let reason
  try {
    const result = await ElMessageBox.prompt(
      `下架后「${row.title || `#${row.id}`}」在信息流不再展示，记录保留、可随时恢复上架；事由进服务端日志。`,
      '下架视频',
      {
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        inputPlaceholder: '选填：下架事由',
        type: 'warning'
      }
    )
    reason = result.value?.trim() || undefined
  } catch {
    return
  }
  try {
    const updated = await offlineVideo(row.id, reason)
    applyRow(row, updated)
    ElMessage.success('视频已下架')
  } catch {
    // 接口层已弹提示
  }
}

async function confirmOnline(row) {
  try {
    await ElMessageBox.confirm(
      `恢复上架「${row.title || `#${row.id}`}」，信息流重新可见。`,
      '恢复上架',
      { confirmButtonText: '确认上架', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const updated = await onlineVideo(row.id)
    applyRow(row, updated)
    ElMessage.success('视频已恢复上架')
  } catch {
    // 同上
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.title || `#${row.id}`}」是逻辑删除，一期没有回收站，删除后无法自助恢复。`,
      '删除视频',
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
    await deleteVideo(row.id)
    ElMessage.success('视频已删除')
    // 删除后这一行从列表里消失：整页重查比本地 splice 稳（后端排序可能受并发影响）
    load()
  } catch {
    // 同上
  }
}

/* ==================== 分类管理（控制栏可增删改） ==================== */

const activeTab = ref('videos')
const categories = ref([])
const catLoading = ref(false)
const catSaving = ref(false)
const catDialog = reactive({ visible: false, id: null, name: '', sort: undefined })

async function loadCategories() {
  catLoading.value = true
  try {
    categories.value = (await fetchVideoCategories()) || []
  } catch {
    // 提示已由 request.js 弹过；保留上一次结果
  } finally {
    catLoading.value = false
  }
}

function openCategoryCreate() {
  catDialog.id = null
  catDialog.name = ''
  catDialog.sort = undefined
  catDialog.visible = true
}

function openCategoryEdit(row) {
  catDialog.id = row.id
  catDialog.name = row.name
  catDialog.sort = row.sort
  catDialog.visible = true
}

async function submitCategory() {
  const name = catDialog.name.trim()
  if (!name) {
    ElMessage.warning('分类名称不能为空')
    return
  }
  catSaving.value = true
  try {
    if (catDialog.id) {
      await updateVideoCategory(catDialog.id, { name, sort: catDialog.sort })
      ElMessage.success('分类已保存')
    } else {
      await createVideoCategory(name, catDialog.sort)
      ElMessage.success('分类已新增')
    }
    catDialog.visible = false
    loadCategories()
  } catch {
    // 接口层已弹提示（如同名拒绝）
  } finally {
    catSaving.value = false
  }
}

async function toggleCategoryStatus(row, enabled) {
  try {
    const updated = await updateVideoCategory(row.id, { status: enabled ? 1 : 0 })
    Object.assign(row, updated)
    ElMessage.success(enabled ? '分类已启用' : '分类已停用')
  } catch {
    // 失败不本地改状态：:model-value 单向绑定，未更新即自动回弹
  }
}

async function confirmDeleteCategory(row) {
  try {
    await ElMessageBox.confirm(
      `删除分类「${row.name}」只让它从前端分类栏消失，已发布视频的分类文本不变。`,
      '删除分类',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteVideoCategory(row.id)
    ElMessage.success('分类已删除')
    loadCategories()
  } catch {
    // 同上
  }
}

onMounted(() => {
  load()
  loadCategories()
})
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

.videos__tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.cat-status-text {
  margin-left: 8px;
}

.sort-hint {
  margin-left: 8px;
}

.hint {
  margin-bottom: 16px;
}

.video-cell {
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

.video-text,
.user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.player {
  width: 100%;
}
</style>
