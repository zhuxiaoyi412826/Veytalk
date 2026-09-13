<template>
  <div class="user-profile im-scroll">
    <div class="user-profile__inner">
      <div class="user-profile__back">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
      </div>

      <el-skeleton v-if="loading" :rows="5" animated class="user-profile__card" />

      <!-- 用户 ID 打错、账号已注销都会走到这里，给一个明确的说法而不是空白页 -->
      <el-empty v-else-if="!card" description="用户不存在或已注销">
        <el-button type="primary" @click="goBack">返回</el-button>
      </el-empty>

      <template v-else>
        <!-- ==================== 身份 ==================== -->
        <section class="user-profile__card">
          <div class="user-profile__head">
            <UserAvatar :src="card.avatar" :name="displayName" :size="88" :online="!!card.online" />
            <div class="user-profile__identity">
              <div class="user-profile__nickname">
                <span class="user-profile__nickname-text">{{ displayName }}</span>
                <el-tag v-if="card.remark" size="small" effect="plain">已备注</el-tag>
                <el-tag v-if="blocked" size="small" type="danger" effect="plain">我已拉黑</el-tag>
                <el-tag v-if="blockedByOther" size="small" type="warning" effect="plain">对方已拉黑我</el-tag>
              </div>
              <div class="user-profile__account">@{{ card.username }}</div>
              <div class="user-profile__signature">{{ card.signature || '这个人很懒，什么都没留下' }}</div>
            </div>
          </div>

          <el-descriptions :column="2" border size="small" class="user-profile__facts">
            <el-descriptions-item label="用户 ID">{{ card.userId }}</el-descriptions-item>
            <el-descriptions-item label="性别">{{ genderText(card.gender) }}</el-descriptions-item>
            <el-descriptions-item label="在线状态">
              <span class="user-profile__state" :class="{ 'user-profile__state--off': !card.online }">
                <i class="user-profile__dot"></i>{{ card.online ? '在线' : '离线' }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="注册时间">
              {{ formatDateTime(card.createTime) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="好友关系">
              {{ isSelf ? '这是你自己' : isFriend ? '已是好友' : '非好友' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="relation" label="好友分组">
              {{ relation.groupName || '未分组' }}
            </el-descriptions-item>
            <!-- 关系建立时间只有 FriendVO 才带，UserCardVO 里没有这个字段 -->
            <el-descriptions-item v-if="relation" label="成为好友">
              {{ formatDateTime(relation.createTime) || '—' }}
            </el-descriptions-item>
            <el-descriptions-item v-if="relatedRequest" label="申请记录" :span="2">
              {{ formatDateTime(relatedRequest.createTime) }} · {{ relatedRequest.statusDesc }}
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="blockedByOther" class="user-profile__warn">
            对方已将你加入黑名单，你发出的消息不会送达，也无法创建会话。
          </div>
          <div v-else-if="blocked" class="user-profile__warn">
            你已将对方加入黑名单，取消拉黑后才能继续收发消息。
          </div>
        </section>

        <!-- ==================== 操作 ==================== -->
        <section class="user-profile__card">
          <div class="user-profile__card-title">{{ isSelf ? '我的资料' : '操作' }}</div>

          <div v-if="isSelf" class="user-profile__actions">
            <el-button type="primary" :icon="Setting" @click="goProfile">前往个人中心</el-button>
            <span class="user-profile__tip">资料卡展示的是别人看到的你，修改资料请到个人中心。</span>
          </div>

          <div v-else class="user-profile__actions">
            <!--
              不可用时置灰而不是隐藏，并把原因写在按钮旁边：
              后端 createSingle 对「非好友 / 任一方拉黑」一律拒绝，
              让按钮可点再弹一条错误码翻译过来的提示，用户只会觉得是系统坏了。
            -->
            <el-tooltip :content="chatDisabledReason" :disabled="!chatDisabledReason" placement="top">
              <span>
                <el-button type="primary" :icon="ChatDotRound" :disabled="!canChat" @click="startChat">
                  发起聊天
                </el-button>
              </span>
            </el-tooltip>

            <template v-if="isFriend">
              <el-button :icon="EditPen" @click="openRemark">修改备注</el-button>
              <el-button
                :type="blocked ? 'success' : 'warning'"
                plain
                :icon="blocked ? RefreshLeft : CircleClose"
                :loading="acting === 'block'"
                @click="toggleBlock"
              >
                {{ blocked ? '取消拉黑' : '拉黑' }}
              </el-button>
              <el-button
                type="danger"
                plain
                :icon="Delete"
                :loading="acting === 'remove'"
                @click="removeFriend"
              >
                删除好友
              </el-button>
            </template>

            <el-button v-else type="success" :icon="Plus" :disabled="!canApply" @click="openApply">
              添加好友
            </el-button>

            <span v-if="chatDisabledReason" class="user-profile__tip">{{ chatDisabledReason }}</span>
          </div>
        </section>
      </template>
    </div>

    <!-- ==================== 添加好友 ==================== -->
    <el-dialog v-model="applyDialog.visible" title="添加好友" width="420px">
      <div class="user-profile__dialog-target">
        <UserAvatar :src="card && card.avatar" :name="displayName" :size="40" />
        <div>
          <div>{{ displayName }}</div>
          <div class="user-profile__account">@{{ card && card.username }}</div>
        </div>
      </div>
      <el-input
        v-model="applyDialog.message"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        placeholder="填写验证消息，让对方知道你是谁"
      />
      <template #footer>
        <el-button @click="applyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="applyDialog.saving" @click="submitApply">发送申请</el-button>
      </template>
    </el-dialog>

    <!-- ==================== 修改备注 ==================== -->
    <el-dialog
      v-model="remarkDialog.visible"
      title="修改备注"
      width="380px"
      @opened="focusRemarkInput"
    >
      <el-input
        ref="remarkInputRef"
        v-model.trim="remarkDialog.value"
        maxlength="32"
        show-word-limit
        clearable
        placeholder="留空则显示对方昵称"
        @keyup.enter="submitRemark"
      />
      <template #footer>
        <el-button @click="remarkDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="remarkDialog.saving" @click="submitRemark">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ChatDotRound,
  CircleClose,
  Delete,
  EditPen,
  Plus,
  RefreshLeft,
  Setting
} from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { fetchUserCard } from '@/api/user'
import { fetchFriendCard } from '@/api/friend'
import { useAuthStore } from '@/stores/auth'
import { useFriendStore } from '@/stores/friend'
import { useConversationStore } from '@/stores/conversation'
import { asId, sameId } from '@/utils/id'
import { formatDateTime, genderText } from '@/utils/format'

defineOptions({ name: 'UserProfile' })

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const friend = useFriendStore()
const conversation = useConversationStore()

/** 路由参数一律是字符串，而后端 ID 是雪花数字，比较前必须先归一化 */
const targetId = computed(() => asId(route.params.id))

const loading = ref(false)
const card = ref(null)
/** 好友关系详情，仅在已是好友且关系仍然存在时才有值 */
const relation = ref(null)
/** 当前正在执行的操作，用于给对应按钮上 loading */
const acting = ref('')

const isSelf = computed(() => sameId(card.value && card.value.userId, auth.userId))
const isFriend = computed(() => !!(card.value && card.value.friend))
const blocked = computed(() => !!(card.value && card.value.blocked))
const blockedByOther = computed(() => !!(card.value && card.value.blockedByOther))
/** 后端 createSingle 要求「已是好友」且「双方都没拉黑」，三个条件缺一不可 */
const canChat = computed(() => isFriend.value && !blocked.value && !blockedByOther.value)
const canApply = computed(() => !isSelf.value && auth.hasPermission('friend:apply'))
const displayName = computed(() => {
  const target = card.value
  if (!target) {
    return ''
  }
  return target.remark || target.nickname || target.username || `用户${target.userId}`
})

const chatDisabledReason = computed(() => {
  if (isSelf.value || canChat.value) {
    return ''
  }
  if (blockedByOther.value) {
    return '对方已将你加入黑名单，无法发起聊天'
  }
  if (blocked.value) {
    return '你已将对方加入黑名单，取消拉黑后才能发消息'
  }
  return '添加为好友后才能发消息'
})

/**
 * 与这个人的好友申请记录，用于展示「好友申请时间」。
 *
 * 只能从 store 里已经加载过的申请列表中找：后端没有「按目标用户查申请」的接口，
 * 而在这里翻页拉全量会覆盖 friend.sent / friend.received，把申请管理页的分页状态搞乱
 * （那个页面只在列表为空时才重新拉数据）。所以这是尽力而为 —— 找到就显示，
 * 找不到整行不渲染，不会给出一个错误的时间。
 */
const relatedRequest = computed(() => {
  const id = targetId.value
  if (!id || isFriend.value) {
    return null
  }
  return (
    friend.sent.find((item) => sameId(item.peerUserId, id)) ||
    friend.received.find((item) => sameId(item.peerUserId, id)) ||
    null
  )
})

/* -------------------------------- 加载 -------------------------------- */

/**
 * 请求序号。快速在两个用户之间切换时，先发出的那个请求可能后返回，
 * 不做这层判断就会把旧用户的资料糊到新用户的页面上。
 */
let loadToken = 0

async function load() {
  const id = targetId.value
  if (!id) {
    return
  }
  const token = ++loadToken
  loading.value = true
  card.value = null
  relation.value = null
  try {
    const vo = await fetchUserCard(id)
    if (token !== loadToken) {
      return
    }
    card.value = vo
    if (vo && vo.friend) {
      try {
        relation.value = await fetchFriendCard(id, { silent: true })
      } catch {
        // 对方刚好在这期间删了好友：把资料卡上的关系位纠正过来，
        // 否则界面会摆着一排点了必然报错的按钮
        if (token === loadToken && card.value) {
          card.value.friend = false
        }
        relation.value = null
      }
    }
  } catch {
    // 用户不存在或已注销，card 保持 null，模板走空状态分支
  } finally {
    if (token === loadToken) {
      loading.value = false
    }
  }
}

// 同一个路由下换 :id 时组件会被复用，必须靠 watcher 而不是 onMounted
watch(targetId, load, { immediate: true })

/* -------------------------------- 操作 -------------------------------- */

function goProfile() {
  router.push({ name: 'profile' })
}

async function startChat() {
  if (!canChat.value) {
    return
  }
  try {
    // openWith 内部是「有则取、无则建」，重复点不会产生第二个会话
    const conversationId = await conversation.openWith(targetId.value)
    router.push({ name: 'chat', params: { conversationId: asId(conversationId) } })
  } catch {
    // 提示已由 request.js 弹出
  }
}

const applyDialog = reactive({ visible: false, saving: false, message: '' })

function openApply() {
  applyDialog.message = `你好，我是 ${auth.nickname}`
  applyDialog.visible = true
}

async function submitApply() {
  applyDialog.saving = true
  try {
    await friend.apply({
      targetUserId: targetId.value,
      verifyMessage: applyDialog.message.trim() || undefined,
      source: 'web'
    })
    applyDialog.visible = false
    ElMessage.success('好友申请已发送，等待对方确认')
  } catch {
    // 已经申请过、对方设置了拒绝添加等情况，提示已弹出，保留对话框让用户改验证消息
  } finally {
    applyDialog.saving = false
  }
}

const remarkInputRef = ref(null)
const remarkDialog = reactive({ visible: false, saving: false, value: '' })

function openRemark() {
  remarkDialog.value = (card.value && card.value.remark) || ''
  remarkDialog.visible = true
}

function focusRemarkInput() {
  remarkInputRef.value && remarkInputRef.value.focus()
}

async function submitRemark() {
  remarkDialog.saving = true
  try {
    const remark = remarkDialog.value.trim()
    await friend.updateRemark(targetId.value, remark)
    if (card.value) {
      card.value.remark = remark
    }
    remarkDialog.visible = false
    ElMessage.success('备注已更新')
  } catch {
    // 提示已弹出
  } finally {
    remarkDialog.saving = false
  }
}

async function toggleBlock() {
  const next = !blocked.value
  const tip = next
    ? '拉黑后对方发来的消息不再推送给你，你也不能向对方发消息。确定拉黑？'
    : '取消拉黑后双方可以正常收发消息。'
  try {
    await ElMessageBox.confirm(tip, next ? '拉黑好友' : '取消拉黑', {
      type: 'warning',
      confirmButtonText: next ? '拉黑' : '取消拉黑',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  acting.value = 'block'
  try {
    if (next) {
      await friend.block(targetId.value)
    } else {
      await friend.unblock(targetId.value)
    }
    if (card.value) {
      card.value.blocked = next
    }
    ElMessage.success(next ? '已拉黑' : '已取消拉黑')
  } catch {
    // 提示已弹出
  } finally {
    acting.value = ''
  }
}

async function removeFriend() {
  try {
    await ElMessageBox.confirm(
      `删除后「${displayName.value}」将从你的好友列表移除，双方的好友关系同时解除，已有的聊天记录仍会保留。确定删除？`,
      '删除好友',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  acting.value = 'remove'
  try {
    await friend.remove(targetId.value)
    ElMessage.success('已删除好友')
    // 关系没了，资料卡上的 friend 位、按钮组、好友时间都要跟着变，重新拉一次最省事
    await load()
  } catch {
    // 提示已弹出
  } finally {
    acting.value = ''
  }
}

function goBack() {
  // 从收藏或刷新直接落进来时没有历史记录，退回好友列表比退到浏览器空白页有用
  if (window.history.state && window.history.state.back) {
    router.back()
    return
  }
  router.replace({ name: 'friends' })
}
</script>

<style scoped>
.user-profile {
  height: 100%;
  overflow-y: auto;
}

.user-profile__inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

@media (max-width: 768px) {
  .user-profile__inner {
    padding: 12px;
    gap: 12px;
  }
}

.user-profile__back {
  margin-left: -8px;
}

.user-profile__card {
  padding: 20px;
  background: var(--im-panel);
  border: 1px solid var(--im-border);
  border-radius: var(--im-radius);
}

.user-profile__card-title {
  margin-bottom: 16px;
  font-size: 15px;
  font-weight: 600;
}

.user-profile__head {
  display: flex;
  align-items: center;
  gap: 20px;
}

.user-profile__identity {
  min-width: 0;
}

.user-profile__nickname {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 20px;
  font-weight: 600;
}

.user-profile__nickname-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-profile__account {
  margin-top: 4px;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.user-profile__signature {
  margin-top: 8px;
  font-size: 13px;
  line-height: 20px;
  color: var(--im-text-secondary);
  word-break: break-all;
}

.user-profile__facts {
  margin-top: 20px;
}

.user-profile__state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #67c23a;
}

.user-profile__state--off {
  color: var(--im-text-secondary);
}

.user-profile__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

/* 拉黑提示用告警色块，比一个 tag 更能让人在点按钮之前先看到 */
.user-profile__warn {
  margin-top: 14px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 20px;
  color: #b88230;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
}

.user-profile__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.user-profile__tip {
  font-size: 12px;
  line-height: 18px;
  color: var(--im-text-secondary);
}

.user-profile__dialog-target {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  font-size: 14px;
}
</style>
