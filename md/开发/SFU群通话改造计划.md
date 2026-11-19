# 群通话 SFU 化改造计划（LiveKit）

> **状态：计划，未开工。** 本文只把方案、边界、阶段与验收口径钉死，不含任何已实施的代码。
> 需求原文见 `md/综合.txt` 最后一行：「现在群聊视频语音使用 SFU」；
> 现状（mesh 一期）的完整文档是《[语音视频通话接入指南](语音视频通话接入指南.md)》§13。
>
> **实施前必读**：本文 §8「风险与未验证项」里有 5 条标注 ★ 的**必须在阶段 0 实测**，
> 任一条实测结果与本文预期不符，方案就要改（尤其 §6.6/§6.7 的信令同源代理与 UDP 直达）。

---

## 一、需求拆解：SFU 到底解决哪三件事

「用 SFU」不是目的，mesh 的三个天花板才是。拆开才知道做完算不算数：

| # | 真诉求 | mesh 为什么做不到 | SFU 后 |
|---|---|---|---|
| 1 | **人更多**（>6 人） | 每人上行 `N-1` 路独立编码，家用宽带上行与移动端 CPU 先崩 | 每人**恒定 1 路上行**，与房间人数无关 |
| 2 | **下行可控** | 每人收 `N-1` 路，画面宫格是「全订」 | 订阅关系可选：只订可见格 + 说话人 |
| 3 | **能录制** | 要录的是 `N-1` 路分立远端流，没混流就没意义（§13.8 因此明确不做） | 服务端侧有完整房间视图，录制有抓手 |

隐含约束（不能因为改造而退化的东西）：

- **1:1 通话**与**直播连麦**不在本次范围，它们继续用现有 `createCallPeer` 的 P2P 链路；
- 群通话现有的**业务语义**一个都不能丢：一群一路、成员资格校验、忙线互斥、振铃超时、
  「后进房者发 offer」这类规则（后者在 SFU 下直接消失，见 §4.3）、只有发起人落一条通话记录；
- **零 DDL** 的传统要守住：房间实时态归 SFU 自己 + Redis，不给 MySQL 加表（见 §7）。

---

## 二、现状盘点（改造要动的东西，逐个点名）

### 2.1 前端（im-ui）

| 文件 | 行数 | mesh 里承担什么 | SFU 下的命运 |
|---|---|---|---|
| `src/stores/groupcall.js` | 689 | 状态机、`peers{}` 字典、共享本地流、花名册合并、计时、通话记录 | **改内核保外壳**：状态机与字段（`status/members/hint/seconds/peerStates`）保留，`ensurePeer/offerToEach/attachRemoteStream` 整段换成 LiveKit Room 事件 |
| `src/utils/webrtc.js` | 265 | `createCallPeer`（RTCPeerConnection 封装）+ `hasMediaApi`/`INSECURE_HINT` | **不动**。1:1 与连麦还在用；群通话不再调它。`hasMediaApi`/`INSECURE_HINT` 继续共用 |
| `src/components/GroupCallOverlay.vue` | 409 | 浮层：来邀面板 + 网格 + 控制条 | 小改：加说话人高亮、翻页/缩略图栏 |
| `src/components/GroupCallTile.vue` | 146 | 单格，按 `peerStates` 画「连接中/已连/连不上」 | 改数据源：`peerStates[uid]` → 由 LiveKit `participant` + `track` 状态映射成同名结构（**保持字段名不变**，模板就不用重写） |
| `src/components/GroupCallInviteDialog.vue` | 185 | 成员选择器 | 不动 |
| `src/ws/dispatch.js` | 333 | `groupcall-signal` 路由 + 跨类型忙线拦截 | 不动（业务信令仍走 `/ws`） |
| `src/stores/call.js` | 680 | 从 `/api/call/config` 取 `groupEnabled/groupMaxSlots/iceServers` | 多接两个字段（`sfuEnabled` / `sfuUrl`） |
| `src/api/call.js` | 37 | `getGroupActiveCall` | 新增 `getGroupCallToken(groupId)` |
| `src/utils/env.js` | 82 | 按访问源推导 http/ws 基址 | 若走 §6.6 方案 A（同源前缀），这里要能推出 wss 前缀地址 |
| `vite.config.js` | 165 | proxy `/api`、`/ws`、`/hls` | 视 §6.6 结论可能新增一条 `/rtc` ws 代理 |

### 2.2 后端（im-websocket / im-common）

| 文件 | mesh 里承担什么 | SFU 下 |
|---|---|---|
| `service/GroupCallSignalingService.java` | 花名册 + SETNX 锁 + SDP/ICE 转发 + 超时清扫 + `activeCallOf` | **删转发、留编排**：`offer/answer/ice` 三个分支与对应额度一起废；新增「签 token」与「成员→identity 映射」 |
| `dto/WsGroupCallPayload.java` | action/callId/groupId/mediaType/calleeUserIds/members/muted/sdp/candidate… | 去掉 `sdp/sdpType/candidate`，加 `identity`（= userId，不下发 token） |
| `dispatch/WsInboundDispatcher.java` | `case GROUPCALL` + `requireGroupCallQuota` | 额度可**收紧**（ICE 逐条帧没了，`ws-group-call-count: 600` 这个数为 mesh 而设） |
| `controller/CallController.java` | `GET /api/call/config`、`GET /api/call/group/active` | 新增 `GET /api/call/group/token` |
| `config/ImProperties.Call` | `groupEnabled` / `groupMaxSlots`（服务端硬夹 `[2,6]`） | 新增内嵌 `Sfu`（`enabled`/`url`/`apiKey`/`apiSecret`/`maxSlots`/`tokenTtlSeconds`）；`[2,6]` 那个硬夹只在 SFU 关闭时保留 |
| `im-common/.../api/ResultCode.java` | 12008/12009/12010 | 复用，另加 `SFU_NOT_CONFIGURED`（口径对齐既有的 `LIVE_NOT_CONFIGURED(11002)`：**配不出来就直接拒绝，不下发拼不出的地址**） |
| `im-bootstrap/.../application.yml` §call | `IM_CALL_GROUP_*` | 新增 `IM_SFU_*` 一组 |

### 2.3 必须一并知道的既有约束（不是本次新增的坑）

- **雪花 ID 一律按字符串**：`groupcall.js` 里 `calleeUserIds` 已注明「`Number()` 会舍掉末尾几位，邀请就发给一个不存在的人」。SFU 的 `identity` 同样必须是字符串。
- **`MediaStream` / `Room` 不能被 Vue 深代理**：现有代码用 `shallowRef` + 整体替换（`remoteStreams`、`peers` 都刻意非响应式）。LiveKit 的 `Room`/`Participant`/`Track` 对象同理，**一律 `shallowRef` 或存普通变量**。
- **安全上下文**：`hasMediaApi()` 与 `INSECURE_HINT` 已经处理过「http://局域网IP 没有麦克风」，SFU 不改变这个前提。
- **Electron 打包产物错配**：改完必须 `npm run build:electron` 重出 `electron/dist`，且**验证要查到包内代码**（`app.asar` 里搜新字符串），不能只看 `im-ui/dist`。

---

## 三、选型：为什么这次是 LiveKit

### 3.1 候选横评（以本机实测环境为前提，不是照抄网帖）

| 方案 | 语言 | Windows 支持 | 本机能否落地 | 结论 |
|---|---|---|---|---|
| **LiveKit** | Go | **官方 `.exe` 发布物** | ✅ 下载解压即跑，单机**不需要 Redis** | **选它** |
| mediasoup | C++/Node worker | 无官方 Windows 支持 | ❌ 要 WSL/Linux | 出局 |
| Janus | C | 需自行编译依赖链 | ❌ 本机 gcc/cmake 有但依赖（libwebsockets 等）齐不齐未知 | 出局 |
| SRS | C++ | 有 Windows 发行 | △ 直播侧已熟，但 WebRTC 会议语义（房间/订阅/权限）要自己搭一层 | 出局 |
| 自研 SFU（pion / Node ws + 转发） | — | — | ❌ 要自己写 ICE/DTLS/SRTP/带宽估计/FEC，工程量是本次全部方案的数倍 | 出局 |

### 3.2 §13.1 当年排除 SFU 的四条理由，现在逐条重验（**这是本计划的立论基础**）

当年写「不引 SFU」给了四条理由。选型前必须先问：这四条今天还成立吗？

| 旧理由 | 今天的事实（本轮实测） | 是否仍成立 |
|---|---|---|
| ① SFU 要新进程，本仓库只有 8080/5173/18923/8088 | 确实要多一个进程（`livekit-server`）。**但项目已有同类先例**：直播侧 `electron/live-media-server.js`（本机 8088，Nginx 的局域网替身）与主进程 spawn ffmpeg。运维口径可以照抄这一套 | ✅ **仍成立**（唯一的真实代价） |
| ② mediasoup Windows 无原生、**LiveKit 要 Docker** | 本机 `docker => NOT INSTALLED`；但 LiveKit 官方文档明确 Windows 走 GitHub Releases 下载，实测 `api.github.com/repos/livekit/livekit/releases/latest` → `tag=v1.13.10`，资产含 **`livekit_1.13.10_windows_amd64.zip`（18.7 MB）**；单机模式 `redis.address` 留空即可（官方：填了地址才切分布式） | ❌ **失效**（不需要 Docker，也不需要新中间件） |
| ③ npm 带原生编译的包在这套环境反复翻过车 | `npm view livekit-client` → **2.23.0**，registry `registry.npmmirror.com` 可解析；依赖树 `jose / tslib / events / machina / loglevel / sdp-transform / typed-emitter / @livekit/mutex / webrtc-adapter / @livekit/protocol` + peer `@types/dom-mediacapture-record` —— **全是纯 JS，无 node-gyp、无 wasm 原生工具链** | ❌ **失效** |
| ④ 目标场景是小房间（4 人），mesh 够用 | 需求已变成 >6 人 | ❌ **不再适用** |

> **结论**：四条里三条已因外部条件变化而失效，剩下一条（多一个进程）是**可接受的代价而非阻塞项**。
> 这就是这份计划现在值得做的全部理由——反之，若目标机没有 Go 也没外网拿 `.exe`，本计划应立即降级为「不做」。

### 3.3 版本与来源约束（实测钉过，别到时候踩空）

- **不要指望源码构建**：`livekit` 模块 `v1.13.10` 的 `go.mod` 写的是 **`go 1.26.0`**，本机是 **Go 1.23.6**（`D:\software\Go1.23.6`）。`GOTOOLCHAIN=auto` 会尝试自动下载新工具链（大依赖、网络不确定），**不作为交付路径**。主路径：**下载官方 Windows zip**。
- **客户端版本要配对**：`livekit-client@2.x` 需要 server **≥ v1.9**；过旧 server 会出现「连上了但没画面」这类难查现象。本计划锁 **server v1.13.x + client 2.23.0**。
- **SFU 用的编解码器与本项目直播侧无关**：群通话是 WebRTC（Opus + VP8/H264/AV1），直播是 HLS（ffmpeg 切 fMP4）。两条链路本次不合并，别把 §18 的清晰度阶梯当成能复用。

---

## 四、目标架构

### 4.1 控制面 / 媒体面分离（与直播同构，不是新发明）

```
im-ui / Electron
   │  ① 业务信令（不变）：/ws  type=groupcall  create/invite/join/reject/leave/state/end
   ▼
Java 后端（im-websocket）
   │  · 群成员资格、一群一路、忙线、人数闸门、通话记录
   │  ② 签发 LiveKit AccessToken（HS256 JWT，密钥永不下发前端）
   ▼
LiveKit SFU（独立进程，新）
   ▲  ③ 媒体面：livekit-client ── wss://{sfu}:7880 ── 房间收发
   │     （WebRTC 媒体 UDP 50000-60000 / TCP 7881 / 内嵌 TURN 3478）
   └── 每个成员只上行 1 路，SFU 按订阅关系转发
```

**Java 仍然一个媒体字节都不碰**，这与直播侧的定位一致（`md/架构与请求链路图.md` §十一 vs §十三）。
新增的只有：Java 多签一种 token，进程表多一行。

### 4.2 房间与身份的映射（安全边界就在这）

| 概念 | 取值 | 谁来定 | 为什么 |
|---|---|---|---|
| LiveKit `room` | `gcall-{callId}`（`callId` 仍是前端生成的 UUID，沿用现有主键） | **服务端拼**，token 里锁死 | 前端不许自报房间名，否则可以签一个「假装是别人的房间」的 token 蹭进去 |
| LiveKit `identity` | 用户 ID **字符串**（雪花 ID 原样） | 服务端从连接身份取（与 mesh 时代 `fromUserId` 取连接身份同规矩） | 不信前端自报；字符串化避免精度丢失 |
| `name` | 群昵称 | 服务端 | 只用于界面显示 |
| 权限 grant | `roomJoin=true, canPublish=true, canSubscribe=true, canPublishData=false` | 服务端 | 数据通道不需要（弹幕/聊天另有链路）；不给 `admin`，谁都不能踢别人 |

**准入流程**：前端要进房 → `GET /api/call/group/token?groupId=` → 服务端在 Redis 花名册 + `GroupSpi.isMember` 校验通过后签 token（TTL 默认 6h）→ 前端 `room.connect(url, token)`。
token **只走 REST**，不走 `/ws`（避免出现在信令帧日志里）。

### 4.3 mesh 那三条规则的去向

| mesh 规则 | SFU 下 |
|---|---|
| 「后进房者发 offer」（§13.4，防 glare） | **整条作废**：没有 offer/answer 协商，各端只与 SFU 建联，不存在双方同时 offer 撞车 |
| 「本地只采集一次，共享给 `N-1` 路」（§13.9-4） | **采集所有权交给 SDK**：不再自己 `getUserMedia`，改由 `localParticipant.createTracks/setCameraEnabled` 系列管；「一按对全房生效」这个语义不变 |
| 「一路断开只影响那两个成员」的隔离性 | **变成「一路断开只影响那一个成员」**：SFU 是单点，它挂则全房断（见 §8 风险 3 与 §6.4 的回退） |

---

## 五、分阶段实施计划

> 每阶段末尾都有**退出判据**：达不到就不进下一阶段，别把「编译过」当成「功能对」。

### 阶段 0：可行性验证（不碰业务代码，1 人日内可完成）

目的只一个：**证明这台机器与这套前端能跑通 LiveKit**。这一阶段的结果会决定 §6.6/§6.7 走哪条路。

1. 拿二进制：下载 `livekit_1.13.10_windows_amd64.zip` 解压到 `tools/livekit/`（**该目录加入 `.gitignore`**，18.7MB 不进仓库）；
2. `livekit-server.exe --dev` 起服务 → 看日志 `starting in development mode`，占位密钥 `devkey/secret`；
3. `livekit-server.exe ports` **打印本机实际要放的端口**（照这份清单配防火墙，不要照抄网帖的 50000-60000）；
4. `Invoke-WebRequest http://127.0.0.1:7880/` 通；
5. 客户端最小验证（两条都要做，因为 §6.6 要的就是这两条的差异）：
   - (a) 用 `wss://`/`ws://` **直连** `ws://<局域网IP>:7880` 跑官方 client sample，两个浏览器窗口互见画面；
   - (b) 把 `serverUrl` 换成**同源带路径前缀**（vite 代理 `/rtc` → `ws://127.0.0.1:7880`）再跑一次，确认 `livekit-client@2.23.0` 是否会自己拼成 `{serverUrl}/rtc?...` 从而需要代理保留前缀（★ **待实测**：文档与实践都提到路径前缀可用，但 SDK 具体拼法必须以本机实测为准）；
6. 手机（同网段 https 页面）连一次：确认 UDP 直达 / 7881 TCP 兜底哪条真的在工作。

**退出判据**：(a) 与 (b) 至少一条在**桌面浏览器 + 手机**上都拿到远端画面与声音，且 `livekit-server` 日志里能看到 room 与 participant 进出；(b) 不成立则 §6.6 改走 LiveKit 自带 TLS。

### 阶段 1：后端接入（im-common / im-websocket，2~3 人日）

| 改动 | 要点 |
|---|---|
| `ImProperties.Call.Sfu` | `enabled`(false) / `url` / `apiKey` / `apiSecret` / `maxSlots`(16) / `tokenTtlSeconds`(21600)。`enabled=false` 时**整套 mesh 老链路照跑**（这是回退开关，不是摆设） |
| `service/LiveKitTokenService`（新） | **用已引入的 `hutool-jwt` 手签 HS256**，不引 `livekit-server-sdk-go`、不引 Node 侧 SDK。Claims 固定为：`{ iss: apiKey, sub: identity, exp, nbf, video: { room, roomJoin: true, canPublish: true, canSubscribe: true } }`（结构来源：LiveKit 官方 token 规范，多语言 SDK 与 Go `auth.NewAccessToken` 一致） |
| `GET /api/call/group/token` | 校验链照抄 mesh 的 `create`：群成员 → 一群一路 → 忙线 → 人数；`sfu.enabled=false` 回 `SFU_NOT_CONFIGURED`（对齐 `LIVE_NOT_CONFIGURED` 的「不猜地址」口径） |
| `CallConfigVO` + `/api/call/config` | 回填 `sfuEnabled` / `sfuUrl` / `sfuMaxSlots`；`sfuUrl` **给前端的是同源相对前缀还是绝对 wss 地址，取决于阶段 0 的 (a)/(b) 结论** |
| `GroupCallSignalingService` | 删 `offer/answer/ice` 转发（保留 `roster/member-join/member-leave/state/end`）；`state` 仍落 Redis 花名册（SFU 不认「谁点了静音」这种业务态） |
| 人数闸门 | `groupMaxSlots` 现硬夹 `[2,6]`：改为 SFU 启用时上限放到 `[2, 50]`（默认 16），**mesh 回退路径继续用 `[2,6]`** —— 两个拓扑两个天花板，不能共用一个夹子 |
| `requireGroupCallQuota` | 额度从 600/60s 收到 **120/60s** 量级（ICE 逐条帧消失后，剩下的是 join/leave/state 三类低频帧）；`application.yml` 注释要写清这个数为 mesh 时代而设 |

**退出判据**：`mvn -q -o compile` EXIT=0；用 `tmp/livekit-token-test.mjs` 之类的**一次性脚本 + 现成 `livekit-server --dev` 的密钥**对同一份 claims 交叉验签（或直接把签出的 token 丢进官方 `lk` CLI / client sample 验能否进房），跑完即删并 `Test-Path` 复核。

### 阶段 2：前端切流（im-ui，3~4 人日）

**原则：`stores/groupcall.js` 对外暴露的 state 字段与 action 名单一个不改**，这样 Overlay/Tile/dispatch 都不动，风险被关在一个文件里。

| 改动 | 要点 |
|---|---|
| `src/utils/livekit.js`（新建，**纯函数**） | 把「订阅策略 / 宫格分页 / 说话人排序 / participant→tile 状态映射」写成不依赖 Vue 与 SDK 的纯函数（沿用 `utils/quality.js` 的经验：**判定逻辑挂在 SFC 里就只能靠真人开播才能验**）。`node tmp/xxx.mjs` 直接 import 跑断言（`im-ui/package.json` 是 `"type": "module"`，已确认可行） |
| `src/stores/groupcall.js` | 删 `peers{}` / `ensurePeer` / `offerToEach` / `attachRemoteStream`，改 `room = new Room()`（`shallowRef`）+ `trackSubscribed/trackUnsubscribed/activeSpeakersChanged/participantsConnected` 事件；`localStream` 由 `room.localParticipant` 接管，**不要再自己 `getUserMedia`**（否则与 SDK 抢设备，正是 mesh 那条「共享轨被误停」坑的变体） |
| 静音/摄像头 | 从 `track.enabled` 改为 `localParticipant.setMicrophoneEnabled(false)` / `setCameraEnabled(false)`，并继续 `signal('state', …)` 广播给业务侧（花名册是唯一权威） |
| 订阅策略 | 音频全订（会议的基本盘），视频**只订可见宫格 + 当前说话人**（`setSubscribed(false)` + 翻页时重算）。这是 SFU 省下行的唯一方式，写错就等于没换架构 |
| 降级与失败 | `room.connect` 失败（SFU 没起 / token 过期 / 端口不通）→ 明确提示「会议服务不可用」，并**按配置回落 mesh 重跑旧链路**（见 §6.4）；`quality` 那次「藏掉 vs 灰掉」的教训同样适用：**能力不可用时要说清楚，不能静默变成没有入口** |
| token 续签 | 长通话接近 TTL 时重取 token（`room.reconnect` 语义需实测），别等 6h 后突然掉线 |
| 录制（若做，见阶段 4） | 只有桌面端，且录的是**本机看到的混视图**，不是服务端全量 |

**退出判据**：`npm run build` / `build:electron` 均 EXIT=0；三窗口 dev 手工链路（照 §13.10 那份步骤改写成 SFU 版）跑通；`GetProblems` 无新增。

### 阶段 3：部署与打包（1~2 人日，含文档）

1. **配置文件** `tools/livekit/livekit.yaml`：`bind_addresses: ["0.0.0.0"]`（`--dev` 只绑回环，局域网必然连不上——这是最常见的「本机好使、远程连不上」根因）、`keys: {apiKey: apiSecret}`、`room.max_participants`、`rtc.tcp_port: 7881`、`turn.enabled`（跨 NAT 才开）、`redis.address` **留空**；
2. **证书**：走 §6.6 的 TLS 路线时，直接复用 `im-ui/certs/server.pem|key`（同一张 SAN 覆盖本机 IPv4 的自签证书，已有 `npm run gen:cert` 与换网段自检 `checkCertSan()`）。**SAN 跟不上 DHCP 换 IP 会导致 wss 被静默拒绝**，这条坑本项目已经踩过两次（远程控制、手机直播），必须在文档里点名；
3. **防火墙**：Windows 入站规则按 `livekit-server ports` 的实际输出一条条加（TCP 7880/7881 + UDP 段），并写清「TCP 通、UDP 没开」的症状就是**能进房但没画面**；
4. **生产 Nginx**：`location` 代理信令（`proxy_http_version 1.1` + `Upgrade/Connection` 头 + `proxy_read_timeout` 要长），媒体 UDP 不经 Nginx；
5. **进程托管**：三选一并写进文档 —— ① 手动 `livekit-server.exe --config livekit.yaml`（自测期）；② Windows 服务/NSSM；③ Electron 主进程按需 spawn（**照 `live.js` spawn ffmpeg 的既有写法**：`windowsHide: true`、退出时必杀，否则就是「打包时 EBUSY 文件锁」那一坑的翻版）。**推荐 ②/③，①只适合验证**；
6. **Electron 打包**：**不把 18.7MB 的 exe 塞进安装包**（客户端不需要本地 SFU，服务器只需要一份）。

**退出判据**：按文档从零在另一台机器上部署一次，手机 + 桌面 + 网页三端能进同一场群通话。

### 阶段 4（可选）：群通话录制

**不用 LiveKit Egress**：Egress 是独立服务，composite 录制依赖 headless Chrome，Windows 部署成本明显高于本仓库现有水位。
**改走客户端录制**：复用 `electron/recorder.js` 的「流式落盘 WebM」链路（已有结论：**MediaRecorder 录 MP4 不按 timeslice 吐片，流式落盘必须用 WebM**），由桌面端主持人这一路录自己看到的画面。
语义要写清：**录到的是「某一个人看到的房间」，不是服务端全量**，掉线/翻页期间的内容会有缺口。不做也不影响主功能，可以整段砍。

### 阶段 5：文档同步（项目硬约束，一次都不能漏）

| 文档 | 要改什么 |
|---|---|
| `md/语音视频通话接入指南.md` | §13.1 **不能删旧四条理由**（那是决策现场），要在其后加「§13.11 SFU 化：条件已变，见《SFU 群通话改造计划》」；§13.9-1「要真正开大会得换 SFU」加前向指针；§13.10 自测步骤补 SFU 版 |
| `md/架构与请求链路图.md` | §十三 标题与正文目前写死「mesh：信令经服务端、媒体点对点」，改造后要新增一节「群通话链路（SFU：信令经服务端、媒体经 SFU 转发）」，并把旧的标注为「回退路径」 |
| `md/数据库表解析.md` | §十五 的 Redis 键表与「三个旋钮」段要加 SFU 相关键（若有）与新上限口径 |
| `md/其他控制软件和远程会议实现原理.md` | §3.6 表「多人 / SFU」行与 §3.7 第 331 行「仍需按 SFU 体系另起一套工程」改为已落地/已立项 |
| `md/待开发功能.txt` | 第 10 条末尾与「【新增缺口】群通话 / 多方会议」两行的「已立项未开工」要改成「已完成」，并把 mesh 那段旧描述标注为**回退路径**（本文立项时已把「Windows 无原生支持」这句错断言订正过一次，别再写回去） |
| `md/综合.txt` | §59-62 的需求注记：四条理由重验结果后面要跟一句实施结论（哪些在真机上被推翻、哪些成立），需求原文那行不动 |
| `README.md` | 「已知局限/路线图」第 238 行那条（>6 人与群通话录制）改为已实现，功能表与环境变量表补 `IM_SFU_*` |
| `md/CHANGELOG.md` | 交付时在 `Unreleased / 新增 Added` 写一条：改了什么、为什么、影响范围、★ 关键取舍、**验证状态（含「未做真人 N 端联调」这类没做的部分必须写）** |
| `md/已踩坑.md` | 阶段 0/3 一定会有新坑（`--dev` 只绑回环、UDP 段没开导致「能连上没画面」、路径前缀拼法）。**只有真踩到才写**，不预写猜测 |

---

## 六、关键设计决策与取舍

### 6.1 房间名由服务端拼、token 只走 REST
前端若能自选 `room`，就等于「拿到一个 token 就能进任何房间」。房间名从 `callId` 派生并由服务端拼接，是这次唯一新增的强安全约束。token 不进 `/ws` 帧，避免被信令日志与本地 sql.js 缓存留下副本。

### 6.2 `canPublishData=false`、不给 admin
本次只需要音视频。给数据通道等于多开一条无校验的任意消息入口；给 admin 等于任何成员能踢人、能改别人的轨道。

### 6.3 人数上限：配置放开 ≠ 能力放开
LiveKit 单机扛多少路要看编解码组合与上行带宽，**没有实测数字前不许在文档里承诺**。默认给 **视频 16 / 音频 50**，`room.max_participants` 同步设到该值，前端宫格超过 9 路一律翻页 + 只订可见格。
阶段 0/3 之后要回填一张真实测得的表（房间数 × 每房人数 × 视频档 → CPU/带宽），并据此改默认值与文档。**这张表没有之前，本文所有人数数字都是占位。**

### 6.4 保留 mesh，作为降级路径而不是双实现
`sfu.enabled=false` 或 `room.connect()` 失败时回落 mesh（≤6 人）。理由：SFU 是新的**单点**，一个没被监控过的进程挂掉，不能让所有群通话直接不可用；mesh 那套代码已经在真实环境里跑过，删掉等于用回退能力换新能力。
代价：`groupcall.js` 里有一条 `if (sfuEnabled)` 的分叉。**可辩护的边界**：分叉只允许存在于「建连方式」这一层（`peers` vs `room`），业务信令、花名册、通话记录、UI 一律共用，绝不允许出现两套状态机。

### 6.5 1:1 通话与直播连麦不迁
它们各自只有两方，SFU 无收益（少一路编码 vs 多一跳转发延迟 + 多一个依赖进程）。**本次范围严格限定群通话。** 将来若要做「直播多嘉宾上麦」，那才是第二次复用这套 SFU（届时连麦与群通话的房间语义要重新对表）。

### 6.6 信令地址：同源代理优先，其次自带 TLS（★ 阶段 0 必须定案）
https 页面连 `ws://` 会被 mixed content 拦掉，本项目对同一个问题已有成熟答案：直播的 `play-base-url=/hls` + vite proxy + 「同源、复用 dev server 的 https 证书、不触发 mixed content、DHCP 换 IP 免配」。

- **方案 A（推荐）**：`sfu-url` 配同源前缀（如 `/rtc`），开发期 vite proxy（`ws: true`）→ `ws://127.0.0.1:7880`，生产期 Nginx 同域代理。**前提**是 `livekit-client` 允许 `serverUrl` 带路径前缀 —— 阶段 0 步骤 5(b) 实测。
- **方案 B（A 不成立时）**：让 `livekit-server` 自己出 `wss://`（`port_tls` + 复用 `im-ui/certs` 那套自签证书），前端按 `location` 推主机名。代价：证书 SAN 必须覆盖局域网 IP，换网段要重签（本项目已两次踩这条）。
- **不可接受**：把 im-ui 降级成 http 局域网访问 —— 那样 `navigator.mediaDevices` 直接没有（`INSECURE_HINT` 说的就是这件事）。

### 6.7 ★ 信令能代理，媒体不能：SFU 的 UDP 必须直达
**这是整个改造里最容易被误判成「配好了」的一点。** 反向代理只搬 WebSocket 信令；真正的音视频走 WebRTC，客户端必须能直达 SFU 主机的 **UDP 端口段**（或退到 `tcp_port 7881`、或内嵌 TURN 3478）。
症状与直播那次「init.mp4 没落盘」一样恶劣：**信令连上、房间进得去、花名册正常，但就是没画面没声音**，后端一行错误都没有。
所以交付文档里必须有一节「三档连通性自证」：`livekit-server ports` 打印 → 防火墙规则逐条对 → 客户端 `getStats()`/官方 debug 页看 candidate 类型（`host`/`srflx`/`relay`），**先确认媒体走的是哪一档再谈音质画质**。

### 6.8 零 DDL 的坚持
花名册、忙线、一群一路仍在 Redis（TTL 兜底回收），通话记录仍复用 `MsgType.CALL(6)` + `extra.callParticipantCount`，SFU 自己的房间态在它进程/内存里，两边不重复记账。
唯一可考虑的增量：若要给管理后台看「当前有哪些会议」，**先只加一个 `GET /api/call/group/sfu-stats` 透传 SFU 的查询接口**，不落库（口径对齐 §13.9-5「muted/cameraOff 只存 Redis，不进管理后台」）。

### 6.9 token TTL 与长通话
默认 6h（覆盖 `max-duration-seconds: 3600` 的 6 倍余量），到期前要主动续签。若不做续签，就会得到一个「通话一小时必断」的诡异现象——这类「只在长时间后暴露」的缺陷，代码自洽看不出来，验收项里要有一条 90 分钟浸泡测试。

---

## 七、新增成本清单（诚实版）

| 项 | 数量 / 体积 | 说明 | 可回退 |
|---|---|---|---|
| 新进程 | 1（`livekit-server.exe`） | 与 8080/5173/8088/18923 并列，**唯一真正新增的运维负担** | 停进程 + `sfu.enabled=false` |
| 二进制获取 | 18.7 MB / 台服务器 | GitHub Releases；`tools/livekit/`，加 `.gitignore` | 删目录 |
| 新 npm 依赖 | `livekit-client@2.23.0`（**10 个直接依赖全是纯 JS**） | 无 node-gyp / 无原生编译；**打包后增量以实测 gzip bundle 差值为准，不预估** | 撤依赖，mesh 路径本就在 |
| Java 新依赖 | **0**（`hutool-jwt` 已在 `im-common/pom.xml`） | token 手签，不引厂商 SDK | — |
| DDL | **0** | 见 §6.8 | — |
| 端口 | TCP 7880 / 7881、UDP 段（以 `ports` 输出为准）、TURN 3478（可选） | 需防火墙与安全组入站 | — |
| 代码量 | 前端 +1 工具模块/改 1 store；后端 +1 Properties +1 TokenService +1 REST，删 3 个转发分支 | 净增有限，**主要成本在部署与联调** | — |

---

## 八、风险与未验证项

| # | 风险 | 触发条件 | 应对 | 状态 |
|---|---|---|---|---|
| 1 | **本机从没跑通过 LiveKit** | 现在就是 | 阶段 0 全量验，不过就不开工 | ★ 待验 |
| 2 | `livekit-client` 对**带路径前缀的 serverUrl** 的拼法与预期不符 | §6.6 方案 A | 阶段 0 步骤 5(b) 定案，不成转方案 B | ★ 待验 |
| 3 | **UDP 段不可达**（企业网 / 手机热点隔离 / 路由器限制） | 非受控网段 | `tcp_port 7881` + 内嵌 TURN；文档写清「三档连通性自证」 | ★ 待验 |
| 4 | **SFU 成为全房单点** | 进程崩 / 机器重启 | 保留 mesh 回退（§6.4）+ 进程托管自动拉起 | 已设计 |
| 5 | 拿不到 GitHub Releases（无外网机器） | 交付环境 | 备选：内网镜像拷贝 exe；**不选源码构建**（`go.mod` 要 Go 1.26，本机 1.23.6） | 已给路径 |
| 6 | 多路音频同播比 mesh 更吵（SFU 不做服务端混音） | >8 人同时说话 | 靠 SDK 的音量/说话人属性做客户端衰减；实测后再定要不要限制可见格数 | ★ 待验 |
| 7 | 单机容量不明（CPU / 轨道数） | 上到十几人 | LiveKit 默认按 CPU 给轨道上限（约 400/CPU），阶段 3 压测后回填 §6.3 的表 | ★ 待压测 |
| 8 | Vue 深代理把 `Room`/`Track` 打坏 | 写进 `ref` 而非 `shallowRef` | 沿用既有 `shallowRef` 约定 + code review 盯这一处 | 已知坑 |
| 9 | Electron 桌面端 `file://` 下信令地址推导（同源相对前缀没有 `location.origin` 意义） | 桌面端 | `utils/env.js` 那套「按访问源推导」要单列为测试项，桌面端曾出现过 `null Origin` 被 CORS/握手双重静默 403 的先例 | ★ 待验 |
| 10 | 自签证书 SAN 跟不上 DHCP 换 IP → wss 被**静默**拒绝 | 走 §6.6 方案 B | 复用 `checkCertSan()` 的启动自检思路；文档写明「服务端一行日志都没有」这一现象特征 | 已有对策 |

---

## 九、验收标准（做完怎么算成）

**代码层（必须全绿）**

1. `mvn -q -o compile`、`im-ui npm run build`、`im-ui npm run build:electron`、`im-admin-ui npm run build` 四项 EXIT=0（沿用本项目既有四件套）；
2. `utils/livekit.js` 的纯函数断言脚本（`node tmp/xxx.mjs`，**跑完即删并 `Test-Path` 复核**）覆盖：订阅集合计算（可见格/说话人）、`participant→tile` 状态映射、宫格分页、token claims 结构；
3. 桌面端交付要查到 **`app.asar` 里的新代码**（搜新增中文串），不是查 `im-ui/dist`。

**端到端（必须真人跑，缺一律在 CHANGELOG 里写明「未做」）**

4. 桌面浏览器 A/B/C + 手机 D 四端进同一场，`livekit-server` 日志与界面花名册一致；
5. **人数跨过旧硬顶**：8 人房成立（mesh 时代做不到的一件事，这条不通过就说明没换成功）；
6. 每人只上行 1 路（任务管理器上行带宽或 `getStats()` 佐证，别只靠「感觉流畅」）；
7. 静音/摄像头开关对全房可见（`state` 帧仍生效）；
8. 有人中途掉线 → 其余人不受影响；发起人挂断 → 全员收 `end` + **只落一条**通话记录；
9. 降级演验：`IM_SFU_ENABLED=false` 重启 → 旧 mesh 链路（≤6 人）照跑；
10. 90 分钟浸泡测试 token 不断（§6.9）。

---

## 十、开工前需要你拍板的三件事

我给默认答案，你不反对就按这个做：

1. **范围**：只改群通话，1:1 与直播连麦不动。→ 推荐：**是**。
2. **默认人数上限**：视频 16 / 音频 50（阶段 3 压测后回填真实数字）。→ 推荐：**先按此设，压测后改**。
3. **SFU 部署位置**：与 Java 后端同机（局域网自测与中小部署够用），不单独起机器。→ 推荐：**同机**；跨公网/多租户时再谈第二节点。

另有一项**建议但可否决**：阶段 4 的录制。它不是「用 SFU」的必要条件，砍掉能让一期范围干净很多。

---

## 十一、工作量与顺序

| 阶段 | 内容 | 估算 | 前置 |
|---|---|---|---|
| 0 | 可行性验证（二进制 + 双路连通性） | 1 人日 | — |
| 1 | 后端（Properties / TokenService / REST / 删转发 / 闸门） | 2~3 人日 | 0 过 |
| 2 | 前端（`utils/livekit.js` + `groupcall.js` 内核替换 + Overlay） | 3~4 人日 | 1 过 |
| 3 | 部署（yaml / 证书 / 防火墙 / 进程托管 / Nginx 样例） + 三端联调 | 1~2 人日 + 半天联调 | 2 过 |
| 4 | 录制（可选，建议砍） | 1~2 人日 | 3 过 |
| 5 | 文档同步（§5 那张表，9 份文档） | 0.5~1 人日 | 全程随阶段推进 |

**总计约 8~11 人日（不含阶段 4）。** 其中**阶段 0 必须最先做完并出结论**——它花一天，但能直接判定整个方案的信令路线与可行性；跳过它写代码，就是在赌 §8 里那 5 条 ★ 项全都不成立。
