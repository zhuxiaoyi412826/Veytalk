# APP 端对接指南：扫码登录 + 文件传输助手

> 面向对象：im-android（原生安卓）开发。
> 本文只描述 **APP 端要做什么**，后端接口与网页端（`/filehelper`、`/transfer`）均已实现，APP 按此契约对接即可。
> 涉及两件事：
> 1. **扫码登录**：APP 扫电脑网页上的二维码，确认后让网页免密登录。
> 2. **文件传输助手**：APP 与电脑之间互传文件（对标微信「文件传输助手」）。

---

## 0. 通用约定（所有接口都适用）

| 项 | 说明 |
|---|---|
| Base URL | 后端根地址，例：`http://<host>:8080`。下文路径都带 `/api` 前缀。 |
| 认证头 | 已登录接口需带请求头 `satoken: <APP当前登录token>`（头名以后端下发的 `tokenName` 为准，默认 `satoken`）。 |
| Content-Type | JSON 接口 `application/json`；上传接口 `multipart/form-data`。 |
| 统一响应体 | 所有接口都返回 **HTTP 200** + JSON 信封：`{ "code": 200, "success": true, "data": ..., "message": "...", "traceId": "..." }`。判断成功只看 `code == 200`（或 `success == true`），**不要用 HTTP 状态码判断业务成败**。 |
| ID 精度 | `conversationId`、`fileId`、`userId` 等是雪花 ID（19 位）。**一律按字符串解析**，不要用 32 位 int 或在 JS/JSON 数字里丢精度。 |
| 未登录/失效 | `code == 1002`（登录状态已失效）、`code == 2010`（被顶下线）→ APP 应跳登录。`code == 1003` 无权限。 |

常用错误码：

| code | 含义 |
|---|---|
| 200 | 成功 |
| 1001 | 参数有误（含「二维码已失效」这类业务校验） |
| 1002 | 未登录 / 登录失效 |
| 1003 | 没有操作权限 |
| 5007 | 当前状态下无法发送消息 |
| 7005 | 无权访问该文件 |

---

# 第一部分：扫码登录

## 1.1 整体流程（时序）

```
电脑网页(/filehelper)                后端                     APP(已登录)
      |                               |                         |
      |  POST /auth/qr-login/create   |                         |
      |------------------------------>|  生成 scene + 二维码     |
      |  <----- {scene, qrcode} ------|                         |
      |  展示二维码                    |                         |
      |                               |<--- 用户用APP扫码 ------|  ① 扫一扫，解析出 scene
      |                               |<--- POST /qr-login/scanned  ② 标记已扫码(可选)
      |  GET /auth/qr-login/status    |                         |
      |<--------- status=1 已扫码 ----|                         |  ③ APP 弹「确认登录」页
      |  (网页显示"已扫码,请在手机确认") |                         |
      |                               |<--- POST /qr-login/authorize  ④ 用户点确认
      |                               |     为 web 设备签发 token |
      |  GET /auth/qr-login/status    |                         |
      |<---- status=2 + token(一次性) -|                         |  ⑤ 网页拿到登录包，自动登录
      |                               |                         |
```

- **网页负责出码和轮询**（`create` / `status`），这两个接口 APP 用不到。
- **APP 只负责两件事**：扫码后调 `scanned`（可选）、用户确认后调 `authorize`。这两个接口**需要 APP 处于登录态**。

## 1.2 识别「登录二维码」

网页展示的二维码内容是一段文本 URL，格式固定为：

```
{网页站点根}/transfer?scene={scene}
```

例：`https://im.example.com/transfer?scene=8f3c1a2b9d4e4f6a7b8c9d0e1f2a3b4c`

APP 扫一扫拿到文本后，判定规则：

1. 能解析成 URL，且 **query 里含 `scene` 参数**；
2. 且 **路径是 `/transfer` 或 `/filehelper`**（网页版可能扩展，两者都按登录码处理）；
3. 命中 → 走「扫码登录」流程，取出 `scene` 值；
4. 不命中 → 交给其它扫码逻辑（如群邀请链接是别的路径，不在本文范围）。

> `scene` 是这次登录会话的唯一标识，也是安全密钥。APP 只需把它原样回传给后端，不需要理解其内容。

## 1.3 接口①：标记已扫码（可选但推荐）

用户扫到有效登录码后，先调它，让网页从「待扫码」变成「已扫码，请在手机确认」。**跳过不调也能工作**，只是网页停在「待扫码」态，体验差一点。

```
POST /api/auth/qr-login/scanned
Header: satoken: <APP登录token>
Body:   { "scene": "8f3c1a2b9d4e4f6a7b8c9d0e1f2a3b4c" }

Resp:   { "code": 200, "success": true, "data": null, "message": "已扫码，请在手机确认" }
```

- `scene` 不能为空。
- 若二维码已过期/不存在：`code 1001`，`message` 形如「二维码已失效，请刷新后重新扫码」→ APP 提示「二维码已失效，请让对方刷新」并退出确认页。

## 1.4 接口②：确认授权登录

用户在 APP 上点「确认登录」后调它。后端会以**当前 APP 登录用户**的身份，为网页签发一个 `web` 设备的登录令牌。

```
POST /api/auth/qr-login/authorize
Header: satoken: <APP登录token>
Body:   { "scene": "8f3c1a2b9d4e4f6a7b8c9d0e1f2a3b4c" }

Resp:   { "code": 200, "success": true, "data": null, "message": "授权成功" }
```

要点：

- **请求体里没有 userId**，被授权的账号 = 当前 APP 登录账号，无法替他人授权。
- 一个 `scene` 只能授权一次；重复授权返回 `code 1001`「该二维码已授权，请刷新后重试」。
- 授权成功后 APP 无需做别的——网页下一次轮询就会拿到登录包并自动登录。
- **用户点「取消」**：APP 没有对应的拒绝接口，直接不调 `authorize` 即可，网页二维码会在 120 秒后自动过期。

## 1.5 APP 端确认页要展示的要素

扫码成功后，建议弹一个确认页（而不是静默授权），至少显示：

- 文案：「已检测到网页登录请求，确认后将登录该网页」；
- 当前账号头像 + 昵称（让user确认是自己）；
- 「确认登录」/「取消」两个按钮；
- 可选：提示「不是你本人扫码？请取消」。

## 1.6 安全与边界（务必遵守）

1. `authorize` 前**必须**有用户显式点击确认，禁止扫到就静默授权。
2. 只处理含 `scene` 且路径为 `/transfer`、`/filehelper` 的二维码，避免被伪造链接诱导。
3. APP 自身未登录时，扫到登录码应引导先登录 APP。
4. 二维码有效期 120 秒，过期后 `scanned`/`authorize` 都会报 `1001`，据此提示刷新。

---

# 第二部分：文件传输助手

## 2.1 它是什么

「文件传输助手」是一个**系统保留账号**：

| 字段 | 值 |
|---|---|
| userId | `2`（固定小整数，不是雪花） |
| username | `filehelper` |
| 昵称 | `文件传输助手` |
| 性质 | `im_user` 表里真实存在的一行，可加为好友、可收发单聊消息；密码是哨兵值，**无法被登录**，只作为「消息对端」存在 |

**核心模型**：它就是「你自己和 userId=2 的一个单聊会话」。
- 你在 APP 里发给它的文件，登录同一账号的电脑网页能收到并下载；
- 电脑网页上传的文件，你在 APP 这个会话里能看到并下载。
- 网页端和 APP 端是**同一个用户**，所以会话里的消息都是「自己发的」——这与微信文件传输助手「自己的跨设备中转站」语义一致。

> **网页版能力范围**：`/filehelper` 网页版现已支持 **文字 + 图片 + 文件 + 语音** 四类互传（不止文件）。APP 发的文字/语音会出现在网页列表里，网页发的文字/语音 APP 也能收到——全部走下面同一套 `msgType` 契约，APP 侧无需区分对端是网页还是自己。

因此 APP 端**不需要新造一套文件传输功能**，只需要：
1. 提供进入「文件传输助手」这个单聊的入口；
2. 保证该好友/会话存在（新用户注册已自动加，存量用户见 2.5）；
3. 用**已有的单聊 + 文件上传 + 消息发送**链路收发文件。

## 2.2 打开 / 定位助手会话

进入时先拿到会话 ID（幂等，没有会自动创建）：

```
POST /api/conversation/assistant
Header: satoken: <APP登录token>

Resp:   { "code": 200, "data": 123456789012345678 }   // data = conversationId（按字符串处理）
```

拿到 `conversationId` 后，直接进普通单聊界面即可。

> 也可以不预先建会话：发消息时直接 `toUserId=2`，服务端会自动定位/创建单聊（见 2.3）。但先用 `/assistant` 拿到 conversationId 更稳，便于加载历史。

## 2.3 发送一个文件到助手（两步）

### 第 1 步：上传文件

小文件（≤ 5MB）直接单请求上传：

```
POST /api/file/upload?bizType=chat_file
Header: satoken: <APP登录token>
Body(multipart): file=<二进制>      // 字段名必须是 file

Resp: {
  "code": 200,
  "data": {
    "fileId": 987654321098765432,
    "originalName": "报告.pdf",
    "size": 204800,
    "sizeText": "200.0 KB",
    "contentType": "application/pdf",
    "ext": "pdf",
    "url": "/api/file/download/987654321098765432",   // 长期受控地址，存进消息用这个
    "signedUrl": "http://.../...?ticket=...",          // 带短时票据，可直接预览，勿落库
    "image": false
  }
}
```

- `bizType` 取值：`chat_file`（普通文件）、`chat_image`（图片）、`chat_voice`（语音，需带 `duration`）。传文件用 `chat_file`。
- 需要 `file:upload` 权限（普通注册用户默认有）。
- **大文件（> 5MB）走分片上传**：`POST /api/file/upload/init` → 逐片 `POST /api/file/upload/chunk` → `POST /api/file/upload/merge`（字段与逻辑同网页端，命中秒传时 `init` 直接返回 `uploaded=true` 的文件信息）。若 APP 已有文件上传封装，复用即可。

### 第 2 步：发送文件消息

```
POST /api/message/send
Header: satoken: <APP登录token>
Body: {
  "clientMsgId": "app-uuid-唯一-重发保持不变",   // 幂等键，必填
  "conversationId": 123456789012345678,          // 二选一定位：conversationId 优先
  "msgType": 3,                                   // 3=文件；2=图片；4=语音
  "content": "987654321098765432",                // 附件类消息：content 放 fileId(字符串)
  "extra": {                                      // 会被服务端按文件记录回填覆盖，可传可不传，传了用于本地即时渲染
    "fileId": 987654321098765432,
    "fileName": "报告.pdf",
    "fileSize": 204800,
    "contentType": "application/pdf",
    "ext": "pdf",
    "fileUrl": "/api/file/download/987654321098765432"
  }
}

Resp: { "code": 200, "data": { ...MessageVO... } }
```

定位会话二选一（优先级）：`conversationId` > `toUserId`（填 `2`）。若不想先调 `/assistant`，这里直接 `toUserId: 2` 也能发。

消息类型对照：

| msgType | 含义 | bizType | content | extra 关键字段 |
|---|---|---|---|---|
| 1 | 文本 | - | 正文 | - |
| 2 | 图片 | chat_image | fileId | width/height（APP 自己读出来传，服务端不回填尺寸） |
| 3 | 文件 | chat_file | fileId | fileName/fileSize/contentType/ext/fileUrl |
| 4 | 语音 | chat_voice | fileId | duration |

> `clientMsgId` 是幂等键：同一条消息重传（网络失败重试）必须用同一个 ID，服务端会去重。

## 2.4 接收 / 查看内容（含从电脑传来的文字 / 图片 / 文件 / 语音）

电脑网页发来的内容（文字、图片、文件、语音），会以**你自己发出的消息**出现在同一个助手会话里。APP 有两种方式拿到：

1. **实时**：APP 已连接的聊天 WebSocket 会推来这条消息（与普通单聊一致，走你现有的消息接收逻辑）。
2. **拉历史**：进入会话时拉取：

```
GET /api/message/history?conversationId={cid}&size=20
GET /api/message/history?conversationId={cid}&beforeSeq={最小seq}&size=20   // 向上翻页
```

返回按 `seq` 升序的 `MessageVO` 列表；筛选 `msgType` 为 2/3/4 的即为文件/图片/语音。

**下载文件**（APP 能带登录头，比浏览器简单）：

```
GET /api/file/download/{fileId}
Header: satoken: <APP登录token>
→ 直接返回文件字节流（Content-Disposition: attachment）
```

或先换直链：`GET /api/file/{fileId}/url` 返回带票据的临时地址，再用系统下载器/浏览器打开。

## 2.5 存量用户与好友关系

- **新注册用户**：后端注册钩子已自动把「文件传输助手」(id=2) 加为好友，无需 APP 处理。
- **老用户（功能上线前注册的）**：好友关系靠运维执行 `sql/im_system_contact_patch.sql` 回填。若某账号尚未回填，APP 直接发消息给 `toUserId=2` 时，服务端 `createSingle` 会校验好友关系——**可能报无权限**。稳妥做法：进入助手时先调 `POST /api/conversation/assistant`（它用 `getOrCreateSingle`，比严格的好友校验更宽松，能懒建会话），拿到 conversationId 再收发。

## 2.6 APP 端入口建议

- 在「通讯录 / 好友列表」里，「文件传输助手」会作为一个普通好友出现（昵称「文件传输助手」）。点击进入即可。
- 建议在首页或侧栏额外放一个**固定「文件传输助手」快捷入口**（对标微信），点击 → 调 `/api/conversation/assistant` → 进会话。
- 「扫一扫」入口需支持识别第一部分的登录二维码。

---

## 3. 接口清单速查

| # | 方法 | 路径 | 登录 | 用途 | 归属 |
|---|---|---|---|---|---|
| A1 | POST | `/api/auth/qr-login/scanned` | 是 | 标记已扫码 | 扫码登录 |
| A2 | POST | `/api/auth/qr-login/authorize` | 是 | 确认授权，签发网页登录态 | 扫码登录 |
| B1 | POST | `/api/conversation/assistant` | 是 | 幂等获取助手单聊 conversationId | 文件传输助手 |
| B2 | POST | `/api/file/upload` | 是 | 上传小文件（≤5MB），返回 fileId/url | 文件传输助手 |
| B2b | POST | `/api/file/upload/init` `/chunk` `/merge` | 是 | 大文件分片/秒传/断点续传 | 文件传输助手 |
| B3 | POST | `/api/message/send` | 是 | 发送文件消息（msgType=3, content=fileId） | 文件传输助手 |
| B4 | GET | `/api/message/history` | 是 | 拉助手会话历史，筛出文件/图片 | 文件传输助手 |
| B5 | GET | `/api/file/download/{fileId}` | 是(带satoken头) | 下载文件字节流 | 文件传输助手 |
| B5b | GET | `/api/file/{fileId}/url` | 是 | 换取带票据的临时直链 | 文件传输助手 |

> 网页端另有 `POST /api/auth/qr-login/create` 与 `GET /api/auth/qr-login/status` 两个匿名接口，是**网页**出码/轮询用的，**APP 不需要调用**。

---

## 4. APP 开发自检清单

**扫码登录**
- [ ] 扫一扫能解析出 `scene`，并只对 `/transfer`、`/filehelper` 路径的链接走登录流程
- [ ] 扫码后调 `scanned`（或至少直接进确认页）
- [ ] 确认页展示当前账号，用户**显式点击**后才调 `authorize`
- [ ] 处理二维码过期（`code 1001`）→ 提示对方刷新
- [ ] APP 未登录时扫登录码 → 先引导登录

**文件传输助手**
- [ ] 有固定入口，点击先调 `/api/conversation/assistant` 拿 conversationId
- [ ] 发文件：先 `/api/file/upload`（大文件走分片）拿 fileId，再 `/api/message/send`（msgType=3，content=fileId）
- [ ] 图片用 msgType=2 / bizType=chat_image，并上传前读出 width/height 放进 extra
- [ ] 会话内能渲染文件卡片并调 `/api/file/download/{fileId}` 下载（带 satoken 头）
- [ ] 收到网页传来的文件（同一会话、自己发的）能正常展示
- [ ] 所有 ID（conversationId/fileId/userId）按字符串处理，避免精度丢失

---

## 5. 最小可跑示例（curl，便于后端联调自测）

```bash
BASE=http://localhost:8080
TOKEN=<APP登录后的satoken>

# 扫码授权（APP 侧）
curl -X POST "$BASE/api/auth/qr-login/authorize" \
  -H "satoken: $TOKEN" -H "Content-Type: application/json" \
  -d '{"scene":"把二维码里的scene值填这里"}'

# 定位助手会话
curl -X POST "$BASE/api/conversation/assistant" -H "satoken: $TOKEN"
# → 记下返回的 data 作为 CID

# 上传一个文件
curl -X POST "$BASE/api/file/upload?bizType=chat_file" \
  -H "satoken: $TOKEN" -F "file=@./test.pdf"
# → 记下 data.fileId 作为 FID、data.url 作为 FURL

# 发送文件消息到助手
curl -X POST "$BASE/api/message/send" \
  -H "satoken: $TOKEN" -H "Content-Type: application/json" \
  -d "{\"clientMsgId\":\"c-$(date +%s)\",\"conversationId\":\"CID\",\"msgType\":3,\"content\":\"FID\",\"extra\":{\"fileId\":\"FID\",\"fileUrl\":\"FURL\"}}"

# 下载
curl -o out.pdf "$BASE/api/file/download/FID" -H "satoken: $TOKEN"
```
