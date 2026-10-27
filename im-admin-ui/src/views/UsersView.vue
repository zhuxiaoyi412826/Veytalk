<template>
  <div class="users">
    <div class="toolbar">
      <h2 class="page-title">用户管理</h2>
      <span class="grow" />
      <el-input
        v-model="query.keyword"
        :maxlength="30"
        clearable
        class="keyword"
        placeholder="账号 / 昵称 / 手机号前缀"
        @clear="reload"
        @keyup.enter="reload"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-select v-model="query.status" class="status" clearable placeholder="全部状态" @change="reload">
        <el-option :value="1" label="正常" />
        <el-option :value="0" label="已禁用" />
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
      title="对用户只有「禁用（可限时）/ 恢复」两个动作"
      description="没有代改资料与代改密码：资料有用户自助入口（后台代改会绕过昵称与头像的敏感词校验），代改密码等于把「知道账号即可接管账号」做成常规操作，真要做得先补审计表。手机号是脱敏值，关键词按手机号搜需要输完整前缀。限时封禁到期后由服务端自动解封（分钟级误差）；永久封禁不会自己开。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="用户" min-width="220">
          <template #default="{ row }">
            <div class="user-cell">
              <AvatarCell :url="row.avatar" :name="row.nickname" :size="34" />
              <div class="user-text">
                <span class="ellipsis">{{ row.nickname || row.account }}</span>
                <span class="muted">#{{ row.userId }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="账号" min-width="120" prop="account" show-overflow-tooltip />
        <el-table-column label="手机号" min-width="130" prop="phone" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="num">{{ row.phone || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="190">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" effect="plain" size="small">
              {{ row.status === 1 ? '正常' : '已禁用' }}
            </el-tag>
            <!-- 封禁时长直接写在行上：管理员下一个要回答的问题就是「什么时候得再看他」。
                 永久封禁单独标出来，因为它意味着「不手动恢复就永远不会自己开」 -->
            <el-tag
              v-if="row.status !== 1"
              class="ml"
              effect="plain"
              size="small"
              :type="row.disableUntil ? 'warning' : 'info'"
            >
              {{ row.disableUntil ? `剩 ${banRemainingText(row.disableUntil)}` : '永久' }}
            </el-tag>
            <el-tag v-if="row.admin" class="ml" effect="plain" size="small" type="warning">管理员</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="在线" width="80">
          <template #default="{ row }">
            <span :class="row.online ? 'online' : 'muted'">{{ row.online ? '在线' : '离线' }}</span>
          </template>
        </el-table-column>
        <el-table-column align="right" label="积分" width="100">
          <template #default="{ row }">
            <span class="num">{{ formatNumber(row.balance) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="150">
          <template #default="{ row }">
            <span class="num">{{ formatListTime(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column align="center" label="未处置工单" width="110">
          <template #default="{ row }">
            <el-button v-if="row.pendingReportCount > 0" text type="danger" @click="goReports(row)">
              {{ row.pendingReportCount }}
            </el-button>
            <span v-else class="muted">0</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="auth.can('system:role:manage')"
          align="center"
          label="后台角色"
          width="110"
        >
          <template #default="{ row }">
            <el-button text type="primary" @click="openRoles(row)">配角色</el-button>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="120">
          <template #default="{ row }">
            <!-- 当前登录的自己：后端直接拒（禁了就没人能进后台），
                 把按钮换成一句说明比让人点了才知道要好 -->
            <span v-if="row.userId === auth.userId" class="muted">当前账号</span>
            <el-button v-else-if="row.status === 1" text type="danger" @click="confirmDisable(row)">禁用</el-button>
            <el-button v-else text type="success" @click="confirmEnable(row)">恢复</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有匹配的用户" :image-size="80" />
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

    <!-- 角色分配弹框：整体替换语义，提交的是复选框最终状态 -->
    <el-dialog v-model="roleDialog.visible" :title="roleDialogTitle" width="440px">
      <el-alert
        class="role-hint"
        :closable="false"
        type="info"
        title="勾选的结果会整体替换该用户的后台角色；清空全部勾选即收回其所有后台权限"
      />
      <el-checkbox-group v-model="roleDialog.checked" v-loading="roleDialog.loading" class="role-group">
        <el-checkbox v-for="role in roleDialog.options" :key="role.id" :value="role.id" class="role-item">
          <div class="role-line">
            <span class="role-name">{{ role.roleName }}</span>
            <span class="code muted">{{ role.roleCode }}</span>
          </div>
          <div class="muted role-desc">{{ role.description }}</div>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button :disabled="roleDialog.saving" @click="roleDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="roleDialog.saving" @click="saveRoles">保存</el-button>
     </template>
    </el-dialog>

    <!-- 禁用弹框：事由必填 + 时长可选。用一个表单而不是 ElMessageBox.prompt，
         是因为 prompt 只装得下一个输入框，而这里要同时收两件都不传就会出错的事 -->
    <el-dialog v-model="banDialog.visible" :title="`禁用账号：${banDialog.name}`" width="460px">
      <el-alert
        class="ban-hint"
        :closable="false"
        type="warning"
        title="确认后立即踢下线且无法重新登录；事由会写进服务端日志，并随踢下线报文送达对方"
      />
      <el-form label-position="top">
        <el-form-item label="事由（必填）">
          <el-input
            v-model="banDialog.reason"
            :maxlength="200"
            :rows="2"
            show-word-limit
            type="textarea"
            placeholder="写清依据，事后没人能替你回忆当时为什么关掉了这个人"
          />
        </el-form-item>
        <el-form-item label="封禁时长">
          <div class="ban-row">
            <el-select v-model="banDialog.minutes" class="ban-pick" placeholder="选择时长">
              <el-option
                v-for="opt in BAN_DURATIONS"
                :key="String(opt.value)"
                :label="opt.label"
                :value="opt.value"
              />
            </el-select>
            <el-input-number
              v-if="banDialog.minutes === CUSTOM"
              v-model="banDialog.customMinutes"
              :max="CUSTOM_MAX_MINUTES"
              :min="1"
              :step="60"
              class="ban-custom"
              controls-position="right"
            />
          </div>
          <div class="muted ban-note">{{ banPreview }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="banDialog.saving" @click="banDialog.visible = false">取消</el-button>
        <el-button
          :disabled="!banReasonReady"
          :loading="banDialog.saving"
          type="danger"
          @click="submitBan"
        >
          确认禁用
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assignUserRoles, disableUser, enableUser, fetchRoles, fetchUserDetail, fetchUserPage, fetchUserRoles } from '@/api/admin'
import AvatarCell from '@/components/AvatarCell.vue'
import { formatListTime, formatNumber } from '@/utils/format'
import { BAN_DURATIONS, CUSTOM, CUSTOM_MAX_MINUTES, banRemainingText, durationText } from '@/utils/ban'
import { useAuthStore } from '@/stores/auth'

/**
 * 用户管理列表。
 *
 * 列表口径由后端钉死为<b>注册时间倒序</b>（接口没有 order by 参数），这里也就不放排序表头：
 * 放一个点了没反应的排序箭头比不放更糟。
 *
 * 关键词的匹配范围与工单队列<b>不一样</b>，值得写清楚：这里按账号 / 昵称 / 手机号前缀，
 * 而工单队列的纯数字关键词是按 ID。所以看板上「被举报榜」那一行点进的是工单页而不是本页——
 * 传用户 ID 到这里只会搜出一个空列表。
 */
const auth = useAuthStore()
const router = useRouter()

const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ keyword: '', status: undefined, current: 1, size: 20 })

/**
 * 禁用弹框的状态。
 *
 * minutes 存的是选项原值（可能是 null 也可能是 'custom'），而不是一个算好的分钟数：
 * 下拉得能回显「你选的是永久还是自定义」，把它们提前收敛成数字就回不去了。
 * 真正的分钟数由 {@link banMinutes} 现算。
 */
const banDialog = reactive({
  visible: false,
  saving: false,
  userId: null,
  name: '',
  reason: '',
  minutes: 1440,
  customMinutes: 1440
})

async function load() {
  loading.value = true
  try {
    const page = await fetchUserPage({
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

async function confirmDisable(row) {
  banDialog.userId = row.userId
  banDialog.name = row.nickname || row.account || String(row.userId)
  banDialog.reason = ''
  // 默认 1 天而不是永久：「反悔了还要人工来解」比「多封几小时」麻烦得多，
  // 弹框默认值应当倒向可逆的那一档
  banDialog.minutes = 1440
  banDialog.customMinutes = 1440
  banDialog.visible = true
}

/** 真正要下发的分钟数：自定义档取输入框，永久档取 null（axios 会把 null 参数整个省掉） */
const banMinutes = computed(() => {
  if (banDialog.minutes === CUSTOM) {
    return banDialog.customMinutes > 0 ? banDialog.customMinutes : null
  }
  return banDialog.minutes
})

const banReasonReady = computed(() => banDialog.reason.trim().length > 0)

/**
 * 弹框里那句「预计什么时候开」。
 *
 * 标<b>预计</b>是必需的：这一行按浏览器时钟算，而真正的到期时间由服务端算并落库，
 * 两边差几分钟很常见。不把这句话写成「将于」，管理员就会拿它去回答用户的「我几时能登」。
 */
const banPreview = computed(() => {
  const minutes = banMinutes.value
  if (!minutes) {
    return '永久封禁：不会自动解封，只能由管理员在本页恢复'
  }
  return `预计 ${dayjs().add(minutes, 'minute').format('YYYY-MM-DD HH:mm')} 自动解封（以服务端时间为准）`
})

async function submitBan() {
  if (!banReasonReady.value) {
    return
  }
  banDialog.saving = true
  try {
    const updated = await disableUser(banDialog.userId, banDialog.reason.trim(), banMinutes.value)
    const row = rows.value.find((item) => item.userId === banDialog.userId)
    applyRow(row, updated)
    ElMessage.success(banMinutes.value ? `已禁用 ${durationText(banMinutes.value)}，到期自动解封` : '账号已永久禁用')
    banDialog.visible = false
  } catch {
    // 后端可能回「不能禁自己」「最后一个可用管理员」「时长非法」，提示已统一弹过；
    // 弹框不关，让管理员改一下再试，而不是重新填一遍事由
  } finally {
    banDialog.saving = false
  }
}

async function confirmEnable(row) {
  // 限时封禁还在期内时，这个按钮的真实含义是「提前解封」，得说破：
  // 只说「恢复」会让人以为自己点了个中性操作，而他其实是把一个判了 7 天的人当天放出去
  const early = row.status !== 1 && row.disableUntil && dayjs(row.disableUntil).isAfter(dayjs())
  try {
    await ElMessageBox.confirm(
      early
        ? `${row.nickname || row.account} 的封禁原本还有 ${banRemainingText(row.disableUntil)} 到期，确认提前解封？到期时间会被清空。`
        : `恢复 ${row.nickname || row.account} 的登录能力；已断开的连接不会自动重连，需要他重新登录。`,
      early ? '提前解封' : '恢复账号',
      { confirmButtonText: early ? '确认提前解封' : '确认恢复', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const updated = await enableUser(row.userId)
    applyRow(row, updated)
    ElMessage.success('账号已恢复')
  } catch {
    // 同上
  }
}

/**
 * 用响应里的最新那一行就地替换，而不是整页重查。
 *
 * 重查会把当前页翻回第一页之外的位置也可能变化（禁用不影响排序，但在线状态会变），
 * 更麻烦的是：一屏 20 行里只有一行动了，其余 19 行的滚动位置与 hover 全被抹掉。
 */
function applyRow(row, updated) {
  if (!updated) {
    return
  }
  Object.assign(row, updated)
}

function goReports(row) {
  router.push({ name: 'reports', query: { keyword: String(row.userId) } })
}

/* ==================== 角色分配 ==================== */

const roleDialog = reactive({
  visible: false,
  loading: false,
  saving: false,
  userId: null,
  options: [],
  checked: []
})

const roleDialogTitle = computed(() => {
  const row = rows.value.find((r) => r.userId === roleDialog.userId)
  return row ? `配角色：${row.nickname || row.account}` : '配角色'
})

/**
 * 先拉可分配角色（下拉选项），再拉该用户现有角色并回填勾选。
 *
 * 两个接口分开而不是一个回「带当前选中标记的角色列表」：后端把「列角色」与「查某人角色」
 * 当成两件事（前者给下拉、后者可单独复用），前端就顺这个形状拼，不要求后端为界面合并接口。
 */
async function openRoles(row) {
  roleDialog.userId = row.userId
  roleDialog.checked = []
  roleDialog.visible = true
  roleDialog.loading = true
  try {
    const [options, owned] = await Promise.all([roleDialog.options.length ? roleDialog.options : fetchRoles(), fetchUserRoles(row.userId)])
    roleDialog.options = options || []
    roleDialog.checked = (owned || []).map((role) => role.id)
  } catch {
    // 接口层已弹提示；拉失败就留空选项，不阻断弹框（用户可取消）
  } finally {
    roleDialog.loading = false
  }
}

async function saveRoles() {
  roleDialog.saving = true
  try {
    await assignUserRoles(roleDialog.userId, roleDialog.checked)
    ElMessage.success('角色已更新')
    roleDialog.visible = false
    // 就地刷这一行的「管理员」标记（角色变了可能影响 admin 列）：重查当页而不整表重刷，
    // 保留滚动位置；后端 setUserRoles 已即时失效该用户权限缓存
    const updated = await fetchUserDetail(roleDialog.userId)
    const target = rows.value.find((r) => r.userId === roleDialog.userId)
    if (updated && target) {
      Object.assign(target, updated)
    }
  } catch {
    // 后端会拒「改自己 / 摘最后一个管理员 / 未知角色」，提示已统一弹过
  } finally {
    roleDialog.saving = false
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
  width: 240px;
}

.status {
  width: 130px;
}

.hint {
  margin-bottom: 16px;
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

.ml {
  margin-left: 6px;
}

.online {
  color: #67c23a;
}

.role-hint {
  margin-bottom: 12px;
}

.role-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.role-item {
  align-items: flex-start;
  height: auto;
  margin-right: 0;
}

.role-line {
  display: flex;
  gap: 8px;
}

.role-name {
  font-weight: 600;
}

.role-desc {
  font-size: 12px;
  line-height: 1.5;
  white-space: normal;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.ban-hint {
  margin-bottom: 12px;
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

.ban-note {
  font-size: 12px;
  line-height: 1.6;
}
</style>
