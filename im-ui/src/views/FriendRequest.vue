<template>
  <div class="friend-request">
    <!-- ==================== 添加好友 ==================== -->
    <section class="friend-request__add">
      <div class="friend-request__add-title">添加好友</div>
      <div class="friend-request__add-row">
        <el-input
          v-model.trim="searchKeyword"
          placeholder="输入对方的账号或手机号"
          clearable
          :prefix-icon="Search"
          @keyup.enter="searchUsers"
        />
        <el-checkbox v-model="exactMatch" label="精确匹配" border />
        <el-button type="primary" :icon="Search" :loading="searching" @click="searchUsers">查找</el-button>
      </div>

      <div v-if="searched" class="friend-request__results">
        <el-empty v-if="results.length === 0" description="没有找到匹配的用户" :image-size="60" />
        <div v-for="user in results" :key="user.userId" class="result">
          <UserAvatar :src="user.avatar" :name="user.nickname || user.username" :size="38" :online="!!user.online" />
          <div class="result__body">
            <div class="result__name im-ellipsis">
              {{ user.nickname || user.username }}
              <span class="result__account">@{{ user.username }}</span>
            </div>
            <div class="result__signature im-ellipsis">{{ user.signature || '这个人很懒，什么都没写' }}</div>
          </div>
          <div class="result__action">
            <el-tag v-if="isSelf(user)" size="small" type="info">这是你自己</el-tag>
            <el-tag v-else-if="user.friend" size="small" type="success">已是好友</el-tag>
            <el-button v-else type="primary" plain size="small" @click="openApply(user)">添加</el-button>
          </div>
        </div>
      </div>
    </section>

    <!-- ==================== 申请列表 ==================== -->
    <section class="friend-request__lists">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane name="received">
          <template #label>
            <el-badge :value="friend.pendingCount" :max="99" :hidden="!friend.pendingCount" class="tab-badge">
              收到的申请
            </el-badge>
          </template>

          <div v-loading="loadingReceived" class="friend-request__body im-scroll">
            <div v-for="item in friend.received" :key="item.id" class="request">
              <UserAvatar
                :src="item.peerAvatar"
                :name="item.peerNickname || item.peerUsername"
                :size="42"
                :online="!!item.peerOnline"
              />
              <div class="request__body">
                <div class="request__row">
                  <span class="request__name im-ellipsis">{{ item.peerNickname || item.peerUsername }}</span>
                  <span class="request__time">{{ formatConvTime(item.createTime) }}</span>
                </div>
                <div class="request__verify im-ellipsis">
                  {{ item.verifyMessage || '对方没有填写验证消息' }}
                </div>
                <div v-if="item.source" class="request__source">来源：{{ item.source }}</div>
              </div>
              <div class="request__action">
                <template v-if="item.actionable">
                  <el-button type="primary" size="small" :loading="acting === item.id" @click="accept(item)">
                    同意
                  </el-button>
                  <el-button size="small" :loading="acting === item.id" @click="reject(item)">拒绝</el-button>
                </template>
                <!--
                  不可操作的申请只展示状态，不画灰掉的按钮：
                  一个点不动的「同意」会让人反复去点并以为是页面卡了。
                -->
                <el-tag v-else size="small" :type="statusTagType(item.status)" effect="plain">
                  {{ item.statusDesc }}
                </el-tag>
              </div>
            </div>

            <el-empty v-if="!loadingReceived && friend.received.length === 0" description="没有收到的申请" :image-size="72" />
          </div>

          <Pagination :total="friend.receivedTotal" v-model:current="receivedPage" @change="loadReceived" />
        </el-tab-pane>

        <el-tab-pane label="我发出的" name="sent">
          <div v-loading="loadingSent" class="friend-request__body im-scroll">
            <div v-for="item in friend.sent" :key="item.id" class="request">
              <UserAvatar
                :src="item.peerAvatar"
                :name="item.peerNickname || item.peerUsername"
                :size="42"
                :online="!!item.peerOnline"
              />
              <div class="request__body">
                <div class="request__row">
                  <span class="request__name im-ellipsis">{{ item.peerNickname || item.peerUsername }}</span>
                  <span class="request__time">{{ formatConvTime(item.createTime) }}</span>
                </div>
                <div class="request__verify im-ellipsis">{{ item.verifyMessage || '你没有填写验证消息' }}</div>
                <div v-if="item.handleTime" class="request__source">处理于 {{ formatConvTime(item.handleTime) }}</div>
              </div>
              <div class="request__action">
                <el-tag size="small" :type="statusTagType(item.status)" effect="plain">{{ item.statusDesc }}</el-tag>
                <el-button
                  v-if="item.status === 1"
                  type="primary"
                  link
                  size="small"
                  @click="startChat(item.peerUserId)"
                >
                  发消息
                </el-button>
              </div>
            </div>

            <el-empty v-if="!loadingSent && friend.sent.length === 0" description="还没有发出过申请" :image-size="72" />
          </div>

          <Pagination :total="friend.sentTotal" v-model:current="sentPage" @change="loadSent" />
        </el-tab-pane>
      </el-tabs>
    </section>

    <!-- ==================== 发起申请 ==================== -->
    <el-dialog v-model="applyDialog.visible" title="添加好友" width="400px">
      <div v-if="applyDialog.target" class="apply-target">
        <UserAvatar
          :src="applyDialog.target.avatar"
          :name="applyDialog.target.nickname || applyDialog.target.username"
          :size="44"
        />
        <div>
          <div class="apply-target__name">{{ applyDialog.target.nickname || applyDialog.target.username }}</div>
          <div class="apply-target__account">@{{ applyDialog.target.username }}</div>
        </div>
      </div>
      <el-input
        v-model.trim="applyDialog.message"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        placeholder="写一句验证消息，让对方知道你是谁"
      />
      <template #footer>
        <el-button @click="applyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="applyDialog.saving" @click="submitApply">发送申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import Pagination from '@/components/Pagination.vue'
import { searchUsers as searchUsersApi } from '@/api/user'
import { useFriendStore } from '@/stores/friend'
import { useConversationStore } from '@/stores/conversation'
import { useAuthStore } from '@/stores/auth'
import { formatConvTime } from '@/utils/format'
import { asId, sameId } from '@/utils/id'

defineOptions({ name: 'FriendRequest' })

const router = useRouter()
const friend = useFriendStore()
const conversation = useConversationStore()
const auth = useAuthStore()

const PAGE_SIZE = 10

/* ------------------------------ 搜索用户 ------------------------------ */

const searchKeyword = ref('')
/**
 * 精确匹配开关。
 *
 * 后端默认是模糊匹配（账号或昵称 LIKE），输手机号的一部分会捞出一堆无关的人；
 * 明确知道对方账号时勾上它，结果会干净很多。
 */
const exactMatch = ref(false)
const searching = ref(false)
const searched = ref(false)
const results = ref([])

function isSelf(user) {
  return sameId(user.userId, auth.userId)
}

async function searchUsers() {
  if (!searchKeyword.value) {
    ElMessage.warning('请先输入账号或手机号')
    return
  }
  searching.value = true
  try {
    const page = await searchUsersApi({
      keyword: searchKeyword.value,
      exact: exactMatch.value,
      current: 1,
      size: PAGE_SIZE
    })
    results.value = (page && page.records) || []
    searched.value = true
  } catch {
    results.value = []
    searched.value = true
  } finally {
    searching.value = false
  }
}

/* ------------------------------ 发起申请 ------------------------------ */

const applyDialog = reactive({ visible: false, saving: false, message: '', target: null })

function openApply(user) {
  applyDialog.target = user
  // 默认带上一句自报家门的验证消息，比空白框更容易被通过
  applyDialog.message = `你好，我是 ${auth.nickname}`
  applyDialog.visible = true
}

async function submitApply() {
  const target = applyDialog.target
  if (!target) {
    return
  }
  applyDialog.saving = true
  try {
    await friend.apply({
      targetUserId: target.userId,
      verifyMessage: applyDialog.message || undefined,
      source: 'web'
    })
    applyDialog.visible = false
    ElMessage.success('申请已发送')
    // 刚发出的申请要能在「我发出的」里立刻看到
    if (tab.value === 'sent') {
      await loadSent()
    }
  } catch {
    // 重复申请、对方已把你拉黑等情况后端会拒绝，提示已弹出；
    // 保留对话框让用户改完验证消息再试
  } finally {
    applyDialog.saving = false
  }
}

/* ------------------------------ 申请列表 ------------------------------ */

const tab = ref('received')
const acting = ref(null)
const loadingReceived = ref(false)
const loadingSent = ref(false)
const receivedPage = ref(1)
const sentPage = ref(1)

async function loadReceived() {
  loadingReceived.value = true
  try {
    await friend.fetchReceived({ current: receivedPage.value, size: PAGE_SIZE, status: 0 })
  } catch {
    // 提示已弹出
  } finally {
    loadingReceived.value = false
  }
}

async function loadSent() {
  loadingSent.value = true
  try {
    await friend.fetchSent({ current: sentPage.value, size: PAGE_SIZE })
  } catch {
    // 提示已弹出
  } finally {
    loadingSent.value = false
  }
}

/**
 * 切 Tab 时才拉对应列表。
 *
 * 两个列表都在挂载时拉的话，用户只看「收到的」也白白发一次「我发出的」请求。
 */
function onTabChange(name) {
  if (name === 'sent' && friend.sent.length === 0) {
    loadSent()
  }
}

/**
 * 状态标签配色。
 *
 * 与 RequestStatus 对齐：0 待处理 / 1 已同意 / 2 已拒绝 / 3 已过期。
 */
function statusTagType(status) {
  switch (Number(status)) {
    case 0:
      return 'warning'
    case 1:
      return 'success'
    case 2:
      return 'danger'
    default:
      return 'info'
  }
}

async function accept(item) {
  acting.value = item.id
  try {
    const conversationId = await friend.accept(item.id)
    // 同意后后端顺手建好了单聊会话，直接问用户要不要过去，省掉「再去好友列表找一遍」
    try {
      await ElMessageBox.confirm(`已添加「${item.peerNickname || item.peerUsername}」为好友，现在发消息？`, '添加成功', {
        confirmButtonText: '发消息',
        cancelButtonText: '稍后',
        type: 'success'
      })
      router.push({ name: 'chat', params: { conversationId: asId(conversationId) } })
      return
    } catch {
      // 用户选了「稍后」，留在本页继续处理下一条
    }
    await loadReceived()
  } catch {
    // 提示已弹出
  } finally {
    acting.value = null
  }
}

async function reject(item) {
  acting.value = item.id
  try {
    await friend.reject(item.id)
    ElMessage.success('已拒绝')
    await loadReceived()
  } catch {
    // 提示已弹出
  } finally {
    acting.value = null
  }
}

async function startChat(userId) {
  try {
    const conversationId = await conversation.openWith(userId)
    router.push({ name: 'chat', params: { conversationId: asId(conversationId) } })
  } catch {
    // 提示已弹出
  }
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  await Promise.all([loadReceived(), friend.refreshPendingCount()])
})
</script>

<style scoped>
.friend-request {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.friend-request__add {
  flex: none;
  padding: 16px 20px;
  background: var(--im-panel);
  border-bottom: 1px solid var(--im-border);
}

.friend-request__add-title {
  margin-bottom: 10px;
  font-size: 15px;
  font-weight: 600;
}

.friend-request__add-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* flex:1 + min-width:0：窄屏换行生效前，输入框也不会被 checkbox 和按钮挤到看不见 */
.friend-request__add-row .el-input {
  flex: 1;
  min-width: 0;
  max-width: 320px;
}

.friend-request__results {
  margin-top: 12px;
  max-height: 180px;
  overflow-y: auto;
}

.result {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  border-top: 1px solid var(--im-border);
}

.result__body {
  flex: 1;
  min-width: 0;
}

.result__name {
  font-size: 14px;
}

.result__account {
  margin-left: 6px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.result__signature {
  margin-top: 2px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.result__action {
  flex: none;
}

.friend-request__lists {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 20px;
  background: var(--im-panel);
}

.friend-request__lists :deep(.el-tabs) {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.friend-request__lists :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.friend-request__lists :deep(.el-tab-pane) {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.friend-request__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.tab-badge :deep(.el-badge__content) {
  top: 2px;
  right: -4px;
  border: none;
}

.request {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 4px;
  border-bottom: 1px solid var(--im-border);
}

.request__body {
  flex: 1;
  min-width: 0;
}

.request__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.request__name {
  font-size: 14px;
}

.request__time {
  flex: none;
  font-size: 11px;
  color: var(--im-text-secondary);
}

.request__verify {
  margin-top: 3px;
  font-size: 12px;
  color: var(--im-text-secondary);
}

.request__source {
  margin-top: 2px;
  font-size: 11px;
  color: #c0c4cc;
}

.request__action {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.apply-target {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.apply-target__name {
  font-size: 14px;
  font-weight: 600;
}

.apply-target__account {
  font-size: 12px;
  color: var(--im-text-secondary);
}

/* ------------------------------ 窄屏 ------------------------------ */
@media (max-width: 768px) {
  .friend-request__add {
    padding: 12px;
  }

  /*
   * 输入框独占一行：和 checkbox、按钮挤在一行时 flex 收缩会把它压到几像素宽，
   * 表现为「打字时搜索框里什么都看不到，只有输入法候选栏有字」。
   */
  .friend-request__add-row {
    flex-wrap: wrap;
  }

  .friend-request__add-row .el-input {
    flex: 1 1 100%;
    max-width: none;
  }

  .friend-request__lists {
    padding: 0 8px;
  }

  /* 矮屏上结果列表多给一点空间，否则只露得出半行 */
  .friend-request__results {
    max-height: 240px;
  }
}
</style>
