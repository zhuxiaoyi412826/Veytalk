<script setup>
// Veytalk 官网：展示单页。静态文案数组驱动，下载卡与历史版本则一起由后台「客户端版本管理」驱动：
// onMounted 各拉一次 GET {APP_BASE}/api/release/list 与 /api/release/history（两条都是匿名端点：
// list 只回启用中的版本，history 含已停用但不给停用包的下载链）
// 拉到了就用服务端数据渲染卡片——后台上传并启用什么版本，访客下载到的就是什么版本；
// 拉不到（后端没起、表没建、跨域未配）则回落下面的静态数组，页面永不白屏。
// 静态回落的链接仍指向 public/downloads/ 下的规范文件名（ASCII，无空格），
// 产物由 scripts/fetch-downloads.ps1 从 electron/release 拷来并改名，详见该脚本与 README。
import { computed, onMounted, ref } from 'vue'

const GITHUB_URL = 'https://github.com/zhuxiaoyi412826/Veytalk'
const DOCS_URL = `${GITHUB_URL}/blob/main/README.md`
const RELEASES_URL = `${GITHUB_URL}/releases`
/** 仅在拉不到服务端数据时兜底展示，不是权威版本号——真实版本号在 im_client_release 表里 */
const STATIC_VERSION = '1.0.0'

const features = [
  { icon: '💬', title: '即时通讯', desc: '单聊 / 群聊实时消息，WebSocket 推送，支持引用、回复、转发与已读回执' },
  { icon: '👥', title: '社交关系', desc: '好友申请、群组管理、黑名单集中管控，构建完整社交链路' },
  { icon: '📁', title: '文件传输', desc: '分片断点续传，MinIO / 本地双存储可切换，闪照阅后即焚' },
  { icon: '🖥️', title: '远程控制', desc: 'ToDesk 式远程桌面：识别码接入、局域网直连三档阶梯，端到端加密' },
  { icon: '🎙️', title: 'AI 面试官', desc: '知识库检索驱动的模拟面试，问答评估一体化' },
  { icon: '📡', title: '直播互动', desc: 'HTTP PUT 上行 + HLS 下行拉流，连麦 PK、礼物打赏、直播回放' },
  { icon: '🔐', title: '隐私保护', desc: '闪照、消息过期清理、撤回销毁等多重隐私能力，安全口径贯穿全链路' },
  { icon: '🛠️', title: '管理后台', desc: '用户 / 角色权限 / 视频 / 直播 / 监控告警 / 日志六位一体的独立后台' }
]

// 静态回落用的三张卡。图标用 💻 而不是 🪟：后者是 Emoji 13.0（2020）的新码点，
// Windows 10 旧字体与部分安卓机上渲染成豆腐块 □，而 💻 是 Emoji 1.0 全平台都有。
const STATIC_DOWNLOADS = [
  {
    icon: '💻',
    title: 'Windows 安装版',
    flag: '推荐',
    desc: `NSIS 安装包，双击安装即装即用，已内置 JRE、ffmpeg 与被控端 Agent，无需任何额外依赖`,
    file: `/downloads/Veytalk-Setup-${STATIC_VERSION}.exe`,
    spec: `${STATIC_VERSION} · x64 · Windows 10/11`
  },
  {
    icon: '📦',
    title: 'Windows 免安装版',
    flag: '绿色版',
    desc: '解压即运行的便携版，不写注册表、不产生卸载项，适合放在 U 盘或受限环境使用',
    file: `/downloads/Veytalk-Windows-x64-portable.zip`,
    spec: `${STATIC_VERSION} · x64 · 解压即用`
  },
  {
    icon: '🤖',
    title: 'Android 版',
    flag: '移动端',
    desc: '手机客户端 APK，支持移动端消息、直播观看与远程控制被控端，适配全面屏手势',
    file: `/downloads/Veytalk-Android.apk`,
    spec: `${STATIC_VERSION} · arm64 · Android 10+`
  }
]

const downloads = ref(STATIC_DOWNLOADS)

/** hero 那一行的版本与平台清单：拉到服务端数据后被顶掉，拉不到才是下面这两个兜底值 */
const version = ref(STATIC_VERSION)
const platformNames = ref([])
const heroMeta = computed(() => {
  const platforms = platformNames.value.length ? platformNames.value.join(' / ') : 'Windows x64 / 免安装版 / Android'
  return `当前版本 v${version.value} · ${platforms}`
})

/**
 * 平台码 → 图标/角标/一句话描述。码表与后端 ReleasePlatform 对齐；
 * 描述只作回落文案，后台填了更新说明（releaseNote）时优先展示那个——
 * 那才是发版的人想对访客说的话。
 */
const PLATFORM_META = {
  windows_setup: { icon: '💻', flag: '推荐', desc: 'NSIS 安装包，双击安装即装即用，已内置 JRE、ffmpeg 与被控端 Agent，无需任何额外依赖' },
  windows_portable: { icon: '📦', flag: '绿色版', desc: '解压即运行的便携版，不写注册表、不产生卸载项，适合放在 U 盘或受限环境使用' },
  android: { icon: '🤖', flag: '移动端', desc: '手机客户端 APK，支持移动端消息、直播观看与远程控制被控端，适配全面屏手势' },
  macos: { icon: '🍎', flag: 'macOS', desc: 'macOS 安装包' },
  linux: { icon: '🐧', flag: 'Linux', desc: 'Linux 安装包' }
}

function formatSize(bytes) {
  const n = Number(bytes)
  if (!n || n <= 0) {
    return ''
  }
  if (n >= 1024 * 1024 * 1024) {
    return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
  }
  if (n >= 1024 * 1024) {
    return `${(n / (1024 * 1024)).toFixed(1)} MB`
  }
  return `${Math.max(1, Math.round(n / 1024))} KB`
}

/**
 * 发布时间只取到「日」：后端 LocalDateTime 序列化成 2026-11-26T15:04:05 这种形态，
 * 截前 10 位就是日期，为这一件事引 dayjs 不值。秒级时间对访客是噪声——
 * 他们只需要知道「这个包是哪天发的」来配合 MD5 判断自己下没下到新包。
 */
function formatDate(text) {
  return text ? String(text).slice(0, 10) : ''
}

/**
 * 拉后台启用中的版本列表。失败一律静默回落静态卡：官网是门面，
 * 后端没起或迁移 SQL 没跑时也不该让访客看到报错——他们只需要一个能点的下载按钮。
 */
async function loadReleases() {
  try {
    const resp = await fetch(`${APP_BASE}/api/release/list`)
    if (!resp.ok) {
      return
    }
    const body = await resp.json()
    const list = body && body.code === 200 && Array.isArray(body.data) ? body.data : []
    if (!list.length) {
      return
    }
    downloads.value = list.map((r) => {
      const meta = PLATFORM_META[r.platform] || { icon: '⬇️', flag: '客户端', desc: '' }
      const size = formatSize(r.fileSize)
      return {
        icon: meta.icon,
        title: r.platformName || meta.flag,
        flag: meta.flag,
        desc: r.releaseNote || meta.desc,
        // downloadPath 是相对路径（/api/release/download/{id}），拼上 APP_BASE 后
        // 同源部署走 nginx 反代、异源部署走环境变量顶掉的基址，两种形态都不需 CORS 预检（GET 简单请求）
        file: `${APP_BASE}${r.downloadPath}`,
        spec: [r.version, size].filter(Boolean).join(' · '),
        // ★ 校验值与发布日期：后端 PublicReleaseVO 一直把 md5 / updateTime 一起发着，
        // 之前只取了 fileSize，等于把「访客能不能自己验包没坏」这件事白扔了。静态回落卡没这两个值，
        // 模板按 v-if 跳过，不编一个假 MD5 上去
        md5: r.md5 || '',
        released: formatDate(r.updateTime)
      }
    })
    // hero 那句「当前版本」跟着服务端走：取码表第一档（Windows 安装版）的版本，
    // 这样它跟下载区第一张卡说的是同一个包，不会出现「hero 写 1.2、第一张卡下的是 1.1」的自相矛盾。
    // ★ 不自己比版本大小：后端实体注释定了「1.10 与 1.9 谁新由发版的人知道，代码不猜」，
    // 前端拿 max() 排序正好违背它
    const first = list.find((r) => r.platform === 'windows_setup') || list[0]
    if (first && first.version) {
      version.value = first.version
    }
    platformNames.value = Array.from(new Set(list.map((r) => r.platformName).filter(Boolean)))
  } catch {
    // 静默回落：静态卡已在 downloads 的初值里
  }
}

onMounted(() => {
  loadReleases()
  loadHistory()
})

/**
 * 历史版本（含已停用），按平台分组展示。
 *
 * <p>拉不到就整块不渲染，而不是先占一个「加载中/加载失败」的坑：官网是门面，
 * 少一个区块访客不会察觉，多一块错误反而直接劝退。与下载卡的回落哲学一致。
 *
 * <p>后端已经把行按平台码表顺序排好了（Windows 安装版在最前），这里只做聚合、不再排序。
 */
const historyRows = ref([])
const historyGroups = computed(() => {
  const groups = []
  const index = new Map()
  for (const row of historyRows.value) {
    const name = row.platformName || row.platform
    if (!index.has(name)) {
      index.set(name, { name, rows: [] })
      groups.push(index.get(name))
    }
    index.get(name).rows.push({
      version: row.version,
      spec: [formatDate(row.updateTime), formatSize(row.fileSize)].filter(Boolean).join(' · '),
      note: row.releaseNote || '',
      // 停用行后端不给 downloadPath（公开下载只认启用行），这里就渲染成「已停用」而不是一个必 404 的死按钮
      file: row.downloadPath ? `${APP_BASE}${row.downloadPath}` : ''
    })
  }
  return groups
})

async function loadHistory() {
  try {
    const resp = await fetch(`${APP_BASE}/api/release/history?limit=5`)
    if (!resp.ok) {
      return
    }
    const body = await resp.json()
    historyRows.value = body && body.code === 200 && Array.isArray(body.data) ? body.data : []
  } catch {
    // 静默：这块是加分项，拉不到就当没这个区块
  }
}

const links = [
  { icon: '🐙', title: 'GitHub 仓库', desc: '源码、Issue 与更新动态一站直达', href: GITHUB_URL },
  { icon: '📖', title: '官方文档', desc: '部署、启动、打包与开发指引全在这份 README 里', href: DOCS_URL },
  { icon: '🚀', title: 'Release 下载', desc: '各平台安装包的历史版本发布页', href: RELEASES_URL }
]

const stack = ['Spring Boot 4', 'JDK 21', 'Vue 3 + Vite', 'Element Plus', 'MySQL 8', 'Redis', 'Netty WebSocket', 'MinIO', 'Electron', 'HLS / ffmpeg']

/**
 * 页脚的三个法定文本入口。
 *
 * 官网自己不抄一份协议正文：真源在数据库（后台可发布新版），
 * 公示页是 im-ui 那个无需登录就能打开的 /legal/{code}。这里只摆链接。
 * 应用与官网不同源时用环境变量把基址顶掉：.env 里写 VITE_APP_BASE=https://im.example.com
 * （不写则按同源处理，适用于官网与应用挂在同一个域名根路径下的部署）。
 * 留空与尾斜杠都归一到不带尾斜杠，否则会拼出 https://x//legal/terms 这种难看又难排的地址。
 */
const APP_BASE = (import.meta.env.VITE_APP_BASE || '').replace(/\/+$/, '')
const legalDocs = [
  { title: '用户协议', href: `${APP_BASE}/legal/terms` },
  { title: '隐私政策', href: `${APP_BASE}/legal/privacy` },
  { title: '服务条款', href: `${APP_BASE}/legal/service` }
]
</script>

<template>
  <header class="nav">
    <div class="container nav-inner">
      <a class="logo" href="#top">
        <span class="logo-badge">💬</span>
        <span>Veytalk</span>
      </a>
      <nav class="nav-links">
        <a href="#features" class="desk-only">产品特性</a>
        <a href="#downloads" class="desk-only">下载</a>
        <!-- 历史版本是拉到位才存在的区块，锚链也跟着条件渲染：链接指向一个不存在的 id 点了没反应 -->
        <a v-if="historyGroups.length" href="#history" class="desk-only">历史版本</a>
        <a href="#resources" class="desk-only">资源</a>
        <a :href="GITHUB_URL" target="_blank" rel="noopener" class="btn btn-ghost btn-sm">GitHub</a>
      </nav>
    </div>
  </header>

  <main id="top">
    <!-- Hero -->
    <section class="hero">
      <div class="container">
        <span class="hero-tag">开箱即用的多模块企业级即时通讯系统</span>
        <h1>Veytalk <em>IM 即时通讯系统</em></h1>
        <p class="hero-sub">
          群聊社交、文件传输、远程控制、AI 面试与 HLS 直播……
          由 Spring Boot 4 + Vue 3 实现，服务端一键打包成<b>单可执行 jar</b>，
          桌面端免装环境直接跑。
        </p>
        <div class="hero-actions">
          <a class="btn btn-primary" href="#downloads">⬇ 下载客户端</a>
          <a class="btn btn-ghost" :href="DOCS_URL" target="_blank" rel="noopener">📖 部署文档</a>
        </div>
        <p class="hero-meta">{{ heroMeta }}</p>
      </div>
    </section>

    <!-- 特性 -->
    <section id="features" class="section section-alt">
      <div class="container">
        <div class="section-head">
          <h2>产品特性</h2>
          <!--
            数目口径核过（2026-11-26）：14 = 根 pom.xml 的 <module> 条数；
            前端是 im-ui（聊天）/ im-admin-ui（控制台）/ im-website（官网）三套，
            另有 im-android 与 im-miniprogram——原先写的「两套前端」是从前的状态，已漂移。
            能力数不写死，直接取 features 数组长度，加一条卡片就不会与实际对不上。
          -->
          <p>14 个 Maven 模块、三套 Web 前端与移动端，{{ features.length }} 大能力覆盖企业通讯全场景</p>
        </div>
        <div class="grid">
          <div v-for="f in features" :key="f.title" class="card">
            <div class="card-icon">{{ f.icon }}</div>
            <h3>{{ f.title }}</h3>
            <p>{{ f.desc }}</p>
          </div>
        </div>
        <div class="stack">
          <span v-for="s in stack" :key="s">{{ s }}</span>
        </div>
      </div>
    </section>

    <!-- 下载 -->
    <section id="downloads" class="section">
      <div class="container">
        <div class="section-head">
          <h2>下载 Veytalk</h2>
          <p>选择适合你的客户端版本，个人与内网部署均可直接使用</p>
        </div>
        <div class="dl-grid">
          <div v-for="d in downloads" :key="d.title" class="dl-card" :class="{ recommend: d.flag === '推荐' }">
            <div class="dl-flag">{{ d.flag }}</div>
            <div class="dl-icon">{{ d.icon }}</div>
            <h3>{{ d.title }}</h3>
            <p class="desc">{{ d.desc }}</p>
            <p class="dl-spec"><b>{{ d.spec }}</b></p>
            <!--
              校验值与发布日期。静态回落卡没这两个值，整行就不出现；
              MD5 给全量而不缩写——缩写过的校验值用户没法直接拿去比对，等于没给。
            -->
            <p v-if="d.released || d.md5" class="dl-check">
              <span v-if="d.released">{{ d.released }} 发布</span>
              <code v-if="d.md5" class="dl-md5" title="安装包整文件 MD5，下载后可用它确认包没坏、是不是最新版">MD5 {{ d.md5 }}</code>
            </p>
            <a class="btn btn-primary" :href="d.file" download>⬇ 立即下载</a>
          </div>
        </div>
        <p class="dl-note">
          站点直链不可用时，可到 <a :href="RELEASES_URL" target="_blank" rel="noopener" style="color: var(--accent)">GitHub Releases</a>
          获取各平台安装包；自建站点请将产物放入 <code>public/downloads/</code>（运行 <code>npm run fetch:downloads</code> 自动收集）。
        </p>
      </div>
    </section>

    <!--
      历史版本。拉到数据才出现（后端未起 / 迁移未跑时整段不渲染），
      不留一个「加载失败」的空坑——与下载卡的回落哲学一致。
      已停用行不给下载按钮：公开下载端点只递启用行，给了就是一个必 404 的死链。
    -->
    <section v-if="historyGroups.length" id="history" class="section section-alt">
      <div class="container">
        <div class="section-head">
          <h2>历史版本</h2>
          <p>各平台近期发布过的版本；已停用的包本站不再递出，需要旧版请到 <a :href="RELEASES_URL" target="_blank" rel="noopener" style="color: var(--accent)">GitHub Releases</a></p>
        </div>
        <div class="his-grid">
          <div v-for="g in historyGroups" :key="g.name" class="his-card">
            <h3>{{ g.name }}</h3>
            <ul class="his-list">
              <li v-for="item in g.rows" :key="item.version">
                <div class="his-row">
                  <b class="his-ver">{{ item.version }}</b>
                  <span class="his-spec">{{ item.spec }}</span>
                  <a v-if="item.file" class="his-get" :href="item.file" download>下载</a>
                  <span v-else class="his-off">已停用</span>
                </div>
                <p v-if="item.note" class="his-note">{{ item.note }}</p>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- 资源：本来也是 section-alt，给历史版本让位改回常规底色，保持「深浅交替」的节奏 -->
    <section id="resources" class="section">
      <div class="container">
        <div class="section-head">
          <h2>开发者资源</h2>
          <p>源码开放，文档完备，从克隆到跑通只需要一条 mvn 命令</p>
        </div>
        <div class="link-grid">
          <a v-for="l in links" :key="l.title" class="link-card" :href="l.href" target="_blank" rel="noopener">
            <div class="card-icon">{{ l.icon }}</div>
            <h3>{{ l.title }}</h3>
            <p>{{ l.desc }}</p>
          </a>
        </div>
      </div>
    </section>
  </main>

  <footer class="footer">
    <div class="container footer-inner">
      <span>© 2026 Veytalk · IM 即时通讯系统 · Apache-2.0 License</span>
      <!--
        法定文本入口：应用商店上架与各类审核都要求「隐私政策链接可从官网直达」，
        页脚那一行就是行业惯例放它的位置（审核人员不会到下载区里去找）。
      -->
      <span class="footer-legal">
        <template v-for="(d, index) in legalDocs" :key="d.href">
          <span v-if="index > 0">·</span>
          <a :href="d.href">{{ d.title }}</a>
        </template>
      </span>
      <span>
        <a :href="GITHUB_URL" target="_blank" rel="noopener">GitHub</a> ·
        <a :href="DOCS_URL" target="_blank" rel="noopener">文档</a>
      </span>
    </div>
  </footer>
</template>
