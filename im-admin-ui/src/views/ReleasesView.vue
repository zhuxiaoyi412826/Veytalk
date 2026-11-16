<template>
  <div class="releases">
    <div class="toolbar">
      <h2 class="page-title">客户端版本管理</h2>
      <span class="grow" />
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新建版本</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="warning"
      title="「启用」就是把安装包递到公网上：官网下载页只展示并递出启用中的行"
      description="同一平台同时只能启用一个版本，启用新的会自动停用旧的（服务端同一事务完成，官网不会出现空窗）。停用等于该平台下载卡立刻消失，是「下线一个坏包」的止损动作。删除只允许停用态，删行不删存储字节。"
    />

    <div class="filters">
      <el-input
        v-model="query.keyword"
        :maxlength="50"
        class="keyword"
        clearable
        placeholder="版本号 / 文件名关键词"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.platform" class="platform" clearable placeholder="全部平台" @change="reload">
        <el-option v-for="p in platforms" :key="p.code" :value="p.code" :label="p.name" />
      </el-select>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="1" label="启用中" />
        <el-option :value="0" label="已停用" />
      </el-select>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
      <span class="muted">启用中的恒排最前，其余按创建时间倒序</span>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="平台" width="150">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.platformName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本号" width="110">
          <template #default="{ row }">
            <span class="num">{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="安装包" min-width="240">
          <template #default="{ row }">
            <div class="file-cell">
              <span class="ellipsis">{{ row.fileName }}</span>
              <span class="muted">{{ formatBytes(row.fileSize) }}</span>
              <!-- 文件行被人工清理过时据实提示：这一行启用会被服务端拒绝，先在这里露出来 -->
              <el-tag v-if="!row.filePresent" type="danger" effect="plain" size="small">文件已丢失</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="更新说明" min-width="180">
          <template #default="{ row }">
            <span class="ellipsis muted">{{ row.releaseNote || EMPTY_TEXT }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下载次数" width="90">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.downloadCount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="120">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status !== 1" link type="success" @click="confirmEnable(row)">启用</el-button>
              <el-button v-else link type="warning" @click="confirmDisable(row)">停用</el-button>
              <el-button v-if="row.status !== 1" link type="danger" @click="confirmDelete(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有登记任何版本" :image-size="80" />
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          @size-change="reload"
          @current-change="load"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.id ? `编辑版本：${dialog.platformName} ${dialog.version}` : '新建版本'"
      width="640px"
      :close-on-click-backdrop="false"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
    >
      <el-form label-position="top">
        <el-form-item label="平台（必填，创建后不可改）">
          <!-- 编辑时锁死：平台是启用互斥的分组键，改平台等于删旧建新，后端也忽略这个字段 -->
          <el-select v-model="form.platform" :disabled="!!dialog.id || uploading" class="platform" placeholder="选择平台">
            <el-option v-for="p in platforms" :key="p.code" :value="p.code" :label="p.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号（必填，最多 32 字符，同平台不可重复）">
          <el-input v-model="form.version" :disabled="uploading" :maxlength="32" placeholder="如 1.0.1" />
        </el-form-item>
        <el-form-item label="更新说明（可留空，最多 500 字）">
          <el-input
            v-model="form.releaseNote"
            :disabled="uploading"
            :maxlength="500"
            :rows="3"
            show-word-limit
            type="textarea"
            placeholder="官网下载卡上原样展示这一段"
          />
        </el-form-item>
        <el-form-item label="安装包（必填，先上传再保存）">
          <div class="upload-box">
            <input
              ref="fileInput"
              accept=".exe,.msi,.zip,.apk,.dmg,.pkg,.deb,.rpm,.appx,.msix,.tar,.gz,.tgz"
              class="file-input"
              type="file"
              :disabled="uploading"
              @change="onFileChosen"
            />
            <div v-if="form.fileId" class="chosen">
              <el-icon><Document /></el-icon>
              <span class="ellipsis">{{ form.fileName }}</span>
              <span class="muted">{{ formatBytes(form.fileSize) }}</span>
              <el-button v-if="!uploading" link type="primary" @click="pickFile">重新选择</el-button>
            </div>
            <el-button v-else :loading="uploading" @click="pickFile">
              <el-icon><UploadFilled /></el-icon>
              <span>选择安装包</span>
            </el-button>
            <div v-if="uploading" class="progress">
              <el-progress :percentage="upload.percent" :stroke-width="10" />
              <span class="muted">{{ upload.stageText }}</span>
            </div>
            <div class="muted upload-note">
              大文件自动走分片上传（2GB 上限、秒传、断点续传）；上传中请勿关闭弹窗
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="!dialog.id">
          <el-checkbox v-model="form.enableNow" :disabled="uploading">
            保存后立即启用（同平台原启用版本会被自动停用，官网下载页即刻换成本包）
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="dialog.saving || uploading" @click="dialog.visible = false">取消</el-button>
        <el-button :disabled="!formReady || dialog.saving || uploading" :loading="dialog.saving" type="primary" @click="submit">
          {{ dialog.id ? '保存修改' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createRelease,
  deleteRelease,
  disableRelease,
  enableRelease,
  fetchReleaseDetail,
  fetchReleasePage,
  fetchReleasePlatforms,
  updateRelease
} from '@/api/admin'
import { uploadRelease } from '@/utils/releaseUpload'
import { EMPTY_TEXT, formatBytes, formatListTime, formatNumber } from '@/utils/format'

/**
 * 客户端版本管理。
 *
 * 上传与登记是两步：先经分片上传通道拿到 fileId（进度条走 hash/upload/merge 三个阶段），
 * 再随表单一起提交登记。两步合成一个「大按钮」看着顺，但一个 2GB 的包传到 90% 时
 * 发现版本号填错，合成式交互只能整个重来；分开则上传结果留在弹窗里，改完字段直接保存。
 *
 * 启用/停用/删除都先 ElMessageBox 确认再调接口，且以服务端回来的结果重新拉列表：
 * 这一屏可能开着好几个管理员，两秒前的状态可能刚被同事改过。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const platforms = ref([])

const query = reactive({ keyword: '', platform: undefined, status: undefined, current: 1, size: 20 })

const dialog = reactive({ visible: false, saving: false, id: null, version: '', platformName: '' })
const form = reactive({
  platform: '',
  version: '',
  releaseNote: '',
  fileId: null,
  fileName: '',
  fileSize: 0,
  enableNow: false
})

const uploading = ref(false)
const upload = reactive({ percent: 0, stageText: '' })
const fileInput = ref(null)

const formReady = computed(
  () => form.platform !== '' && form.version.trim() !== '' && form.fileId != null
)

const STAGE_TEXT = {
  hash: '正在计算文件 MD5（秒传判定）…',
  upload: '正在上传分片…',
  merge: '服务端正在合并校验…',
  instant: '秒传命中，无需上传',
  done: '上传完成'
}

async function load() {
  loading.value = true
  try {
    const page = await fetchReleasePage({
      keyword: query.keyword || undefined,
      platform: query.platform || undefined,
      status: query.status,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
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

async function loadPlatforms() {
  try {
    platforms.value = await fetchReleasePlatforms()
  } catch {
    // 码表拉不到时下拉是空的，但列表仍可看（platformName 由服务端带回），不阻断整屏
  }
}

function platformName(code) {
  return platforms.value.find((p) => p.code === code)?.name || code
}

function resetForm() {
  form.platform = ''
  form.version = ''
  form.releaseNote = ''
  form.fileId = null
  form.fileName = ''
  form.fileSize = 0
  form.enableNow = false
  upload.percent = 0
  upload.stageText = ''
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

function openCreate() {
  dialog.id = null
  dialog.version = ''
  dialog.platformName = ''
  resetForm()
  dialog.visible = true
}

/** 编辑前回查详情：拿到最新的 fileId 与「这一行现在什么状态」，不拿两秒前的列表行去填表 */
async function openEdit(row) {
  let vo
  try {
    vo = await fetchReleaseDetail(row.id)
  } catch {
    return
  }
  dialog.id = vo.id
  dialog.version = vo.version
  dialog.platformName = vo.platformName
  resetForm()
  form.platform = vo.platform
  form.version = vo.version
  form.releaseNote = vo.releaseNote || ''
  form.fileId = vo.fileId
  form.fileName = vo.fileName
  form.fileSize = vo.fileSize
  dialog.visible = true
}

function pickFile() {
  fileInput.value && fileInput.value.click()
}

async function onFileChosen(event) {
  const file = event.target.files && event.target.files[0]
  if (!file) {
    return
  }
  uploading.value = true
  upload.percent = 0
  upload.stageText = STAGE_TEXT.hash
  try {
    const vo = await uploadRelease(file, (percent, stage, chunkInfo) => {
      upload.percent = percent
      upload.stageText = stage === 'upload' && chunkInfo
        ? `${STAGE_TEXT.upload}（${chunkInfo.loaded}/${chunkInfo.total} 片）`
        : STAGE_TEXT[stage] || ''
    })
    // 登记要的是 fileId；FileVO 与 im_file 同一套字段名（fileId/originalName/size）
    form.fileId = vo.fileId
    form.fileName = vo.originalName || file.name
    form.fileSize = Number(vo.size) || file.size
    ElMessage.success('安装包上传完成，保存后生效')
  } catch {
    // 失败原因 request.js 已弹；清掉 input 的 value 让同一个文件可以立刻重选重试
    event.target.value = ''
  } finally {
    uploading.value = false
  }
}

async function submit() {
  if (!formReady.value) {
    return
  }
  dialog.saving = true
  const dto = {
    platform: form.platform,
    version: form.version.trim(),
    releaseNote: form.releaseNote.trim(),
    fileId: form.fileId
  }
  try {
    if (dialog.id) {
      await updateRelease(dialog.id, dto)
    } else {
      await createRelease({ ...dto, enableNow: form.enableNow })
    }
    dialog.visible = false
    reload()
  } catch {
    // 接口失败：弹窗留着，request.js 已经报过原因，改完可以直接再点
  } finally {
    dialog.saving = false
  }
}

async function confirmEnable(row) {
  const current = rows.value.find((r) => r.platform === row.platform && r.status === 1 && r.id !== row.id)
  const extra = current ? `该平台当前启用的「${current.version}」会被自动停用。` : ''
  try {
    await ElMessageBox.confirm(
      `启用后官网下载页的「${row.platformName}」入口立即换成这个包，任何访客都能匿名下载。${extra}`,
      `确认启用 ${row.platformName} ${row.version}？`,
      { type: 'warning', confirmButtonText: '确认启用', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  try {
    await enableRelease(row.id)
    load()
  } catch {
    // 已提示
  }
}

async function confirmDisable(row) {
  try {
    await ElMessageBox.confirm(
      '停用后官网下载页该平台没有下载入口（卡片消失），直到启用新的版本为止。',
      `确认停用 ${row.platformName} ${row.version}？`,
      { type: 'warning', confirmButtonText: '确认停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await disableRelease(row.id)
    load()
  } catch {
    // 已提示
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      '删除是物理删行，没有回收站；存储里的安装包字节不随删（可能被别的版本秒传复用）。',
      `确认删除 ${row.platformName} ${row.version}？`,
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteRelease(row.id)
    // 删掉的是本页最后一条时让页码往回退一格，否则停在一个空页上
    if (rows.value.length === 1 && query.current > 1) {
      query.current -= 1
    }
    load()
  } catch {
    // 已提示
  }
}

onMounted(() => {
  loadPlatforms()
  load()
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.hint {
  margin-bottom: 16px;
}

.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}

.keyword {
  width: 240px;
}

.platform {
  width: 180px;
}

.status {
  width: 140px;
}

.file-cell {
  align-items: center;
  display: flex;
  gap: 8px;
}

.upload-box {
  width: 100%;
}

/* 原生 input 藏起来而不是用 el-upload：上传逻辑在 releaseUpload.js 里自己管
   （分片、秒传、重试），el-upload 的自动上传与文件列表在这里全是干扰 */
.file-input {
  display: none;
}

.chosen {
  align-items: center;
  display: flex;
  gap: 8px;
}

.progress {
  align-items: center;
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

.progress .el-progress {
  flex: 1;
}

.upload-note {
  margin-top: 4px;
}
</style>
