<template>
  <div class="roles">
    <div class="toolbar">
      <h2 class="page-title">角色管理</h2>
      <span class="grow" />
      <el-button :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon>
        <span>刷新</span>
      </el-button>
    </div>

    <el-alert
      class="hint"
      :closable="false"
      type="info"
      title="这一页只读地列出后台可分配的角色；「给某个人配角色」在用户管理页做"
      description="后台二期把伞权限 system:manage 拆成了看板 / 用户管理 / 举报处置 / 角色分配四个细粒度点。角色到权限点的映射由服务端种子数据固定（见 sql/im_data.sql），这里不提供在线编辑角色的入口——改角色该发哪些权限点是一次代码 + 迁移脚本的变更，不该做成能在后台随手点的开关。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows">
        <el-table-column label="角色编码" min-width="140">
          <template #default="{ row }">
            <span class="code">{{ row.roleCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="140" prop="roleName" show-overflow-tooltip />
        <el-table-column label="权限点" min-width="260">
          <template #default="{ row }">
            <el-tag
              v-for="perm in permsOf(row.roleCode)"
              :key="perm"
              class="perm-tag"
              effect="plain"
              size="small"
              type="info"
            >
              {{ perm }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="muted">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有可分配的角色" :image-size="80" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { fetchRoles } from '@/api/admin'

/**
 * 角色目录（只读）。
 *
 * 权限点这一列不是接口给的，是本地一份「roleCode → 它被种子里授予了哪些点」的对照表：
 * 后端 /admin/role/list 只回角色本身（id/code/name/description），不回角色—权限点绑定，
 * 因为那是 im-user 侧 RBAC 表的内部关系，没为后台单独开查询口。这里按 roleCode 显示一份说明用的
 * 映射，够界面讲清楚「审核员到底能看哪几屏」；真正的鉴权仍在后端 @SaCheckPermission，与此无关。
 */
const ROLE_PERMS = {
  admin: ['system:manage（伞权限，覆盖以下全部）', 'system:dashboard', 'system:user:manage', 'system:report:handle', 'system:role:manage'],
  moderator: ['system:dashboard', 'system:report:handle'],
  operator: ['system:dashboard', 'system:user:manage', 'system:role:manage'],
  user: []
}

const rows = ref([])
const loading = ref(false)

function permsOf(roleCode) {
  return ROLE_PERMS[roleCode] || []
}

async function load() {
  loading.value = true
  try {
    rows.value = await fetchRoles()
  } catch {
    // 提示已由 request.js 弹过；保留上一次的结果
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-title {
  font-size: 18px;
  margin: 0;
}

.hint {
  margin-bottom: 16px;
}

.code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.perm-tag {
  margin: 2px 6px 2px 0;
}
</style>
