# Capacitor 打包安卓 APK 完全指南（2026-10 实测，学习+复刻版）

> 本文把 im-ui 安卓端打包从「能跑通」写成「能理解、能复刻、能排错」。
> 结构：**原理篇 → 环境篇 → 配置篇 → 打包篇 → 验证排错篇 → 踩坑大全 → 发布篇 → 附录**。
> 只想快速复刻的直接跳第二、四篇；想搞懂每步为什么的从第一篇读起。
>
> 姊妹篇：[Tauri打包实战记录.md](./Tauri打包实战记录.md)（Windows exe 线）、
> [打包为EXE和Android指南.md](./打包为EXE和Android指南.md)（理论背景）。

---

# 第一篇 原理（懂了原理，坑就少一半）

## 1.1 Capacitor 是什么，APK 里到底装了什么

Capacitor 是 Ionic 团队出的「Web 套壳」方案。打出来的 APK 内部结构：

```
app-debug.apk
├── classes.dex / kotlin 类        ← 原生壳代码（Capacitor 桥，自动生成的模版工程）
├── res/                            ← 图标、启动页等原生资源
├── lib/<abi>/*.so                  ← 原生库（本次未额外引入）
└── assets/public/                  ← ★ 你的整个前端：vite build 的 dist 原样拷进来
    ├── index.html
    └── assets/*.js / *.css / *.wasm
```

App 启动后：原生壳起一个**全屏系统 WebView**，以 `https://localhost` 为源加载
`assets/public/index.html`，之后所有页面都是你的 Vue 应用；调原生能力（相机、文件）
时走 Capacitor 的 JS↔Java 桥。

**关键认知**：APK 的性能/兼容性上限 = 那台手机的**系统 WebView 版本**，不是你的代码。

## 1.2 为什么安卓用 Capacitor 而不用 Tauri Mobile

| | Capacitor | Tauri v2 Mobile |
|---|---|---|
| 渲染层 | 系统 Android WebView | **同一个**系统 WebView |
| 原生桥 | Java/Kotlin 插件 | Rust 核心 + IPC |
| 工具链 | Android Studio + Gradle | 另需 Android NDK + Rust 安卓交叉编译 |
| 插件生态 | 成熟（推送/相机/后台服务全有现成的） | 很新，缺的自己写 Rust+Kotlin |
| 体积 | 本次 42 MB | 多 ABI 的 Rust .so，不会更小 |

Tauri 桌面端赢在「不自带 Chromium，蹭系统 WebView2」；安卓上 Capacitor 本来也不自带
浏览器引擎，**大家蹭的是同一个系统 WebView，Tauri 优势归零，劣势全留**。所以本项目
定案：Windows 轻量端用 Tauri，安卓用 Capacitor。

## 1.3 三个 SDK 版本号（最容易误解的地方）

`android/variables.gradle`：

```groovy
minSdkVersion = 24        // 能安装在多老的系统上：安卓 7.0+
compileSdkVersion = 36    // 用哪版 SDK 编译：决定可用的 API 与依赖要求
targetSdkVersion = 36     // 按哪版行为规则运行：影响权限弹窗、后台限制等
```

**「用 Android 13 (API 33) SDK 编译」和「能不能装在安卓 11」毫无关系**——能不能装看
`minSdkVersion`。也别试图降 compileSdk：androidx 新版依赖要求 compileSdk ≥ 35，降了编译期就报错。

## 1.4 WebView 版本与 vivo 白屏事件

- 安卓 10 之前：WebView 跟随系统固件，基本不可升
- 安卓 10+：WebView 是独立 App，可在应用商店升级
- 本案例：vivo 安卓 11 出厂 WebView 是 Chrome 8x，荣耀安卓 15 是 Chrome 130+
- Vite 7 默认构建目标 Chrome 107+，产物含 `?.at()`、top-level await 等语法，
  旧内核一解析就抛错 → **白屏，且没有任何界面提示**
- 修复：`@vitejs/plugin-legacy` 产出双包（现代包 + 转译到 Chrome 80 的 legacy 包），
  浏览器按 `<script type="module">` / `<script nomodule>` 自动选包。详见 3.5

## 1.5 Origin 与 CORS：壳里接口 403 的来龙去脉

浏览器发跨域请求时会带 `Origin` 头，Spring 后端按白名单校验，不在名单里预检直接 403。
各端页面的 Origin：

| 端 | 页面加载源 | Origin |
|---|---|---|
| vite dev 浏览器 | `http://localhost:5173` | `http://localhost:5173` |
| Tauri exe（Windows） | `http://tauri.localhost` | `http://tauri.localhost` |
| Capacitor 安卓 | `https://localhost`（androidScheme 配置而来） | `https://localhost` |
| Capacitor iOS | `capacitor://localhost` | `capacitor://localhost` |

后端白名单只有开发地址时，浏览器版正常、exe/APK 全部「无法连接服务器」——**这是
CORS 拒绝，和网络通不通无关**（本项目真实踩坑，见踩坑 4）。

## 1.6 debug 与 release 签名

- **debug 包**：用 SDK 自带的调试证书签名，有效期 30 年，所有机器生成的 debug 证书
  不同——所以换台电脑打的 debug 包，在已装旧 debug 包的手机上会**覆盖安装失败，需先卸载**
- **release 包**：用你自己 `keytool` 生成的 keystore 签名。**keystore 文件丢失 = 永远无法
  更新已发布的 App**（用户只能卸载重装，数据全丢），务必多处备份

---

# 第二篇 环境准备（每步带验证）

## 2.1 软件清单（实测版本）

| 软件 | 实测版本 | 安装方式 | 用途 |
|---|---|---|---|
| Node.js | v24 | 官网 | 前端构建 |
| Android Studio + SDK | API 33+ | 官网 | 安卓构建环境 |
| JDK | 21 | 已有即可 | Gradle 编译（AGP 8.x 要求 JDK 17+） |
| Gradle | 8.14.3 | **wrapper 自动下载，别手装** | 构建执行 |

## 2.2 Android Studio 装好后必做

打开 **More Actions → SDK Manager**：

1. **SDK Platforms** 页 → 勾 **Android 13 (API 33)** 或更高 → Apply（自动下载）
2. **SDK Tools** 页 → 勾 **Android SDK Build-Tools** → Apply

记下 SDK 路径（SDK Manager 页顶部「Android SDK Location」），默认是
`C:\Users\<你>\AppData\Local\Android\Sdk`，2.10 写 local.properties 要用。

## 2.3 npm 依赖（在 im-ui 目录执行，写进 package.json）

```powershell
cd c:\daima\Veytalk\im-ui
npm i -D @capacitor/cli                          # 壳命令行，实测 ^8.x
npm i @capacitor/core @capacitor/android         # 壳运行时，实测 ^8.5.3
npm i -D @vitejs/plugin-legacy@^7 core-js        # 旧 WebView 兼容
```

> ⚠️ **版本匹配坑**：plugin-legacy 最新大版本要求 Vite 8，本项目是 Vite 7，
> 必须锁 `@^7`。装错了构建时报 peer 依赖冲突。

验证：`npx cap --version` 输出版本号即 CLI 就绪。

---

# 第三篇 配置篇（每个文件给全 + 逐条解释）

## 3.1 初始化（只在新工程做一次）

```powershell
cd c:\daima\Veytalk\im-ui
npx cap init IM通讯 com.im.app --web-dir dist   # 生成 capacitor.config.json
npx cap add android                              # 生成整个 android/ 原生工程
```

`appId com.im.app` 是 App 的全局唯一标识（上架后不可改）；`--web-dir dist` 告诉
Capacitor 前端产物在哪。

## 3.2 `im-ui/capacitor.config.json`（完整内容 + 逐条解释）

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

| 配置 | 作用 | 不配的后果 |
|---|---|---|
| `androidScheme: https` | WebView 以 https 加载本地页 | getUserMedia 语音/通话在非安全上下文直接不可用 |
| `allowMixedContent: true` | 允许 https 页面发 http 请求 | 内网 http 后端的所有接口/WebSocket 被 WebView 拦死 |
| `webContentsDebuggingEnabled: true` | 允许 chrome://inspect 调试 | 白屏时只能瞎猜，看不到 Console |
| `captureInput: true` | WebView 接管硬件返回键等输入 | 安卓返回键行为怪异 |

## 3.3 `im-ui/.env.capacitor`（编译期后端地址）

```ini
# 安卓壳里 axios / WebSocket 要连的后端根地址（不含 /api）。打进包后成为默认值
VITE_IM_SERVER_URL=http://192.168.1.3:8080

# 直播 HLS 流媒体服务器地址
VITE_IM_MEDIA_BASE=http://192.168.1.3:8088
```

- 复刻时把 IP 换成你的后端机器内网 IP（`ipconfig` 查）
- **热点换 IP 不用改这里重打**：App 登录页有「服务器地址」运行时覆盖框（3.4）

## 3.4 前端适配（已在仓库内改好，换电脑不用重做）

| 文件 | 改动内容 |
|---|---|
| [im-ui/src/utils/env.js](../../im-ui/src/utils/env.js) | `isCapacitor()` 判 `window.Capacitor`；`isNativeShell()` = Electron‖Tauri‖Capacitor；`serverBase()` 在壳里优先读运行时覆盖 `localStorage.IM_SERVER_URL_OVERRIDE`，否则回落 `VITE_IM_SERVER_URL`；`livePlayBase()` 跟随覆盖地址的 host、端口换 8088 |
| [im-ui/src/router/index.js](../../im-ui/src/router/index.js) | `isNativeShell()` 时用 `createWebHashHistory`——壳里 `https://localhost` 没有服务器做 history 回退，history 模式下深层路由刷新直接 404 |
| [im-ui/src/views/Login.vue](../../im-ui/src/views/Login.vue) | `isNativeShell()` 时登录页显示「服务器地址」输入框，保存即生效 |

运行时覆盖的核心逻辑（理解这段就理解了免重打包的原理）：

```js
function serverBase() {
  if (window.__IM_SERVER__ && window.__IM_SERVER__.baseUrl) return window.__IM_SERVER__.baseUrl
  if (isTauri() || isCapacitor()) {
    // 运行时覆盖优先于编译期默认值：热点换 IP 时登录页改一下即可，无需重打包
    const override = window.localStorage && window.localStorage.getItem('IM_SERVER_URL_OVERRIDE')
    if (override) return override.replace(/\/+$/, '')
    return (import.meta.env && import.meta.env.VITE_IM_SERVER_URL) || ''
  }
  return ''  // 浏览器 Web：空串 → 走同源
}
```

## 3.5 `im-ui/vite.config.js`（旧 WebView 兼容，关键）

```js
import legacy from '@vitejs/plugin-legacy'

export default defineConfig(({ mode, command }) => {
  return {
    // Electron 从 file://、Tauri 从 tauri://localhost 加载，必须用相对 base；
    // Capacitor 从 https://localhost 根路径伺服 dist，保持 '/'
    base: (mode === 'electron' || mode === 'tauri') ? './' : '/',
    plugins: [
      vue(),
      // 只给 capacitor 模式加旧内核兜底
      mode === 'capacitor' && legacy({
        targets: ['Chrome >= 80'],
        modernPolyfills: true,
        renderLegacyChunks: true
      })
    ].filter(Boolean),
    build: {
      outDir: mode === 'electron' ? '../electron/dist' : 'dist',
      emptyOutDir: true,
      sourcemap: false,
      chunkSizeWarningLimit: 1500
    }
  }
})
```

打包后 `dist/index.html` 里会同时出现两类 script 标签：新内核加载 `type="module"`
的现代包，旧内核忽略它、加载 `nomodule` 的转译包——**自动分流，无需代码判断**。
代价：APK 约大 5 MB（polyfill + 转译包）。

## 3.6 `android/app/src/main/AndroidManifest.xml`（关键改动）

`<application>` 标签加两个属性：

```xml
<application
    ...
    android:usesCleartextTraffic="true"
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

权限段补全（按需删，别多配——上架时权限多会被审）：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- 文件上传/下载 -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<!-- 拍照/录像/语音通话 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

> `usesCleartextTraffic`：安卓 9+ 默认禁止一切 http 明文。内网后端是 http，不开这个
> App 里**所有**接口直接失败，且报错信息不含「明文」二字，极易误判成后端问题。

## 3.7 `android/app/src/main/res/xml/network_security_config.xml`（新建，完整内容）

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

> 与 3.6 的 `usesCleartextTraffic` 双保险。将来要收紧安全（只对特定内网域名放行 http），
> 把 `base-config` 换成 `domain-config` 逐域名配置即可。

## 3.8 `android/local.properties`（新建，机器专属）

```properties
sdk.dir=C:/Users/zhuxiaoyi/AppData/Local/Android/Sdk
```

- 路径用**正斜杠**或双反斜杠，单反斜杠会被转义出问题
- 此文件是你机器的绝对路径，**加进 .gitignore**，别提交
- 比 `ANDROID_HOME` 环境变量稳的原因：环境变量是后设的，老终端/IDE 驻留进程拿不到，
  Gradle 直接 `SDK location not found`（踩坑 1）

## 3.9 `android/gradle/wrapper/gradle-wrapper.properties`（完整内容）

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-all.zip
networkTimeout=120000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

两处关键改动：

| 项 | 默认 | 改为 | 为什么 |
|---|---|---|---|
| distributionUrl | services.gradle.org | **腾讯镜像** | 官方源国内拉不动/极慢 |
| networkTimeout | `10000`（10 秒） | **`120000`** | 默认 10 秒会在下载到 80% 时掐断报 `Read timed out`（踩坑 2） |

## 3.10 后端 CORS 白名单（服务端配置，客户端的命根子）

`im-bootstrap/src/main/resources/application.yml` 的 `im.cors.allowed-origins`：

```yaml
      - https://localhost           # Capacitor 安卓（androidScheme: https 而来）
      - http://localhost            # 兜底
      - capacitor://localhost       # Capacitor iOS
      - http://tauri.localhost      # Tauri exe（Windows）
      - tauri://localhost           # Tauri（macOS/Linux）
```

改完**重启后端生效，客户端不用重打包**。

---

# 第四篇 打包流程（每条命令背后发生了什么）

## 4.1 完整序列

```powershell
cd c:\daima\Veytalk\im-ui

# 第 1 步：vite 构建前端（mode capacitor → 读 .env.capacitor、启用 legacy 双包）
npm run build:capacitor

# 第 2 步：把 dist 拷进安卓工程 assets/public，同步插件与配置
npx cap sync android

# 第 3 步：进安卓工程，Gradle 编译出 APK
cd android
.\gradlew assembleDebug
```

各步内部：

| 步骤 | 实际行为 | 耗时参考 |
|---|---|---|
| build:capacitor | vite 转换 ~3100 个模块，产出现代包+legacy 包到 dist/ | ~17s |
| cap sync | dist → `android/app/src/main/assets/public`；更新插件清单 | <1s |
| gradlew assembleDebug | 首次：下载 Gradle 8.14.3 → 编译 Java/Kotlin → dex → 打包签名 | 首次几分钟，之后 ~1min |

**看到 `BUILD SUCCESSFUL` 后取件**：
`android\app\build\outputs\apk\debug\app-debug.apk`

## 4.2 三种变更场景的重打路径

| 改了什么 | 要做的 |
|---|---|
| 前端代码 | 完整跑 4.1 四步（`cap sync` 负责把新 dist 搬进去，**少这步 APK 里还是旧前端**） |
| 后端 IP 默认值 | 改 `.env.capacitor` 后跑 4.1；或直接用 App 登录页的运行时覆盖框，**免重打** |
| 权限/原生配置 | 改 Manifest 后只需 `cd android; .\gradlew assembleDebug` |

---

# 第五篇 验证与真机调试

## 5.1 分层验证法（出问题时从第一层往下查）

1. **网络层**：手机浏览器开 `http://<电脑IP>:8080/api/captcha`，显示 JSON = 网络通
2. **后端 CORS 层**：电脑 PowerShell 模拟壳的预检（见踩坑 4 的命令），200 = 白名单 OK
3. **App 渲染层**：装上打开，白屏 → WebView 内核问题（踩坑 3）；能进登录页但登录失败 → 看第 2 层
4. **业务层**：登录 → 收发消息 → 图片/语音 → 直播观看

## 5.2 chrome://inspect 真机调试（排错终极手段）

`webContentsDebuggingEnabled: true` 已开，步骤：

1. 手机：设置 → 关于手机 → 连点「版本号」7 次开开发者模式 → 开发者选项里开 **USB 调试**
2. USB 连电脑，手机上弹窗点「允许调试」
3. 电脑 Chrome 地址栏输 `chrome://inspect`
4. 在设备列表找到你的 App WebView，点 **inspect**
5. 弹出的 DevTools 和调试网页一样：Console 看报错、Network 看接口、Sources 断点

白屏类问题在这里一眼定位（语法报错会精确到行）；接口类问题看 Network 里是 403（CORS）
还是根本发不出去（明文流量被拦）。

## 5.3 运行前提清单

```powershell
# ① 后端启动（im-bootstrap 目录）
$env:MYSQL_USER='root'; $env:MYSQL_PASSWORD='<密码>'; mvn spring-boot:run

# ② 防火墙放行（电脑管理员 PowerShell，一次性）
netsh advfirewall firewall add rule name="IM Backend 8080" dir=in action=allow protocol=TCP localport=8080
netsh advfirewall firewall add rule name="IM Media 8088" dir=in action=allow protocol=TCP localport=8088
```

③ 手机与电脑同一网段（双网卡电脑见踩坑 5）。

---

# 第六篇 踩坑大全（每条：现象→根因→排查→修复→预防）

### 1. `SDK location not found`
- **现象**：`gradlew assembleDebug` 报 `Define a valid SDK location with an ANDROID_HOME environment variable or sdk.dir`
- **根因**：`ANDROID_HOME` 是装 SDK 后设的，当前终端是之前开的，进程继承旧环境变量
- **修复**：`android/local.properties` 写死 `sdk.dir`（3.8），从此与终端无关
- **预防**：机器专属路径文件不进 git，但换电脑复刻清单里必须有它

### 2. Gradle 下载 80% 报 `Read timed out`
- **现象**：`Downloading https://mirrors.cloud.tencent.com/... 80%` 然后超时失败
- **根因**：wrapper 默认 `networkTimeout=10000`（10 秒），镜像本身没问题
- **修复**：`networkTimeout=120000`（3.9）重跑即过
- **预防**：所有国内镜像下载大文件的场景都先调大超时

### 3. vivo 安卓 11 白屏，荣耀安卓 15 正常
- **现象**：装完打开纯白屏，无任何提示；chrome://inspect 里 Console 报语法错误
- **根因**：系统 WebView 是 Chrome 8x，解析不了 Vite 7 默认（Chrome 107+）产物的语法
- **修复**：plugin-legacy（3.5）；或手机应用商店升级 Android System WebView
- **预防**：给旧机发包前先查 WebView 版本（设置→应用→显示系统应用→Android System WebView）

### 4. exe/APK 提示「无法连接服务器」，但浏览器版正常
- **现象**：页面能开、登录一点就提示后端没启动；地址在浏览器里明明能访问
- **根因**：后端 CORS 白名单没放壳的 Origin（`https://localhost` / `http://tauri.localhost`），
  预检 403，axios 报 Network Error，前端统一提示「无法连接服务器」
- **排查**（PowerShell 模拟预检）：

```powershell
Invoke-WebRequest -Uri http://<电脑IP>:8080/api/captcha -Method Options -Headers @{
  'Origin'='https://localhost'; 'Access-Control-Request-Method'='GET';
  'Access-Control-Request-Headers'='satoken' }
# 返回 403 = 坐实；200 = CORS 没问题，往别处查
```

- **修复**：3.10 补白名单，重启后端。**客户端不用重打**
- **预防**：每加一种新壳，先想「它的 Origin 是什么，白名单放了吗」

### 5. 双网卡电脑，「同一网段」却连不上
- **现象**：手机和电脑「都连着网」，手机浏览器访问电脑 IP 超时
- **根因**：电脑同时有以太网（192.168.1.3）和 WiFi（192.168.17.18），两个不同子网；
  打包写死的是以太网 IP，手机实际在 WiFi 网段，路由不到
- **排查**：`ipconfig` 看本机所有网卡；手机浏览器逐个试各 IP 的 `/api/captcha`
- **修复**：用登录页运行时覆盖填手机能通的 IP
- **预防**：路由器 DHCP 静态绑定服务器 IP（MAC 绑定），IP 永不变

### 6. debug 包覆盖安装失败
- **现象**：`adb install` 或手机安装器报签名冲突/安装失败
- **根因**：换电脑或换签名后，新旧包证书不一致，安卓拒绝覆盖
- **修复**：先卸载旧版再装（用户数据会丢，正式分发必须用固定 keystore，见第七篇）

---

# 第七篇 Release 签名打包（正式分发）

debug 包只适合自用测试。要上架/长期装机：

```powershell
# 1. 生成 keystore（只做一次。文件和密码丢了 = 永远无法更新已发布的 App）
keytool -genkey -v -keystore im-release.keystore -alias im -keyalg RSA -keysize 2048 -validity 36500

# 2. android/ 下新建 keystore.properties（务必加 .gitignore）：
#    storeFile=../im-release.keystore
#    storePassword=你的密码
#    keyAlias=im
#    keyPassword=你的密码

# 3. app/build.gradle 的 android { } 里配置 signingConfigs 引用上面的属性（详细写法见
#    打包为EXE和Android指南.md 第九部分）

# 4. 打正式包
cd c:\daima\Veytalk\im-ui\android
.\gradlew assembleRelease
```

产物：`android\app\build\outputs\apk\release\app-release.apk`

**release 前自查**：`webContentsDebuggingEnabled` 改 `false`（防逆向调试）、确认
`.env.capacitor` 是正式地址、keystore 已备份。

---

# 第八篇 附录

## 8.1 日常命令速查

```powershell
# 日常重打（改了前端）
cd c:\daima\Veytalk\im-ui
npm run build:capacitor; npx cap sync android; cd android; .\gradlew assembleDebug

# 正式包
cd c:\daima\Veytalk\im-ui\android; .\gradlew assembleRelease

# 看已连设备（adb 在 SDK platform-tools 里）
adb devices
```

## 8.2 换电脑复刻路径

装 Node + Android Studio（SDK API 33+/Build-Tools）→ 项目里 `npm i` →
改 `.env.capacitor` 的 IP → 新建 `android/local.properties` 的 sdk.dir → 跑 4.1。
（gradle-wrapper、Manifest、legacy、CORS 等都在仓库里，随 git 走，不用重做。）

## 8.3 本指南涉及的文件清单

| 文件 | 状态 | 进 git |
|---|---|---|
| `im-ui/capacitor.config.json` | 已配置 | ✅ |
| `im-ui/.env.capacitor` | 已配置 | ✅（IP 是内网，无碍） |
| `im-ui/vite.config.js` / `env.js` / `router/index.js` / `Login.vue` | 已改好 | ✅ |
| `im-ui/package.json`（capacitor、legacy 依赖与脚本） | 已改好 | ✅ |
| `im-ui/android/`（含 Manifest、network_security_config、wrapper） | 已生成+配置 | ✅ |
| `im-ui/android/local.properties` | 已配置 | ❌ 机器专属 |
| `im-bootstrap/.../application.yml`（CORS 白名单） | 已配置 | ✅ |
