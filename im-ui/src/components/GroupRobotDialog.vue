<template>
  <el-dialog
    v-model="visible"
    title="群机器人"
    width="520px"
    :close-on-click-modal="false"
    append-to-body
    @open="onOpen"
  >
    <div v-loading="loading" class="grd__body">
      <div class="grd__intro">
        机器人可让外部系统凭 Webhook 地址向本群推送消息。复制回调地址，向它 POST
        <code>{"content": "文本消息"}</code> 即可。
      </div>

      <!-- 机器人列表 -->
      <div v-if="robots.length" class="grd__list im-scroll">
        <div v-for="r in robots" :key="r.robotId" class="grd__item">
          <div class="grd__item-head">
            <UserAvatar :src="r.avatar" :name="r.name" :size="34" />
            <div class="grd__item-title">
              <span class="grd__item-name im-ellipsis">{{ r.name }}</span>
              <span v-if="r.description" class="grd__item-desc im-ellipsis">{{ r.description }}</span>
            </div>
            <el-switch
              :model-value="!!r.enabled"
              size="small"
              @change="(val) => onToggle(r, val)"
            />
          </div>

          <div class="grd__url-row">
            <span class="grd__url im-ellipsis" :title="r.webhookUrl">{{ r.webhookUrl }}</span>
            <el-button size="small" text type="primary" :icon="CopyDocument" @click="copyUrl(r.webhookUrl)">复制</el-button>
          </div>

          <div class="grd__item-actions">
            <el-button link size="small" :icon="RefreshRight" @click="onResetToken(r)">重置密钥</el-button>
            <el-button link size="small" type="danger" :icon="Delete" @click="onDelete(r)">删除</el-button>
          </div>
        </div>
      </div>
      <el-empty v-else-if="!loading && !showForm" description="暂无机器人" :image-size="70" />

      <!-- 新建表单 -->
      <el-form
        v-if="showForm"
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="72px"
        class="grd__form"
      >
        <el-form-item label="名称" prop="name">
          <el-input v-model.trim="form.name" placeholder="机器人显示名称" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="头像">
          <el-input v-model.trim="form.avatar" placeholder="头像 URL（可留空）" maxlength="512" clearable />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model.trim="form.description" type="textarea" :rows="2" placeholder="用途说明（可留空）" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <template v-if="showForm">
        <el-button @click="cancelForm">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
      <el-button v-else type="primary" :icon="Plus" @click="openForm">添加机器人</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Delete, Plus, RefreshRight } from '@element-plus/icons-vue'
import UserAvatar from '@/components/UserAvatar.vue'
import {
  fetchRobots,
  createRobot,
  updateRobot,
  resetRobotToken,
  deleteRobot
} from '@/api/robot'

/**
 * 群机器人管理面板：列表 + 新建 + 启停 + 复制回调地址 + 重置密钥 + 删除。
 *
 * 仅群主与管理员可打开（由父组件 GroupSettings 的 canManage 控制入口）；
 * webhook 地址由后端按当前环境基址拼出，token 即凭证，复制即可供外部系统调用。
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

const robots = ref([])
const loading = ref(false)
const showForm = ref(false)
const creating = ref(false)
const formRef = ref(null)

const form = reactive({ name: '', avatar: '', description: '' })
const rules = {
  name: [{ required: true, message: '请输入机器人名称', trigger: 'blur' }]
}

async function onOpen() {
  showForm.value = false
  await loadList()
}

async function loadList() {
  if (!props.groupId) return
  loading.value = true
  try {
    robots.value = (await fetchRobots(props.groupId)) || []
  } catch {
    robots.value = []
  } finally {
    loading.value = false
  }
}

function openForm() {
  form.name = ''
  form.avatar = ''
  form.description = ''
  showForm.value = true
}

function cancelForm() {
  showForm.value = false
}

async function submitCreate() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  creating.value = true
  try {
    await createRobot({
      groupId: props.groupId,
      name: form.name,
      avatar: form.avatar || undefined,
      description: form.description || undefined
    })
    ElMessage.success('机器人已创建')
    showForm.value = false
    await loadList()
  } catch { /* request.js 已处理 */ } finally {
    creating.value = false
  }
}

async function onToggle(robot, val) {
  try {
    const updated = await updateRobot(robot.robotId, { enabled: val })
    robot.enabled = updated.enabled
    robot.status = updated.status
    robot.webhookUrl = updated.webhookUrl
    ElMessage.success(val ? '已启用' : '已停用')
  } catch {
    // 失败回滚开关显示：重新拉一次列表以服务端为准
    await loadList()
  }
}

async function onResetToken(robot) {
  try {
    await ElMessageBox.confirm(
      '重置后旧的回调地址立即失效，外部系统需改用新地址。确定重置？',
      '重置 Webhook 密钥',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const updated = await resetRobotToken(robot.robotId)
    robot.webhookToken = updated.webhookToken
    robot.webhookUrl = updated.webhookUrl
    ElMessage.success('密钥已重置')
  } catch { /* request.js 已处理 */ }
}

async function onDelete(robot) {
  try {
    await ElMessageBox.confirm(`删除机器人「${robot.name}」？其回调地址将立即失效。`, '删除机器人', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteRobot(robot.robotId)
    ElMessage.success('已删除')
    await loadList()
  } catch { /* request.js 已处理 */ }
}

async function copyUrl(text) {
  if (!text) {
    ElMessage.warning('回调地址为空，请重新打开面板')
    return
  }
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('回调地址已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择地址')
  }
}
</script>

<style scoped>
.grd__body {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.grd__intro {
  font-size: 12px;
  color: var(--im-text-secondary);
  line-height: 1.6;
  background: var(--im-bg-hover, #f5f7fa);
  border-radius: 6px;
  padding: 8px 10px;
}

.grd__intro code {
  background: rgba(0, 0, 0, 0.06);
  padding: 1px 4px;
  border-radius: 3px;
}

.grd__list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 340px;
  overflow-y: auto;
}

.grd__item {
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 8px;
  padding: 10px 12px;
}

.grd__item-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.grd__item-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.grd__item-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--im-text);
}

.grd__item-desc {
  font-size: 12px;
  color: var(--im-text-secondary);
}

.grd__url-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 5px 8px;
  border-radius: 6px;
  background: var(--im-bg-hover, #f5f7fa);
}

.grd__url {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--im-text);
  word-break: break-all;
}

.grd__item-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}

.grd__form {
  margin-top: 4px;
}
</style>
