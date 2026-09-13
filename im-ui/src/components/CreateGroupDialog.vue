<template>
  <el-dialog
    v-model="visible"
    title="创建群聊"
    width="480px"
    :close-on-click-modal="false"
    @opened="onOpened"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="群名称" prop="name">
        <el-input v-model.trim="form.name" placeholder="请输入群名称" maxlength="64" show-word-limit />
      </el-form-item>

      <el-form-item label="群公告" prop="notice">
        <el-input
          v-model.trim="form.notice"
          type="textarea"
          :rows="2"
          placeholder="选填，群公告最多 512 字"
          maxlength="512"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="成员上限" prop="maxMember">
        <el-input-number v-model="form.maxMember" :min="2" :max="1000" :step="10" />
      </el-form-item>

      <el-form-item label="邀请成员">
        <div class="create-group__members">
          <div v-if="!friend.friends.length" class="create-group__empty">
            还没有好友，无法邀请成员。创建后可在群设置中继续邀请。
          </div>
          <template v-else>
            <div class="create-group__search">
              <el-input
                v-model.trim="memberKeyword"
                placeholder="搜索好友"
                clearable
                :prefix-icon="Search"
                size="small"
              />
            </div>
            <div class="create-group__list im-scroll">
              <label
                v-for="item in filteredFriends"
                :key="item.friendId"
                class="create-group__friend"
              >
                <el-checkbox
                  :model-value="selectedMembers.has(item.friendId)"
                  @change="(val) => toggleMember(item.friendId, val)"
                />
                <UserAvatar :src="item.avatar" :name="item.displayName || item.nickname" :size="28" />
                <span class="im-ellipsis">{{ item.displayName || item.nickname }}</span>
              </label>
            </div>
            <div v-if="selectedMembers.size" class="create-group__selected">
              已选 {{ selectedMembers.size }} 人
            </div>
          </template>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!form.name.trim()" @click="submit">
        创建
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import { useGroupStore } from '@/stores/group'
import { useFriendStore } from '@/stores/friend'
import { useConversationStore } from '@/stores/conversation'
import { asId } from '@/utils/id'

/**
 * 创建群聊对话框。
 *
 * 通过 v-model 控制显隐，创建成功后自动跳转到群聊窗口并关闭对话框。
 * 好友列表从 FriendStore 取，避免重复请求。
 */

const props = defineProps({
  modelValue: { type: Boolean, default: false }
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

const formRef = ref(null)
const saving = ref(false)
const memberKeyword = ref('')

const form = reactive({
  name: '',
  notice: '',
  maxMember: 200
})

const rules = {
  name: [{ required: true, message: '请输入群名称', trigger: 'blur' }]
}

/** 已选成员 ID 集合 */
const selectedMembers = reactive(new Set())

function toggleMember(userId, checked) {
  if (checked) {
    selectedMembers.add(userId)
  } else {
    selectedMembers.delete(userId)
  }
}

/** 按关键字过滤好友列表 */
const filteredFriends = computed(() => {
  const kw = memberKeyword.value.toLowerCase()
  if (!kw) return friend.friends
  return friend.friends.filter((item) => {
    const name = (item.displayName || item.nickname || '').toLowerCase()
    const account = (item.username || '').toLowerCase()
    return name.includes(kw) || account.includes(kw)
  })
})

function onOpened() {
  // 打开时重置表单
  form.name = ''
  form.notice = ''
  form.maxMember = 200
  selectedMembers.clear()
  memberKeyword.value = ''
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    const vo = await group.createGroup({
      name: form.name,
      notice: form.notice || null,
      maxMember: form.maxMember,
      memberIds: Array.from(selectedMembers)
    })
    visible.value = false
    ElMessage.success('群聊已创建')
    // 刷新会话列表，群会话已在后端创建
    await conversation.fetchList()
    // 跳转到群聊窗口
    if (vo && vo.conversationId) {
      router.push({ name: 'chat', params: { conversationId: asId(vo.conversationId) } })
    }
  } catch {
    // request.js 已经弹过错误
  } finally {
    saving.value = false
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
.create-group__members {
  width: 100%;
}

.create-group__empty {
  padding: 12px 0;
  font-size: 13px;
  color: var(--im-text-secondary);
}

.create-group__search {
  margin-bottom: 8px;
}

.create-group__list {
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--im-border);
  border-radius: 4px;
}

.create-group__friend {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 13px;
}

.create-group__friend:hover {
  background: #f5f5f5;
}

.create-group__selected {
  margin-top: 6px;
  font-size: 12px;
  color: var(--im-text-secondary);
}
</style>
