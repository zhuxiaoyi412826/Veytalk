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
        <!-- 六个内容板块在侧栏只占一条「直播」，具体切哪个板块走顶栏那一排分类入口。
             这里只是导航容器：路由、meta.perm、守卫、后端鉴权全没动，所以旧地址仍然能收藏，
             「只给了图片权限的人进不去小说」也照旧成立。目标路取第一个有权限的板块，
             于是只持单一板块权限的人点这条菜单也能直接落到他能看的那一屏。 -->
        <el-menu-item v-if="visibleBoards.length" :index="boardTarget" :route="{ name: boardTarget }">
          <el-icon><VideoPlay /></el-icon>
          <span>直播</span>
        </el-menu-item>
        <!-- 交友三屏（V1.0.7，权限点 132~134）：三条而不是合在一条「交友」菜单里——
             照片驳回、删动态、改字典是三个风险量级不同的动作，种子按三个点分别授，
             菜单得能把「谁能看到哪一屏」一眼读出来 -->
        <el-menu-item v-if="auth.can('system:dating:manage')" index="dating" :route="{ name: 'dating' }">
          <el-icon><Postcard /></el-icon>
          <span>交友管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:moment:manage')" index="moments" :route="{ name: 'moments' }">
          <el-icon><ChatLineSquare /></el-icon>
          <span>动态管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:tag:manage')" index="dating-dict" :route="{ name: 'dating-dict' }">
          <el-icon><PriceTag /></el-icon>
          <span>交友字典</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:group:manage')" index="groups" :route="{ name: 'groups' }">
          <el-icon><UserFilled /></el-icon>
          <span>群组管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:message:manage')" index="messages" :route="{ name: 'messages' }">
          <el-icon><ChatDotSquare /></el-icon>
          <span>消息管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:wallet')" index="wallet" :route="{ name: 'wallet' }">
          <el-icon><Wallet /></el-icon>
          <span>钱包与礼物</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:interview')" index="interviews" :route="{ name: 'interviews' }">
          <el-icon><Reading /></el-icon>
          <span>AI 面试记录</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:ai:guard')" index="ai-guard" :route="{ name: 'ai-guard' }">
          <el-icon><Coin /></el-icon>
          <span>AI 用量管控</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:monitor')" index="monitor" :route="{ name: 'monitor' }">
          <el-icon><Odometer /></el-icon>
          <span>监控告警</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:log')" index="logs" :route="{ name: 'logs' }">
          <el-icon><Document /></el-icon>
          <span>日志管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:log')" index="moderation-logs" :route="{ name: 'moderation-logs' }">
          <el-icon><PictureFilled /></el-icon>
          <span>审核记录</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:config')" index="config" :route="{ name: 'config' }">
          <el-icon><Setting /></el-icon>
          <span>系统配置</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:remote')" index="remote" :route="{ name: 'remote' }">
          <el-icon><Connection /></el-icon>
          <span>远程控制</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:announcement')" index="announcements" :route="{ name: 'announcements' }">
          <el-icon><Bell /></el-icon>
          <span>全服公告</span>
        </el-menu-item>
        <!-- 放在公告之后：两屏都是「对外说话」，但公告是可撤回的日常运营，
             协议是改一个字就得让全数人重新确认一次的合同，误操作代价不一样，排在后面 -->
        <el-menu-item v-if="auth.can('system:agreement:manage')" index="agreements" :route="{ name: 'agreements' }">
          <el-icon><Document /></el-icon>
          <span>法定文本</span>
        </el-menu-item>
        <!-- 排在最后：这一屏动的是「往公网递一个可执行文件」，启用的那一行就是官网的匿名下载口，
             误操作代价全后台最高，放在菜单末尾让「给了谁什么」一眼能看出来 -->
        <el-menu-item v-if="auth.can('system:release')" index="releases" :route="{ name: 'releases' }">
          <el-icon><UploadFilled /></el-icon>
          <span>版本管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.can('system:feedback')" index="feedbacks" :route="{ name: 'feedbacks' }">
          <el-icon><ChatDotSquare /></el-icon>
          <span>意见反馈</span>
        </el-menu-item>
        <!-- 移动端：凡只服务手机客户端的管理动作都收在这一条，进去后顶部页签切换。
             三个页签各自判权限（见 MobileView），所以菜单只问「一个有没有」；
             一个都没有时整条隐藏，而不是让他点进去看一个空壳 -->
        <el-menu-item v-if="hasMobileAny" index="mobile" :route="{ name: 'mobile' }">
          <el-icon><Iphone /></el-icon>
          <span>移动端</span>
        </el-menu-item>
        <!-- SQL 版本管理：全后台唯一按钮直接改库结构的一页，永远放菜单最后一项 -->
        <el-menu-item v-if="auth.can('system:schema:manage')" index="schema" :route="{ name: 'schema' }">
          <el-icon><Tickets /></el-icon>
          <span>SQL 版本</span>
        </el-menu-item>
      </el-menu>
      <p class="muted side-note">
        一期没有待办角标：新工单数只有登录后进看板才拿得到，为一个小红点在每个页面轮询
        一个聚合接口不划算。
      </p>
    </el-aside>

    <el-container>
      <el-header class="header">
        <!-- 六个内容板块的分类入口只在自己的那一屏里出现（isBoardPage）：它是「六块之间横向切换」的控件，
             在用户管理、数据看板那些页面上摆一排「视频 / 音乐 / 小说」既不对应当前内容，
             又让人以为顶栏随时可以切走。不在板块页时整排隐藏，而不是置灰。
             它属于「去哪个屏」的导航，不是业务操作，所以不违反下面那条「顶栏不放业务操作」的约定。 -->
        <el-segmented v-if="isBoardPage && visibleBoards.length > 1" v-model="boardTab" :options="boardOptions" class="board-tabs" />
        <!-- 移动端的三个页签同样放顶栏（与 admin / 退出 同一行），也只在 /mobile 那一屏出现：
             与板块切换条同一条规矩——它是「看哪个屏」的导航，不是业务操作。
             当前页签走地址栏的 ?tab= 而不是局部状态，这样收藏 /mobile?tab=feedback 能直接落到那一页，
             刷新也不会因为组件重建而跳回第一个页签 -->
        <el-segmented
          v-if="isMobilePage && mobileOptions.length > 1"
          v-model="mobileTab"
          :options="mobileOptions"
          class="mobile-tabs"
        />
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
import { resolveMobileTab, visibleMobileTabs } from '@/utils/mobileTabs'

/**
 * 后台外框：一条侧边菜单 + 一个顶栏 + 内容区。
 *
 * 顶栏<b>不放任何业务操作</b>（没有搜索框、没有快捷按钮）：后台的四个页面各自有筛选条件，
 * 把它们提到全局就会出现「在用户页顶部输关键词却搜了工单」这类归属不清的交互。
 * 唯一的例外是六个内容板块的分类入口（el-segmented）：它只回答「去哪个屏」，不碰任何数据，而且只在
 * 那六个板块页自己出现（见模板里的 isBoardPage）。
 *
 * 身份与权限已由路由守卫确认过（拿到 /auth/me 且至少持有一个后台权限点），
 * 这里直接读 store 就行，不再兜一层「没登录怎么办」——真走到那一步早就被守卫弹回登录页了。
 *
 * 菜单用 <code>auth.can(...)</code> 逐条判权限。所有权限点一个都没有的人进不了后台（守卫拦下），
 * 所以这里不存在「整条菜单都是空的」那种中间态；真出现了就只能说明种子数据里角色被改坏了。
 *
 * 六个内容板块在侧栏只占一条「直播」，剩下五块移到顶栏的分段控件上（与 admin / 退出 同一行）：
 * 它们本来就是同一个页面的六个标签，在菜单里摆六条既挤长了列表，又让人误以为是六个不相干的系统。
 * 合并的只是导航形状，路由与权限点一个没动。
 *
 * 「系统配置」与「全服公告」是两条而不是合在一条：前者改的是可以立刻改回来的旋钮，
 * 后者一发就对着全部在线连接喊话、弹出去的通知收不回来。服务端把它们分成两个权限点，
 * 菜单跟着分——能调开关的人不必能发公告。
 *
 * 四期第五批的四条同样与服务端的四个点一一对应，且彼此不顺带：
 * 「群组管理」与「消息管理」分开，是因为前者只能掐一个群、后者能读到任意两个人的聊天正文；
 * 「钱包与礼物」单列一条，是因为它是全后台唯一能凭空造出货币的屏；
 * 「AI 面试记录」单列一条，是因为它读的是候选人的作答原文。
 * 菜单上多四条不是为了好看，是为了让「给了谁什么」这件事在一个不写 SQL 的人眼中也能看出来。
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/**
 * 直播页的六个内容板块：侧栏合成一条、顶栏横排切换。
 *
 * 顺序就是用户从直播页看到的顺序（直播 / 视频 / 文章 / 图片 / 音乐 / 小说），与侧栏
 * 原本从上到下摆的顺序故意不一致：那一列原来按「风险量级」排，而这里按「用户心智」排。
 * perm 与 router/index.js 里各路由的 meta.perm 逐项对应，新增板块时两边一起加。
 */
const BOARDS = [
  { name: 'lives', label: '直播', perm: 'system:live:manage' },
  { name: 'videos', label: '视频', perm: 'system:video:manage' },
  { name: 'articles', label: '文章', perm: 'system:article:manage' },
  { name: 'images', label: '图片', perm: 'system:image:manage' },
  { name: 'musics', label: '音乐', perm: 'system:music:manage' },
  { name: 'novels', label: '小说', perm: 'system:novel:manage' }
]

// 移动端入口的显隐与顶栏页签：表只有一份（utils/mobileTabs），内容区 MobileView 吃的是同一份，
// 于是不会出现「菜单里有、顶栏上没得、点进去渲染第三个」这种对不上号的状态
const mobileTabs = computed(() => visibleMobileTabs((code) => auth.can(code)))
const hasMobileAny = computed(() => mobileTabs.value.length > 0)
const isMobilePage = computed(() => route.name === 'mobile')
const mobileOptions = computed(() =>
  mobileTabs.value.map((tab) => ({ label: tab.label, value: tab.value }))
)
const mobileTab = computed({
  get: () => resolveMobileTab(mobileTabs.value, route.query.tab),
  set: (value) => {
    if (value && value !== mobileTab.value) {
      router.push({ name: 'mobile', query: { ...route.query, tab: value } })
    }
  }
})

// 只列持有权的板块：给了图片权限的人不该在顶部看到一个点了就 1003 的小说标签
const visibleBoards = computed(() => BOARDS.filter((b) => auth.can(b.perm)))
const boardTarget = computed(() => visibleBoards.value[0]?.name || 'dashboard')
const boardOptions = computed(() => visibleBoards.value.map((b) => ({ label: b.label, value: b.name })))
const isBoardPage = computed(() => visibleBoards.value.some((b) => b.name === route.name))
const boardTab = computed({
  get: () => route.name,
  set: (name) => {
    if (name && name !== route.name) {
      router.push({ name })
    }
  }
})

// 板块页统一回落到合并项的 index，其余页面用路由名：如果这里直接返回 route.name，
// 那么在 /users 这类页面时 activeName 是 users，而合并项的 index 也跟着变成 users，
// 会出现「用户管理」和「直播」两条同时高亮
const activeName = computed(() => (isBoardPage.value ? boardTarget.value : route.name))

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
  /* 菜单条数超过一屏时要能自己滚：flex 子项默认 min-height:auto 会撑到内容高、
     不收缩，于是被外层 .side 的 overflow:hidden 直接裁掉、底部条目永远够不到。
     min-height:0 解除这条默认，配合 overflow-y:auto 让滚轮落在菜单内部。 */
  min-height: 0;
  overflow-y: auto;
}

/* 侧栏注释固定在底部不跟着滚：flex:none 不参与伸缩，菜单滚到底也该一直看得见 */
.side-note {
  flex: none;
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

/* 顶栏板块切换：不参与伸缩，窄屏下自己横向滚，不把右侧的退出按钮顶出屏幕 */
.board-tabs {
  flex: none;
  max-width: 100%;
  overflow-x: auto;
}

/* 移动端页签与板块切换条同一条策略：宽度跟着选项文字走（el-segmented 默认就是内容宽），
   靠 flex:none 不去抢中间那段空白，窄屏下自己横滚，不把右侧的 admin / 退出 顶出屏幕 */
.mobile-tabs {
  flex: none;
  max-width: 100%;
  overflow-x: auto;
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
