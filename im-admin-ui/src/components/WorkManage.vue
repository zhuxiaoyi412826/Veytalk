<template>
  <div class="work-manage">
    <el-alert
      v-if="perm && !auth.can(perm)"
      class="hint"
      type="error"
      :closable="false"
      title="当前账号没有这一屏的权限"
      :description="`缺少权限点 ${perm}，操作会被服务端打回（1003），请联系超级管理员开通。`"
    />

    <el-tabs v-model="activeTab" class="work-manage__tabs">
      <el-tab-pane :label="`${label}列表`" name="list">
        <div class="toolbar">
          <h2 class="page-title">{{ title }}</h2>
          <span class="grow" />
          <el-input
            v-model="query.keyword"
            :maxlength="30"
            clearable
            class="keyword"
            placeholder="标题关键词；纯数字也认作品 ID / 作者 ID"
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
          <el-button type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            <span>新建{{ label }}</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="info"
          title="发布即可见：这里没有审核环节，只有「下架 / 恢复上架 / 删除」三个处置动作"
          :description="`下架后 ${label} 板块的信息流不再展示该条，记录保留、随时可恢复；删除是逻辑删除且没有回收站。代建的作品归属记在你自己名下。`"
        />

        <el-card shadow="never">
          <el-table v-loading="loading" :data="rows">
            <el-table-column label="作品" min-width="260">
              <template #default="{ row }">
                <div class="work-cell">
                  <img v-if="thumbs[row.id]" class="cover" :src="thumbs[row.id]" alt="" />
                  <div v-else class="cover cover-empty"><el-icon><Picture /></el-icon></div>
                  <div class="work-text">
                    <span class="ellipsis">{{ row.title || '（无标题）' }}</span>
                    <span class="muted">#{{ row.id }} · {{ row.category || EMPTY_TEXT }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="作者" min-width="150">
              <template #default="{ row }">
                <div class="work-text">
                  <span class="ellipsis">{{ row.uploaderName || '已注销用户' }}</span>
                  <span class="muted">#{{ row.uploaderId }}</span>
                </div>
              </template>
            </el-table-column>
            <!-- 板块专有列：图片看张数、音乐看时长、小说看字数，都是列表卡片上展示的那个数 -->
            <el-table-column v-if="type === 'image'" label="张数" width="80">
              <template #default="{ row }">
                <span class="num">{{ row.imageCount || 0 }} 图</span>
              </template>
            </el-table-column>
            <el-table-column v-else-if="type === 'music'" label="时长" width="80">
              <template #default="{ row }">
                <span class="num">{{ formatDuration(row.duration) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-else label="字数" width="90">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.wordCount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="90">
              <template #default="{ row }">
                <span class="num">{{ row.size ? formatBytes(row.size) : EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" :label="countWord" width="80">
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
            <el-table-column fixed="right" label="操作" width="230">
              <template #default="{ row }">
                <el-button text type="primary" @click="openPreview(row)">查看</el-button>
                <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button v-if="row.status === 1" text type="danger" @click="confirmOffline(row)">下架</el-button>
                <el-button v-else text type="success" @click="confirmOnline(row)">上架</el-button>
                <el-button text type="danger" @click="confirmDelete(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="`没有匹配的${label}`" :image-size="80" />
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
          <span class="muted">
            自定义{{ label }}板块顶部分类栏：增删改、排序（改 sort）、启停。停用/删除只影响展示与新的选择，
            不动历史作品已落库的分类文本。
          </span>
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

    <!-- 新建 / 编辑弹窗：三板块共用一个表单，按 type 只换媒体那一块 -->
    <el-dialog
      v-model="editor.visible"
      :title="`${editor.id ? '编辑' : '新建'}${label}`"
      :width="type === 'novel' ? '720px' : '560px'"
      top="6vh"
      :close-on-click-modal="false"
    >
      <el-form label-width="72px">
        <el-form-item label="标题">
          <el-input v-model="editor.title" :maxlength="60" show-word-limit placeholder="留空则服务端落「未命名」" />
        </el-form-item>
        <el-form-item label="分类">
          <!-- allow-create：分类列是自由文本，字典停用的旧分类还得能原样存回去 -->
          <el-select v-model="editor.category" filterable allow-create default-first-option placeholder="留空落「综合」">
            <el-option v-for="c in categories" :key="c.id" :value="c.name" :label="c.name" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="type === 'image'" label="图集">
          <div class="album">
            <div v-for="(item, i) in editor.album" :key="`${item.fileId || 'new'}-${i}`" class="album__cell">
              <img :src="item.src" alt="" />
              <span v-if="i === 0" class="album__tag">封面</span>
              <span class="album__del" title="移除" @click="removeAlbum(i)">✕</span>
            </div>
            <div class="album__cell album__add" @click="albumInput && albumInput.click()">
              <el-icon :size="20"><Plus /></el-icon>
            </div>
            <input ref="albumInput" type="file" accept="image/*" multiple hidden @change="onAlbumPick" />
          </div>
          <p class="muted field-hint">1~9 张，顺序即展示顺序，首图作封面。编辑时不增删图片就不改图集。</p>
        </el-form-item>

        <el-form-item v-else-if="type === 'music'" label="音频">
          <div class="row">
            <el-button @click="audioInput && audioInput.click()">
              {{ editor.audio?.file ? '重新选择' : (editor.id ? '更换音频' : '选择音频') }}
            </el-button>
            <span class="muted ellipsis">{{ audioText }}</span>
            <input ref="audioInput" type="file" accept="audio/*" hidden @change="onAudioPick" />
          </div>
          <p class="muted field-hint">
            mp3 / wav / flac / aac / m4a / ogg / opus / amr；时长由浏览器读出后随表单上报。新建必传，编辑不换则保持原样。
          </p>
        </el-form-item>

        <template v-else>
          <el-form-item label="摘要">
            <el-input
              v-model="editor.summary"
              type="textarea"
              :rows="2"
              :maxlength="255"
              show-word-limit
              resize="none"
              placeholder="留空则由服务端从正文截取前 80 字"
            />
          </el-form-item>
          <el-form-item label="正文">
            <el-input
              v-model="editor.content"
              type="textarea"
              :autosize="{ minRows: 12, maxRows: 20 }"
              :placeholder="`Markdown 正文，上限 ${CONTENT_MAX} 字符（长篇请分篇发布）`"
            />
          </el-form-item>
        </template>

        <el-form-item label="封面">
          <div class="row">
            <el-button @click="coverInput && coverInput.click()">
              {{ editor.coverPick?.file ? '重新选择' : (editor.coverRaw ? '更换封面' : '上传封面') }}
            </el-button>
            <img v-if="editor.coverPick?.src" class="cover cover-thumb" :src="editor.coverPick.src" alt="" />
            <span v-else-if="editor.coverRaw" class="muted">已有封面</span>
            <el-button v-if="editor.coverPick || editor.coverRaw" text type="danger" @click="clearCover">移除封面</el-button>
            <input ref="coverInput" type="file" accept="image/*" hidden @change="onCoverPick" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <span v-if="editor.hint" class="muted saving-hint">{{ editor.hint }}</span>
        <el-button @click="editor.visible = false">取消</el-button>
        <el-button type="primary" :loading="editor.saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分类新增/编辑弹窗：sort 留空则后端排到最后 -->
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

    <!-- 查看弹窗：图片看整组、音乐可试听、小说渲成 HTML 核对 -->
    <el-dialog v-model="viewer.visible" :title="viewer.title" :width="type === 'image' ? '760px' : '640px'" destroy-on-close>
      <div v-loading="viewer.loading" class="viewer">
        <template v-if="type === 'image'">
          <div v-for="(src, i) in viewer.images" :key="i" class="viewer__image">
            <img v-if="src" :src="src" alt="" />
            <p v-else class="muted">第 {{ i + 1 }} 张加载失败（文件被删或无权访问）</p>
          </div>
          <p v-if="!viewer.images.length && !viewer.loading" class="muted">该记录没有可展示的图集</p>
        </template>
        <template v-else-if="type === 'music'">
          <audio v-if="viewer.url" class="player" :src="viewer.url" controls preload="metadata" />
          <p v-else-if="!viewer.loading" class="muted">音频加载失败或该记录没有文件地址</p>
        </template>
        <template v-else>
          <div class="markdown-body" v-html="viewer.html"></div>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import {
  createWork,
  createWorkCategory,
  deleteWork,
  deleteWorkCategory,
  fetchWorkCategories,
  fetchWorkDetail,
  fetchWorkPage,
  offlineWork,
  onlineWork,
  updateWork,
  updateWorkCategory,
  uploadWorkFile
} from '@/api/admin'
import { fetchMedia } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import { EMPTY_TEXT, formatBytes, formatDuration, formatListTime, formatNumber } from '@/utils/format'

/**
 * 直播页「图片 / 音乐 / 小说」三板块共用的后台管理屏。
 *
 * 一份组件吃三个板块：三张表的列口径已对齐、后端一组端点按 {type} 分发、DTO 也只有一个，
 * 所以这里只按 props.type 换「专有列 + 媒体表单项」，其余（筛选、上下架、删除、分类字典）
 * 三份同构的代码本来就该长成一份。薄壳页面见 ImagesView / MusicsView / NovelsView。
 *
 * 与「视频管理」的分工：视频仍走 /api/admin/video/**，那一屏一行不动；
 * 这里的 type 传 video 会被后端守卫拒（400），这是路由归属而不是权限问题。
 *
 * 编辑表单刻意是「只改动要改的」：AdminWorkUpdateDTO 的 null 一律表示不改，
 * 所以换标题时不会顺手把图集重传一遍，也不会因为没填封面就把作者的封面清空
 * （清空要显式点「移除封面」，那时才传 coverFileId=0）。
 */
const props = defineProps({
  /** 板块码：image / music / novel，决定接口路径与表单形态 */
  type: { type: String, required: true },
  /** 页面标题，同时用来拼按钮文案 */
  title: { type: String, default: '作品管理' },
  /** 本屏对应的权限点，仅用于进错页面时给一句人话提示（真正的拦截在服务端） */
  perm: { type: String, default: '' }
})

const auth = useAuthStore()
const LABELS = { image: '图片', music: '音乐', novel: '小说' }
const COUNT_WORDS = { image: '打开', music: '播放', novel: '阅读' }
/** 与后端 LiveNovel.CONTENT_MAX 对齐，超限服务端会拒而不是截断 */
const CONTENT_MAX = 200000
const ALBUM_MAX = 9

const label = computed(() => LABELS[props.type] || '作品')
const countWord = computed(() => COUNT_WORDS[props.type] || '计数')

const activeTab = ref('list')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', status: undefined, current: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const page = await fetchWorkPage(props.type, {
      keyword: query.keyword || undefined,
      status: query.status,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
    loadThumbs()
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

/**
 * 封面缩略图（row.id -> object URL）。
 *
 * 后端回的是 /api/file/download/{fileId} 受控地址，<img src> 带不了登录头，直接塞只会裂图，
 * 与视频/文章页同一套路用 fetchMedia 取 blob。图片板块的 coverUrl 恒等于首图，
 * 所以三个板块这里取的是同一个字段。
 */
const thumbs = reactive({})

function loadThumbs() {
  rows.value.forEach((row) => {
    if (row.coverUrl && !thumbs[row.id]) {
      fetchMedia(row.coverUrl).then((src) => {
        thumbs[row.id] = src
      })
    }
  })
}

/** 用响应里的最新那一行就地替换，避免整页重查丢滚动位置 */
function applyRow(row, updated) {
  if (updated) {
    Object.assign(row, updated)
    if (updated.coverUrl && !thumbs[row.id]) {
      fetchMedia(updated.coverUrl).then((src) => {
        thumbs[row.id] = src
      })
    }
  }
}

async function confirmOffline(row) {
  let reason
  try {
    const result = await ElMessageBox.prompt(
      `下架后「${row.title || `#${row.id}`}」在${label}板块不再展示，记录保留、可随时恢复上架；事由进服务端日志。`,
      `下架${label}`,
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
    const updated = await offlineWork(props.type, row.id, reason)
    applyRow(row, updated)
    ElMessage.success(`${label}已下架`)
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
    const updated = await onlineWork(props.type, row.id)
    applyRow(row, updated)
    ElMessage.success(`${label}已恢复上架`)
  } catch {
    // 同上
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.title || `#${row.id}`}」是逻辑删除，没有回收站，删除后无法自助恢复（im_file 里的媒体本体一期也不级联清理）。`,
      `删除${label}`,
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
    await deleteWork(props.type, row.id)
    ElMessage.success(`${label}已删除`)
    // 删除后这一行整体隐身：整页重查比本地 splice 稳（后端排序可能受并发影响）
    load()
  } catch {
    // 同上
  }
}

/* ==================== 新建 / 编辑 ==================== */
const editor = reactive({
  visible: false,
  saving: false,
  hint: '',
  id: null,
  title: '',
  category: '',
  summary: '',
  content: '',
  /** 图集条目：{ fileId, src } 已上传 | { file, src } 新选待传 */
  album: [],
  /** 音频本体：{ file } 新选 | { fileId } 已上传（编辑不换时只是占位） */
  audio: null,
  /** 新选的封面文件；coverRaw 表示「服务端已有封面」，两者都没了才算移除 */
  coverPick: null,
  coverRaw: '',
  /** 点过「移除封面」的标记：提交时只有它为 true 才传 coverFileId=0 */
  coverRemoved: false,
  /** 编辑期间的状态位：图集是否被改动过 */
  albumDirty: false,
  size: null,
  ext: '',
  contentType: '',
  duration: null
})
const albumInput = ref(null)
const audioInput = ref(null)
const coverInput = ref(null)
/** 本组件自己造的 objectURL，关弹窗时统一回收 */
const localUrls = new Set()

function localUrl(file) {
  const url = URL.createObjectURL(file)
  localUrls.add(url)
  return url
}

function releaseUrls() {
  localUrls.forEach((u) => URL.revokeObjectURL(u))
  localUrls.clear()
}

function resetEditor() {
  editor.id = null
  editor.title = ''
  editor.category = ''
  editor.summary = ''
  editor.content = ''
  editor.album = []
  editor.audio = null
  editor.coverPick = null
  editor.coverRaw = ''
  editor.coverRemoved = false
  editor.albumDirty = false
  editor.size = null
  editor.ext = ''
  editor.contentType = ''
  editor.duration = null
  editor.hint = ''
  releaseUrls()
}

function openCreate() {
  resetEditor()
  editor.visible = true
}

/**
 * 编辑回填：小说要走详情才拿得到正文（列表刻意不含 LONGTEXT），
 * 另两个板块列表那一行就够铺表单。
 */
async function openEdit(row) {
  resetEditor()
  try {
    const detail = props.type === 'novel' ? await fetchWorkDetail(props.type, row.id) : row
    editor.id = detail.id
    editor.title = detail.title || ''
    editor.category = detail.category || ''
    editor.summary = detail.summary || ''
    editor.content = detail.content || ''
    editor.coverRaw = detail.coverUrl || ''
    editor.size = detail.size ?? null
    editor.duration = detail.duration ?? null
    if (props.type === 'image') {
      const urls = detail.imageUrls || []
      // 已存在的图逐个取 blob 预览；受控地址直接塞 img 会裂
      editor.album = (detail.imageIds || []).map((fileId) => ({ fileId, src: '' }))
      // 必须经 editor.album[i] 这个代理写回：拿着 map 出来的原始对象赋值不会触发重渲染，blob 到位了图也不刷新
      editor.album.forEach((item, i) => {
        if (urls[i]) {
          fetchMedia(urls[i]).then((src) => {
            editor.album[i].src = src
          })
        }
      })
    } else if (props.type === 'music') {
      editor.audio = { fileId: null }
    }
    editor.visible = true
  } catch {
    // 详情拉不到（记录已被删等），错误提示已由 request.js 弹过
  }
}

const audioText = computed(() => {
  if (editor.audio?.file) {
    return editor.audio.file.name
  }
  if (editor.id) {
    return '不选择即保持原音频不变'
  }
  return '未选择音频文件'
})

function onAlbumPick(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  if (editor.album.length + files.length > ALBUM_MAX) {
    ElMessage.warning(`一个图集最多 ${ALBUM_MAX} 张，当前已选 ${editor.album.length} 张`)
    return
  }
  files.forEach((file) => editor.album.push({ file, src: localUrl(file) }))
  editor.albumDirty = true
  if (!editor.title) {
    editor.title = files[0].name.replace(/\.[^.]+$/, '').slice(0, 60)
  }
}

function removeAlbum(i) {
  editor.album.splice(i, 1)
  editor.albumDirty = true
}

function onAudioPick(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  editor.audio = { file }
  if (!editor.title) {
    editor.title = file.name.replace(/\.[^.]+$/, '').slice(0, 60)
  }
}

function onCoverPick(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  editor.coverPick = { file, src: localUrl(file) }
}

function clearCover() {
  editor.coverPick = null
  // 原封面还在的话，置空即代表「要移除」（提交时传 coverFileId=0）
  editor.coverRaw = ''
  editor.coverRemoved = true
}

/** 读音频时长：后台没有用户端那套 utils，这里就地实现一个最小版本 */
function readDuration(file) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const audio = document.createElement('audio')
    // preload 必须给 metadata，否则部分浏览器不读时长，loadedmetadata 永远不触发
    audio.preload = 'metadata'
    const finish = (value) => {
      URL.revokeObjectURL(url)
      resolve(value)
    }
    audio.onloadedmetadata = () => {
      const seconds = Number(audio.duration)
      finish(Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) : null)
    }
    audio.onerror = () => finish(null)
    audio.src = url
  })
}

/** 图集按顺序逐张上传：并发会打乱顺序，而「第几张」是这组的语义本身 */
async function uploadAlbum() {
  const ids = []
  const sizes = []
  let firstExt = ''
  let firstType = ''
  let uploaded = 0
  for (const item of editor.album) {
    if (item.fileId) {
      ids.push(item.fileId)
      continue
    }
    uploaded++
    editor.hint = `上传第 ${ids.length + 1} 张…`
    const vo = await uploadWorkFile(item.file, 'image_work')
    ids.push(vo.fileId)
    sizes.push(vo.size || 0)
    if (!firstExt) {
      firstExt = vo.ext || ''
      firstType = vo.contentType || ''
    }
  }
  if (!ids.length || ids.length > ALBUM_MAX) {
    throw new Error(`一个图集要 1~${ALBUM_MAX} 张`)
  }
  // 没新增图片时拿不到逐张字节数（库里只存合计），沿用原值比填 0 诚实
  const size = uploaded > 0 ? sizes.reduce((a, b) => a + b, 0) : editor.size || 0
  return { imageIds: ids, size, ext: firstExt || editor.ext, contentType: firstType || editor.contentType }
}

async function submit() {
  if (editor.saving) return
  const body = {}
  if (editor.title.trim()) body.title = editor.title.trim()
  if (editor.category) body.category = editor.category
  // 上传图集/音频可能要几十秒，先置位把保存按钮锁住，避免重复提交
  editor.saving = true
  try {
    if (props.type === 'image') {
      if (!editor.id || editor.albumDirty) {
        Object.assign(body, await uploadAlbum())
      }
    } else if (props.type === 'music') {
      if (editor.audio?.file) {
        editor.hint = '上传音频中…'
        const duration = await readDuration(editor.audio.file)
        const vo = await uploadWorkFile(editor.audio.file, 'music', duration)
        Object.assign(body, {
          fileId: vo.fileId,
          duration,
          size: vo.size,
          ext: vo.ext,
          contentType: vo.contentType
        })
      } else if (!editor.id) {
        throw new Error('请选择音频文件')
      }
    } else {
      if (!editor.content.trim()) {
        throw new Error('请填写正文')
      }
      if (editor.content.length > CONTENT_MAX) {
        throw new Error(`正文超过 ${CONTENT_MAX} 字符，长篇请分篇发布`)
      }
      body.content = editor.content
      body.summary = editor.summary.trim()
    }
    if (editor.coverPick?.file) {
      editor.hint = '上传封面中…'
      const scene = props.type === 'music' ? 'music_cover' : 'novel_cover'
      const vo = await uploadWorkFile(editor.coverPick.file, scene)
      body.coverFileId = vo.fileId
    } else if (!editor.coverPick && !editor.coverRaw && editor.coverRemoved) {
      // null 已被「不改」占用，所以「去掉封面」只能传 0
      body.coverFileId = 0
    }
    if (editor.id) {
      const updated = await updateWork(props.type, editor.id, body)
      ElMessage.success('已保存')
      const row = rows.value.find((x) => x.id === editor.id)
      if (row && updated) {
        applyRow(row, updated)
      }
    } else {
      await createWork(props.type, body)
      ElMessage.success('已发布')
      query.current = 1
      await load()
    }
    editor.visible = false
  } catch (err) {
    ElMessage.error(`保存失败：${(err && err.message) || '上传中断'}`)
  } finally {
    editor.saving = false
    editor.hint = ''
  }
}

/* ==================== 查看 ==================== */
const viewer = reactive({ visible: false, loading: false, title: '', url: '', images: [], html: '' })
let viewerSeq = 0

async function openPreview(row) {
  const seq = ++viewerSeq
  viewer.title = `查看：${row.title || `#${row.id}`}`
  viewer.url = ''
  viewer.images = []
  viewer.html = ''
  viewer.visible = true
  if (props.type === 'music') {
    if (!row.fileUrl) return
    viewer.loading = true
    try {
      // 几十 MB 的音频不能被 axios 默认的 20s 超时掐断，这里显式取消超时
      const src = await fetchMedia(row.fileUrl, { timeout: 0 })
      if (seq === viewerSeq) viewer.url = src
    } finally {
      if (seq === viewerSeq) viewer.loading = false
    }
    return
  }
  if (props.type === 'image') {
    viewer.loading = true
    try {
      const list = await Promise.all((row.imageUrls || []).map((u) => fetchMedia(u, { timeout: 0 })))
      if (seq === viewerSeq) viewer.images = list
    } finally {
      if (seq === viewerSeq) viewer.loading = false
    }
    return
  }
  // 小说正文不在列表里，点开才取
  viewer.loading = true
  try {
    const detail = await fetchWorkDetail(props.type, row.id)
    if (seq === viewerSeq) {
      viewer.html = detail?.content ? DOMPurify.sanitize(marked.parse(detail.content, { breaks: true, gfm: true })) : ''
    }
  } finally {
    if (seq === viewerSeq) viewer.loading = false
  }
}

/* ==================== 分类字典 ==================== */
const categories = ref([])
const catLoading = ref(false)
const catSaving = ref(false)
const catDialog = reactive({ visible: false, id: null, name: '', sort: undefined })

async function loadCategories() {
  catLoading.value = true
  try {
    categories.value = (await fetchWorkCategories(props.type)) || []
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
      const updated = await updateWorkCategory(props.type, catDialog.id, { name, sort: catDialog.sort })
      ElMessage.success('分类已保存')
      if (updated) {
        const row = categories.value.find((x) => x.id === catDialog.id)
        if (row) Object.assign(row, updated)
      }
    } else {
      await createWorkCategory(props.type, name, catDialog.sort)
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
    const updated = await updateWorkCategory(props.type, row.id, { status: enabled ? 1 : 0 })
    Object.assign(row, updated)
    ElMessage.success(enabled ? '分类已启用' : '分类已停用')
  } catch {
    // 失败不本地改状态：:model-value 单向绑定，未更新即自动回弹
  }
}

async function confirmDeleteCategory(row) {
  try {
    await ElMessageBox.confirm(
      `删除分类「${row.name}」只让它从前端分类栏消失，已发布${label}的分类文本不变。`,
      '删除分类',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteWorkCategory(props.type, row.id)
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

.work-manage__tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.cat-status-text,
.sort-hint,
.field-hint {
  margin-left: 8px;
}

.hint {
  margin-bottom: 16px;
}

.work-cell {
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

.cover-thumb {
  height: 48px;
  width: 72px;
}

.work-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.row {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.album {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.album__cell {
  border-radius: 4px;
  flex: none;
  height: 72px;
  overflow: hidden;
  position: relative;
  width: 72px;
}

.album__cell img {
  display: block;
  height: 100%;
  object-fit: cover;
  width: 100%;
}

.album__add {
  align-items: center;
  border: 1px dashed #c9cdd1;
  color: #86909c;
  cursor: pointer;
  display: flex;
  justify-content: center;
}

.album__add:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.album__tag {
  background: rgba(237, 100, 100, 0.85);
  border-radius: 0 0 4px 0;
  bottom: 0;
  color: #fff;
  font-size: 11px;
  left: 0;
  line-height: 15px;
  padding: 0 4px;
  position: absolute;
}

.album__del {
  background: rgba(0, 0, 0, 0.55);
  border-radius: 3px;
  color: #fff;
  cursor: pointer;
  font-size: 11px;
  line-height: 16px;
  padding: 0 3px;
  position: absolute;
  right: 2px;
  top: 2px;
}

.saving-hint {
  margin-right: 12px;
}

.player {
  width: 100%;
}

.viewer__image {
  margin-bottom: 12px;
}

.viewer__image img {
  display: block;
  max-height: 60vh;
  max-width: 100%;
}

/* 小说正文：与 ArticlesView 同口径的 scoped 排版（这个类不是全局样式，每个用到它的页面自带一份） */
.markdown-body {
  font-size: 14px;
  line-height: 1.8;
  max-height: 62vh;
  overflow-y: auto;
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  font-weight: 600;
  margin: 18px 0 10px;
}

.markdown-body :deep(p) {
  margin: 0 0 12px;
}

.markdown-body :deep(img) {
  max-width: 100%;
}

.markdown-body :deep(pre) {
  background: #f5f7fa;
  border-radius: 4px;
  overflow-x: auto;
  padding: 12px;
}

.markdown-body :deep(code) {
  font-family: Consolas, Monaco, monospace;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid #dcdfe6;
  color: #606266;
  margin: 0 0 12px;
  padding-left: 12px;
}
</style>
