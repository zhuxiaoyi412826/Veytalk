# im-android 原生安卓客户端开发方案（待评审）

> **状态：待用户评审，评审通过后开工。**
> 目标：在仓库根目录新建 **`im-android/`** 纯原生安卓工程（Kotlin + Jetpack Compose），
> 功能与 `im-ui`（Web 前端 + Capacitor 壳 APK）**对齐**，登录页额外提供**后端地址设置**
> （默认 `http://192.168.1.2:8080`）。
> 与现有 `im-ui/android/`（Capacitor 壳）**并存不冲突**：壳方案不动，本模块是独立原生工程。

---

## 一、模块定位

| | im-ui + Capacitor 壳（现有） | **im-android（本方案）** |
|---|---|---|
| 渲染 | 系统 WebView 跑 Vue 页面 | 原生 Compose 渲染 |
| 性能/体验 | 受 WebView 内核制约（旧机白屏） | 不受内核影响，安卓 7+ 原生体验 |
| 功能 | 已全量 | **逐页对齐（见第四节对照表）** |
| 安装包 | app-debug.apk（com.im.app） | 独立 APK，**包名 `com.im.native`**，可与壳版同机共存对比 |
| 维护成本 | 一套前端三端跑 | 安卓单独一套 Kotlin 代码 |

**定位**：原生体验参照物 + 不受 WebView 制约的安卓正式客户端。
**边界**：后端零改动——原生端复用同一套 REST API（`/api`）、WebSocket（`/ws`）、流媒体（`:8088`）。

## 二、技术栈与版本（复用已验证环境）

| 项 | 选型 | 版本/说明 |
|---|---|---|
| 语言 | Kotlin | 2.x |
| UI | Jetpack Compose + Material 3 | Compose BOM 2024+ |
| 架构 | MVVM + 单向数据流 | ViewModel + StateFlow |
| 网络 | OkHttp + Retrofit + Moshi/kotlinx.serialization | REST |
| WebSocket | OkHttp WebSocket | 消息推送/在线状态，带心跳重连 |
| 本地存储 | Room（消息/会话缓存）+ DataStore（设置、token、**服务器地址**） | |
| 异步 | Kotlin Coroutines + Flow | |
| 依赖注入 | Hilt | |
| 图片 | Coil | 聊天图片/头像 |
| 音频 | Media3（ExoPlayer）+ AudioRecord | 语音播放/录制 |
| 直播播放 | Media3 HLS | 拉 `:8088` 的 m3u8 |
| 实时音视频 | WebRTC Android SDK（io.github.webrtc-sdk） | 1:1 通话/连麦 |
| 拍照/相册 | CameraX + Photo Picker | 发图/视频 |
| OCR/图像识别 | ML Kit（文字识别/图像标签） | 对齐 Web 端 tesseract/nsfw 的降级替代 |
| minSdk / targetSdk | **24（安卓 7.0+）/ 36** | 与壳版一致；纯原生无 WebView 白屏问题 |
| 构建 | Gradle wrapper 8.14.3 + AGP 8.x + JDK 21 | wrapper 沿用腾讯镜像 + `networkTimeout=120000` 的踩坑经验 |

## 三、工程结构（新建 `c:\daima\Veytalk\im-android\`）

```
im-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   # 腾讯镜像 + networkTimeout=120000
├── local.properties                            # sdk.dir（机器专属，.gitignore）
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml               # 权限沿用壳版清单 + FOREGROUND_SERVICE
        ├── res/                              # 图标（复用 im-ui 图标转 mipmap）、主题
        └── java/com/im/native/
            ├── ImApp.kt                      # Hilt Application
            ├── MainActivity.kt               # 单 Activity + Compose Navigation
            ├── core/
            │   ├── config/ServerConfig.kt    # ★ 服务器地址运行时配置（DataStore，默认 192.168.1.2:8080）
            │   ├── network/                  # Retrofit/OkHttp/拦截器（satoken 头、错误统一）
            │   ├── ws/ImWebSocket.kt         # /ws 长连：鉴权、心跳、断线重连、事件分发
            │   └── storage/                  # Room 库、DataStore
            ├── data/                         # Repository：auth/friend/conversation/message/group/file/live…
            └── ui/
                ├── login/                    # ★ 登录/注册 + 服务器地址设置入口
                ├── home/                     # 会话列表（对齐 ChatHome）
                ├── chat/                     # 聊天页（文字/图片/语音/文件/撤回/回执）
                ├── friend/                   # 好友列表/申请（FriendList/FriendRequest）
                ├── group/                    # 群管理/加群（GroupJoin）
                ├── live/                     # 直播列表/观看（HLS）
                ├── call/                     # 1:1 音视频通话（WebRTC）
                ├── profile/  settings/       # 个人资料/设置
                └── components/               # 语音条、图片预览、礼物面板等复用件
```

> 说明：`im-android` 是**独立 Gradle 工程**，不进 Maven 多模块（pom.xml 不动），
> 也不影响 `im-ui/android/` 壳工程。仓库根目录从此有两个安卓工程，用途分明。

## 四、功能对照表（im-ui 页面 → 原生实现 → 分期）

| im-ui 页面/能力 | 原生实现方案 | 分期 |
|---|---|---|
| 登录/注册/验证码 | Compose 表单 + `/api/captcha` + `/api/auth/*`，satoken 存 DataStore | **P0** |
| **服务器地址设置** | 登录页入口，DataStore 持久化，改后重建网络层；**默认 `http://192.168.1.2:8080`** | **P0** |
| 会话列表/未读数 | Room 缓存 + WS 事件刷新 | **P0** |
| 单聊：文字/表情/图片/文件/语音条 | Retrofit 上传 `/api/file/*`，语音 AmrRecoder/MediaRecorder + Media3 播放 | **P0** |
| 消息回执/撤回/历史分页 | 复用 `/api/message/*` 现有协议 | **P0** |
| 好友列表/申请/搜索 | `/api/friend/*` | **P0** |
| 群聊/加群/群管理 | `/api/group/*` | **P0** |
| 个人资料/头像/设置 | `/api/user/*` + 上传头像 | **P0** |
| WebSocket 实时消息/在线状态/已读 | OkHttp WS + 心跳重连 + 前台服务保活（原生优势，壳版缺） | **P0** |
| 1:1 音视频通话 | WebRTC Android SDK，信令走现有 `/ws` 通道 | **P1** |
| 直播观看（HLS/连麦观看/弹幕礼物） | Media3 HLS 拉 `:8088`；互动走 `/api/live/*` + WS | **P1** |
| 文件下载管理 | 原生 DownloadManager/OkHttp 落盘 + 通知栏进度（壳版的短板，原生补齐） | **P1** |
| 直播开播（推流） | CameraX 采集 + 软编/硬编 + RTMP/HTTP 推流到媒体服务器 | **P2** |
| AI 助手/面试（语音问答） | `/api/ai/*` + 系统 TTS/第三方 ASR | **P2** |
| OCR/图像内容识别 | ML Kit 端侧识别（替代 tesseract.js/nsfwjs，无 wasm 依赖） | **P2** |
| 远程控制 | 安卓系统限制大（无障碍+MediaProjection 只能做有限被控），**评估后裁剪** | **P3 视评估** |

**分期原则**：P0 = 日常聊天闭环（登录→加好友→收发消息→群→文件语音）；P1 = 实时通话与
直播观看 + 下载；P2 = 开播与 AI 类增强；P3 = 平台能力受限项，先出评估结论再定。
每期结束出可安装 APK 验收。

## 五、登录页与服务器地址设置（需求专项）

- 登录页布局对齐 im-ui（账号/密码/验证码/登录/注册入口），**原生 Compose 重写**
- 顶部或设置图标进入「服务器地址」：
  - 输入框 + 保存/恢复默认按钮；校验格式 `http(s)://host:port`
  - **出厂默认 `http://192.168.1.2:8080`**；用户修改存 DataStore，立即生效（重建 Retrofit/WS 客户端）
  - 流媒体地址自动跟随：同 host、端口换 `8088`（与 Web 端 override 逻辑一致）
- 清空覆盖 = 恢复出厂默认
- 换网段/热点场景免重打包，与壳版登录页的体验保持一致

## 六、后端对接（零后端改动）

| 通道 | 地址 | 用途 | 鉴权 |
|---|---|---|---|
| REST | `{server}/api/**` | 全部业务接口（复用现接口文档/前端 request 封装为准） | `satoken` 请求头 |
| WebSocket | `ws://{host}:8080/ws` | 消息/回执/在线状态/通话信令 | 连接后首帧带 token |
| 流媒体 | `http://{host}:8088/hls/**` | 直播拉流 | 沿用现有签名参数 |

> 开发时以 `im-ui/src/api/*.js` 与 `request.js` 为接口清单事实源，逐个端点对照实现；
> 如发现 Web 端有而接口文档缺失的，直接读前端调用代码对齐参数。

## 七、构建与打包（沿用已验证的踩坑经验）

```powershell
cd c:\daima\Veytalk\im-android
# 首次：新建 local.properties 写 sdk.dir（机器专属，勿提交）
.\gradlew assembleDebug     # wrapper 腾讯镜像 + networkTimeout=120000
```

- 产物：`im-android\app\build\outputs\apk\debug\app-debug.apk`
- debug 签名直接装；正式分发再走 keystore（流程同《Capacitor打包安卓指南》第七篇）
- 与壳版**包名不同**（`com.im.native` vs `com.im.app`），可同机安装对比测试

## 八、里程碑与验收

| 里程碑 | 内容 | 验收标准 |
|---|---|---|
| M0 | 脚手架 + 登录 + 服务器地址设置 + REST/WS 打通 | 默认地址下能登录，改地址后立即生效 |
| M1 | 会话/单聊/群聊/好友全链路 + 消息缓存 | 两台手机互发文字/图片/语音/文件，杀进程重进消息不丢 |
| M2 | 通话 + 直播观看 + 下载管理 | 与 Web/Electron 端互通 |
| M3 | P2 增强项逐个并入 | 按功能对照表验收 |

## 九、风险与已知代价

1. **工作量主体在页面重写**——im-ui 的 ChatHome/Live 等页面交互复杂，P0 是最大的一块
2. **远程控制在安卓平台能力受限**（被控需无障碍+录屏授权，主控端体验与桌面不可比），P3 先评估
3. 双客户端长期并行维护（壳版与原生版），新功能要决定「只更一边还是两边」
4. 后端接口若有 Web 端隐式约定（字段容错等），原生端实现时逐个对齐，属于正常联调成本

---

> **评审确认后**：按 M0 开工——先建 `im-android/` 脚手架并把「登录 + 服务器地址设置
> （默认 192.168.1.2:8080）+ REST/WS 打通」跑通出首个 APK。
