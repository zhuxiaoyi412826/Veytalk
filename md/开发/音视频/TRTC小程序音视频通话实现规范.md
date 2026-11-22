# TRTC 小程序音视频通话实现规范

> 适用端：**仅微信小程序**。
> 信令复用现有 IM WebSocket，媒体走腾讯云 TRTC；Web / 桌面 / Android 仍走自建 WebRTC，两条链路并存、互不替代。
>
> **当前仓库状态**：小程序端通话页已实现；后端签名接口与 TRTC 配置项**已按要求回滚，不在仓库内**。本文第三节是「后端要如何实现」的口径与参考实现，落地时按此单独实现并部署。

---

## 一、硬边界（先看这一节，能省掉多数无效排查）

| 约束 | 说明 |
|---|---|
| **只能小程序 ↔ 小程序** | 媒体在 TRTC 房间内交换，Web/App 端的 WebRTC 不进这个房间，通话必然单向无声无画 |
| **必须开通直播类目** | 小程序后台未开「直播」类目时，`live-pusher` / `live-player` 不渲染，表现为黑屏且**无任何报错** |
| **同一 userId 同房间只保留一路** | 同一账号多端同时进房会互相踢掉，不做多端同房间合流 |
| **SecretKey 只在服务端** | 前端只拿到 `userSig`，任何情况下都不得下发或内置 SecretKey |
| **未配置即可用性降级** | `sdk-app-id=0` 时接口正常返回、前端禁用通话按钮，不抛异常 |

---

## 二、链路总览

```
主叫                                    服 务端                              被叫
 │  POST /api/call/config（查开关）        │                                   │
 │  WS  call/invite ───────────────────▶ │ ────────────── call-signal ─────▶ │
 │  GET /api/call/trtc-user-sig ───────▶ │ 签发 UserSig                      │
 │  live-pusher ──┐                      │                                   │
 │                └──▶ TRTC 房间（roomId 由 callId 派生） ◀── live-player ◀── │
 │  WS  call/hangup ───────────────────▶ │ ────────────── call-signal ─────▶ │
```

分工：

| 职责 | 归属 |
|---|---|
| 开关下发、UserSig 签发 | 后端（`im-websocket`） |
| 房间号派生、推拉流地址、进/退房 | 小程序（`pages/call`） |
| invite / accept / hangup 信令 | 现有 IM WebSocket（`call` / `call-signal`） |

---

## 三、后端实现

### 3.1 配置项

`application.yml`（默认值，可被外挂配置或环境变量覆盖）：

```yaml
im:
  call:
    trtc:
      sdk-app-id: ${IM_TRTC_SDK_APP_ID:0}
      secret-key: ${IM_TRTC_SECRET_KEY:}
      user-sig-expire-seconds: 604800
```

- 生产环境写 `Docker/app/extra/application-prod-extra.yml`，键层级为 `im.call.trtc`，**值不要用反引号包裹**。
- 环境变量：`IM_TRTC_SDK_APP_ID`、`IM_TRTC_SECRET_KEY`。
- **改完必须 `docker compose restart im-server`**（Java 仅在启动时读取该文件，`up -d` 未改镜像不会重读）。

### 3.2 UserSig 签发

实现类：`im-websocket/src/main/java/com/im/websocket/util/TLSSigAPIv2.java`，对齐腾讯云官方 TLS 2.0 算法，仅用 JDK 标准库（`javax.crypto.Mac` + `Deflater` + `Base64`），不引入第三方 SDK。

四步，顺序不可变：

1. 拼签名原文（**字段名与换行必须逐字一致**）：
   ```
   TLS.identifier:{userId}\n
   TLS.sdkappid:{sdkAppId}\n
   TLS.time:{currTime}\n
   TLS.expire:{expireSeconds}\n
   ```
2. 以 `secretKey` 为密钥做 **HMAC-SHA256**，结果做标准 Base64，得到 `TLS.sig`。
3. 组装 JSON：`TLS.ver`（固定 `"2.0"`）、`TLS.identifier`、`TLS.sdkappid`、`TLS.expire`、`TLS.time`、`TLS.sig`。
4. JSON 做 **zlib deflate** 压缩，再 Base64，并做三处字符替换：`+ → *`、`/ → -`、`= → _`。

约定：

- `TLS.identifier` 用用户雪花 ID 的**字符串**形式，全链路不做数值转换。
- 每次请求现签，不缓存；签名本身不敏感，过期后前端重新拉取即可。
- 签名有效期默认 7 天，覆盖一次通话绰绰有余；不要为了「省一次请求」把 TTL 拉满。

### 3.3 接口契约

```http
GET /api/call/trtc-user-sig
需要：登录态（satoken 请求头）
```

已配置时的响应：

```json
{
  "code": 200,
  "data": {
    "sdkAppId": 1600134873,
    "userId": "1234567890123456789",
    "userSig": "eJyrVkrLz1eyUlAq..."
  }
}
```

未配置时的响应（**不是错误**）：

```json
{ "code": 200, "data": { "sdkAppId": 0, "userId": "...", "userSig": "" } }
```

实现约定：

- 该端点是**唯一**新增的后端 REST 接口，签完即返回，不落库、不审计。
- 不要把它塞进 `/api/call/config`：Web/桌面端根本用不到，没必要每次多取一份签名。
- 不得在响应里回传 `secretKey`、`sdkAppId` 以外的任何密钥材料，也不得提供“前端自算签名”的备用路径。

### 3.4 服务端实现清单（新增模块照此扩展）

1. `ImProperties.Call.Trtc`：`sdkAppId` / `secretKey` / `userSigExpireSeconds` 三个字段，默认 `0` / `""` / `604800`。
2. `TLSSigAPIv2`：构造入参 `(sdkAppId, secretKey)`，方法 `genUserSig(userId, expireSeconds)`。
3. `TrtcUserSigVO`：`sdkAppId` / `userId` / `userSig`。
4. `CallController#trtcUserSig()`：读配置 → 未配置直接返回空 VO → 已配置则现签。
5. 配置模板同步到 `Docker/app/extra/application-prod-extra.example.yml`（占位符，不写真实密钥）。

---

## 四、小程序端职责

### 4.1 房间号派生

`roomId` 不通过网络透传，由 `callId` 本地哈希得出，主被叫各算一次必然一致：

```ts
let hash = 0
for (const ch of callId) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
const roomId = (hash % 2000000000) + 1   // 必须落在 1 ~ 4294967295
```

### 4.2 推拉流地址

```
room://cloud.tencent.com/rtc?sdkappid={sdkAppId}&roomId={roomId}&userId={userId}&userSig={userSig}
```

- 推流：`userId` 填**自己**。
- 拉流：`userId` 填**对端**，且须在对端回 `accept` 之后才开始拉。

### 4.3 组件与属性

| 组件 | 关键属性 |
|---|---|
| `live-pusher` | `url`、`mode='RTC'`、`autopush`、`enableMic`、`enableCamera`（音频通话置 `false`）、`muted` |
| `live-player` | `src`、`mode='RTC'`、`autoplay`、`soundMode`（听筒/扬声器） |

权限：`scope.camera`、`scope.record` 需在小程序后台与 `app.config.ts` 同时声明。

---

## 五、部署步骤

```bash
# 1) 本机构建（Windows 同样适用）
mvn -pl im-bootstrap -am package -DskipTests
# 产物：im-bootstrap/target/im-server.jar

# 2) 上传
scp im-bootstrap/target/im-server.jar <user>@<host>:/opt/im-docker/app/im-server.jar

# 3) 服务器重建容器（tag 换号，便于回滚）
cd /opt/im-docker
sed -i 's|^IM_SERVER_TAG=.*|IM_SERVER_TAG=local/im-server:1.0.1|' .env
docker compose build im-server && docker compose up -d im-server
docker compose ps

# 4) 填 TRTC 配置到 app/extra/application-prod-extra.yml 后
docker compose restart im-server
```

验证顺序：接口存在（带 token 不再返回 `1004`）→ 返回 `sdkAppId > 0` → 小程序进房出画面出声。

---

## 六、排错对照表

| 现象 | 原因 | 处理 |
|---|---|---|
| `请求的资源不存在`（code `1004`） | 线上后端未部署 `/api/call/trtc-user-sig` | 按第五节换 jar 重建 |
| `sdkAppId=0` / 提示“通话服务未配置” | 配置没读到 | 检查 `im.call.trtc` 层级与缩进、是否 `restart` 而非仅 `up -d` |
| 调试器里 `ApiError: 请求的资源不存在` | 同上，是业务码 1004，不是 HTTP 404 | 同上 |
| 进房成功但黑屏 | 未开通直播类目，或 `live-pusher` 未被渲染 | 开通类目后重新编译小程序 |
| 一方有画面一方没有 | 对端未回 `accept` 就拉流，或 `userId` 填错 | 检查信令时序与推/拉流各自的 `userId` |
| 小程序与 Web/App 通话无声 | 跨端不互通 | 只能小程序 ↔ 小程序 |
| 通话被踢 | 同账号多端进同一房间 | 退出其他端 |

---

## 附录：堆栈里的 `18148` 不是端口

真机报错栈形如：

```
at Generator.next (https://usr/app-service.js:18088:25)
at n (https://usr/app-service.js:17834:21)
```

`app-service.js:18148:55` 是 **Taro 编译产物中的「行号:列号」**，用于在打包后的 JS 里定位代码，与端口无关。

本项目的实际端口只有：

| 用途 | 端口 |
|---|---|
| HTTPS / WSS 对外 | `443`（nginx 终止 TLS） |
| nginx 反代到后端 | `8080`（仅内网） |
| TRTC 媒体 | 不经过本服务，走腾讯云 RTC 服务 |
