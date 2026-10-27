<template>
  <div class="groups">
    <div class="toolbar">
      <h2 class="page-title">群组管理</h2>
      <span class="grow" />
      <span class="muted">台账默认含已解散的群</span>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="warning"
      title="后台在群里只能做两件事：掐掉一个正在滋事的群，或者让除组织者以外的所有人闭嘴"
      description="没有改群名、换群主、踢某个成员、设管理员——那些是群内治理，是群主的权利；system:group:manage 的授权理由是「阻断」，不是「接管」。两个写动作都只跳过「操作人必须是群主/管理员」这一条校验，其余（置状态、成员批量退群、会话通知、WebSocket 广播、缓存失效）与群主亲自操作走同一段代码，所以群里那 200 个人看到的是一条「群已被解散」的系统通知加一个进不去的会话，而不是「后台悄悄把群拔了，成员还在往里发消息」。强制解散不可逆：成员关系要重新生成，那是造数据而不是撤销。"
    />

    <el-tabs v-model="tab" class="mb" @tab-change="onTabChange">
      <el-tab-pane label="群台账" name="list">
        <div class="filters">
          <el-input
            v-model="query.keyword"
            :maxlength="30"
            clearable
            class="keyword"
            placeholder="群名称关键词"
            @clear="reloadGroups"
            @keyup.enter="reloadGroups"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-input
            v-model="query.ownerId"
            :maxlength="20"
            clearable
            class="uid"
            placeholder="群主用户 ID"
            @clear="reloadGroups"
            @keyup.enter="reloadGroups"
          />
          <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reloadGroups">
            <el-option :value="1" label="正常" />
            <el-option :value="0" label="已解散" />
          </el-select>
          <el-date-picker
            v-model="dateRange"
            :default-time="defaultTime"
            class="range"
            end-placeholder="结束"
            start-placeholder="开始"
            type="datetimerange"
            unlink-panels
            value-format="YYYY-MM-DDTHH:mm:ss"
            @change="reloadGroups"
          />
          <el-button :loading="loading" @click="reloadGroups">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-card shadow="never">
          <el-table v-loading="loading" :data="rows">
            <el-table-column label="群" min-width="220">
              <template #default="{ row }">
                <div class="group-cell">
                  <img v-if="avatars[row.groupId]" class="avatar" :src="avatars[row.groupId]" alt="" />
                  <div v-else class="avatar avatar-empty"><el-icon><UserFilled /></el-icon></div>
                  <div class="stack">
                    <span class="ellipsis">{{ row.name || '（未命名群）' }}</span>
                    <span class="muted">#{{ row.groupId }}</span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="群主" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.ownerName || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.ownerId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column align="right" label="成员 / 上限" width="110">
              <template #default="{ row }">
                <span class="num">{{ formatNumber(row.memberCount) }} / {{ displayValue(row.maxMember) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ row.status === 1 ? '正常' : '已解散' }}
                </el-tag>
                <el-tag v-if="row.muteAll === 1" class="ml" effect="dark" size="small" type="danger">全员禁言</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="入群审批" width="90">
              <template #default="{ row }">
                <span class="muted">{{ row.joinApproval === 1 ? '需审批' : '自由加入' }}</span>
              </template>
            </el-table-column>
            <el-table-column align="right" label="待处理申请" width="100">
              <template #default="{ row }">
                <span class="num" :class="row.pendingJoinCount > 0 ? 'warning' : ''">
                  {{ displayValue(row.pendingJoinCount) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.createTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column fixed="right" label="操作" width="230">
              <template #default="{ row }">
                <el-button text type="primary" @click="openDetail(row.groupId)">档案</el-button>
                <el-button text type="primary" @click="openMembers(row)">成员</el-button>
                <template v-if="row.status === 1">
                  <el-button text :type="row.muteAll === 1 ? 'success' : 'warning'" @click="toggleMute(row)">
                    {{ row.muteAll === 1 ? '解除禁言' : '全员禁言' }}
                  </el-button>
                  <el-button text type="danger" @click="confirmDismiss(row)">解散</el-button>
                </template>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有匹配的群组" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="query.current"
              v-model:page-size="query.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="total"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadGroups"
              @current-change="loadGroups"
            />
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="群成员" name="members">
        <div class="filters">
          <el-input
            v-model="memberQuery.groupId"
            :maxlength="20"
            class="uid"
            placeholder="群 ID（必填）"
            @clear="reloadMembers"
            @keyup.enter="reloadMembers"
          />
          <el-select v-model="memberQuery.role" class="status" clearable placeholder="全部角色" @change="reloadMembers">
            <el-option :value="1" label="群主" />
            <el-option :value="2" label="管理员" />
            <el-option :value="3" label="成员" />
          </el-select>
          <el-input
            v-model="memberQuery.keyword"
            :maxlength="30"
            clearable
            class="keyword"
            placeholder="群内昵称 / 用户昵称或账号"
            @clear="reloadMembers"
            @keyup.enter="reloadMembers"
          />
          <el-button :loading="memberLoading" @click="reloadMembers">
            <el-icon><Refresh /></el-icon>
            <span>查询</span>
          </el-button>
        </div>

        <el-alert
          class="hint"
          :closable="false"
          type="info"
          title="只列在群成员，按入群时间正序，群主与管理员靠角色提前"
          description="这一屏要看的是「这个群的组织者是谁、他被禁言了没有」，所以退群历史本批不开开关。全员禁言挡不住群主与管理员（豁免规则沿用群内禁言，后台不重新发明一遍），禁一个滋事的群不等于禁掉那个群里最嗓子的两个人。"
        />

        <el-card shadow="never">
          <el-table v-loading="memberLoading" :data="members">
            <el-table-column label="成员" min-width="160">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.nickname || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="群内昵称" min-width="140">
              <template #default="{ row }">
                <span class="ellipsis">{{ row.nicknameInGroup || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="90">
              <template #default="{ row }">
                <el-tag :type="roleTag(row.role).type" effect="plain" size="small">{{ roleTag(row.role).text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="禁言" width="140">
              <template #default="{ row }">
                <el-tag v-if="row.muted === 1" effect="dark" size="small" type="danger">禁言中</el-tag>
                <span v-else class="muted">正常</span>
                <div v-if="row.muteEndTime" class="muted num">至 {{ formatListTime(row.muteEndTime) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="入群时间" width="130">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.joinTime) }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="填一个群 ID 再看成员" :image-size="80" />
            </template>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="memberQuery.current"
              v-model:page-size="memberQuery.size"
              :page-sizes="[10, 20, 50, 100]"
              :total="memberTotal"
              layout="total, sizes, prev, pager, next"
              @size-change="reloadMembers"
              @current-change="loadMembers"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 单群档案：三段快照的条数都由服务端夹死（成员 20 / 申请 20 / 机器人全部），真分页走「群成员」页签 -->
    <el-drawer v-model="drawer" :size="drawerSize" title="群档案">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="detail-head">
            <el-tag :type="detail.status === 1 ? 'success' : 'info'" effect="dark">
              {{ detail.status === 1 ? '正常' : '已解散' }}
            </el-tag>
            <el-tag v-if="detail.muteAll === 1" effect="dark" type="danger">全员禁言</el-tag>
            <span class="muted">#{{ detail.groupId }} · 建于 {{ formatDateTime(detail.createTime) }}</span>
          </div>

          <el-descriptions :column="1" border class="block">
            <el-descriptions-item label="群名称">{{ detail.name || '（未命名群）' }}</el-descriptions-item>
            <el-descriptions-item label="群主">
              {{ detail.ownerName || DELETED_NAME }} #{{ detail.ownerId }}
            </el-descriptions-item>
            <el-descriptions-item label="成员 / 上限">
              <span class="num">{{ formatNumber(detail.memberCount) }} / {{ displayValue(detail.maxMember) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="入群方式">
              <span class="muted">{{ detail.joinApproval === 1 ? '需群主/管理员审批' : '自由加入' }}</span>
              <span class="muted ml">邀请码与 webhookToken 不下发后台：前者是入群凭证，后者是代发群消息的凭证</span>
            </el-descriptions-item>
            <el-descriptions-item label="待处理申请">
              <span class="num">{{ displayValue(detail.pendingJoinCount) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="公告">
              <span class="notice">{{ detail.notice || '（未填写）' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="最后变动">
              <span class="num">{{ formatDateTime(detail.updateTime) }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="detail-actions">
            <template v-if="detail.status === 1">
              <el-button :type="detail.muteAll === 1 ? 'success' : 'warning'" @click="toggleMute(detail)">
                {{ detail.muteAll === 1 ? '解除全员禁言' : '开启全员禁言' }}
              </el-button>
              <el-button type="danger" @click="confirmDismiss(detail)">强制解散</el-button>
            </template>
            <span v-else class="muted">这个群已经解散，两个动作都没有可做的了（解散幂等、禁言只对活群有意义）</span>
          </div>

          <h4 class="sub-title">最近成员快照（{{ detail.members?.length ?? 0 }} 人，按入群时间正序）</h4>
          <el-table :data="detail.members || []" size="small">
            <el-table-column label="成员" min-width="140">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.nickname || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="80">
              <template #default="{ row }">
                <span class="muted">{{ roleTag(row.role).text }}</span>
              </template>
            </el-table-column>
            <el-table-column label="禁言" width="70">
              <template #default="{ row }">
                <span :class="row.muted === 1 ? 'out' : 'muted'">{{ row.muted === 1 ? '禁言中' : '正常' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="入群" width="120">
              <template #default="{ row }">
                <span class="num">{{ formatListTime(row.joinTime) }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有成员快照" :image-size="60" />
            </template>
          </el-table>

          <h4 class="sub-title">最近入群申请（{{ detail.joinRequests?.length ?? 0 }} 条，含已处理）</h4>
          <el-table :data="detail.joinRequests || []" size="small">
            <el-table-column label="申请人" min-width="130">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.nickname || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="requestTag(row.status)" effect="plain" size="small">
                  {{ requestText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <span class="muted">{{ sourceText(row.source) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="留言" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.message || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="申请 / 处理" width="150">
              <template #default="{ row }">
                <div class="stack">
                  <span class="num muted">{{ formatListTime(row.createTime) }}</span>
                  <span class="num muted">
                    {{ row.handleTime ? formatListTime(row.handleTime) : '未处理' }}
                    <template v-if="row.handleUserId"> · #{{ row.handleUserId }}</template>
                  </span>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有入群申请" :image-size="60" />
            </template>
          </el-table>

          <h4 class="sub-title">群机器人（{{ detail.robots?.length ?? 0 }} 个，含已停用）</h4>
          <el-table :data="detail.robots || []" size="small">
            <el-table-column label="名称" min-width="130">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.name || EMPTY_TEXT }}</span>
                  <span class="muted">#{{ row.robotId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="描述" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ row.description || EMPTY_TEXT }}</span>
              </template>
            </el-table-column>
            <el-table-column label="创建者" width="130">
              <template #default="{ row }">
                <div class="stack">
                  <span class="ellipsis">{{ row.creatorName || DELETED_NAME }}</span>
                  <span class="muted">#{{ row.creatorId }}</span>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="这个群没有机器人" :image-size="60" />
            </template>
          </el-table>
          <p class="muted note">机器人项里没有 webhookToken：那是外部系统代发群消息的凭证，展示在一个用来「看群」的抽屉里没有用处，只有泄露面。</p>
        </template>
        <el-empty v-else description="群档案没取到（群不存在时后端回 6001）" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  dismissGroup,
  fetchGroupDetail,
  fetchGroupMemberPage,
  fetchGroupPage,
  setGroupMuteAll
} from '@/api/admin'
import { fetchMedia } from '@/api/request'
import { EMPTY_TEXT, displayValue, formatDateTime, formatListTime, formatNumber } from '@/utils/format'

/**
 * 群组管理（四期第五批）。
 *
 * 台账页签是默认含已解散的群的——这是台账不是收件箱，「这个群什么时候没的」必须查得到；
 * 所以状态筛选留空时不筛，而不是默认只看活群。
 *
 * 成员页签必须带群 ID（后端把它做成硬约束：跨全站拉成员既没有索引支撑，也不是这一屏该给的能力）。
 * 从台账点「成员」会带着那一行的群 ID 过来；手输也支持，因为排查时常常只有一个群号从别处抄来。
 *
 * 群像是 /api/file/download 受控地址，<img src> 带不了登录头，与视频/直播封面同一套路取 blob。
 */
const DELETED_NAME = '已注销用户'

const tab = ref('list')

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ keyword: '', ownerId: '', status: undefined, current: 1, size: 20 })
const dateRange = ref(null)

const members = ref([])
const memberTotal = ref(0)
const memberLoading = ref(false)
const memberLoaded = ref(false)
const memberQuery = reactive({ groupId: '', role: undefined, keyword: '', current: 1, size: 20 })

const drawer = ref(false)
const drawerSize = ref('720px')
const detailLoading = ref(false)
const detail = ref(null)

/** 群头像地址映射（groupId -> object URL），取回前画占位图标 */
const avatars = reactive({})

/** el-date-picker 的范围端点：开始那天取 00:00:00、结束那天取 23:59:59，不然「今天」会漏掉一整天 */
const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

function digitsOrUndefined(value) {
  const text = String(value ?? '').trim()
  return /^\d+$/.test(text) ? text : undefined
}

async function loadGroups() {
  loading.value = true
  try {
    const page = await fetchGroupPage({
      keyword: query.keyword || undefined,
      ownerId: digitsOrUndefined(query.ownerId),
      status: query.status,
      startTime: dateRange.value?.[0] || undefined,
      endTime: dateRange.value?.[1] || undefined,
      current: query.current,
      size: query.size
    })
    rows.value = page.records || []
    total.value = Number(page.total) || 0
    rows.value.forEach((row) => {
      if (row.avatar && !avatars[row.groupId]) {
        fetchMedia(row.avatar).then((src) => {
          avatars[row.groupId] = src
        })
      }
    })
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果
  } finally {
    loading.value = false
  }
}

function reloadGroups() {
  query.current = 1
  loadGroups()
}

async function loadMembers() {
  const groupId = digitsOrUndefined(memberQuery.groupId)
  if (!groupId) {
    // 后端把 groupId 为空判成 1001，这里先拦下：省一次往返，也让空表格配一句人话而不是一个红条
    members.value = []
    memberTotal.value = 0
    return
  }
  memberLoading.value = true
  try {
    const page = await fetchGroupMemberPage({
      groupId,
      role: memberQuery.role,
      keyword: memberQuery.keyword || undefined,
      current: memberQuery.current,
      size: memberQuery.size
    })
    members.value = page.records || []
    memberTotal.value = Number(page.total) || 0
    memberLoaded.value = true
  } catch {
    // 同上
  } finally {
    memberLoading.value = false
  }
}

function reloadMembers() {
  memberQuery.current = 1
  loadMembers()
}

function onTabChange(name) {
  if (name === 'members' && !memberLoaded.value) {
    loadMembers()
  }
}

/** 从台账进成员页签：带着这一行的群 ID，省得从抽屉标题里抄一串 19 位数字 */
function openMembers(row) {
  memberQuery.groupId = String(row.groupId)
  memberQuery.keyword = ''
  memberQuery.role = undefined
  tab.value = 'members'
  reloadMembers()
}

async function openDetail(groupId) {
  detail.value = null
  drawer.value = true
  detailLoading.value = true
  try {
    detail.value = await fetchGroupDetail(groupId)
  } catch {
    // 取不到就画空态：快照三段没有任何一段能以列表行冒充
  } finally {
    detailLoading.value = false
  }
}

async function toggleMute(row) {
  const next = row.muteAll !== 1
  try {
    const updated = await setGroupMuteAll(row.groupId, next)
    if (updated) {
      Object.assign(row, updated)
    }
  } catch {
    // 群已解散等情形由后端拒绝，接口层已弹提示
  }
}

/**
 * 强制解散：确认框里必须把「不可逆」和「不可逆的具体含义」都写出来。
 *
 * 只对活群给这个按钮（解散后的行连点都是幂等空转，不如不放），但确认文案仍然重复一遍后果——
 * 读到「成员关系不会恢复」的人，和看到按钮的人不一定是同一个。
 */
async function confirmDismiss(row) {
  try {
    await ElMessageBox.confirm(
      `强制解散「${row.name || `#${row.groupId}`}」（${formatNumber(row.memberCount)} 名成员）：` +
        `成员全部置为已退群、会话从此进不去、群里会收到一条「群已被解散」的系统通知。` +
        `历史消息仍留在库里，但成员关系不会恢复——这是不可逆操作，不是「暂时关掉」。`,
      '强制解散群组',
      { confirmButtonText: '确认解散', cancelButtonText: '取消', type: 'error' }
    )
  } catch {
    return
  }
  try {
    const updated = await dismissGroup(row.groupId)
    if (updated) {
      Object.assign(row, updated)
      if (updated.status === 1) {
        ElMessage.warning('解散指令未生效，请刷新后重试')
      } else {
        ElMessage.success('群组已强制解散')
      }
    } else {
      loadGroups()
    }
  } catch {
    // 接口层已弹提示
  }
}

function roleTag(role) {
  switch (role) {
    case 1:
      return { type: 'danger', text: '群主' }
    case 2:
      return { type: 'warning', text: '管理员' }
    case 3:
      return { type: 'info', text: '成员' }
    default:
      return { type: 'info', text: EMPTY_TEXT }
  }
}

function requestText(status) {
  switch (status) {
    case 0:
      return '待处理'
    case 1:
      return '已通过'
    case 2:
      return '已拒绝'
    default:
      return EMPTY_TEXT
  }
}

function requestTag(status) {
  switch (status) {
    case 0:
      return 'warning'
    case 1:
      return 'success'
    case 2:
      return 'info'
    default:
      return 'info'
  }
}

/**
 * 申请来源。库里只有 link 与 qrcode 两个值（GroupJoinRequest.SOURCE_*，写入前由
 * normalizeSource 折叠过），所以不认识的串一律原样显示而不是猜一个中文名：
 * 它是「这个人从哪进来的」的线索，猜错比不猜更糟。
 */
function sourceText(source) {
  switch (source) {
    case 'link':
      return '邀请链接'
    case 'qrcode':
      return '二维码'
    default:
      return source || EMPTY_TEXT
  }
}

onMounted(() => {
  loadGroups()
  drawerSize.value = window.innerWidth < 900 ? '100%' : '720px'
})
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.mb {
  margin-bottom: 16px;
}

.hint {
  margin-bottom: 16px;
}

.group-cell {
  align-items: center;
  display: flex;
  gap: 10px;
  min-width: 0;
}

.avatar {
  background: #f5f7fa;
  border-radius: 4px;
  flex: none;
  height: 36px;
  object-fit: cover;
  width: 36px;
}

.avatar-empty {
  align-items: center;
  color: #c0c4cc;
  display: flex;
  justify-content: center;
}

.stack {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.filters {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.keyword {
  width: 220px;
}

.status {
  width: 130px;
}

.uid {
  width: 160px;
}

.range {
  width: 340px;
}

.warning {
  color: #e6a23c;
}

.out {
  color: #f56c6c;
}

.ml {
  margin-left: 6px;
}

.detail-head {
  align-items: center;
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.detail-actions {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.block {
  margin-bottom: 16px;
}

.notice {
  white-space: pre-wrap;
}

.note {
  font-size: 12px;
  line-height: 1.6;
  margin: 8px 0 16px;
}

.sub-title {
  align-items: center;
  display: flex;
  font-size: 14px;
  margin: 16px 0 8px;
}
</style>
