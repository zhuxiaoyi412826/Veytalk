<template>
  <div class="announce">
    <div class="toolbar">
      <h2 class="page-title">全服公告</h2>
      <span class="grow" />
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新建公告</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="warning"
      title="「发布」是对着所有在线连接喊话，弹出去的那一条收不回来"
      description="撤回只让之后登录的人补拉不到它，已经显示在别人屏幕上的通知不会消失。要改一条已发布的公告，只能撤回旧的再发一条新的，两条都留在这一页里当台账。标题与正文入库前都会先过后台自己的敏感词库，所以管理员看到的花名册里的星号不是显示坏了。"
    />

    <div class="filters">
      <el-input
        v-model="query.keyword"
        :maxlength="50"
        class="keyword"
        clearable
        placeholder="标题 / 正文关键词"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="0" label="草稿" />
        <el-option :value="1" label="已发布" />
        <el-option :value="2" label="已撤回" />
      </el-select>
      <el-button :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span>查询</span>
      </el-button>
      <span class="muted">列表固定按创建时间倒序，接口不提供排序参数</span>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <!-- 展开行装正文：2000 字的公告塞不进 tooltip，而每一行都可能想看清全文与当时是谁动的它 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail">
              <div class="detail-content">{{ row.content }}</div>
              <div class="detail-meta">
                <span>状态：{{ row.statusName }}</span>
                <span>发布：{{ formatDateTime(row.publishTime) }}</span>
                <span>失效：{{ row.expireTime ? formatDateTime(row.expireTime) : '不失效' }}</span>
                <span>创建：{{ formatDateTime(row.createTime) }}</span>
                <span>最后修改人：#{{ row.updateBy || EMPTY_TEXT }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="200">
          <template #default="{ row }">
            <span class="ellipsis">{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="正文" min-width="260">
          <template #default="{ row }">
            <span class="ellipsis muted">{{ oneLine(row.content) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="样式" width="90">
          <template #default="{ row }">
            <el-tag :type="toneTag(row.tone)" effect="plain" size="small">{{ row.tone }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="announcementStatusTag(row.status)" effect="plain" size="small">
              {{ row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布" width="120">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.publishTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失效" width="120">
          <template #default="{ row }">
            <span class="num">{{ row.expireTime ? formatListTime(row.expireTime) : '不失效' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <div class="row-actions">
              <template v-if="row.status === 0">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="success" @click="confirmPublish(row)">发布</el-button>
                <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
              </template>
              <el-button v-else-if="row.status === 1" link type="warning" @click="confirmRevoke(row)">
                撤回
              </el-button>
              <!-- 已撤回的行没有下一步，但空白单元格会被读成「渲染坏了」，给一句说明 -->
              <span v-else class="muted">已留档</span>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有公告" :image-size="80" />
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
      :title="dialog.id ? `编辑草稿：${dialog.name}` : '新建公告'"
      width="640px"
      :close-on-click-backdrop="false"
    >
      <el-form label-position="top">
        <el-form-item label="标题（必填，最多 100 字）">
          <el-input
            v-model="form.title"
            :maxlength="100"
            show-word-limit
            placeholder="一句话说清是什么事，它同时也是客户端通知的标题"
          />
        </el-form-item>
        <el-form-item label="正文（必填，最多 2000 字）">
          <el-input
            v-model="form.content"
            :maxlength="2000"
            :rows="8"
            show-word-limit
            type="textarea"
            placeholder="换行会原样保留。这一条会推给所有在线连接，发出后只能撤回、不能修改"
          />
        </el-form-item>
        <el-form-item label="通知样式">
          <el-select v-model="form.tone" class="tone">
            <el-option value="info" label="info · 普通通知" />
            <el-option value="success" label="success · 好消息" />
            <el-option value="warning" label="warning · 需要注意" />
            <el-option value="error" label="error · 故障与事故" />
          </el-select>
          <div class="muted tone-note">
            只决定客户端那条通知的颜色，不影响送达范围；留空后端按 info 处理
          </div>
        </el-form-item>
        <el-form-item label="失效时间（可留空）">
          <el-date-picker
            v-model="form.expireTime"
            :disabled-date="disabledPastDate"
            class="expire"
            placeholder="留空表示不失效"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
          <div class="muted tone-note">
            只管「之后登录的人还能不能补拉到」：到点后补拉不再返回它，但已经弹出去的通知不受影响
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="dialog.saving" @click="dialog.visible = false">取消</el-button>
        <el-button :disabled="!formReady || dialog.saving" :loading="dialog.saving" @click="submit(false)">
          {{ dialog.id ? '保存修改' : '存为草稿' }}
        </el-button>
        <el-button :disabled="!formReady || dialog.saving" type="primary" @click="submit(true)">
          {{ dialog.id ? '保存并发布' : '确认发布' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAnnouncement,
  deleteAnnouncement,
  fetchAnnouncementDetail,
  fetchAnnouncementPage,
  publishAnnouncement,
  revokeAnnouncement,
  updateAnnouncement
} from '@/api/admin'
import { EMPTY_TEXT, announcementStatusTag, formatDateTime, formatListTime } from '@/utils/format'

/**
 * 全服公告管理（四期第四批）。
 *
 * 状态机在这里是看得见的：草稿给「编辑 / 发布 / 删除」，已发布只给「撤回」，已撤回什么都不给。
 * 这不是界面偷懒——后端本来就只允许这三条边（已发布不可编辑、已撤回不可再发布），
 * 把按钮铺满再让接口回一句 14012 只会让管理员以为是自己点错了。
 *
 * 每一次写动作都以服务端回来的那行为准并重新拉列表：这一页可能开着好几个管理员，
 * 两秒前的草稿可能刚被同事发布掉。
 */
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ keyword: '', status: undefined, current: 1, size: 20 })

const dialog = reactive({ visible: false, saving: false, id: null, name: '' })
const form = reactive({ title: '', content: '', tone: 'info', expireTime: '' })

const formReady = computed(() => form.title.trim() !== '' && form.content.trim() !== '')

async function load() {
  loading.value = true
  try {
    const page = await fetchAnnouncementPage({
      keyword: query.keyword || undefined,
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

function oneLine(content) {
  return String(content || '').replace(/\s+/g, ' ').trim()
}

/** tone 的四个取值里只有 error 在 Element Plus 里叫 danger，其余原样就是标签类型 */
function toneTag(tone) {
  return tone === 'error' ? 'danger' : tone || 'info'
}

function disabledPastDate(date) {
  return dayjs(date).isBefore(dayjs().startOf('day'))
}

function openCreate() {
  dialog.id = null
  dialog.name = ''
  form.title = ''
  form.content = ''
  form.tone = 'info'
  form.expireTime = ''
  dialog.visible = true
}

/**
 * 编辑前重新取一次详情。
 *
 * 列表行本身带着完整的 content，够用；仍然回查一次是因为「这条还是不是草稿」
 * 只有当场问过才知道，而按两秒前的行去 PUT 一条已被发布的公告，
 * 后端会拒绝（14012）——但那时弹框已经开着，管理员已经改了几个字。
 */
async function openEdit(row) {
  let vo
  try {
    vo = await fetchAnnouncementDetail(row.id)
  } catch {
    return
  }
  if (vo.status !== 0) {
    ElMessage.warning(`这条公告已是「${vo.statusName}」，不能再编辑`)
    load()
    return
  }
  dialog.id = vo.id
  dialog.name = vo.title
  form.title = vo.title
  form.content = vo.content
  form.tone = vo.tone || 'info'
  // 服务端回来的串可能带小数秒，而 value-format 是秒精度；先过一道 dayjs 归一，
  // 否则选择框拿到解析不了的初值会显示成空，管理员以为失效时间被清掉了
  form.expireTime = vo.expireTime ? dayjs(vo.expireTime).format('YYYY-MM-DDTHH:mm:ss') : ''
  dialog.visible = true
}

function payload() {
  return {
    title: form.title.trim(),
    content: form.content.trim(),
    tone: form.tone,
    // 空串会被 Jackson 当成时间文本拒掉，留空就显式送 null
    expireTime: form.expireTime || null
  }
}

/**
 * 保存，可选顺手发布。
 *
 * 新建时「存为草稿」与「确认发布」都是一次调用（body 里的 publishNow），
 * 而编辑必须两步：后端的 PUT 只改草稿内容、不认 publishNow（状态迁移走专门端点）。
 * 中间那步失败时草稿内容已经保住了，所以要单独说一句清楚，
 * 别让管理员以为「保存并发布」跟新建一样是一次原子动作。
 */
async function submit(publish) {
  if (!formReady.value) {
    return
  }
  dialog.saving = true
  try {
    if (dialog.id) {
      const vo = await updateAnnouncement(dialog.id, payload())
      if (publish) {
        try {
          await publishAnnouncement(vo.id)
        } catch {
          load()
          return
        }
      }
    } else {
      await createAnnouncement({ ...payload(), publishNow: publish })
    }
    dialog.visible = false
    reload()
  } catch {
    // 接口失败：弹框留着，request.js 已经报过原因，改完可以直接再点
  } finally {
    dialog.saving = false
  }
}

async function confirmPublish(row) {
  try {
    await ElMessageBox.confirm(
      `「${row.title}」将立即推送给所有在线连接。弹出去的通知收不回来——要改内容只能撤回后重发。`,
      '确认发布这条公告？',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  try {
    await publishAnnouncement(row.id)
    load()
  } catch {
    // 已提示
  }
}

async function confirmRevoke(row) {
  try {
    await ElMessageBox.confirm(
      '撤回后，之后登录的用户补拉不到这条公告；已经弹在别人屏幕上的那一条收不回来。',
      `确认撤回「${row.title}」？`,
      { type: 'warning', confirmButtonText: '确认撤回', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await revokeAnnouncement(row.id)
    load()
  } catch {
    // 已提示
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      '删除草稿是物理删行，没有回收站。已发布过的公告不允许删除，只能撤回留档。',
      `确认删除草稿「${row.title}」？`,
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteAnnouncement(row.id)
    // 删掉的是本页最后一条时让页码往回退一格，否则停在一个空页上
    if (rows.value.length === 1 && query.current > 1) {
      query.current -= 1
    }
    load()
  } catch {
    // 已提示
  }
}

onMounted(load)
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

.status {
  width: 140px;
}

.tone {
  width: 200px;
}

.expire {
  width: 240px;
}

.tone-note {
  margin-top: 4px;
}

.detail {
  padding: 4px 48px 12px;
}

.detail-content {
  /* 正文里的换行是管理员打上去的，这里不能当成排版空白吃掉 */
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-meta {
  border-top: 1px dashed #dcdfe6;
  color: #909399;
  display: flex;
  flex-wrap: wrap;
  font-size: 12px;
  gap: 16px;
  margin-top: 10px;
  padding-top: 8px;
}
</style>
