<template>
  <el-container class="admin-layout">
    <el-aside width="200px" class="side">
      <div class="brand">
        <el-icon><Platform /></el-icon>
        <span>管理后台</span>
      </div>
      <el-menu :default-active="activeName" router class="menu">
        <el-menu-item v-if="auth.can('system:dashboard')" index="dashboard" :route="{ name: 'dashboard' }">
          <el-icon><DataLine /></el-icon>
          <span>数据看板</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:user:manage')" index="users" :route="{ name: 'users' }">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:report:handle')" index="reports" :route="{ name: 'reports' }">
          <el-icon><Warning /></el-icon>
          <span>举报工单</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:role:manage')" index="roles" :route="{ name: 'roles' }">
          <el-icon><Lock /></el-icon>
          <span>角色管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:video:manage')" index="videos" :route="{ name: 'videos' }">
          <el-icon><VideoCamera /></el-icon>
          <span>视频管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:live:manage')" index="lives" :route="{ name: 'lives' }">
          <el-icon><VideoPlay /></el-icon>
          <span>直播管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:monitor')" index="monitor" :route="{ name: 'monitor' }">
          <el-icon><Odometer /></el-icon>
          <span>监控告警</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:log')" index="logs" :route="{ name: 'logs' }">
          <el-icon><Document /></el-icon>
          <span>日志管理</span>
        </el-menu-item>
      </el-menu>
      <p class="muted side-note">
        一期没有待办角标：新工单数只有登录后进看板才拿得到，为一个小红点在每个页面轮询
        一个聚合接口不划算。
      </p>
    </el-aside>

    <el-container>
      <el-header class="header">
        <span class="narrow-hint muted">界面按宽屏设计，窄屏下表格会被挤压，建议在电脑上使用</span>
        <span class="grow" />
        <span class="muted">{{ auth.username }}</span>
        <AvatarCell :url="auth.avatarRaw" :name="auth.nickname" :size="30" />
        <el-button text type="primary" @click="onLogout">退出</el-button>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AvatarCell from '@/components/AvatarCell.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 后台外框：一条侧边菜单 + 一个顶栏 + 内容区。
 *
 * 顶栏<b>不放任何业务操作</b>（没有搜索框、没有快捷按钮）：后台的四个页面各自有筛选条件，
 * 把它们提到全局就会出现「在用户页顶部输关键词却搜了工单」这类归属不清的交互。
 *
 * 身份与权限已由路由守卫确认过（拿到 /auth/me 且至少持有一个后台权限点），
 * 这里直接读 store 就行，不再兜一层「没登录怎么办」——真走到那一步早就被守卫弹回登录页了。
 *
 * 菜单用 <code>auth.can(...)</code> 逐条判权限。所有权限点一个都没有的人进不了后台（守卫拦下），
 * 所以这里不存在「整条菜单都是空的」那种中间态；真出现了就只能说明种子数据里角色被改坏了。
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const activeName = computed(() => route.name)

async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新输入账号密码，确定？', '退出登录', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '再看看'
    })
  } catch {
    return
  }
  await auth.logout()
  ElMessage.success('已退出')
  router.replace({ name: 'login' })
}
</script>

<style scoped>
.admin-layout {
  height: 100%;
}

.side {
  background: #fff;
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.brand {
  align-items: center;
  color: #303133;
  display: flex;
  font-size: 16px;
  font-weight: 600;
  gap: 8px;
  height: 60px;
  padding: 0 16px;
}

.menu {
  border-right: none;
  flex: 1;
}

.side-note {
  line-height: 1.6;
  padding: 12px 16px 20px;
}

.header {
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  gap: 12px;
  height: 60px;
  padding: 0 20px;
}

.header .grow {
  flex: 1;
}

.main {
  padding: 20px;
}

/* 窄屏提示只在 < 900px 出现，宽屏下一个像素都不占；用 CSS 而不是 JS 量宽度，
   省掉 resize 监听与它在窗口来回拖动时的抖动 */
.narrow-hint {
  display: none;
}

@media (max-width: 900px) {
  .narrow-hint {
    display: inline;
  }
}
</style>
