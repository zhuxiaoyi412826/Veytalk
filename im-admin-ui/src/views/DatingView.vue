<template>
  <div class="dating">
    <el-tabs v-model="activeTab" class="dating__tabs">
      <!-- ==================== 交友资料 ==================== -->
      <el-tab-pane label="交友资料" name="profiles">
        <div class="toolbar">
          <h2 class="page-title">交友资料</h2>
          <span class="grow" />
          <el-input
            v-model="query.keyword"
            :maxlength="50"
            clearable
            class="keyword"
            placeholder="城市 / 职业模糊；纯数字也认用户 ID"
            @clear="reload"
            @keyup.enter="reload"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-input v-model="query.city" :maxlength="20" clearable class="city" placeholder="城市精确" @clear="reload" @keyup.enter="reload" />
          <el-select v-model="query.discoverable" class="pick" clearable placeholder="全部可见性" @change="reload">
            <el-option :value="1" label="可被发现" />
            <el-option :value="0" label="已隐身" />
          </el-select>
          <el-select v-model="query.qualityFlag" class="pick" clearable placeholder="全部质量" @change="reload">
            <el-option :value="0" label="正常" />
            <el-option :value="1" label="低质降权" />
            <el-option :value="2" label="已升权" />
          </el-select>
          <el-input-number v-model="query.minScore" :min="0" :max="100" :step="10" class="score" placeholder="完资料度≥" />
          <el-button :loading="loading" @click="reload">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="info"
          title="这里改的是「能不能被推荐」，不是账号封禁"
          description="强制隐身复用用户自己的 discoverable 列，所以本人也能重新打开；要硬封人去「用户管理」禁用账号。已配对与好友关系不受隐身影响。列表含隐身与已降权的人，按最近活跃倒序。"
        />

        <el-card shadow="never">
          <el-table v-loading="loading" :data="rows">
            <el-table-column label="用户" min-width="190">
              <template #default="{ row }">
                <div class="user-cell">
                  <AvatarCell :url="row.avatar" :name="row.nickname" :size="34" />
                  <div class="user-text">
                    <span class="ellipsis">{{ row.nickname || '已注销用户' }}</span>
                    <span class="muted">#{{ row.userId }}{{ row.username ? ' · ' + row.username : '' }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="性别/年龄" width="110">
              <template #default="{ row }">
                <span>{{ genderText(row.gender) }} · {{ ageText(row.birthday) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="所在城市" min-width="130">
              <template #default="{ row }">
                <span class="ellipsis">{{ areaText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="兴趣标签" min-width="170">
              <template #default="{ row }">
                <el-tag v-for="name in (row.tagNames || []).slice(0, 3)" :key="name" effect="plain" size="small" class="tag">
                  {{ name }}
                </el-tag>
                <span v-if="(row.tagNames || []).length > 3" class="muted">+{{ row.tagNames.length - 3 }}</span>
                <span v-if="!(row.tagNames || []).length" class="muted">{{ EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="完资料度" width="120">
              <template #default="{ row }">
                <el-progress :percentage="row.profileScore || 0" :stroke-width="10" :text-inside="true" />
              </template>
            </el-table-column>
            <el-table-column align="center" label="照片" width="96">
              <template #default="{ row }">
                <span class="num">{{ row.photoCount || 0 }}</span>
                <el-tag v-if="row.pendingPhotoCount" type="warning" effect="dark" size="small" class="pending">
                  {{ row.pendingPhotoCount }} 待审
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="190">
              <template #default="{ row }">
                <el-tag :type="row.discoverable === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ row.discoverable === 1 ? '可被发现' : '已隐身' }}
                </el-tag>
                <el-tag v-if="row.qualityFlag === 1" type="danger" effect="plain" size="small">低质降权</el-tag>
                <el-tag v-else-if="row.qualityFlag === 2" type="success" effect="dark" size="small">已升权</el-tag>
                <el-tag v-if="row.userStatus !== 1" :type="row.userStatus === 0 ? 'danger' : 'info'" size="small">
                  {{ row.userStatus === 0 ? '账号已禁用' : '账号已注销' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近活跃" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.lastActiveTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="200">
              <template #default="{ row }">
                <el-button text type="primary" @click="openProfile(row)">档案</el-button>
                <el-button v-if="row.discoverable === 1" text type="danger" @click="confirmHide(row)">隐身</el-button>
                <el-button v-else text type="success" @click="confirmReveal(row)">恢复</el-button>
                <el-button text type="primary" @click="openQuality(row)">质量</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的交友资料" :image-size="80" />
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
              @current-change="loadProfiles"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 照片审核 ==================== -->
      <el-tab-pane name="photos">
        <template #label>
          照片审核
          <el-badge v-if="photoQuery.auditStatus === 0" :value="photoTotal" type="warning" class="tab-badge" />
        </template>

        <div class="toolbar">
          <span class="muted">先传来的先审（上传时间正序），新图不会插队。通过后才对他人可见并重算完资料度。</span>
          <span class="grow" />
          <el-input v-model="photoQuery.userId" :maxlength="20" clearable class="city" placeholder="用户 ID" @clear="reloadPhotos" @keyup.enter="reloadPhotos" />
          <el-select v-model="photoQuery.auditStatus" class="pick" placeholder="全部状态" @change="reloadPhotos">
            <el-option :value="0" label="待审核" />
            <el-option :value="1" label="已通过" />
            <el-option :value="2" label="已驳回" />
          </el-select>
          <el-button :loading="photoLoading" @click="reloadPhotos">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="photoLoading" :data="photoRows">
            <el-table-column label="照片" width="120">
              <template #default="{ row }">
                <img v-if="thumbs[row.id]" class="thumb" :src="thumbs[row.id]" alt="" @click="openBig(row)" />
                <div v-else class="thumb thumb-empty"><el-icon><Picture /></el-icon></div>
              </template>
            </el-table-column>
            <el-table-column label="作者" min-width="150">
              <template #default="{ row }">
                <div class="user-text">
                  <span class="ellipsis">{{ row.userNickname || '已注销用户' }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="版式" width="150">
              <template #default="{ row }">
                <span class="num">{{ row.width && row.height ? `${row.width}×${row.height}` : EMPTY_TEXT }}</span>
                <span class="muted">{{ formatBytes(row.size) }}</span>
              </template>
            </el-table-column>
            <el-table-column align="center" label="主图" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.isMain === 1" type="success" effect="plain" size="small">封面</el-tag>
                <span v-else class="muted">{{ EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态与原因" min-width="190">
              <template #default="{ row }">
                <el-tag :type="auditTag(row.auditStatus).type" effect="plain" size="small">
                  {{ auditTag(row.auditStatus).text }}
                </el-tag>
                <span v-if="row.rejectReason" class="reason ellipsis">{{ row.rejectReason }}</span>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="180">
              <template #default="{ row }">
                <el-button v-if="row.auditStatus !== 1" text type="success" @click="confirmApprove(row)">通过</el-button>
                <el-button v-if="row.auditStatus !== 2" text type="warning" @click="confirmReject(row)">驳回</el-button>
                <el-button text type="danger" @click="confirmDeletePhoto(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="这一档没有照片" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="photoQuery.current"
              v-model:page-size="photoQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="photoTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadPhotos"
              @current-change="loadPhotos"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 档案弹框：一次读全部字段，列表里塞不下那十来个下拉码值 -->
    <el-dialog v-model="profileDialog.visible" title="交友档案" width="620px">
      <el-descriptions v-if="profileDialog.row" :column="2" border size="small">
        <el-descriptions-item label="用户">
          {{ profileDialog.row.nickname || '已注销用户' }} <span class="muted">#{{ profileDialog.row.userId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="账号名">{{ displayValue(profileDialog.row.username) }}</el-descriptions-item>
        <el-descriptions-item label="性别">{{ genderText(profileDialog.row.gender) }}</el-descriptions-item>
        <el-descriptions-item label="年龄">{{ ageText(profileDialog.row.birthday) }}</el-descriptions-item>
        <el-descriptions-item label="身高">
          {{ profileDialog.row.heightCm ? profileDialog.row.heightCm + ' cm' : EMPTY_TEXT }}
        </el-descriptions-item>
        <el-descriptions-item label="学历">{{ dictText(dict.educations, profileDialog.row.education) }}</el-descriptions-item>
        <el-descriptions-item label="收入">{{ dictText(dict.incomes, profileDialog.row.income) }}</el-descriptions-item>
        <el-descriptions-item label="婚恋状况">{{ dictText(dict.marriages, profileDialog.row.marriage) }}</el-descriptions-item>
        <el-descriptions-item label="交友目的">{{ dictText(dict.datingGoals, profileDialog.row.datingGoal) }}</el-descriptions-item>
        <el-descriptions-item label="职业">{{ displayValue(profileDialog.row.occupation) }}</el-descriptions-item>
        <el-descriptions-item label="所在地区">{{ areaText(profileDialog.row) }}</el-descriptions-item>
        <el-descriptions-item label="是否授权定位">
          {{ profileDialog.row.hasLocation ? '已授权' : '未授权（附近的人不出现）' }}
        </el-descriptions-item>
        <el-descriptions-item label="动态可见窗口">{{ daysText(profileDialog.row.momentVisibleDays) }}</el-descriptions-item>
        <el-descriptions-item label="完资料度">{{ profileDialog.row.profileScore || 0 }} 分</el-descriptions-item>
        <el-descriptions-item label="照片（通过/总）">
          {{ profileDialog.row.photoCount || 0 }} 张，其中 {{ profileDialog.row.pendingPhotoCount || 0 }} 张待审
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ formatDateTime(profileDialog.row.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="兴趣标签" :span="2">
          <el-tag v-for="name in (profileDialog.row.tagNames || [])" :key="name" effect="plain" size="small" class="tag">
            {{ name }}
          </el-tag>
          <span v-if="!(profileDialog.row.tagNames || []).length" class="muted">未选</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 质量标记：三个码值互斥，用 radio 而不是开关；改 2（真人认证）必须写事由 -->
    <el-dialog v-model="qualityDialog.visible" title="调整推荐质量" width="460px">
      <el-radio-group v-model="qualityDialog.flag" class="quality-group">
        <el-radio :value="0">0 正常</el-radio>
        <el-radio :value="1">1 低质降权（不进推荐流）</el-radio>
        <el-radio :value="2">2 已升权（真人认证，优先推荐）</el-radio>
      </el-radio-group>
      <el-input
        v-model="qualityDialog.reason"
        type="textarea"
        :rows="2"
        :maxlength="200"
        show-word-limit
        placeholder="事由（选填，只进服务端日志，不外显给用户）"
      />
      <template #footer>
        <el-button @click="qualityDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="qualitySaving" @click="submitQuality">保存</el-button>
      </template>
    </el-dialog>

    <!-- 大图：审核时 64×40 的缩略图根本看不清细节，点开单独看一张 -->
    <el-dialog v-model="bigDialog.visible" title="照片原图" width="640px">
      <img v-if="bigDialog.url" class="big" :src="bigDialog.url" alt="" />
      <p v-else class="muted">图片取不到（文件可能已被删除）</p>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  approveDatingPhoto,
  fetchDatingDict,
  fetchDatingPhotoPage,
  fetchDatingProfilePage,
  hideDatingProfile,
  rejectDatingPhoto,
  deleteDatingPhoto,
  revealDatingProfile,
  setDatingQuality
} from '@/api/admin'
import { fetchMedia } from '@/api/request'
import AvatarCell from '@/components/AvatarCell.vue'
import { EMPTY_TEXT, displayValue, formatBytes, formatDateTime, formatListTime } from '@/utils/format'
import dayjs from 'dayjs'

/**
 * 后台「交友管理」屏：交友资料 + 照片审核（权限点 132 system:dating:manage）。
 *
 * 两处口径与后端钉死的一致，别在这里加回来：
 *  - 列表排序固定「最近活跃倒序」，不给排序表头（点了没反应的箭头比没有更糟）；
 *  - 照片固定「上传时间正序」，这是审核队列而不是相册，先来的先来。
 *
 * 学历 / 收入 / 婚恋 / 交友目的的文案<b>来自用户侧的 /api/dating/dict</b>，不是这里手写一份常量：
 * 同一份枚举存在第三处的那一刻就开始漂移（后端「学历 4 = 本科」、页面显示成大专就是这么来的）。
 * 字典拉取失败不挡页面——列子会退化成显示 code，人还是能做处置。
 *
 * 隐身与驳回都复用用户自己那两列（discoverable / audit_status），所以「本人可以自行解开」是
 * 设计而非漏洞：这一屏是运营干预，硬封在「用户管理」那一屏。
 */
const activeTab = ref('profiles')

/* ==================== 字典 ==================== */

const dict = reactive({ educations: [], incomes: [], marriages: [], datingGoals: [] })

onMounted(() => {
  loadProfiles()
  loadPhotos()
  fetchDatingDict()
    .then((data) => {
      if (data) {
        dict.educations = data.educations || []
        dict.incomes = data.incomes || []
        dict.marriages = data.marriages || []
        dict.datingGoals = data.datingGoals || []
      }
    })
    .catch(() => {
      // 拉不到就显示 code，不拿一个错误弹框拦住整屏
    })
})

function dictText(options, code) {
  if (code === null || code === undefined) {
    return '未填'
  }
  const hit = (options || []).find((item) => item.code === code)
  return hit ? hit.desc : String(code)
}

function genderText(gender) {
  if (gender === 1) {
    return '男'
  }
  if (gender === 2) {
    return '女'
  }
  return EMPTY_TEXT
}

/** 生日只有年月日，虚岁口径不做——按周岁算，与用户端资料卡同一句 birthday.diff */
function ageText(birthday) {
  if (!birthday) {
    return EMPTY_TEXT
  }
  const target = dayjs(birthday)
  return target.isValid() ? `${dayjs().diff(target, 'year')} 岁` : String(birthday)
}

function areaText(row) {
  if (!row) {
    return EMPTY_TEXT
  }
  const joined = [row.province, row.city, row.district].filter(Boolean).join(' ')
  return joined || '未填'
}

function daysText(days) {
  if (days === null || days === undefined) {
    return EMPTY_TEXT
  }
  return days === 0 ? '全部可见' : `最近 ${days} 天`
}

function auditTag(status) {
  if (status === 1) {
    return { type: 'success', text: '已通过' }
  }
  if (status === 2) {
    return { type: 'danger', text: '已驳回' }
  }
  return { type: 'warning', text: '待审核' }
}

/* ==================== 资料翻页 ==================== */

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', city: '', discoverable: undefined, qualityFlag: undefined, minScore: 0, current: 1, size: 20 })

async function loadProfiles() {
  loading.value = true
  try {
    const page = await fetchDatingProfilePage({
      keyword: query.keyword || undefined,
      city: query.city || undefined,
      discoverable: query.discoverable,
      qualityFlag: query.qualityFlag,
      // 0 是「不限」而不是「完资料度≥ 0」，不传才走后端的全量分支
      minScore: query.minScore || undefined,
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
  loadProfiles()
}

const profileDialog = reactive({ visible: false, row: null })

function openProfile(row) {
  profileDialog.row = row
  profileDialog.visible = true
}

function confirmHide(row) {
  ElMessageBox.prompt(
    `隐身「${row.nickname || `#${row.userId}`}」后他不再进推荐流、附近的人与动态流；已配对和好友关系不受影响。事由进服务端日志。`,
    '强制隐身',
    { confirmButtonText: '确认隐身', cancelButtonText: '取消', inputPlaceholder: '选填：事由', type: 'warning' }
  )
    .then(async ({ value }) => {
      const updated = await hideDatingProfile(row.userId, (value || '').trim() || undefined)
      Object.assign(row, updated)
      ElMessage.success('已强制隐身')
    })
    .catch(() => {})
}

async function confirmReveal(row) {
  try {
    const updated = await revealDatingProfile(row.userId)
    Object.assign(row, updated)
    ElMessage.success('已恢复可被发现')
  } catch {
    // 接口层已弹提示
  }
}

const qualityDialog = reactive({ visible: false, row: null, flag: 0, reason: '', saving: false })
const qualitySaving = ref(false)

function openQuality(row) {
  qualityDialog.row = row
  qualityDialog.flag = row.qualityFlag ?? 0
  qualityDialog.reason = ''
  qualityDialog.visible = true
}

async function submitQuality() {
  qualitySaving.value = true
  try {
    const updated = await setDatingQuality(qualityDialog.row.userId, qualityDialog.flag, qualityDialog.reason.trim() || undefined)
    Object.assign(qualityDialog.row, updated)
    qualityDialog.visible = false
    ElMessage.success('质量标记已更新')
  } catch {
    // 同上
  } finally {
    qualitySaving.value = false
  }
}

/* ==================== 照片审核 ==================== */

const photoRows = ref([])
const photoTotal = ref(0)
const photoLoading = ref(false)
// 默认停在待审核：打开这一屏要做的就是没审的那些，而不是从已通过里翻起
const photoQuery = reactive({ auditStatus: 0, userId: '', current: 1, size: 20 })

async function loadPhotos() {
  photoLoading.value = true
  try {
    const page = await fetchDatingPhotoPage({
      auditStatus: photoQuery.auditStatus,
      userId: photoQuery.userId || undefined,
      current: photoQuery.current,
      size: photoQuery.size
    })
    photoRows.value = page.records || []
    photoTotal.value = Number(page.total) || 0
    loadThumbs()
  } catch {
    // 同上
  } finally {
    photoLoading.value = false
  }
}

function reloadPhotos() {
  photoQuery.current = 1
  loadPhotos()
}

/**
 * 缩略图地址映射（row.id -> blob URL）。
 *
 * 后端回的是受控下载地址，<img src> 带不了登录头，直接塞只会裂图；与 VideosView 的封面同一做法。
 * 失败不缓存（fetchMedia 内部保证），翻页回来还会重试。
 */
const thumbs = reactive({})

function loadThumbs() {
  photoRows.value.forEach((row) => {
    if (row.url && !thumbs[row.id]) {
      fetchMedia(row.url).then((src) => {
        thumbs[row.id] = src
      })
    }
  })
}

const bigDialog = reactive({ visible: false, url: '' })

async function openBig(row) {
  bigDialog.url = thumbs[row.id] || ''
  bigDialog.visible = true
  if (!bigDialog.url && row.url) {
    // 缩略图还没回来就先开框，这里补上；地址相同，fetchMedia 内部会合流不会重下一遍
    bigDialog.url = await fetchMedia(row.url)
  }
}

async function confirmApprove(row) {
  try {
    const updated = await approveDatingPhoto(row.id)
    Object.assign(row, updated)
    ElMessage.success('照片已通过')
  } catch {
    // 同上
  }
}

function confirmReject(row) {
  ElMessageBox.prompt('驳回会保留这张图与原因，用户下次进照片墙能看到「哪张被拦、为什么」，可改可删。', '驳回照片', {
    confirmButtonText: '确认驳回',
    cancelButtonText: '取消',
    inputPlaceholder: '请写驳回原因（不超过 200 字）',
    inputValidator: (value) => ((value || '').trim() ? true : '请填写驳回原因'),
    type: 'warning'
  })
    .then(async ({ value }) => {
      const updated = await rejectDatingPhoto(row.id, value.trim())
      Object.assign(row, updated)
      ElMessage.success('照片已驳回')
    })
    .catch(() => {})
}

async function confirmDeletePhoto(row) {
  try {
    await ElMessageBox.confirm(
      '删除用于重复上传与明显垃圾：行会整体隐身、无法自助恢复。内容不当但图还想留着的，走驳回更合适。',
      '删除照片',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'error',
        inputPlaceholder: '请输入「删除」两个字确认',
        inputValidator: (value) => ((value || '').trim() === '删除' ? true : '请精确输入：删除')
      }
    )
  } catch {
    return
  }
  try {
    await deleteDatingPhoto(row.id)
    ElMessage.success('照片已删除')
    loadPhotos()
  } catch {
    // 同上
  }
}
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.keyword {
  width: 250px;
}

.city {
  width: 140px;
}

.pick {
  width: 130px;
}

.score {
  width: 130px;
}

.dating__tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.hint {
  margin-bottom: 16px;
}

.tab-badge {
  margin-left: 6px;
  margin-top: -6px;
}

.user-cell {
  align-items: center;
  display: flex;
  gap: 8px;
  min-width: 0;
}

.user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tag {
  margin-right: 4px;
}

.pending {
  margin-left: 4px;
}

.reason {
  color: #f56c6c;
  display: block;
  max-width: 220px;
}

.thumb {
  background: #f5f7fa;
  border-radius: 4px;
  cursor: zoom-in;
  height: 60px;
  object-fit: cover;
  width: 90px;
}

.thumb-empty {
  align-items: center;
  color: #c0c4cc;
  cursor: default;
  display: flex;
  justify-content: center;
}

.big {
  max-height: 60vh;
  width: 100%;
  object-fit: contain;
}

.quality-group {
  display: flex;
  flex-direction: column;
  margin-bottom: 12px;
}
</style>
