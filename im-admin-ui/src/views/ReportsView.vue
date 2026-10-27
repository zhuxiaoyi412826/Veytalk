<template>
  <div class="reports">
    <div class="toolbar">
      <h2 class="page-title">举报工单</h2>
      <span class="grow" />
      <el-input
        v-model="query.keyword"
        :maxlength="30"
        clearable
        class="keyword"
        placeholder="对象 ID / 用户 ID，或描述关键词"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.status" class="pick" clearable placeholder="全部状态" @change="reload">
        <el-option :value="0" label="待处理" />
        <el-option :value="1" label="已受理" />
        <el-option :value="2" label="已驳回" />
        <el-option :value="3" label="已处置" />
      </el-select>
      <el-select v-model="query.targetType" class="pick" clearable placeholder="全部对象" @change="reload">
        <el-option v-for="item in config?.targetTypes || []" :key="item.code" :label="item.name" :value="item.code" />
      </el-select>
      <el-select v-model="query.reasonCode" class="pick" clearable placeholder="全部理由" @change="reload">
        <el-option v-for="item in config?.reasons || []" :key="item.code" :label="item.name" :value="item.code" />
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
      title="证据是提交那一刻抓的快照"
      description="消息后来被撤回、直播后来关播、群后来解散都不影响处置：判案看的存下来的这份证据，不是现在的对象。关键词是纯数字时按「对象 ID / 被举报人 ID / 举报人 ID」精确匹配，否则只在补充描述上做模糊匹配（不搜昵称，昵称在 im-user 那张表里）。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="工单" width="110">
          <template #default="{ row }">
            <div class="stack">
              <span>#{{ row.id }}</span>
              <span class="muted num">{{ formatListTime(row.createTime) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="被举报对象" min-width="220">
          <template #default="{ row }">
            <div class="stack">
              <span class="ellipsis">{{ row.evidence?.title || row.targetTypeName }}</span>
              <span class="muted">{{ row.targetTypeName }} #{{ row.targetId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="责任人" min-width="170">
          <template #default="{ row }">
            <div v-if="row.reportedUserId" class="user-cell">
              <div class="stack">
                <span class="ellipsis">{{ row.reportedUserName || '未知用户' }}</span>
                <span class="muted">#{{ row.reportedUserId }}</span>
              </div>
              <el-tag :type="accountStatusTag(row.reportedUserStatus).type" effect="plain" size="small">
                {{ accountStatusTag(row.reportedUserStatus).text }}
              </el-tag>
            </div>
            <!-- 系统通知与机器人消息没有责任人：这不是数据缺失，硬留空白会被当成渲染坏了 -->
            <el-tooltip v-else content="该对象没有可追溯的责任人（系统消息或机器人），只能驳回或标注为已处理" placement="top">
              <span class="muted">无责任人</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="举报人" min-width="150">
          <template #default="{ row }">
            <div class="user-cell">
              <AvatarCell :url="row.reporterAvatar" :name="row.reporterName" :size="28" />
              <span class="ellipsis">{{ row.reporterName || '未知用户' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="理由" min-width="150">
          <template #default="{ row }">
            <div class="user-cell">
              <span>{{ row.reasonName }}</span>
              <el-tooltip v-if="row.reasonText" :content="row.reasonText" placement="top">
                <el-icon class="hint-icon"><ChatLineSquare /></el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="reportStatusTag(row.status)" effect="plain" size="small">
              {{ row.statusName }}
            </el-tag>
            <span v-if="row.dispositionName" class="muted ml">{{ row.dispositionName }}</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="140">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button text type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 0" text type="warning" @click="onAccept(row)">受理</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的工单" :image-size="80" />
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

    <el-drawer v-model="drawer" :size="drawerSize" title="工单详情">
      <template v-if="current">
        <div class="detail-head">
          <el-tag :type="reportStatusTag(current.status)" effect="dark">{{ current.statusName }}</el-tag>
          <span class="muted">#{{ current.id }} · {{ formatDateTime(current.createTime) }}</span>
        </div>

        <el-descriptions :column="1" border class="block">
          <el-descriptions-item label="对象类型">{{ current.targetTypeName }} #{{ current.targetId }}</el-descriptions-item>
          <el-descriptions-item label="举报理由">{{ current.reasonName }}</el-descriptions-item>
          <el-descriptions-item label="补充描述">
            <span v-if="current.reasonText">{{ current.reasonText }}</span>
            <span v-else class="muted">举报人没填</span>
          </el-descriptions-item>
          <el-descriptions-item label="举报人">{{ current.reporterName || '未知用户' }}</el-descriptions-item>
          <el-descriptions-item label="责任人">
            <span v-if="current.reportedUserId">
              {{ current.reportedUserName || '未知用户' }} #{{ current.reportedUserId }}
              <el-tag class="ml" :type="accountStatusTag(current.reportedUserStatus).type" effect="plain" size="small">
                {{ accountStatusTag(current.reportedUserStatus).text }}
              </el-tag>
              <span v-if="current.reportedPendingCount > 1" class="muted ml">
                名下还有 {{ current.reportedPendingCount - 1 }} 张未处置工单
              </span>
            </span>
            <span v-else class="muted">该对象没有可追溯的责任人</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="sub-title">证据快照</h4>
        <el-alert
          v-if="!current.evidence"
          :closable="false"
          type="warning"
          title="这张工单没有证据快照"
          description="提交那一刻归属模块没能取出对象（模块未装配或对象当时就不存在），只能凭描述判断。"
        />
        <template v-else>
          <p v-if="current.evidence.excerpt" class="excerpt">{{ current.evidence.excerpt }}</p>
          <!-- 附件证据的真实内容：图片/视频/语音内联渲染，其余只给下载链接。
               地址是后台自己的 /admin/report/{id}/media（带 system:report:handle 门），
               不经 im-file 的会话成员校验；同样取成 blob 才能交给媒体标签 -->
          <div v-if="current.evidence.mediaFileId" class="evidence-media">
            <p v-if="media.loading" class="muted">媒体证据加载中…</p>
            <p v-else-if="!media.url" class="muted">媒体证据不可用（文件已被删除，或闪照查看后即销毁）</p>
            <template v-else>
              <img v-if="current.evidence.mediaKind === 'image'" :src="media.url" alt="" />
              <video v-else-if="current.evidence.mediaKind === 'video'" :src="media.url" controls preload="metadata" />
              <audio v-else-if="current.evidence.mediaKind === 'audio'" :src="media.url" controls />
              <a v-else :href="media.url" :download="current.evidence.mediaName || 'evidence'">
                下载附件证据：{{ current.evidence.mediaName || '未知文件名' }}
              </a>
            </template>
          </div>
          <el-table v-if="metaEntries.length" :data="metaEntries" class="meta-table" size="small">
            <el-table-column label="字段" prop="label" width="140" />
            <el-table-column label="值" prop="value" show-overflow-tooltip />
          </el-table>
          <p class="muted">
            meta 的键由归属模块给出、是可直接显示的中文标签，后台只按键值对展示，
            不按键取值（所以加字段只改后端一个文件）。
          </p>
        </template>

        <template v-if="current.status === 2 || current.status === 3">
          <h4 class="sub-title">处理结论</h4>
          <el-descriptions :column="1" border class="block">
            <el-descriptions-item label="处置动作">{{ current.dispositionName || '（无）' }}</el-descriptions-item>
            <el-descriptions-item label="处理人">{{ current.handlerName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="处理时间">
              <span class="num">{{ formatDateTime(current.handleTime) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="说明">{{ current.handleRemark || '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>

        <template v-if="editable">
          <h4 class="sub-title">处置</h4>
          <el-form label-position="top">
            <el-form-item label="动作">
              <el-select v-model="form.action" class="action-pick" placeholder="选择处置动作">
                <el-option
                  v-for="code in current.allowedDispositions || []"
                  :key="code"
                  :label="dispositionName(code)"
                  :value="code"
                />
              </el-select>
              <span class="muted action-note">
                下拉里只有<b>提交那一刻</b>归属模块报上来的可执行动作；执行失败时工单停在原状态，原因会原样回显。
              </span>
            </el-form-item>
            <!-- 只在选择「封禁账号」时才出现：其它动作没有「到期」这个概念，
                 摆一个平时隐藏的时长下拉比永远摆着不让人误会强 -->
            <el-form-item v-if="form.action === 'ban_user'" label="封禁时长（到期自动解封）">
              <div class="ban-row">
                <el-select v-model="form.minutes" class="ban-pick" placeholder="选择时长">
                  <el-option
                    v-for="opt in BAN_DURATIONS"
                    :key="String(opt.value)"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select>
                <el-input-number
                  v-if="form.minutes === CUSTOM"
                  v-model="form.customMinutes"
                  :max="CUSTOM_MAX_MINUTES"
                  :min="1"
                  :step="60"
                  class="ban-custom"
                  controls-position="right"
                />
              </div>
              <span class="muted action-note">{{ banPreview }}</span>
            </el-form-item>
            <el-form-item label="说明（回显给举报人并留档）">
              <el-input
                v-model="form.remark"
                :maxlength="500"
                placeholder="写清依据与结论，事后没人能替你回忆当时为什么动了他"
                show-word-limit
                type="textarea"
                :rows="3"
              />
            </el-form-item>
          </el-form>
          <div class="actions">
            <el-button v-if="current.status === 0" :loading="submitting" @click="onAccept(current)">
              先受理
            </el-button>
            <el-button :loading="submitting" type="primary" @click="onHandle">执行处置</el-button>
            <el-button :loading="submitting" type="warning" @click="onReject">驳回</el-button>
          </div>
        </template>
        <p v-else class="muted block">这张工单已经是终态，不能再处置或驳回。</p>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  acceptReport,
  fetchReportConfig,
  fetchReportDetail,
  fetchReportPage,
  handleReport,
  rejectReport
} from '@/api/admin'
import AvatarCell from '@/components/AvatarCell.vue'
import { fetchMedia } from '@/api/request'
import { accountStatusTag, formatDateTime, formatListTime, reportStatusTag } from '@/utils/format'
import { BAN_DURATIONS, CUSTOM, CUSTOM_MAX_MINUTES, durationText } from '@/utils/ban'
import dayjs from 'dayjs'

/**
 * 举报工单队列。
 *
 * 列表行就带上处置按钮要用的 {@code allowedDispositions}（后端在行数据里给了），
 * 不必每点开一行再回查一次；但点开详情时仍然重新打一次
 * {@code GET /admin/report/{id}}——队列是多人看的，两秒前那行的状态可能已经被另一个管理员改掉，
 * 按旧数据点「封号」只会收到一句「工单已是终态」。
 *
 * 三份码表（状态 / 对象类型 / 理由 / 处置动作）全部来自 {@code /api/report/config}：
 * 后台不复制一份中文文案，否则加了个理由枚举而前端没跟，队列上就会出现「未知理由」。
 */
const route = useRoute()

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const config = ref(null)

const drawer = ref(false)
const current = ref(null)
const submitting = ref(false)
const form = reactive({ action: '', remark: '', minutes: 1440, customMinutes: 1440 })

/**
 * 本次要下发的封禁分钟数（null = 永久）。
 *
 * 只在 {@code action === 'ban_user'} 时被读：切到其它动作后这里仍会算出一个数，
 * 但后端不读它（ReportDisposeCmd.disableMinutes 在服务端那侧就被按动作筛过一遍），
 * 所以管理员「选了时长又改成只警告」不会意外封掉任何人。
 */
const banMinutes = computed(() => {
  if (form.minutes === CUSTOM) {
    return form.customMinutes > 0 ? form.customMinutes : null
  }
  return form.minutes
})

const banPreview = computed(() => {
  const minutes = banMinutes.value
  if (!minutes) {
    return '永久封禁：不会自动解封，需要回到用户管理手动恢复'
  }
  return `预计 ${dayjs().add(minutes, 'minute').format('YYYY-MM-DD HH:mm')} 自动解封（以服务端时间为准）`
})

const query = reactive({
  keyword: '',
  status: undefined,
  targetType: undefined,
  reasonCode: undefined,
  current: 1,
  size: 20
})

/**
 * 抽屉宽度：窄屏铺满，宽屏留一半（证据摘要与 meta 表在 380px 里会挤成一列竖排）。
 *
 * 用 ref 而不是 computed 在开抽屉那一刻算：computed 里没有响应式依赖，
 * 算过一次就永久缓存，管理员把窗口从窄拖宽再开抽屉看到的还是旧宽度。
 */
const drawerSize = ref('620px')

const metaEntries = computed(() => {
  const meta = current.value?.evidence?.meta || {}
  return Object.keys(meta).map((label) => ({ label, value: String(meta[label] ?? '') }))
})

const editable = computed(() => current.value?.status === 0 || current.value?.status === 1)

function dispositionName(code) {
  const item = (config.value?.dispositions || []).find((d) => d.code === code)
  return item ? item.name : code
}

async function load() {
  loading.value = true
  try {
    const page = await fetchReportPage({
      keyword: query.keyword || undefined,
      status: query.status,
      targetType: query.targetType,
      reasonCode: query.reasonCode,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
  } catch {
    // 提示已由 request.js 弹过
  } finally {
    loading.value = false
  }
}

function reload() {
  query.current = 1
  load()
}

async function openDetail(row) {
  try {
    current.value = await fetchReportDetail(row.id)
  } catch {
    // 详情拿不到就退回用列表行渲染：至少证据快照与可执行动作是当场有的，
    // 总比点「详情」什么都不发生要好
    current.value = row
  }
  form.action = ''
  form.remark = ''
  form.minutes = 1440
  form.customMinutes = 1440
  drawerSize.value = window.innerWidth < 900 ? '100%' : '620px'
  drawer.value = true
  loadEvidenceMedia(current.value)
}

/**
 * 证据媒体的 blob 地址（按 mediaFileId 经后台自己的 /admin/report/{id}/media 取回）。
 *
 * 不复用 im-file 的受控地址：那套 canAccess 只认上传者/同会话成员，管理员两者都不是；
 * 新端点的门就是本页已在的 system:report:handle 权限点。同样要取成 blob——
 * <img>/<video> 带不了登录头（与视频管理页同一套路）；大视频不设超时。
 * 自增序号防快速连开两张工单时先回的覆盖后开的。
 */
const media = reactive({ url: '', loading: false })
let mediaSeq = 0

async function loadEvidenceMedia(report) {
  const seq = ++mediaSeq
  media.url = ''
  media.loading = false
  if (!report?.evidence?.mediaFileId) {
    return
  }
  media.loading = true
  try {
    const src = await fetchMedia(`/api/admin/report/${report.id}/media`, { timeout: 0 })
    if (seq === mediaSeq) {
      media.url = src
    }
  } finally {
    if (seq === mediaSeq) {
      media.loading = false
    }
  }
}

async function onAccept(row) {
  try {
    await acceptReport(row.id)
    ElMessage.success('已受理')
    await refreshAfterAction(row.id)
  } catch {
    // 状态被别人抢先改过时会回「只有待处理的工单可以受理」，提示已弹过
  }
}

async function onHandle() {
  if (!form.action) {
    ElMessage.warning('先选一个处置动作')
    return
  }
  const label = dispositionName(form.action)
  const dangerous = ['ban_user', 'recall_message', 'close_room'].includes(form.action)
  if (dangerous) {
    // 封号时把时长写进确认语：同一个「封禁账号」按钮，封 10 分钟与封永久是两个量级的决定，
    // 只在事后才在回执里看到「已永久封禁」已经晚了
    const scope = form.action === 'ban_user' ? `（${durationText(banMinutes.value)}）` : ''
    if (!(await confirm(`「${label}${scope}」会直接作用于线上对象（封号会踢下线、撤回会消失、中断直播会断流），确定执行？`))) {
      return
    }
  }
  submitting.value = true
  try {
    const message = await handleReport(current.value.id, form.action, form.remark.trim(),
      form.action === 'ban_user' ? banMinutes.value : undefined)
    ElMessage.success(message || '处置完成')
    drawer.value = false
    await load()
  } catch {
    // 归属模块执行失败时工单停在原状态，原因由 request.js 原样回显，可以改了再试
  } finally {
    submitting.value = false
  }
}

async function onReject() {
  const remark = form.remark.trim()
  if (!remark && !(await confirm('没有填驳回理由。举报人会收到「经核实不成立：（空）」，确定就这样？'))) {
    return
  }
  submitting.value = true
  try {
    const message = await rejectReport(current.value.id, remark)
    ElMessage.success(message || '已驳回')
    drawer.value = false
    await load()
  } catch {
    // 后端在 handle-remark-required=true（默认）时会打回「驳回必须写明理由」
  } finally {
    submitting.value = false
  }
}

function confirm(text) {
  return ElMessageBox.confirm(text, '请确认', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(
    () => true,
    () => false
  )
}

/** 行内动作（受理）后只刷新那一行所在的数据，不重查整页，避免滚动位置被抹掉 */
async function refreshAfterAction(id) {
  try {
    const fresh = await fetchReportDetail(id)
    const index = rows.value.findIndex((item) => item.id === id)
    if (index >= 0) {
      rows.value[index] = fresh
    }
    if (current.value?.id === id) {
      current.value = fresh
    }
  } catch {
    await load()
  }
}

onMounted(async () => {
  // 看板与用户页都会带着筛选条件跳过来（status=0 看待办、keyword=用户 ID 看某个人）
  if (route.query.status !== undefined) {
    const status = Number(route.query.status)
    query.status = Number.isNaN(status) ? undefined : status
  }
  if (typeof route.query.keyword === 'string') {
    query.keyword = route.query.keyword
  }
  try {
    config.value = await fetchReportConfig()
  } catch {
    // 字典拿不到只影响下拉与筛选的文案，队列本身仍可显示（行数据里带来中文名）
  }
  load()
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.keyword {
  width: 260px;
}

.pick {
  width: 130px;
}

.hint {
  margin-bottom: 16px;
}

.stack {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-cell {
  align-items: center;
  display: flex;
  gap: 8px;
  min-width: 0;
}

.hint-icon {
  color: #909399;
  cursor: help;
}

.ml {
  margin-left: 6px;
}

.detail-head {
  align-items: center;
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.sub-title {
  font-size: 14px;
  margin: 20px 0 8px;
}

.block {
  margin-bottom: 12px;
}

.excerpt {
  background: #f5f7fa;
  border-left: 3px solid #c0c4cc;
  margin: 0 0 8px;
  padding: 8px 10px;
  white-space: pre-wrap;
}

.meta-table {
  margin-bottom: 6px;
}

.evidence-media {
  margin-bottom: 8px;
}

.evidence-media img,
.evidence-media video {
  background: #000;
  border-radius: 6px;
  display: block;
  max-height: 320px;
  max-width: 100%;
}

.evidence-media audio {
  width: 100%;
}

.action-pick {
  width: 220px;
}

.ban-row {
  align-items: center;
  display: flex;
  gap: 8px;
}

.ban-pick {
  width: 160px;
}

.ban-custom {
  width: 130px;
}

.action-note {
  display: block;
  line-height: 1.6;
  margin-top: 6px;
}

.actions {
  display: flex;
  gap: 10px;
}
</style>
