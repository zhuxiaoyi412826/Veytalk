<template>
  <el-dialog
    v-model="visible"
    class="acd im-dialog--fit"
    title="请阅读并同意以下条款"
    width="min(640px, calc(100vw - 24px))"
    top="8vh"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="dismissible"
    :show-close="dismissible"
  >
    <p class="acd__lead">
      以下文本需要你确认后继续使用。每一段都可以直接读下去，勾上即表示你已阅读并同意所显示的这一版内容。
    </p>

    <el-tabs v-model="activeCode" class="acd__tabs" @tab-change="ensureContent">
      <el-tab-pane v-for="item in store.pending" :key="item.code" :name="item.code">
        <template #label>
          <span class="acd__tab-label">
            {{ item.codeName }}
            <em>v{{ item.version }}</em>
            <el-tag v-if="item.required === false" size="small" type="info" effect="plain">可选</el-tag>
          </span>
        </template>
        <div v-loading="!contents[item.code]" class="acd__doc">
          <template v-if="contents[item.code]">
            <p class="acd__doc-meta">
              第 {{ contents[item.code].version }} 版｜{{ formatDate(contents[item.code].effectiveTime) }} 起生效
            </p>
            <MarkdownView :content="contents[item.code].content" />
          </template>
        </div>
        <p class="acd__doc-more">
          <router-link
            class="acd__link"
            :to="{ name: 'legal', params: { code: item.code }, query: { version: item.version } }"
            target="_blank"
          >在新窗口阅读全文 / 打印留存</router-link>
        </p>
      </el-tab-pane>
    </el-tabs>

    <div class="acd__checks">
      <label v-for="item in store.pending" :key="`check-${item.code}`" class="acd__check">
        <el-checkbox v-model="agreed[item.code]">
          我已阅读并同意
          <b>{{ item.codeName }}</b>（第 {{ item.version }} 版）
          <span v-if="item.required === false" class="acd__check-tip">不勾选将记录为「明确拒绝」，此后不再就同一版本询问你</span>
        </el-checkbox>
      </label>
    </div>

    <template #footer>
      <div class="acd__footer">
        <!--
          不可跳过时左下这个按钮是用户唯一的退出路径：不给「关闭」但也不给「走人」，
          等于把人锁在一个他没法同意的界面里，那本身就是一种胁迫式体验。
        -->
        <el-button v-if="!dismissible" link type="danger" @click="refuseAndSignOut">不同意并退出登录</el-button>
        <el-button v-else @click="onDismiss">暂不处理</el-button>
        <el-button type="primary" :loading="store.submitting" :disabled="!canAccept" @click="onAccept">
          同意并继续
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 登录后强制再同意弹窗（全站只挂一份，挂在 MainLayout）。
 *
 * 它兜住三种「账号当前没有对生效版本的同意记录」：
 *  1. 后台发了新版 —— 旧版的同意覆盖不了新版新增的收集行为，必须重新取；
 *  2. 验证码 / 邮箱 / 扫码登录自动建号 —— 这些人从来没经过注册页那个勾选框；
 *  3. 这套功能上线之前就注册的老账号 —— 库里一条留痕都没有。
 *
 * 「能不能关」不由前端判：后端 pending 接口给了 dismissible，
 * 因为「哪些算必签」是法务口径，写进前端代码就会变成两份要同步维护的真相。
 *
 * 弹窗里直接渲全文而不是只放一个链接：只给「我已阅读」的勾选框而读不到内容，
 * 取到的同意在举证时站不住 —— 所以这一屏必须真能读，且读的就是所勾的那一版。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MarkdownView from '@/components/MarkdownView.vue'
import { useAgreementStore } from '@/stores/agreement'
import { signOut } from '@/stores'

const store = useAgreementStore()

/** 正在看的那一份的 code，等于 store.pending 的第一项 */
const activeCode = ref('')
/** 已下载正文，按 code 存；store 内部有跨页面缓存，这里只存本弹窗这一份引用 */
const contents = reactive({})
/** 勾选状态，按 code 存；每次待签清单变化都整体重置，不给上一轮的勾留到这里 */
const agreed = reactive({})

const dismissible = computed(() => store.dismissible !== false)

/** 必签项全勾上才放行；可选项不参与这个判断 */
const canAccept = computed(() =>
  store.pending.filter((item) => item.required !== false).every((item) => agreed[item.code] === true)
)

const visible = computed({
  get: () => store.dialogVisible,
  // 关闭路径（右上角 X / Esc）一律走 dismiss：可跳过才记本地标记，逻辑收在 store 里
  set: (value) => {
    if (!value) {
      store.dismiss()
    }
  }
})

function formatDate(value) {
  return String(value || '').replace('T', ' ').slice(0, 10)
}

/** 只在下拉的那一版没下载过时打一次接口 */
async function ensureContent(code) {
  if (!code || contents[code]) {
    return
  }
  const item = store.pending.find((one) => one.code === code)
  if (!item) {
    return
  }
  const vo = await store.loadContent(code, item.version)
  if (vo) {
    contents[code] = vo
  } else {
    ElMessage.warning('文本加载失败，可点开「在新窗口阅读全文」重试')
  }
}

async function resetForPending() {
  Object.keys(agreed).forEach((key) => delete agreed[key])
  store.pending.forEach((item) => {
    agreed[item.code] = false
  })
  activeCode.value = store.pending.length ? store.pending[0].code : ''
  Object.keys(contents).forEach((key) => delete contents[key])
  await ensureContent(activeCode.value)
}

watch(() => store.pending, resetForPending, { immediate: true })

async function onAccept() {
  if (!canAccept.value) {
    return
  }
  const items = store.pending.map((item) => ({
    code: item.code,
    version: item.version,
    // 必签项能提交到这里必然是勾了的；可选项按勾选状态原样上报，
    // 没勾也提交（accept=false）而不是干脆不发 —— 那条「他明确拒过」的记录才是拒的凭据
    accept: item.required === false ? agreed[item.code] === true : true
  }))
  const result = await store.submit(items)
  if (result === 'ok') {
    ElMessage.success('已记录你的同意')
  } else if (result === 'mismatch') {
    // 待签清单已被后端刷新，勾选状态在 watch 里重置了，用户会看到新版内容并重新一遍
    ElMessage.warning('条款内容有更新，请重新阅读后再确认')
  }
}

function onDismiss() {
  store.dismiss()
}

/**
 * 不同意 = 不用这个产品。
 *
 * 只清本地登录态与各类缓存后回登录页，不写任何服务端留痕：
 * 「拒绝」这件事只有他主动点下同意/拒绝并被服务端记下才算证据，
 * 退出登录不能替他表态（退出也可能只是他要去回个邮件）。
 */
async function refuseAndSignOut() {
  await signOut()
}
</script>

<style scoped>
.acd__lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--im-text-secondary, #606266);
}

.acd__tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.acd__tab-label em {
  font-style: normal;
  color: var(--im-text-secondary, #909399);
  font-size: 12px;
}

.acd__doc {
  padding: 12px 14px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 8px;
  background: var(--im-bg, #f7f8fa);
  /*
   * 这里**不再**自己开滚动区是一个刻意的改动，不是删漏了。
   * 旧写法是 `max-height: 300px + overflow-y: auto + overscroll-behavior: contain`，
   * 等于在弹窗里再套一个滚动容器：矮屏（实测 490px 视口）下整弹窗本来就高于屏幕，
   * 外层 .el-overlay-dialog 与内层正文区抢同一个滚轮，滚到正文底部又被 contain 锁住
   * 不向上交接，用户得到的现象就是「三份文本都下拉不到底、下面的勾选框也看不见」。
   * 现在只留弹窗 body 一个滚动容器（见 styles/index.css 的 .im-dialog--fit），
   * 正文区按内容自然长高，整屏一次滚到底。
   */
}

.acd__doc-meta {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

.acd__doc-more {
  margin: 8px 0 0;
  text-align: right;
}

.acd__link {
  font-size: 12px;
  color: var(--im-primary, #409eff);
  text-decoration: none;
}

.acd__checks {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.acd__check {
  display: block;
  cursor: pointer;
}

.acd__check-tip {
  display: block;
  margin-left: 2px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--im-text-secondary, #909399);
}

.acd__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
