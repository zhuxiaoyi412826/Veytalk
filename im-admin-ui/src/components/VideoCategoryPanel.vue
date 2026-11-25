<template>
  <div class="category-panel">
    <el-alert
      v-if="perm && !auth.can(perm)"
      class="hint"
      type="error"
      :closable="false"
      title="当前账号没有这一屏的权限"
      :description="`缺少权限点 ${perm}，操作会被服务端打回（1003），请联系超级管理员开通。`"
    />

    <div class="toolbar">
      <span class="muted">{{ hint }}</span>
      <span class="grow" />
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        <span>新增分类</span>
      </el-button>
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>重新读取</span>
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="categories">
        <el-table-column align="center" label="排序" prop="sort" width="90" />
        <el-table-column label="分类名称" prop="name" min-width="160" />
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-switch :model-value="row.status === 1" @change="(v) => toggleStatus(row, v)" />
            <span class="muted status-text">{{ row.status === 1 ? '启用' : '停用' }}</span>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="160">
          <template #default="{ row }">
            <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" @click="confirmDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有分类" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 新增/编辑弹框：sort 留空则后端排到最后 -->
    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑分类' : '新增分类'" width="420px">
      <el-form label-width="72px">
        <el-form-item label="名称">
          <el-input v-model="dialog.name" :maxlength="32" placeholder="分类名称（不超过 32 字）" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="dialog.sort" :min="0" :max="9999" />
          <span class="muted sort-hint">升序展示，留空排最后</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createVideoCategory,
  deleteVideoCategory,
  fetchVideoCategories,
  updateVideoCategory
} from '@/api/admin'
import { useAuthStore } from '@/stores/auth'

/**
 * 视频板块二级分类字典的维护面板（增删改、排序、启停）。
 *
 * 抽成组件是因为同一份字典有两个入口：「视频管理 · 分类管理」页签，以及「移动端」里
 * 给手机客户端视频页标签栏用的那一个 tab。两处改的是<b>同一批行</b>（type=video），
 * 所以刻意不做「移动端单独一套分类」——那样 Web 与 App 的分类栏会各长各的，
 * 而发布者下拉里只有 Web 那套，选不出 App 独有的分类。
 *
 * 分类是「视图」而不是外键：停用或删除只让它从前端分类栏隐身，历史视频落库的
 * category 文本一律不动（服务端同一事务里也不动），所以不会出现「删了分类，历史视频集体消失」。
 *
 * 启停开关用 :model-value 单向绑定 + 服务端回写：接口失败时不本地改状态，
 * 开关自己会弹回去，比「先点亮再报错、状态还是错的」诚实。
 */
const props = defineProps({
  /** 面板顶部的说明文案：两个入口服务的对象不同，措辞跟着入口走 */
  hint: {
    type: String,
    default: '自定义视频板块顶部分类栏：增删改、排序（改 sort）、启停。停用/删除只影响新的展示，不动历史视频已落库的分类文本。'
  },
  /** 本面板对应的权限点，仅用于进错页面时给一句人话提示（真正的拦截在服务端） */
  perm: { type: String, default: 'system:video:manage' }
})

const auth = useAuthStore()
const categories = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = reactive({ visible: false, id: null, name: '', sort: undefined })

async function load() {
  loading.value = true
  try {
    categories.value = (await fetchVideoCategories()) || []
  } catch {
    // 提示已由 request.js 弹过；保留上一次结果
  } finally {
    loading.value = false
  }
}

function openCreate() {
  dialog.id = null
  dialog.name = ''
  dialog.sort = undefined
  dialog.visible = true
}

function openEdit(row) {
  dialog.id = row.id
  dialog.name = row.name
  dialog.sort = row.sort
  dialog.visible = true
}

async function submit() {
  const name = dialog.name.trim()
  if (!name) {
    ElMessage.warning('分类名称不能为空')
    return
  }
  saving.value = true
  try {
    if (dialog.id) {
      await updateVideoCategory(dialog.id, { name, sort: dialog.sort })
      ElMessage.success('分类已保存')
    } else {
      await createVideoCategory(name, dialog.sort)
      ElMessage.success('分类已新增')
    }
    dialog.visible = false
    load()
  } catch {
    // 接口层已弹提示（如同名拒绝）
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row, enabled) {
  try {
    const updated = await updateVideoCategory(row.id, { status: enabled ? 1 : 0 })
    Object.assign(row, updated)
    ElMessage.success(enabled ? '分类已启用' : '分类已停用')
  } catch {
    // 失败不本地改状态：:model-value 单向绑定，未更新即自动回弹
  }
}

async function confirmDelete(row) {
  try {
    await ElMessageBox.confirm(
      `删除分类「${row.name}」只让它从前端分类栏消失，已发布视频的分类文本不变。`,
      '删除分类',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await deleteVideoCategory(row.id)
    ElMessage.success('分类已删除')
    load()
  } catch {
    // 同上
  }
}

onMounted(load)
</script>

<style scoped>
.hint {
  margin-bottom: 16px;
}

.status-text {
  margin-left: 8px;
}

.sort-hint {
  margin-left: 8px;
}
</style>
