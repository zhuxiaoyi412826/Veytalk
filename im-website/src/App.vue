<script setup>
// Veytalk 官网：纯展示单页。数据全部数组驱动，改文案不动模板。
// 下载链接约定指向 public/downloads/ 下的规范文件名（ASCII，无空格），
// 产物由 scripts/fetch-downloads.ps1 从 electron/release 拷来并改名，详见该脚本与 README。
const GITHUB_URL = 'https://github.com/zhuxiaoyi412826/Veytalk'
const DOCS_URL = `${GITHUB_URL}/blob/main/README.md`
const RELEASES_URL = `${GITHUB_URL}/releases`
const VERSION = '1.0.0'

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

const downloads = [
  {
    icon: '🪟',
    title: 'Windows 安装版',
    flag: '推荐',
    desc: `NSIS 安装包，双击安装即装即用，已内置 JRE、ffmpeg 与被控端 Agent，无需任何额外依赖`,
    file: `/downloads/Veytalk-Setup-${VERSION}.exe`,
    spec: `${VERSION} · x64 · Windows 10/11`
  },
  {
    icon: '📦',
    title: 'Windows 免安装版',
    flag: '绿色版',
    desc: '解压即运行的便携版，不写注册表、不产生卸载项，适合放在 U 盘或受限环境使用',
    file: `/downloads/Veytalk-Windows-x64-portable.zip`,
    spec: `${VERSION} · x64 · 解压即用`
  },
  {
    icon: '🤖',
    title: 'Android 版',
    flag: '移动端',
    desc: '手机客户端 APK，支持移动端消息、直播观看与远程控制被控端，适配全面屏手势',
    file: `/downloads/Veytalk-Android.apk`,
    spec: `${VERSION} · arm64 · Android 10+`
  }
]

const links = [
  { icon: '🐙', title: 'GitHub 仓库', desc: '源码、Issue 与更新动态一站直达', href: GITHUB_URL },
  { icon: '📖', title: '官方文档', desc: '部署、启动、打包与开发指引全在这份 README 里', href: DOCS_URL },
  { icon: '🚀', title: 'Release 下载', desc: '各平台安装包的历史版本发布页', href: RELEASES_URL }
]

const stack = ['Spring Boot 4', 'JDK 21', 'Vue 3 + Vite', 'Element Plus', 'MySQL 8', 'Redis', 'Netty WebSocket', 'MinIO', 'Electron', 'HLS / ffmpeg']
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
        <p class="hero-meta">当前版本 v{{ VERSION }} · Windows x64 / 免安装版 / Android</p>
      </div>
    </section>

    <!-- 特性 -->
    <section id="features" class="section section-alt">
      <div class="container">
        <div class="section-head">
          <h2>产品特性</h2>
          <p>14 个 Maven 模块、两套前端，九大能力覆盖企业通讯全场景</p>
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
            <a class="btn btn-primary" :href="d.file" download>⬇ 立即下载</a>
          </div>
        </div>
        <p class="dl-note">
          站点直链不可用时，可到 <a :href="RELEASES_URL" target="_blank" rel="noopener" style="color: var(--accent)">GitHub Releases</a>
          获取各平台安装包；自建站点请将产物放入 <code>public/downloads/</code>（运行 <code>npm run fetch:downloads</code> 自动收集）。
        </p>
      </div>
    </section>

    <!-- 资源 -->
    <section id="resources" class="section section-alt">
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
      <span>
        <a :href="GITHUB_URL" target="_blank" rel="noopener">GitHub</a> ·
        <a :href="DOCS_URL" target="_blank" rel="noopener">文档</a>
      </span>
    </div>
  </footer>
</template>
