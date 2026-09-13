<template>
  <el-drawer
    v-model="visible"
    title="群设置"
    :size="drawerWidth"
    :close-on-click-modal="true"
    direction="rtl"
    @opened="onOpened"
  >
    <template v-if="group.currentGroup">
      <!-- ==================== 群资料 ==================== -->
      <div class="group-settings__section">
        <div class="group-settings__avatar-row">
          <UserAvatar :src="group.currentGroup.avatar" :name="group.currentGroup.name" :size="56" />
          <div class="group-settings__info">
            <div class="group-settings__name-row">
              <span class="group-settings__group-name im-ellipsis">{{ group.currentGroup.name }}</span>
              <el-tag v-if="myRole === ROLE_OWNER" size="small" type="warning" effect="plain">群主</el-tag>
              <el-tag v-else-if="myRole === ROLE_ADMIN" size="small" type="primary" effect="plain">管理员</el-tag>
            </div>
            <div class="group-settings__meta">
              {{ group.currentGroup.memberCount || 0 }} 人
              · 成员上限 {{ group.currentGroup.maxMember || 200 }}
            </div>
          </div>
        </div>

        <!-- 群名编辑 -->
        <div v-if="canEditGroup" class="group-settings__edit-row">
          <el-input
            v-model.trim="editName"
            placeholder="修改群名称"
            maxlength="64"
            show-word-limit
            clearable
          >
            <template #append>
              <el-button :loading="savingName" :disabled="!editName" @click="saveName">保存</el-button>
            </template>
          </el-input>
        </div>

        <!-- 群公告 -->
        <div class="group-settings__notice">
          <div class="group-settings__label">群公告</div>
          <div v-if="canEditGroup" class="group-settings__notice-edit">
            <el-input
              v-model.trim="editNotice"
              type="textarea"
              :rows="2"
              placeholder="输入群公告"
              maxlength="512"
              show-word-limit
            />
            <el-button
              size="small"
              type="primary"
              :loading="savingNotice"
              @click="saveNotice"
            >保存公告</el-button>
          </div>
          <div v-else class="group-settings__notice-text">
            {{ group.currentGroup.notice || '暂无公告' }}
          </div>
        </div>
      </div>

      <!-- ==================== 全员禁言（管理员以上） ==================== -->
      <div v-if="canManage" class="group-settings__section">
        <div class="group-settings__label-row">
          <span class="group-settings__label">全员禁言</span>
          <el-switch
            :model-value="!!group.currentGroup.muteAll"
            @change="onToggleMuteAll"
          />
        </div>
        <div v-if="group.currentGroup.muteAll" class="group-settings__hint">
          已开启全员禁言，仅管理员与群主可发言
        </div>
      </div>

      <!-- ==================== 成员列表 ==================== -->
      <div class="group-settings__section">
        <div class="group-settings__label-row">
          <span class="group-settings__label">群成员（{{ group.members.length }}/{{ group.membersTotal }}）</span>
          <el-button v-if="canManage" size="small" text type="primary" :icon="Plus" @click="showInvite = true">
            邀请
          </el-button>
        </div>

        <div v-loading="group.membersLoading" class="group-settings__members im-scroll">
          <div
            v-for="member in group.members"
            :key="member.userId"
            class="group-settings__member"
          >
            <UserAvatar
              :src="member.avatar"
              :name="member.displayName || member.nicknameInGroup"
              :size="32"
              :online="!!member.online"
            />
            <div class="group-settings__member-info">
              <div class="group-settings__member-name im-ellipsis">
                {{ member.nicknameInGroup || member.displayName || member.nickname }}
                <el-tag v-if="member.role === ROLE_OWNER" size="small" type="warning" effect="plain">群主</el-tag>
                <el-tag v-else-if="member.role === ROLE_ADMIN" size="small" type="primary" effect="plain">管理员</el-tag>
                <el-tag v-if="member.muted" size="small" type="danger" effect="plain">禁言中</el-tag>
              </div>
              <div class="group-settings__member-sub im-ellipsis">
                {{ member.displayName || member.nickname || `@${member.userId}` }}
              </div>
            </div>

            <!-- 成员操作（管理员以上可操作其他人） -->
            <div v-if="canOperate(member)" class="group-settings__member-actions" @click.stop>
              <el-dropdown trigger="click" @command="(cmd) => onMemberAction(cmd, member)">
                <el-button text size="small">
                  <el-icon><MoreFilled /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      v-if="canMuteMember(member)"
                      :command="member.muted ? 'unmute' : 'mute'"
                    >
                      {{ member.muted ? '解除禁言' : '禁言' }}
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canSetAdmin(member)"
                      :command="member.role === ROLE_ADMIN ? 'unadmin' : 'setadmin'"
                    >
                      {{ member.role === ROLE_ADMIN ? '取消管理员' : '设为管理员' }}
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="canRemoveMember(member)"
                      command="remove"
                      divided
                    >
                      移除成员
                    </el-dropdown-item>
                    <el-dropdown-item
                      v-if="myRole === ROLE_OWNER && member.role !== ROLE_OWNER"
                      command="transfer"
                      divided
                    >
                      转让群主
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <!-- 加载更多成员 -->
          <div v-if="group.members.length < group.membersTotal" class="group-settings__load-more">
            <el-button link type="primary" :loading="group.membersLoading" @click="loadMoreMembers">
              加载更多成员
            </el-button>
          </div>
        </div>
      </div>

      <!-- ==================== 我的群昵称 ==================== -->
      <div class="group-settings__section">
        <div class="group-settings__label">我的群昵称</div>
        <div class="group-settings__edit-row">
          <el-input
            v-model.trim="myNickname"
            placeholder="留空则显示账号昵称"
            maxlength="32"
            show-word-limit
            clearable
          >
            <template #append>
              <el-button :loading="savingNickname" @click="saveNickname">保存</el-button>
            </template>
          </el-input>
        </div>
      </div>

      <!-- ==================== 退出 / 解散 ==================== -->
      <div class="group-settings__section group-settings__danger-zone">
        <el-button v-if="myRole === ROLE_OWNER" type="danger" plain @click="onDismiss">
          解散群聊
        </el-button>
        <el-button v-else type="danger" plain @click="onQuit">
          退出群聊
        </el-button>
      </div>
    </template>

    <template v-else>
      <el-empty description="群信息加载中" :image-size="80" />
    </template>

    <!-- ==================== 邀请成员对话框 ==================== -->
    <el-dialog v-model="showInvite" title="邀请成员" width="420px" :close-on-click-modal="false" append-to-body>
      <div v-if="!friend.friends.length" class="group-settings__invite-empty">
        还没有好友可以邀请
      </div>
      <template v-else>
        <el-input
          v-model.trim="inviteKeyword"
          placeholder="搜索好友"
          clearable
          :prefix-icon="Search"
          size="small"
        />
        <div class="group-settings__invite-list im-scroll">
          <label
            v-for="item in inviteFriends"
            :key="item.friendId"
            class="group-settings__invite-item"
          >
            <el-checkbox
              :model-value="inviteSelected.has(item.friendId)"
              @change="(val) => toggleInvite(item.friendId, val)"
            />
            <UserAvatar :src="item.avatar" :name="item.displayName || item.nickname" :size="28" />
            <span class="im-ellipsis">{{ item.displayName || item.nickname }}</span>
          </label>
        </div>
        <div v-if="inviteSelected.size" class="group-settings__invite-hint">
          已选 {{ inviteSelected.size }} 人
        </div>
      </template>
      <template #footer>
        <el-button @click="showInvite = false">取消</el-button>
        <el-button type="primary" :loading="inviting" :disabled="!inviteSelected.size" @click="doInvite">
          邀请
        </el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled, Plus, Search } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useGroupStore, ROLE_OWNER, ROLE_ADMIN, ROLE_MEMBER } from '@/stores/group'
import { useFriendStore } from '@/stores/friend'
import { useConversationStore } from '@/stores/conversation'
import { useAuthStore } from '@/stores/auth'
import { asId, sameId } from '@/utils/id'

/**
 * 群设置抽屉面板。
 *
 * 按当前用户角色分层展示：
 * - 所有成员：群名、公告、成员列表、我的群昵称、退出群聊
 * - 管理员 / 群主：邀请成员、全员禁言、成员操作（禁言、移除、设管理员）
 * - 群主额外：转让群主、解散群聊
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  groupId: { type: [String, Number], default: null }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
})

const router = useRouter()
const group = useGroupStore()
const friend = useFriendStore()
const conversation = useConversationStore()
const auth = useAuthStore()

const drawerWidth = computed(() => (typeof window !== 'undefined' && window.innerWidth < 768) ? '100%' : '380px')

/* ------------------------------ 角色判断 ------------------------------ */

const myRole = computed(() => Number(group.currentGroup?.myRole) || ROLE_MEMBER)
const canManage = computed(() => myRole.value === ROLE_OWNER || myRole.value === ROLE_ADMIN)
const canEditGroup = computed(() => canManage.value)

/** 是否能对某个成员执行操作：不能操作自己 */
function canOperate(member) {
  if (!member || sameId(member.userId, auth.userId)) return false
  if (myRole.value === ROLE_OWNER) return true
  if (myRole.value === ROLE_ADMIN) return Number(member.role) !== ROLE_OWNER
  return false
}

function canMuteMember(member) {
  // 群主可以禁任何人，管理员可以禁普通成员和另一个管理员（但不能动群主）
  if (myRole.value === ROLE_OWNER) return true
  if (myRole.value === ROLE_ADMIN) return Number(member.role) !== ROLE_OWNER
  return false
}

function canSetAdmin(member) {
  // 只有群主能设/取消管理员
  return myRole.value === ROLE_OWNER && Number(member.role) !== ROLE_OWNER
}

function canRemoveMember(member) {
  // 群主可移除任何人，管理员只能移除普通成员
  if (myRole.value === ROLE_OWNER) return true
  if (myRole.value === ROLE_ADMIN) return Number(member.role) === ROLE_MEMBER
  return false
}

/* ------------------------------ 初始化 ------------------------------ */

async function onOpened() {
  if (props.groupId) {
    await Promise.all([
      group.fetchDetail(props.groupId),
      group.fetchMembers(props.groupId)
    ])
  }
}

/* ------------------------------ 群名 / 公告编辑 ------------------------------ */

const editName = computed({
  get: () => group.currentGroup?.name || '',
  set: () => {} // 通过 saveName 写入
})
const editNotice = ref('')
const savingName = ref(false)
const savingNotice = ref(false)

watch(() => group.currentGroup, (g) => {
  if (g) {
    editNotice.value = g.notice || ''
  }
}, { immediate: true })

async function saveName() {
  if (!editName.value || !props.groupId) return
  savingName.value = true
  try {
    await group.updateGroup(props.groupId, { name: editName.value })
    ElMessage.success('群名已更新')
  } catch { /* request.js 已处理 */ } finally {
    savingName.value = false
  }
}

async function saveNotice() {
  if (!props.groupId) return
  savingNotice.value = true
  try {
    await group.updateGroup(props.groupId, { notice: editNotice.value })
    ElMessage.success('公告已更新')
  } catch { /* request.js 已处理 */ } finally {
    savingNotice.value = false
  }
}

/* ------------------------------ 全员禁言 ------------------------------ */

async function onToggleMuteAll(val) {
  try {
    await group.toggleMuteAll(props.groupId, val)
    ElMessage.success(val ? '已开启全员禁言' : '已关闭全员禁言')
  } catch { /* request.js 已处理 */ }
}

/* ------------------------------ 成员操作 ------------------------------ */

async function onMemberAction(cmd, member) {
  const gid = props.groupId
  if (!gid) return
  const name = member.nicknameInGroup || member.displayName || member.nickname || ''
  try {
    switch (cmd) {
      case 'mute': {
        const { value } = await ElMessageBox.prompt('禁言时长（分钟），留空表示永久', `禁言「${name}」`, {
          inputPlaceholder: '留空为永久禁言',
          confirmButtonText: '禁言',
          cancelButtonText: '取消',
          inputValidator: (v) => !v || (/^\d+$/.test(v) && Number(v) > 0) || '请输入正整数'
        })
        const minutes = value ? Number(value) : undefined
        await group.muteMember(gid, member.userId, true, minutes)
        ElMessage.success('已禁言')
        break
      }
      case 'unmute':
        await group.muteMember(gid, member.userId, false)
        ElMessage.success('已解除禁言')
        break
      case 'setadmin':
        await ElMessageBox.confirm(`将「${name}」设为管理员？`, '设置管理员', {
          confirmButtonText: '确定', cancelButtonText: '取消'
        })
        await group.updateRole(gid, member.userId, ROLE_ADMIN)
        ElMessage.success('已设为管理员')
        break
      case 'unadmin':
        await ElMessageBox.confirm(`取消「${name}」的管理员身份？`, '取消管理员', {
          confirmButtonText: '确定', cancelButtonText: '取消'
        })
        await group.updateRole(gid, member.userId, ROLE_MEMBER)
        ElMessage.success('已取消管理员')
        break
      case 'remove':
        await ElMessageBox.confirm(`将「${name}」移出群聊？`, '移除成员', {
          confirmButtonText: '移除', cancelButtonText: '取消', type: 'warning'
        })
        await group.removeMember(gid, member.userId)
        ElMessage.success('已移除')
        break
      case 'transfer':
        await ElMessageBox.confirm(`将群主转让给「${name}」？转让后你将成为普通成员。`, '转让群主', {
          confirmButtonText: '转让', cancelButtonText: '取消', type: 'warning'
        })
        await group.transferGroup(gid, member.userId)
        ElMessage.success('群主已转让')
        break
      default:
        break
    }
  } catch {
    // 用户取消或请求已处理
  }
}

/* ------------------------------ 加载更多成员 ------------------------------ */

function loadMoreMembers() {
  const nextPage = Math.ceil(group.members.length / 50) + 1
  group.fetchMembers(props.groupId, nextPage)
}

/* ------------------------------ 我的群昵称 ------------------------------ */

const myNickname = ref('')
const savingNickname = ref(false)

watch(() => group.currentGroup, (g) => {
  if (g) {
    myNickname.value = g.myNickname || ''
  }
}, { immediate: true })

async function saveNickname() {
  if (!props.groupId) return
  savingNickname.value = true
  try {
    await group.updateMyNickname(props.groupId, myNickname.value)
    ElMessage.success('群昵称已更新')
  } catch { /* request.js 已处理 */ } finally {
    savingNickname.value = false
  }
}

/* ------------------------------ 退出 / 解散 ------------------------------ */

async function onQuit() {
  try {
    await ElMessageBox.confirm('退出后需要被重新邀请才能再次加入群聊。', '退出群聊', {
      confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  try {
    await group.quitGroup(props.groupId)
    // 同时隐藏会话
    const convId = group.currentGroup?.conversationId
    if (convId) {
      await conversation.remove(convId)
    }
    visible.value = false
    ElMessage.success('已退出群聊')
    router.replace({ name: 'chat' })
  } catch { /* request.js 已处理 */ }
}

async function onDismiss() {
  try {
    await ElMessageBox.confirm('解散后所有成员将被移出群聊，聊天记录保留但群不再存在。', '解散群聊', {
      confirmButtonText: '解散', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  try {
    await group.dismissGroup(props.groupId)
    const convId = group.currentGroup?.conversationId
    if (convId) {
      await conversation.remove(convId)
    }
    visible.value = false
    ElMessage.success('群聊已解散')
    router.replace({ name: 'chat' })
  } catch { /* request.js 已处理 */ }
}

/* ------------------------------ 邀请成员 ------------------------------ */

const showInvite = ref(false)
const inviting = ref(false)
const inviteKeyword = ref('')
const inviteSelected = reactive(new Set())

function toggleInvite(userId, checked) {
  if (checked) {
    inviteSelected.add(userId)
  } else {
    inviteSelected.delete(userId)
  }
}

/** 过滤掉已在群内的成员 */
const inviteFriends = computed(() => {
  const memberIds = new Set(group.members.map((m) => String(m.userId)))
  const kw = inviteKeyword.value.toLowerCase()
  return friend.friends.filter((item) => {
    if (memberIds.has(String(item.friendId))) return false
    if (!kw) return true
    const name = (item.displayName || item.nickname || '').toLowerCase()
    const account = (item.username || '').toLowerCase()
    return name.includes(kw) || account.includes(kw)
  })
})

async function doInvite() {
  if (!inviteSelected.size || !props.groupId) return
  inviting.value = true
  try {
    await group.addMembers(props.groupId, Array.from(inviteSelected))
    showInvite.value = false
    inviteSelected.clear()
    inviteKeyword.value = ''
    ElMessage.success('已邀请成员')
  } catch { /* request.js 已处理 */ } finally {
    inviting.value = false
  }
}

// 好友列表为空时主动拉一次
watch(visible, (val) => {
  if (val && friend.friends.length === 0 && !friend.loading) {
    friend.fetchFriends()
  }
})
</script>

<style scoped>
.group-settings__section {
  padding: 16px 0;
  border-bottom: 1px solid var(--im-border);
}

.group-settings__section:last-child {
  border-bottom: none;
}

.group-settings__avatar-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
}

.group-settings__info {
  flex: 1;
  min-width: 0;
}

.group-settings__name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.group-settings__group-name {
  font-size: 16px;
  font-weight: 600;
}

.group-settings__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.group-settings__label {
  font-size: 13px;
  font-weight: 500;
  color: var(--im-text);
  margin-bottom: 8px;
}

.group-settings__label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.group-settings__label-row .group-settings__label {
  margin-bottom: 0;
}

.group-settings__edit-row {
  margin-top: 8px;
}

.group-settings__notice {
  margin-top: 12px;
}

.group-settings__notice-edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.group-settings__notice-text {
  font-size: 13px;
  color: var(--im-text-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
}

.group-settings__hint {
  font-size: 12px;
  color: var(--im-text-secondary);
  margin-top: 4px;
}

/* 成员列表 */
.group-settings__members {
  max-height: 360px;
  overflow-y: auto;
}

.group-settings__member {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.group-settings__member-info {
  flex: 1;
  min-width: 0;
}

.group-settings__member-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.group-settings__member-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.group-settings__member-actions {
  flex: none;
}

.group-settings__load-more {
  padding: 8px 0;
  text-align: center;
}

/* 危险操作区 */
.group-settings__danger-zone {
  display: flex;
  justify-content: center;
  padding: 20px 0;
}

/* 邀请对话框 */
.group-settings__invite-empty {
  padding: 20px 0;
  text-align: center;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.group-settings__invite-list {
  max-height: 260px;
  overflow-y: auto;
  margin-top: 10px;
  border: 1px solid var(--im-border);
  border-radius: 4px;
}

.group-settings__invite-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 13px;
}

.group-settings__invite-item:hover {
  background: #f5f5f5;
}

.group-settings__invite-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--im-text-secondary);
}
</style>
