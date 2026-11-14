<template>
  <!-- legal--printing 只在导出合集那一瞬挂上：打印时当前页面整块让位，纸上只留合集（见下方 @media print） -->
  <div class="legal" :class="{ 'legal--printing': exporting }">
    <header class="legal__bar">
      <div class="legal__brand">
        <el-icon :size="22"><ChatDotRound /></el-icon>
        <span>Veytalk</span>
      </div>
      <!--
        三份文本互相能跳。导航用的是本地兜底表而不是等服务端清单：
        这个页面的存在意义就是「任何人都能打开」，接口不通的时候它仍然要把三个链接摆出来。
      -->
      <nav class="legal__nav">
        <router-link
          v-for="item in navItems"
          :key="item.code"
          class="legal__link"
          :class="{ 'legal__link--active': item.code === code }"
          :to="{ name: 'legal', params: { code: item.code } }"
        >{{ item.codeName }}</router-link>
      </nav>
    </header>

    <main ref="mainRef" class="legal__main">
      <div v-loading="loading" class="legal__card">
        <template v-if="error">
          <div class="legal__title legal__title--error">{{ error }}</div>
          <p class="legal__desc">请从上方导航选择要查看的文本，或稍后重试。</p>
        </template>

        <template v-else-if="doc">
          <h1 class="legal__title">{{ doc.title }}</h1>
          <p class="legal__meta">
            <span>第 {{ doc.version }} 版</span>
            <span v-if="doc.effectiveTime">｜{{ formatDate(doc.effectiveTime) }} 起生效</span>
            <span v-if="doc.required === false">｜可选条款，可拒绝</span>
          </p>
          <!-- 历史版本必须让人看清「这不是现行文本」，否则公示页反而成了误导源 -->
          <el-alert
            v-if="historical"
            class="legal__notice"
            type="warning"
            :closable="false"
            show-icon
            title="你正在查看一份已被替换的历史版本"
          >
            <template #default>
              现行有效版本是第 {{ currentVersion }} 版，
              <router-link class="legal__link-inline" :to="{ name: 'legal', params: { code } }">查看现行版本</router-link>。
            </template>
          </el-alert>
          <p v-if="doc.summary" class="legal__summary">{{ doc.summary }}</p>
          <MarkdownView class="legal__content" :content="doc.content" />
        </template>
      </div>
    </main>

    <footer class="legal__foot">
      <span>本页面为 Veytalk 法定文本公示页，无需登录即可访问，欢迎保存或打印。</span>
      <router-link class="legal__link-inline" :to="{ name: 'login' }">返回登录</router-link>
      <!--
        导出走浏览器打印对话框（目标选「另存为 PDF」），不引 html2canvas/jsPDF：
        那样出来的是位图 PDF，法律文本里的字既选不中也搜不了，还白背几百 KB 依赖，
        与本仓「PDF 预览用浏览器内置查看器、不引 pdf.js」的既有口径一致（见 FileViewer.vue）。
        用原生 button 而不是 el-button：这一行是 12px 的小字，颜色与内边距都要和旁边的链接对齐。
      -->
      <button
        type="button"
        class="legal__link-inline legal__export"
        :disabled="exporting || !!error"
        @click="onExport"
      >{{ exporting ? '正在准备…' : '导出 PDF' }}</button>
    </footer>

    <!--
      打印导出用的合集：屏幕上完全不现身，只在纸上排版。
      为什么在前端拼而不是后端直接出 PDF：三份文本本身就是 markdown，后端再排一遍就会出
      第二套版式，将来「页面所见」与「留档所得」对不上的时候，没人说得清以哪边为准。
    -->
    <div v-if="exporting" class="legal__print">
      <div class="legal__print-cover">
        <h1 class="legal__print-h1">Veytalk 法定文本合集</h1>
        <p class="legal__print-meta">
          导出时间：{{ exportAt }}
          <span v-if="origin">｜来源：{{ origin }}/legal</span>
        </p>
        <ul class="legal__print-toc">
          <li v-for="item in exportDocs" :key="item.code">{{ item.codeName }}：第 {{ item.version }} 版<span v-if="item.effectiveTime">（{{ formatDate(item.effectiveTime) }} 起生效）</span></li>
        </ul>
        <p class="legal__print-tip">
          本合集由公示页直接打印生成，内容与页面所见一致。只印其中一份时，可在对应文本页用浏览器自身的打印。
        </p>
      </div>
      <section v-for="item in exportDocs" :key="`doc-${item.code}`" class="legal__print-doc">
        <h2 class="legal__print-h2">{{ item.title }}</h2>
        <p class="legal__print-meta">
          第 {{ item.version }} 版
          <span v-if="item.effectiveTime">｜{{ formatDate(item.effectiveTime) }} 起生效</span>
        </p>
        <p v-if="item.summary" class="legal__print-summary">{{ item.summary }}</p>
        <MarkdownView class="legal__print-content" :content="item.content" />
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * 法定文本公示页：/legal/:code（terms 用户协议 / privacy 隐私政策 / service 服务条款）。
 *
 * 路由是 public：没登录的人、被分享进来看的第三方、审核人员都必须能直接打开全文，
 * 这也是后端把 /api/agreement/list 与 /api/agreement/view/** 放进匿名白名单的原因。
 * 反过来，如果这一页需要登录才能看，「已公示」就不成立了。
 *
 * ?version=N 用来查历史版本：一份文本被替换后，它仍然是某批用户同意的凭据所指，
 * 公示页要能把那一版原样摆出来（草稿态查不到 —— 没发布过的东西谈不上历史版本）。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { useAgreementStore } from '@/stores/agreement'
import { setSectionTitle } from '@/utils/title'
import { isCapacitor } from '@/utils/env'

const route = useRoute()
const store = useAgreementStore()

/** 后端顺序拿到之前先按这个顺序摆导航，中文名与数据库 code_name 一致 */
const FALLBACK_ITEMS = [
  { code: 'terms', codeName: '用户协议' },
  { code: 'privacy', codeName: '隐私政策' },
  { code: 'service', codeName: '服务条款' }
]

const loading = ref(false)
const doc = ref(null)
const error = ref('')
const mainRef = ref(null)
/** 导出中：既驱动按钮的文案/禁用，也控制那份只在纸上出现的合集 */
const exporting = ref(false)
const exportDocs = ref([])
const exportAt = ref('')

/** 打印出来的纸不会带地址栏，所以得在正文里留一处能对得上来源的地方 */
const origin = computed(() => (typeof window !== 'undefined' ? window.location.origin : ''))

const code = computed(() => String(route.params.code || ''))
const navItems = computed(() => (store.list.length ? store.list : FALLBACK_ITEMS))

/**
 * 导出对象与导航同一口径：以后台清单为准，拉不到才退回本地兼容表。
 * 这里不写死三份是因为第四份文本迟早要来（`im_agreement.code` 故意没建 ENUM），
 * 而写死了就是「导航会跟着后台走、导出静默少一份」——后者没人会发现。
 */
const exportTargets = computed(() => (store.list.length ? store.list : FALLBACK_ITEMS))

/** URL 上带了 version 且与当前生效版不同，才算在查历史版 */
const historical = computed(() => {
  const version = Number(route.query.version) || 0
  return version > 0 && !!doc.value && version !== currentVersionOf(code.value)
})

const currentVersion = computed(() => currentVersionOf(code.value))

function currentVersionOf(value) {
  return store.byCode(value)?.version || doc.value?.version || 0
}

function formatDate(value) {
  // 后端给的是 LocalDateTime 序列化结果，取到日就够：公示页要的是「哪天开始算」
  return String(value || '').replace('T', ' ').slice(0, 10)
}

async function load() {
  const target = code.value
  if (!FALLBACK_ITEMS.some((item) => item.code === target)) {
    doc.value = null
    error.value = '没有这一份文本'
    return
  }
  loading.value = true
  error.value = ''
  // 三份文本共用同一个组件实例（只换 :code），滚动容器因此不会自己归零：
  // 不手动滚回顶部的话，从「用户协议」读到一半再点「隐私政策」，会直接落在对方中段
  if (mainRef.value) {
    mainRef.value.scrollTop = 0
  }
  try {
    const version = Number(route.query.version) || undefined
    const vo = await store.loadContent(target, version)
    if (!vo) {
      doc.value = null
      error.value = '文本暂时无法加载，请稍后重试'
      return
    }
    doc.value = vo
    setSectionTitle(vo.title || '法律文本')
  } finally {
    loading.value = false
  }
}

/**
 * 导出三份文本：拉齐现行版 → 渲一份合集 → 交给浏览器打印（目标选「另存为 PDF」）。
 *
 * 三份都取现行版而不是「当前正在看的这一版」：用户可能正打开的是 ?version=2 的历史版，
 * 而留档要的是「此刻生效的那一份」；封面会把每份的版本号列清楚，不存在对不上。
 * loadContent 自带 code:version 缓存，所以当前这份不会重拉一次。
 */
async function onExport() {
  if (exporting.value) {
    return
  }
  // 安卓 WebView 里 window.print() 是个空操作（Capacitor 没接打印），不先拦住就是「点了没反应」
  if (isCapacitor()) {
    ElMessage.info('安卓端暂不支持直接导出，请在电脑浏览器打开本页，用「打印 / 另存为 PDF」')
    return
  }
  exporting.value = true
  try {
    const targets = exportTargets.value
    const results = await Promise.all(
      targets.map(async (item) => {
        const vo = await store.loadContent(item.code)
        // 中文名取自清单自己：后端清单拉不到时也不能把 TOC 写成空白
        return vo ? { ...vo, code: item.code, codeName: item.codeName } : null
      })
    )
    const docs = results.filter(Boolean)
    const missing = targets.filter((_, index) => !results[index]).map((item) => item.codeName)
    if (!docs.length) {
      ElMessage.error('文本内容拉取失败，请稍后重试')
      return
    }
    if (missing.length) {
      // 不能悄悄少给一份：留档的人以为拿到的是全套，实际缺一份就是合规问题
      ElMessage.warning(`以下文本未能导出：${missing.join('、')}`)
    }
    exportDocs.value = docs
    exportAt.value = new Date().toLocaleString()
    // 等一帧把合集渲出来再触发打印：print() 是同步的，DOM 没就位就会打出空页
    await nextTick()
    window.print()
  } finally {
    exporting.value = false
    exportDocs.value = []
  }
}

onMounted(() => {
  // 清单只为「当前版本号」与导航中文名服务，拉不到不影响正文显示，因此不等它
  store.refreshList(true)
  load()
})

watch(
  () => route.fullPath,
  () => load()
)
</script>

<style scoped>
.legal {
  /* 全局 body 是 overflow:hidden（聊天窗里气泡与拖拽选择很容易触发视口滚动，从根上禁了），
     而它的 overflow 会向上传播到视口——所以这一页不能指望浏览器自带的滚动条，
     必须自己带滚动容器，否则长文本会完整排版出来却一屏都动不了。
     做法与 index.css 的 .im-dialog--fit 同一口径：头尾钉住，只滚中间那一段。
     两行写法是有意的：老内核不认 dvh 时退回 100vh，移动端地址栏伸缩用 dvh。 */
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--im-bg, #f5f7fa);
}

.legal__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 14px 24px;
  background: var(--im-bg-card, #fff);
  border-bottom: 1px solid var(--im-border, #e4e7ed);
}

.legal__brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--im-primary, #409eff);
}

.legal__nav {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.legal__link {
  padding: 5px 12px;
  border-radius: 14px;
  font-size: 13px;
  color: var(--im-text-secondary, #606266);
  text-decoration: none;
}

.legal__link:hover {
  background: var(--el-fill-color-light);
}

.legal__link--active {
  color: #fff;
  background: var(--im-primary, #409eff);
}

.legal__main {
  flex: 1;
  /* flex 项默认 min-height:auto，不写这一行 overflow-y 不会生效：内容会把容器顶高而不是让它滚 */
  min-height: 0;
  overflow-y: auto;
  /* 必写：`align-items` 默认 normal 在 flex 里等价于 stretch，而本容器高度是确定的（flex:1），
     卡片会被钉成容器可见高度（实测 335px）而内容高达 2838px，白底就只盖住开头那一截，
     后面的正文全部溢到灰底上。改成 flex-start 后卡片高度跟着内容走，背景才包得住全文。 */
  align-items: flex-start;
  padding: 24px 16px;
  display: flex;
  justify-content: center;
}

.legal__card {
  width: 100%;
  max-width: 820px;
  min-height: 320px;
  padding: 28px 32px 40px;
  background: var(--im-bg-card, #fff);
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
}

.legal__title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  color: var(--im-text, #303133);
}

.legal__title--error {
  color: var(--im-danger, #f56c6c);
}

.legal__meta {
  margin: 0 0 16px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--im-text-secondary, #909399);
}

.legal__notice {
  margin-bottom: 16px;
}

.legal__summary {
  margin: 0 0 16px;
  padding: 10px 14px;
  border-left: 3px solid var(--im-primary, #409eff);
  background: var(--el-fill-color-lighter);
  border-radius: 0 6px 6px 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--im-text-secondary, #606266);
}

.legal__desc {
  font-size: 13px;
  color: var(--im-text-secondary, #909399);
}

.legal__link-inline {
  color: var(--im-primary, #409eff);
  text-decoration: none;
}

.legal__foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px;
  font-size: 12px;
  color: var(--im-text-secondary, #909399);
}

/*
  导出用的合集在屏幕上不占任何位置：它只是打印的一个载体，
  真要用时由 @media print 里那一条把它放出来（v-if 保证它只在导出那一瞬存在于 DOM）。
*/
.legal__print {
  display: none;
}

/* 页脚那一行是 12px 小字，按钮得长得跟旁边的链接一样：去掉浏览器默认的边框与底色 */
.legal__export {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  cursor: pointer;
}

/* 正文没加载成功（error）与导出进行中都不可点，禁用态靠透明度提示而不是换色 */
.legal__export:disabled {
  cursor: default;
  opacity: 0.6;
}

@media (max-width: 640px) {
  .legal__card {
    padding: 20px 16px 28px;
  }
}

/* 打印：公示文本常被要求「保存到本地留档」，去掉外框与背景色更接近一份正式文件 */
@media print {
  /*
    打印永远是把内容印在白纸上，而外观可以选深色：那时 --im-text 与 EP 的
    --el-text-color-primary 都是「浅字」（settings store 往 <html> 上挂 .dark，见 index.css），
    不就地翻成深色的话导出的 PDF 就是白底白字。自定义属性沿继承往下走，
    所以在 .legal 上重定义一次，MarkdownView 里那些 var(...) 也跟着翻正。
  */
  .legal {
    --im-text: #303133;
    --im-text-secondary: #606266;
    --im-bg-card: #fff;
    --el-text-color-primary: #303133;
    --el-text-color-regular: #606266;
    --el-fill-color-light: #f5f5f5;
    --el-fill-color-lighter: #fafafa;
    --el-border-color: #dcdfe6;

    background: #fff;
    /* 屏幕上是定高 + 内层滚动，打印时必须放开：否则整份协议会被裁在第一页里 */
    height: auto;
    /* ★ 从 flex 改回普通块级：弹性容器里的子项分页在部分内核上不可靠（容易整块挤在一页里被截断），
       而纸上本来就不需要「居中」与「等分高度」这两件事 */
    display: block;
  }

  .legal__bar,
  .legal__foot,
  .legal__notice {
    display: none;
  }

  .legal__main {
    padding: 0;
    height: auto;
    overflow: visible;
    display: block;
  }

  .legal__card {
    max-width: none;
    padding: 0;
    box-shadow: none;
  }

  /* 导出合集时当前这一页整块让位：否则正在看的那份会被重复打一遍（合集里已经有它） */
  .legal--printing .legal__main {
    display: none;
  }

  /* 屏幕上不存在的那份合集，到纸上才排版；字号按 A4 正文的习惯走，行距放宽便于阅读 */
  .legal--printing .legal__print {
    display: block;
    font-size: 11pt;
    line-height: 1.8;
    color: #303133;
  }

  .legal__print-cover {
    /* 封面单独占一页，后面三份文本各自从新的一页开始（page-break-* 是老内核的写法，两套都留） */
    break-after: page;
    page-break-after: always;
  }

  /* 每份文本各起一页：合起来看是一份文件，拆开读仍是三份独立文本 */
  .legal__print-doc + .legal__print-doc {
    break-before: page;
    page-break-before: always;
  }

  .legal__print-h1,
  .legal__print-h2 {
    /* 标题不许落在页面最后一行、正文却跑到下一页：法律文本的目录感全靠这一条 */
    break-after: avoid;
    page-break-after: avoid;
  }

  .legal__print-h1 {
    margin: 0 0 12pt;
    font-size: 20pt;
    font-weight: 600;
  }

  .legal__print-h2 {
    margin: 0 0 6pt;
    font-size: 15pt;
    font-weight: 600;
  }

  .legal__print-meta {
    margin: 0 0 12pt;
    font-size: 10pt;
    color: #606266;
  }

  .legal__print-toc {
    margin: 0 0 12pt;
    padding-left: 18pt;
    font-size: 10pt;
    line-height: 1.9;
  }

  .legal__print-tip {
    margin: 0;
    padding: 8pt 10pt;
    border-left: 2pt solid #409eff;
    background: #f5f7fa;
    font-size: 9pt;
    color: #606266;
    /* 浏览器打印默认会把背景色省掉，这句说明的底色必须显式要求印出来 */
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }

  .legal__print-summary {
    margin: 0 0 10pt;
    font-size: 10pt;
    color: #606266;
  }
}
</style>
