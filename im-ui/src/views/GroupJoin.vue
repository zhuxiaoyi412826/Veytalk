<template>
  <div class="gj">
    <div class="gj__card">
      <el-icon class="gj__logo" :size="40"><ChatDotRound /></el-icon>

      <!-- 请求中 -->
      <template v-if="phase === 'loading'">
        <div v-loading="true" class="gj__loading">正在处理入群申请…</div>
      </template>

      <!-- 直接入群成功 -->
      <template v-else-if="phase === 'joined'">
        <div class="gj__title">已加入群聊</div>
        <div class="gj__desc">你已加入「{{ result?.groupName || '群聊' }}」，现在可以开始聊天了。</div>
        <el-button type="primary" class="gj__btn" @click="goChat">进入群聊</el-button>
      </template>

      <!-- 已提交待审批 -->
      <template v-else-if="phase === 'pending'">
        <div class="gj__title">申请已提交</div>
        <div class="gj__desc">
          「{{ result?.groupName || '群聊' }}」已开启入群审批，请等待管理员确认。通过后会自动通知你。
        </div>
        <el-button class="gj__btn" @click="goHome">返回首页</el-button>
      </template>

      <!-- 失败 / 无效 -->
      <template v-else-if="phase === 'error'">
        <div class="gj__title gj__title--error">无法入群</div>
        <div class="gj__desc">{{ errorMsg }}</div>
        <el-button class="gj__btn" @click="goHome">返回首页</el-button>
      </template>

      <!-- 缺邀请码 -->
      <template v-else>
        <div class="gj__title gj__title--error">链接无效</div>
        <div class="gj__desc">缺少邀请码，请通过有效的邀请链接或二维码进入。</div>
        <el-button class="gj__btn" @click="goHome">返回首页</el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useGroupStore } from '@/stores/group'
import { useConversationStore } from '@/stores/conversation'
import { getToken } from '@/utils/token'
import { asId } from '@/utils/id'

/**
 * 扫码 / 点链接入群落地页。
 *
 * 路由 /group/join?code=xxx&src=link|qrcode：登录后带 token 调 applyJoin，后端按目标群的
 * join_approval 决定直接入群（joined）还是转待审批（pending）。未登录时路由守卫会先把用户送去
 * 登录并携带 redirect，登录后自动回到本页。
 *
 * 幂等：本组件只在挂载时申请一次；已在群里（GROUP_ALREADY_MEMBER）当作 joined 处理，直接引导进群聊。
 */

const route = useRoute()
const router = useRouter()
const group = useGroupStore()
const conversation = useConversationStore()

const phase = ref('loading') // loading | joined | pending | error | invalid
const result = ref(null)
const errorMsg = ref('')

function goChat() {
  const convId = result.value && result.value.conversationId
  if (convId) {
    router.replace({ name: 'chat', params: { conversationId: asId(convId) } })
  } else {
    // 拿不到会话 ID 时退回首页并刷新会话列表，群会话应已在其中
    conversation.fetchList().finally(() => router.replace({ name: 'chat' }))
  }
}

function goHome() {
  router.replace({ name: 'chat' })
}

onMounted(async () => {
  const code = route.query.code
  if (!code) {
    phase.value = 'invalid'
    return
  }
  if (!getToken()) {
    // 守卫通常已拦截，这里兜底：带 redirect 去登录，登录后回到本页继续
    router.replace({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const source = route.query.src === 'qrcode' ? 'qrcode' : 'link'
  try {
    const vo = await group.applyJoin(code, source, '')
    result.value = vo
    phase.value = vo && vo.status === 'joined' ? 'joined' : 'pending'
  } catch (e) {
    // 已在群里：request.js 抛出的业务错误，按“已加入”引导进群更友好
    const msg = (e && (e.message || e.msg)) || '入群申请失败，请稍后重试'
    if (/已经在群|已是成员|群成员/i.test(msg)) {
      phase.value = 'joined'
      result.value = result.value || {}
    } else {
      errorMsg.value = msg
      phase.value = 'error'
    }
  }
})
</script>

<style scoped>
.gj {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--im-bg, #f5f7fa);
  padding: 20px;
}

.gj__card {
  width: 100%;
  max-width: 380px;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
  padding: 32px 24px;
  text-align: center;
}

.gj__logo {
  color: var(--im-primary, #409eff);
  margin-bottom: 12px;
}

.gj__loading {
  padding: 24px 0;
  color: var(--im-text-secondary);
  font-size: 14px;
}

.gj__title {
  font-size: 18px;
  font-weight: 600;
  color: var(--im-text);
  margin-bottom: 10px;
}

.gj__title--error {
  color: var(--im-danger, #f56c6c);
}

.gj__desc {
  font-size: 13px;
  color: var(--im-text-secondary);
  line-height: 1.6;
  margin-bottom: 20px;
}

.gj__btn {
  width: 100%;
}
</style>
