# Tauri / 安卓 打包实战记录（2026-10-21 实测，可复刻版）

> 本文是**一次真实打包全过程的逐步复刻手册**，与 [Tauri打包指南.md](./Tauri打包指南.md)（理论篇）配套。
> 指南里过时/遗漏的地方本文已修正，**冲突时以本文为准**。
>
> 路线：**Windows exe 走 Tauri v2，安卓走 Capacitor 8**（Tauri 指南不含安卓步骤；
> 安卓沿用 [打包为EXE和Android指南.md](./打包为EXE和Android指南.md) 的已验证方案）。

## 最终产物

| 产物 | 完整路径 | 大小 |
|---|---|---|
| Windows 安装包 | `C:\daima\Veytalk\im-ui\src-tauri\target\release\bundle\nsis\IM通讯_1.0.0_x64-setup.exe` | 31.2 MB |
| Windows 免安装 | `C:\daima\Veytalk\im-ui\src-tauri\target\release\app.exe` | 37.7 MB |
| 安卓调试 APK | `C:\daima\Veytalk\im-ui\android\app\build\outputs\apk\debug\app-debug.apk` | 37.5 MB |

---

## 一、需要下载的软件和版本（实测环境）

| 软件 | 实测版本 | 安装方式 | 用途 |
|---|---|---|---|
| Node.js | v24 | 官网 | 前端构建 |
| **Rust（rustup）** | **1.99.1（stable-msvc，cargo 1.99.1）** | `winget install Rustlang.Rustup` | Tauri 编译，**必装** |
| Visual Studio（含 **C++ 工作负载**） | VS 18 Enterprise | winget / 官网安装器 | 提供 `link.exe` 与 Windows SDK |
| WebView2 Runtime | 155 | Win11 自带 | Tauri 运行时 |
| Android Studio + Android SDK | **API 33（Android 13）** | 官网 | 安卓构建 |
| JDK | 21 | 已有 | Gradle 编译 |
| Gradle | 8.14.3（wrapper 自动下载） | 无需手装 | 安卓构建 |

Rust 安装命令（装前先设镜像，装完**关掉终端重开**验证）：

```powershell
$env:RUSTUP_DIST_SERVER="https://rsproxy.cn"; $env:RUSTUP_UPDATE_ROOT="https://rsproxy.cn/rustup"
winget install Rustlang.Rustup
# 重开终端后：
rustc --version; cargo --version   # 期望：rustc 1.99.1 / cargo 1.99.1
```

Android Studio 装完后打开 **More Actions → SDK Manager** 确认勾上：

- **SDK Platforms** 页：**Android 13 (API 33)**
- **SDK Tools** 页：**Android SDK Build-Tools**

npm 依赖（在 `im-ui` 目录执行，写进 package.json）：

```powershell
cd c:\daima\Veytalk\im-ui
npm i -D @tauri-apps/cli@^2                          # 实测 2.x
npm i -D @capacitor/cli                              # 实测 ^8.x
npm i @capacitor/core @capacitor/android             # 实测 ^8.5.3
```

---

## 二、一次性准备（配置文件逐个给全）

### 2.1 Rust 国内镜像 `%USERPROFILE%\.cargo\config.toml`

> ⚠️ 指南里的 `rsproxy.cn/crates.io-index/` 已 **404**，必须用现行地址 `rsproxy.cn/index/`：

```toml
[source.crates-io]
replace-with = 'rsproxy-sparse'

[source.rsproxy-sparse]
registry = "sparse+https://rsproxy.cn/index/"

[http]
check-revoke = false
```

### 2.2 后端地址编译期注入

`im-ui/.env.tauri`（Tauri exe 用）与 `im-ui/.env.capacitor`（安卓 APK 用）**内容完全一样**，
打进包后不可改，后端换地址必须改这里重新打包：

```ini
# 桌面壳/安卓壳里 axios / WebSocket / 直播媒体要连的远程后端根地址（不含 /api）。
VITE_IM_SERVER_URL=http://192.168.1.3:8080

# 直播 HLS 流媒体服务器地址（live-media-server.js / Nginx 播放口）。
VITE_IM_MEDIA_BASE=http://192.168.1.3:8088
```

> `192.168.1.3` 是本次打包时的后端机器内网 IP，复刻时换成你的。

### 2.3 package.json 增加的脚本（完整 scripts 段）

```jsonc
"scripts": {
  "dev": "vite",
  "dev:lan": "vite --mode lan",
  "build": "vite build",
  "build:electron": "vite build --mode electron",
  "build:tauri": "vite build --mode tauri",
  "build:capacitor": "vite build --mode capacitor",
  "tauri": "tauri",
  "preview": "vite preview",
  "gen:cert": "node scripts/gen-cert.cjs"
}
```

---

## 三、前端代码改动（逐文件，完整代码）

### 3.1 `im-ui/src/utils/env.js` —— 识别三种壳 + 编译期地址回落

在原有 Electron 逻辑上**新增** 3 个函数并改 `serverBase()` / `livePlayBase()`，
其余函数（`apiBaseURL` / `mediaBaseURL` / `wsBaseURL` / `webhookBase`）只是把判定从
`isElectron()` 放宽为 `isNativeShell()`。新增/改动的部分：

```js
/** 是否运行在 Tauri 壳里（v2 无论 withGlobalTauri 与否都会注入 __TAURI_INTERNALS__） */
export function isTauri() {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window
}

/** 是否运行在 Capacitor 原生壳里（安卓/iOS，原生桥会注入 window.Capacitor） */
export function isCapacitor() {
  return typeof window !== 'undefined' && !!window.Capacitor
}

/** 是否任意原生壳（Electron / Tauri / Capacitor）——凡是「非浏览器同源」的宿主都用它 */
export function isNativeShell() {
  return isElectron() || isTauri() || isCapacitor()
}

function serverBase() {
  // ① Electron：preload 注入
  if (typeof window !== 'undefined' && window.__IM_SERVER__ && window.__IM_SERVER__.baseUrl) {
    return window.__IM_SERVER__.baseUrl
  }
  // ② Tauri / Capacitor：编译期 Vite 环境变量（.env.tauri / .env.capacitor）
  if (isTauri() || isCapacitor()) {
    return (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || ''
  }
  return '' // ③ 浏览器 Web：空串 → 回落同源
}

export function livePlayBase() {
  // Tauri / Capacitor：流媒体服务器地址编译期注入（.env.tauri / .env.capacitor 的 VITE_IM_MEDIA_BASE）
  if (isTauri() || isCapacitor()) return import.meta.env.VITE_IM_MEDIA_BASE || 'http://127.0.0.1:8088'
  return isElectron() ? 'http://127.0.0.1:8088' : ''
}
```

> Web / Electron 行为**零改动**：`__IM_SERVER__` 无、非 Tauri/Capacitor → `serverBase()` 返回空串 → 全部走同源。

### 3.2 `im-ui/src/router/index.js` —— 原生壳用 hash 路由

Tauri 的 `tauri://localhost` / Capacitor 的 `https://localhost` 都没有服务器做 history 回退，
深层路由整页刷新会 404，必须 hash 模式：

```js
import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
import { isNativeShell } from '@/utils/env'

const router = createRouter({
  // Electron/Tauri/Capacitor 没有服务器做 URL 重写，history 模式刷新会白屏
  history: isNativeShell() ? createWebHashHistory() : createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})
```

### 3.3 `im-ui/vite.config.js` —— tauri 模式用相对 base

自定义协议下 `/assets` 会解析到协议根之外，必须 `./`。
（Capacitor 从 `https://localhost` 根路径伺服 dist，**不需要** `./`，所以不在条件里。）

```js
export default defineConfig(({ mode, command }) => {
  // ...
  return {
    // Electron 从 file://、Tauri 从 tauri://localhost 加载，必须用相对 base
    base: (mode === 'electron' || mode === 'tauri') ? './' : '/',
    // ...
    build: {
      // electron 产物直接落 electron/dist；tauri/capacitor 都用默认的 dist
      outDir: mode === 'electron' ? '../electron/dist' : 'dist',
      emptyOutDir: true,
      sourcemap: false,
      chunkSizeWarningLimit: 1500
    }
  }
})
```

### 3.4 运行时服务器地址覆盖（热点/换网段免重打包）

打包后后端 IP 写死在包里，但笔记本连手机热点、DHCP 重分 IP 后地址就变了。为此加了
**运行时覆盖**：登录页输入新地址 → 存 `localStorage.IM_SERVER_URL_OVERRIDE` →
`serverBase()` 每次请求实时读取，保存后立即生效，无需重打包。

**后端 CORS 必须放行壳的 Origin**（否则预检 403，前端提示「无法连接服务器」，见教训 8）——
`im-bootstrap/src/main/resources/application.yml` 的 `im.cors.allowed-origins` 需包含：

```yaml
      - http://tauri.localhost      # Tauri exe（Windows）
      - tauri://localhost           # Tauri（macOS/Linux）
      - https://localhost           # Capacitor 安卓（androidScheme: https）
      - capacitor://localhost       # Capacitor iOS
```

`env.js` 的 `serverBase()` / `livePlayBase()` 里读取覆盖地址的逻辑：

```js
function serverBase() {
  if (window.__IM_SERVER__ && window.__IM_SERVER__.baseUrl) return window.__IM_SERVER__.baseUrl
  if (isTauri() || isCapacitor()) {
    // 运行时覆盖优先于编译期默认值：热点换 IP 时登录页改一下即可，无需重打包
    const override = window.localStorage && window.localStorage.getItem('IM_SERVER_URL_OVERRIDE')
    if (override) return override.replace(/\/+$/, '')
    return (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || ''
  }
  return ''
}

export function livePlayBase() {
  if (isTauri() || isCapacitor()) {
    // 流媒体与后端同机部署：跟随覆盖地址的 host，只换端口为 8088
    const override = window.localStorage && window.localStorage.getItem('IM_SERVER_URL_OVERRIDE')
    if (override) return override.replace(/\/+$/, '').replace(/:\d+$/, '') + ':8088'
    return import.meta.env.VITE_IM_MEDIA_BASE || 'http://127.0.0.1:8088'
  }
  return isElectron() ? 'http://127.0.0.1:8088' : ''
}
```

`Login.vue` 在 `isNativeShell()` 时显示输入框，保存时写入/删除 `IM_SERVER_URL_OVERRIDE`。

### 3.5 安卓兼容旧系统 WebView（白屏修复，@vitejs/plugin-legacy）

vivo 安卓 11 出厂 WebView 是 Chrome 8x 内核，而 Vite 7 默认打 Chrome 107+ 的包，
JS 一解析就抛错 → **白屏**（荣耀安卓 15 的 WebView 是 Chrome 130+，所以正常）。
用 `@vitejs/plugin-legacy` 只给 capacitor 模式增强：额外产出一份转译到 Chrome 80 +
core-js 自动 polyfill 的旧内核包，由 `<script nomodule>` 自动回落加载；
**新内核设备仍走原现代包，功能/体积/行为零影响**。

安装（注意版本匹配：plugin-legacy 最新版要 Vite 8，Vite 7 项目要装 ^7）：

```powershell
npm i -D @vitejs/plugin-legacy@^7 core-js
```

`vite.config.js` 的 plugins 改为：

```js
plugins: [
  vue(),
  mode === 'capacitor' && legacy({
    targets: ['Chrome >= 80'],
    modernPolyfills: true,
    renderLegacyChunks: true
  })
].filter(Boolean),
```

> 只作用于 `--mode capacitor`，Web / Electron / Tauri 构建产物完全不受影响。
> 代价：APK 约大 5 MB（转译包 + polyfill）。

---

## 四、Tauri 侧（Windows exe）

### 4.1 初始化

```powershell
cd c:\daima\Veytalk\im-ui
npx tauri init
```

问询应答（本项目实际填写）：

| 问询 | 填什么 |
|---|---|
| app_name | `app` |
| window_title | `IM 即时通讯` |
| webview_address（devUrl） | `http://localhost:5173` |
| dev_path（frontendDist） | `../dist` |
| before_dev_command | `npm run dev` |
| before_build_command | `npm run build:tauri` |
| bundle_identifier | `com.im.desktop` |

生成 `im-ui/src-tauri/`（Cargo.toml / tauri.conf.json / src / icons / capabilities）。

### 4.2 `src-tauri/tauri.conf.json`（完整内容，直接可用）

```jsonc
{
  "$schema": "https://schema.tauri.app/config/2",
  "productName": "IM通讯",
  "version": "1.0.0",
  "identifier": "com.im.desktop",
  "build": {
    "beforeDevCommand": "npm run dev",
    "devUrl": "http://localhost:5173",
    "beforeBuildCommand": "npm run build:tauri",
    "frontendDist": "../dist"
  },
  "app": {
    "withGlobalTauri": false,
    "windows": [
      {
        "title": "IM 即时通讯",
        "width": 1200, "height": 800,
        "minWidth": 800, "minHeight": 600,
        "resizable": true, "center": true
      }
    ],
    "security": {
      "csp": "default-src 'self'; img-src 'self' blob: data: http: https:; style-src 'self' 'unsafe-inline'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self' ipc: http://ipc.localhost http://localhost:8080 ws://localhost:8080 http://127.0.0.1:8088 blob: http: https: ws: wss:; media-src 'self' blob: http: https:; worker-src 'self' blob:; frame-src 'self' blob: data:;"
    }
  },
  "bundle": {
    "active": true,
    "targets": ["nsis"],
    "category": "SocialNetworking",
    "icon": ["icons/32x32.png", "icons/128x128.png", "icons/icon.ico"],
    "windows": {
      "nsis": { "installMode": "currentUser", "languages": ["SimpChinese"] }
    }
  }
}
```

CSP 要点（本项目真实依赖决定）：

| 指令 | 为什么 |
|---|---|
| `connect-src ... ws: wss:` | **内网明文后端是 `ws://`，指南里只有 `wss:` 会被拦死**（教训 2） |
| `script-src 'wasm-unsafe-eval'` | onnxruntime-web / tesseract.js 的 wasm |
| `worker-src blob:` | 识别模型的 Web Worker |
| `img-src` / `media-src` 加 `blob: data:` | 图片预览、语音 blob |

### 4.3 `src-tauri/Cargo.toml` 依赖段（init 默认生成，未加插件）

```toml
[build-dependencies]
tauri-build = { version = "2.7.3", features = [] }

[dependencies]
serde_json = "1.0"
serde = { version = "1.0", features = ["derive"] }
log = "0.4"
tauri = { version = "2.12.4", features = [] }
tauri-plugin-log = "2"
```

> 本次打包**没有**实现指南第七节的下载/防截屏 Rust 命令——Tauri 包定位为轻量客户端，
> 「点下载没反应、闪照不防截屏」属于已知取舍。要补防截屏按指南 7.2 加
> `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` 命令即可，工程量约几十行。

### 4.4 打包命令（实际执行序列）

```powershell
cd c:\daima\Veytalk\im-ui
# 仅当终端是装 Rust 之前开的老窗口时才需要第一句（教训 1）
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
$env:CARGO_HTTP_CHECK_REVOKE="false"
npm run tauri build
```

内部流程：`beforeBuildCommand` 先跑 `vite build --mode tauri` → cargo release 编译
（首次拉全依赖树走 rsproxy，很慢；之后增量很快）→ tauri-bundler 出 NSIS。
**看到 `Finished 2 bundles` 才算完整产出**——中途退出只更新 `app.exe`，安装包还是旧的（教训 6）。

---

## 五、安卓侧（Capacitor）

### 5.1 初始化

```powershell
cd c:\daima\Veytalk\im-ui
npx cap init IM通讯 com.im.app --web-dir dist   # 生成 capacitor.config.json
npx cap add android                              # 生成 android/ 目录
```

### 5.2 `im-ui/capacitor.config.json`（完整内容）

```json
{
  "appId": "com.im.app",
  "appName": "IM通讯",
  "webDir": "dist",
  "server": {
    "androidScheme": "https"
  },
  "android": {
    "allowMixedContent": true,
    "captureInput": true,
    "webContentsDebuggingEnabled": true
  }
}
```

> `androidScheme: https` 让 WebView 以 https 加载本地页（getUserMedia 需要安全上下文）；
> 但后端是 http 明文，所以必须 `allowMixedContent: true` 放行混合内容。

### 5.3 `android/app/src/main/AndroidManifest.xml`（关键改动）

`<application>` 标签加两行：

```xml
<application
    ...
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

`<!-- Permissions -->` 段补全：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- 文件上传/下载需要 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<!-- 拍照/录像/语音通话需要 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

### 5.4 `android/app/src/main/res/xml/network_security_config.xml`（新建，完整内容）

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### 5.5 `android/local.properties`（新建，机器专属，**建议加 .gitignore**）

```properties
sdk.dir=C:/Users/zhuxiaoyi/AppData/Local/Android/Sdk
```

> 写死 SDK 路径比依赖 `ANDROID_HOME` 环境变量稳得多（教训 3）。复刻时换成你的 SDK 路径。

### 5.6 `android/gradle/wrapper/gradle-wrapper.properties`（完整内容）

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-all.zip
networkTimeout=120000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

> 两处改动：distributionUrl 换**腾讯镜像**；`networkTimeout` 从默认 `10000` 调到 **`120000`**（教训 4）。

### 5.7 打包命令（实际执行序列）

```powershell
cd c:\daima\Veytalk\im-ui
npm run build:capacitor      # vite build --mode capacitor → dist/
npx cap sync android         # 拷贝 dist + 插件进 android/
cd android
.\gradlew assembleDebug      # 首次自动下载 Gradle 8.14.3（走腾讯镜像）
```

产物：`android\app\build\outputs\apk\debug\app-debug.apk`，debug 签名，可直接拷手机安装。
正式分发再按 [打包为EXE和Android指南.md](./打包为EXE和Android指南.md) 第九节生成 keystore 打 release 包。

---

## 六、打包后的运行前提

1. **后端必须已在编译进去的地址启动**（`192.168.1.3:8080`）：

   ```powershell
   cd c:\daima\Veytalk\im-bootstrap
   $env:MYSQL_USER='root'; $env:MYSQL_PASSWORD='412826'
   mvn spring-boot:run
   ```

2. **局域网设备要连得上 8080**（管理员 PowerShell 执行一次）：

   ```powershell
   netsh advfirewall firewall add rule name="IM Backend 8080" dir=in action=allow protocol=TCP localport=8080
   ```

3. 流媒体 8088 同理需要时开端口：`netsh advfirewall firewall add rule name="IM Media 8088" dir=in action=allow protocol=TCP localport=8088`

---

## 七、教训（真实踩坑，按出现顺序）

### 1. `cargo metadata ... program not found` —— 老终端没有新 PATH
装完 Rust 后**当时开着的终端/IDE 窗口**继承的是旧环境变量，`cargo` 死活找不到；「重启 IDE」也未必够
（托盘/驻留进程没退，子进程仍拿旧 PATH）。rustup 其实已把 `C:\Users\zhuxiaoyi\.cargo\bin` 写进用户 PATH 注册表。
**对策**：临时方案 `$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"`；彻底方案是任务管理器里把 IDE 进程全结束再开。

### 2. CSP 只放 `wss:` 不够 —— 内网明文后端要 `ws:`
指南 CSP 的 `connect-src` 只有 `wss:`，而内网后端是 `ws://192.168.1.3:8080`，不加 `ws:` 打包后 WebSocket 直接被 CSP 拦死。
**对策**：`connect-src` 同时写 `http: https: ws: wss:`（见 4.2 的完整配置）。

### 3. `SDK location not found` —— 别依赖 ANDROID_HOME 环境变量
gradle 在没继承到新环境变量的终端里报找不到 SDK。
**对策**：`android/local.properties` 写死 `sdk.dir`（见 5.5），任何终端/账号都能构建。

### 4. Gradle 下载到 80% 报 `Read timed out` —— 默认 10 秒网络超时太短
腾讯镜像本身能用（进度走到 80%），是 wrapper 默认 `networkTimeout=10000` 把它掐了。
**对策**：`networkTimeout=120000` + 腾讯镜像（见 5.6），重跑即过。

### 5. rsproxy 索引地址已变 —— 指南里的旧地址 404
指南写的 `sparse+https://rsproxy.cn/crates.io-index/` 现在 404，cargo 全依赖拉取失败。
**对策**：改用官方现行地址 `sparse+https://rsproxy.cn/index/`（见 2.1）。

### 6. 产物藏得深，且「构建成功」≠「安装包已更新」
exe 在 `src-tauri\target\release\`、安装包在其下 `bundle\nsis\`、apk 在 `android\app\build\outputs\apk\debug\`。
`tauri build` 若没跑到 bundling 阶段，安装包还是旧时间戳。**认准 `Finished 2 bundles` 再取件**。

### 7. 能力取舍要提前想清楚
Tauri 包是**轻量客户端**：屏幕共享开播（WebView2 的 getDisplayMedia 无选择器，网页层无解）、
防截屏闪照、随包被控端 Agent 这些 Electron 资产在 Tauri 里退化/需重写（指南第十节）。
要全功能桌面端继续用 Electron 方案，两者可并存分发。

### 8. exe/APK 都提示「无法连接服务器」—— CORS 白名单没放壳的 Origin
后端地址 ping 得通、浏览器也打得开，但 exe 和 APK 就是连不上——根因是
[application.yml](../../im-bootstrap/src/main/resources/application.yml) 的 CORS 白名单
只写了 `localhost:5173` 等开发地址。Tauri exe 的页面 Origin 是 `http://tauri.localhost`、
Capacitor 安卓是 `https://localhost`，预检直接被 403，axios 表现为 Network Error，
前端统一提示「无法连接服务器，请确认后端已启动」——**和网络连通性完全无关**。
**对策**：白名单补上 `http://tauri.localhost`、`https://localhost`、`capacitor://localhost`
（完整配置见 3.4），重启后端。**服务端修复，客户端不用重打包**。
排查法：PowerShell 模拟预检
`Invoke-WebRequest -Uri http://<后端IP>:8080/api/captcha -Method Options -Headers @{ 'Origin'='http://tauri.localhost'; 'Access-Control-Request-Method'='GET'; 'Access-Control-Request-Headers'='satoken' }`，
返回 403 即坐实。

### 9. `tauri build` 报「拒绝访问 (os error 5)」—— exe 正在运行锁住了输出文件
cargo 链接 `app.exe` 时写不进去。**对策**：关掉正在运行的 app.exe（以及占用 release 目录的
调试器/杀毒实时扫描）再重打。

### 10. vivo 安卓 11 白屏而荣耀安卓 15 正常—— 旧系统 WebView 内核太老
见 3.5。根因不是安卓版本号，是**系统 WebView 版本**（安卓 10+ WebView 可独立升级）。
修复二选一：手机上升级 Android System WebView；或按 3.5 接入 plugin-legacy 给旧内核兜底。

### 11. 同一台电脑双网卡，客户端「同网段」却连不上
打包时写死的是**以太网**的 `192.168.1.3`，而手机实际连的是 **WiFi 的 192.168.17.x**——
两个不同子网互相路由不到。本机访问 `http://192.168.1.3:8080` 正常不能说明客户端那边通。
**对策**：手机浏览器分别试两个 IP 的 `/api/captcha` 确认自己在哪个网段；长期方案是
路由器里给服务器做 DHCP 静态绑定（MAC 绑 IP），或直接用 3.4 的运行时地址覆盖。


---

## 八、重新打包速查

```powershell
# Windows exe（改了前端或 Rust 代码后）
cd c:\daima\Veytalk\im-ui
$env:CARGO_HTTP_CHECK_REVOKE="false"; npm run tauri build

# 安卓 APK（改了前端后）
cd c:\daima\Veytalk\im-ui
npm run build:capacitor; npx cap sync android; cd android; .\gradlew assembleDebug
```

**改了后端地址**：改 `.env.tauri` / `.env.capacitor` 的 `VITE_IM_SERVER_URL` 后按上面重打包。

**换了电脑**：装第一节全部软件 → 重做 2.1 镜像 → 改 `.env.*` 的 IP → 改 `local.properties` 的 sdk.dir → 走第四、五节。