# im-admin-ui —— 管理后台前端

Vue 3 + Vite + Element Plus 的**独立 npm 工程**，对应后端的 `im-admin` 模块。
它**不参与 Maven 构建**（根 `pom.xml` 里没有它），和 `im-ui` 一样是纯前端目录。

一期三个页面（**数据看板 / 用户管理 / 举报工单**），二期加**角色管理**，三期再加四页
（**视频管理 / 直播管理 / 监控告警 / 日志管理**）。

---

## 1. 起步

```powershell
cd im-admin-ui
npm install
npm run dev        # http://127.0.0.1:5273
```

前提是后端已经在 `8080` 上跑着（`im-bootstrap` 的 `ImApplication`）。
浏览器打开上面那个地址，用**持有后台权限点的账号**登录（二期后四个细粒度点里任意一个就能进门，
或持老伞权限 `system:manage` 也算）；种子数据里就是 `admin` 角色那一个（账号密码见 `md/账号密码.txt`）。

构建与本地预览：

```powershell
npm run build      # 产物在 dist/
npm run preview    # http://127.0.0.1:4273
```

> `npm install` 需要能访问 npm 源。装不上时 `npm run build` 也一定跑不起来，
> 这不是本工程的问题——本 README 末尾的「本批验证状态」里如实记了当时的情形。

## 2. 为什么是**独立工程**而不是 `im-ui` 里的一个路由

| 考虑 | 结论 |
| --- | --- |
| 权限边界 | 后台接口按细粒度权限点鉴权（二期四点 `system:dashboard` / `system:user:manage` / `system:report:handle` / `system:role:manage`，三期再加 `system:video:manage` / `system:live:manage` / `system:monitor` / `system:log`），普通用户一个都不持、永远进不去；低权限角色（如 moderator）能进门但只看到自己那几页菜单。如果做成 `im-ui` 里的几个路由，聊天端的登录页、注册页、WebSocket、IndexedDB 消息库这些代码会一并打进后台用户手里的那个包，白下载几百 KB |
| 依赖 | `im-ui` 里有 `hls.js`、`onnxruntime-web`、`tesseract.js`、`ffmpeg` 相关的东西，后台一个都不需要 |
| 部署 | 后台应该只在内网可达、只监听 `127.0.0.1` 或内网域名；和面向用户的聊天端打成同一个包就没法分开加访问控制 |
| 登录态 | 两个工程各存各的 token（`im_token` / `im_admin_token`），互不干扰；同一台机器上开两个标签页各登一边不会出现「聊天端把后台身份也带上了」 |

代价是**两份要同步的公共件**：`api/request.js`、`utils/token.js`、`utils/format.js` 这三份在两个工程里
各有一份实现。它们都只有百来行，且行为差异是有意的（后台多拦一道 `1003`、不做 blob 持久缓存），
真要合并就得引入 workspace / monorepo 工具链——为三个小文件不值得，所以每个文件的头部注释里
都写清了「与另一侧的差异以及为什么」。

## 3. 端口、CORS 与代理（改一处要改三处）

本工程 dev 用 **5273**、preview 用 **4273**（`vite.config.js`，`strictPort: true`，端口被占直接报错不顺延）。
`application.yml` 的 `im.cors.allowed-origins` 里已经成对放了
`http|https://localhost|127.0.0.1:5273|4273` 这 8 条。

**改端口必须同时改 `vite.config.js` 与 `application.yml`**，只改一边会得到
「页面打得开、接口全 403 Invalid CORS request」——这个错在浏览器控制台里只表现为 CORS，
看不出是端口对不上。

代理只配了 `/api` 一条：后台是纯拉模型（看板与工单队列都靠刷新），
既不建 WebSocket 也不播 HLS，多两条代理只是多两个没人走的路径。

`server.host` 钉在 `127.0.0.1`：**后台不该在局域网里对任何设备敞开**。
真要临时给同事看效果，显式加 `npm run dev -- --host` 覆盖，不要把默认值改掉。

## 4. 登录链路（和聊天端不同的地方）

```
POST /api/auth/login  { loginType:'username', account, password, deviceId:'web' }
     → LoginVO { token, tokenName, expiresIn, deviceType, userInfo }
GET  /api/auth/me     → UserVO（含 roles / permissions），刷新页面后靠它恢复身份
POST /api/auth/logout
```

三条规矩：

1. **没有注册、没有短信 / 邮箱验证码登录、没有找回密码**。后台账号只能由管理员在库里加，
   把注册入口做进界面等于自己把门拆了。
2. **没有图形验证码**。后端 `AuthServiceImpl.login` 对账号密码这条路**根本不校验**
   `captchaKey / captchaCode`（那两个字段只用于「发短信、发邮件之前的闸门」）。
   照抄聊天端那块验证码图只会多一个没人校验的输入框。
3. **登录成功还要再验一道权限**：`userInfo.permissions` 里必须有 `*`、伞权限 `system:manage`，
   或八个细粒度点（二期四点 + 三期四点 `system:video:manage` / `system:live:manage` /
   `system:monitor` / `system:log`）里的**任意一个**（二期改的：低权限角色也该能进门，只是菜单少几项；
   三期把这四个新点也加进了 `ADMIN_PERMS`，否则只持新点的账号会被挡在登录页外），
   都没有就**不写 token**，直接停在登录页并提示「该账号没有管理后台权限」。
   如果先存 token 再清，中间那次跳转会让拦截器带着一个「有身份但没权限」的 token 去打管理接口，
   登录页上会闪出一串 `1003` 红条。

   ⚠ 这道闸门**故意比后端的 `@SaCheckPermission` 宽**（后端只认细粒度点、不认伞权限），
   为的是兼容历史配置。代价就是「菜单全可见、点进去全 1003」这种不对称 —— 存量库权限行
   停在对应批次之前时必现，修法见第 10 节。

会话中途权限被收回的情况由 `src/api/request.js` 兜：它除了 `1002`（未登录）之外
**还额外拦 `1003`（无权限）**，两种都清凭证回登录页。这一点和 `im-ui` 那份 request 是
唯一的行为差异。

## 5. 后端接口清单（前端严格对齐，一个都不自作主张）

| 页面 | 方法 | 说明 |
| --- | --- | --- |
| 看板 | `GET /api/admin/dashboard?refresh=false` | `refresh=true` 绕过服务端 60s 缓存真跑一遍聚合，**只能人手点，不做定时轮询** |
| 用户 | `GET /api/admin/user/page` | 入参 `keyword / status / current / size`，**没有排序参数**，口径固定注册时间倒序 |
| 用户 | `GET /api/admin/user/{userId}` | 单个用户的后台视角档案 |
| 用户 | `POST /api/admin/user/{userId}/disable?reason=…` | `reason` 是 **query 参数不是 JSON body**；必填（服务端校验），会即时踢掉该用户全部长连接 |
| 用户 | `POST /api/admin/user/{userId}/enable` | 恢复后不会自动重连，需要用户重新登录 |
| 工单 | `GET /api/admin/report/page` | 入参 `status / targetType / reasonCode / keyword / current / size` |
| 工单 | `GET /api/admin/report/{id}` | 含完整证据快照与本行可执行的处置动作 |
| 工单 | `POST /api/admin/report/{id}/accept` | 待处理 → 已受理，不写处理时间 |
| 工单 | `POST /api/admin/report/{id}/reject` | body `{ remark }`，只改状态不动对象 |
| 工单 | `POST /api/admin/report/{id}/handle` | body `{ action, remark }`，执行动作并置为已处置 |
| 角色 | `GET /api/admin/role/list` | 角色目录（id / code / name / 描述 / 持有权限点），只读 |
| 角色 | `GET /api/admin/role/user/{userId}` | 某用户当前挂着的角色 id 列表 |
| 角色 | `PUT /api/admin/role/user/{userId}` | body 只有 `{ roleIds }`（清空要显式传空数组），**整体替换**语义；三条护栏（拒改自己 / 拒摘最后一个可用管理员 / 拒未知或停用角色），保存后服务端 `evictCache` 即时生效 |
| 视频 | `GET /api/admin/video/page` | 入参 `keyword / status / uploaderId / current / size`；含已下架（status=2），固定发布时间倒序；关键词纯数字时额外认上传者 ID |
| 视频 | `POST /api/admin/video/{id}/offline?reason=…` | `reason` 是 **query 参数不是 JSON body**（与 disable 同一口径），选填但进服务端审计日志 |
| 视频 | `POST /api/admin/video/{id}/online` | 恢复上架，幂等 |
| 视频 | `DELETE /api/admin/video/{id}` | 逻辑删除且不可自助恢复，前端做了「敲『删除』两字」的二次确认 |
| 直播 | `GET /api/admin/live/page` | 入参 `keyword / status / anchorId / current / size`；含全部历史与已封禁场次，直播中恒排最前；**响应绝不含 streamKey** |
| 直播 | `GET /api/admin/live/{roomId}` | 单场档案，不存在回 11003 |
| 直播 | `POST /api/admin/live/{roomId}/stop` | 强制关播（原因固定 ban，无 body）；封禁是终态无解封；已结束场次幂等跳过，响应带回最新档案就地刷行 |
| 监控 | `GET /api/admin/monitor/overview` | JVM/HTTP/连接池/健康/慢接口一屏快照，取不到的指标为 `null`（前端渲染「—」）；手动刷新不做轮询 |
| 监控 | `GET /api/admin/monitor/slow` | 只回慢接口分组，比整屏便宜 |
| 日志 | `GET /api/admin/log/files` | 七个逻辑档的存在性/大小/修改时间；**前端只认 key 不传路径** |
| 日志 | `GET /api/admin/log/content?key=&tail=&keyword=` | 尾读；tail 服务端夹上限（`im.admin.log-tail-max-lines`），关键词只在尾读窗口内过滤（不是全文件搜索）、不下载 |
| 字典 | `GET /api/report/config` | 理由 / 对象类型 / 处置动作三份码表，**后台与用户侧共用这一个接口** |

各 `/api/admin/**` 控制器类上都是 `@SaCheckLogin` + `@SaCheckPermission(...)`，二期从伞权限
**细化为各自那一个点**：看板 `system:dashboard`、用户 `system:user:manage`、
工单 `system:report:handle`、角色 `system:role:manage`；三期再加视频 `system:video:manage`、
直播 `system:live:manage`、监控 `system:monitor`、日志 `system:log`（`system:manage` 仍不被任何控制器引用，
`system:log` 单独设点是因为日志原文可能含敏感内容，能看指标的人不必能看正文）。
`/api/report/**`（提交举报、我的举报）是**任何登录用户都能调**的用户侧接口，
后台界面不调它们——路径前缀就是那道权限边界。

## 6. 各页面在做什么

**数据看板**（`DashboardView.vue`）
分区数字来自各业务模块的 `AdminMetricsSpi`（用户 / 消息 / 直播 / 连接四个），
**模块没装配就少一个分区，而不是补一个 0**；`value` 为 `null` 显示成「—」而不是 0
（0 会被读成「今天真的没人注册」。）每格旁边那个小问号是后端给的**口径说明**（如
「仅统计今日零点起」「仅统计当前实例」），这句话是这份看板最重要的信息，别嫌它啰嗦。
趋势与理由分布用 `div` + `el-progress` 画，**没有引入 echarts**：7 根柱子和几条进度带
不值得为它多背几百 KB 的首包。定时轮询走缓存路径（`refresh=false`），间隔 60s 与后端缓存对齐。

**用户管理**（`UsersView.vue`）
找人（账号 / 昵称 / 手机号前缀）、看状态与积分、禁用与恢复。
`phone` 是后端脱敏后的值。`pendingReportCount` 那一列可以直接点到工单队列（带 `keyword=用户ID`）。
**没有代改资料与代改密码的入口**：后端就没有这两个端点（原因写在 `AdminUserController` 的类注释里，
要做得先补审计表）。当前登录的自己那一行不给「禁用」按钮——后端本来就会拒。

**举报工单**（`ReportsView.vue`）
筛选 + 队列 + 详情抽屉。抽屉里能看到**提交那一刻抓的证据快照**（`evidence.title / excerpt / meta`），
`meta` 的键是可直接显示的中文标签，后台只按键值对展示、**不按键取值**（要按键取值请改成结构化字段）。
处置下拉的选项来自 `allowedDispositions`：那是归属模块在提交时报上来的可执行动作集合，
所以不会出现「一个注定失败的按钮」；执行失败时工单停在原状态、原因原样回显，可以改了再重试。
`ban_user / recall_message / close_room` 三个动作会二次确认——它们直接作用于线上对象。

**角色管理**（`RolesView.vue`，二期新增）
只读的角色目录：每个角色发了哪几个后台权限点一目了然（前端不另拷一份角色→权限字典，
否则后端改种子就要两边对账）。真正**改**角色不在这一页，而在「用户管理 → 配角色」那一列的
对话框里（仅 `auth.can('system:role:manage')` 时渲染）：复选框整体替换，保存后就地刷那一行。

**视频管理**（`VideosView.vue`，三期新增）
筛选 + 表格 + 预览弹框（`<video>` 直接吃后端的受控访问地址，不是预签名短链）。三个动作：
下架（prompt 选填事由，进审计日志）/ 恢复上架 / 删除（敲「删除」两字才能确认——逻辑删除没有回收站，
删了就只能进数据库恢复）。就地刷那一行而不整页重查（删除除外）。

**直播管理**（`LivesView.vue`，三期新增）
只有「看」与「掐」：详情弹框展示单场档案（含公告这个主要文字证据），强制关播只对直播中的行给按钮、
`type: 'error'` 二次确认。已结束场次的「在线」列画「—」而不是定格值，避免把 peakOnline 口径读成实时在线；
streamKey 后端不下发、前端也不留字段位。

**监控告警**（`MonitorView.vue`，三期新增）
健康检查大卡片 + JVM/HTTP/连接池/慢接口四张指标卡 + 最近慢接口明细表。手动刷新不做轮询；
所有空值走「—」口径（`metric()` 统一兜），堆水位进 `el-progress`（≥90% 红、≥75% 黄）。

**日志管理**（`LogsView.vue`，三期新增）
左边七个逻辑档的清单（存在性/大小/修改时间，敏感档打标），右边尾读查看器（tail 下拉 + 窗口内关键词过滤，
黑底等宽字体区，行序旧→新与 `tail -f` 肌肉记忆一致）。没有任何路径输入口，选档只传 key。

## 7. 目录

```
im-admin-ui/
├─ index.html
├─ package.json                # 不参与 Maven，根 pom.xml 里没有这个模块
├─ vite.config.js              # 127.0.0.1:5273 / preview 4273 / 只代理 /api
└─ src/
   ├─ main.js                  # pinia → router → element-plus（顺序有讲究，见文件注释）
   ├─ App.vue                  # 只一层 router-view
   ├─ api/{request,auth,admin}.js
   ├─ components/AvatarCell.vue  # 头像要先换 blob 才能显示，逻辑收在这一个组件里
   ├─ layout/AdminLayout.vue
   ├─ router/index.js          # 守卫里 await /auth/me 确认权限（动态 import store 断循环）
   ├─ stores/auth.js           # 权限闸门在这一层，与 request.js 的 1003 处理成对
   ├─ styles/index.css         # 只放跨页面复用的少数几条
   ├─ utils/{token,format}.js
   └─ views/{Login,Dashboard,Users,Reports,Roles,Videos,Lives,Monitor,Logs}View.vue
```

## 8. 上线部署（还没做，步骤留在这）

一期只跑到本地 dev / preview，下面这些是真要对外时**必须**处理的：

1. **别用 vite preview 当生产服务器**。把 `dist/` 交给 nginx 或后端静态资源，
   并且要配 `try_files $uri /index.html`——`createWebHistory` 模式下刷新 `/users` 会 404。
2. **同源部署**。本工程只请求相对路径 `/api`，所以部署时后台页面必须和后端在同一个域名下，
   否则就要回到「改 `axios` 的 `baseURL` + 把新 origin 加进 CORS 白名单」这条路。
   推荐放在内网域名的 `/admin/` 子路径下（那样 `createWebHistory('/admin/')` 要传 base）。
3. **加访问限制**。真正的门是后端的四个细粒度权限点（外加向后兼容的伞权限 `system:manage`），但网络层的内网白名单、
   登录失败次数限制、以及「后台只对运维网段开放」仍然是该有的第二层。
4. **`admin` 账号的默认密码必须改**。种子数据里的口令在 `md/账号密码.txt` 里是明文可见的，
   公网部署还用它等于把后台钥匙贴在门上。

## 9. 本批验证状态（如实记录）

- **三期构建已实测通过**（2026-10-21）：`npm run build` EXIT=0（built in 9.59s），且产物里
  `VideosView/LivesView/MonitorView/LogsView` 四个新 chunk 都在；后端 `mvn -q -DskipTests compile`
  全仓 EXIT=0（含 im-admin 新增的 actuator/prometheus 依赖与 im-live 两个 SPI 实现）。
  这只能证明代码能编译打包、模板指令与 import 不报错。
- 一期时的构建记录（供对照）：`npm install` 成功，`npm run build` EXIT=0（built in 8.86s，主包
  `dist/assets/index-*.js` 1,189.29 kB / gzip 382.81 kB）。
- **仍然没有做浏览器实测**：登录、看板数字、举报处置二次确认、三期四页的交互路径，
  都需要跑着的后端 + 已执行 `sql/migration_admin_phase3.sql` 并清过权限缓存的库，
  本批不具备这个条件。接口形状是照 Controller / VO 源码抄的，不是猜的。
- 已知会踩的第一个坑（一期记的那条至今有效）：如果 `sql/migration_report.sql` 没跑就直接启动后端，
  登录仍正常，但一进后台就会看到「Table 'im_db.im_report' doesn't exist」——
  那张表只有 `im-admin` 用，别的模块不受影响。

## 10. 已知坑：登录提示成功、四个菜单全部可见、点进去全部 1003

这是存量库升级到二期后的典型现场，成因在第 4 节那条 ⚠（前端闸门比后端宽）：
库里还停在二期之前的权限行（实测就是 `im_role` 2 行 / `im_permission` 只到 107 /
`im_role_permission` 12 行）时，admin 手里只有伞权限 `system:manage`；
而 Sa-Token 的匹配是**按字符串精确命中**，`system:manage` 不是层级通配（只有 `*` 才是），
于是四个细化后的端点全部回 `code=1003` —— 后端拦得住，前端因为额外认伞权限所以拦不住。

**种子文件里 `admin` 一直是齐的**（`sql/im_data.sql`：`im_user` 1000 + `im_user_role` (5001,1000,1)
+ role 1 绑 101~111 全十一点），所以这个坑不需要改种子，需要的是给**已经跑着的库**补行：

```powershell
# 1) 先判断要不要跑（只读）：回 2 / 7 / 12 就是二期前的库，回 4 / 11 / 21 则无需再跑
mysql -u root -p --default-character-set=utf8mb4 im_db -e "SELECT (SELECT COUNT(*) FROM im_role) roles,(SELECT COUNT(*) FROM im_permission) perms,(SELECT COUNT(*) FROM im_role_permission) binds"

# 2) 备份（只涉 4 张表，不必全库）
mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role im_role_permission im_user_role > backup_rbac.sql

# 3) 执行补丁（全 INSERT IGNORE，可重跑；脚本末尾自带只读核对 SELECT）
mysql -u root -p --default-character-set=utf8mb4 im_db -e "source sql/migration_admin_phase2.sql"

# 4) ★ 必做：清一次 Redis 权限缓存，否则最长 30 分钟看起来像「补了没生效」
redis-cli DEL "im:auth:perm:1000" "im:auth:role:1000"
```

第 4 步不是可选项：`PermissionProviderImpl` 把权限码按用户缓存在 `im:auth:perm:{userId}`（TTL 30 分钟），
而它是「命中就直接返回、未命中才回源」，`AuthServiceImpl.logout` 又不碰这两个键 ——
所以**「退出再登录」不管用**（只有走后台「用户管理 → 配角色」保存时服务端才会 `evictCache`）。
不必重启后端。验收：刷页面重跑 `/auth/me`，`permissions` 应当从 7 个串变 11 个，四个页面不再报无权限。

**三期同一坑的新版本**：库里停在二期数据（4 / 11 / 21）时，老四页正常、四个新页全 1003。
修法是再跑 `sql/migration_admin_phase3.sql`（权限点 112~115 + admin 绑定 6012~6015，全
`INSERT IGNORE` 可重跑），跑完同样**必须清 `im:auth:perm:*` / `im:auth:role:*`**；验收数字变成
4 / 15 / 25，`/auth/me` 回 15 个权限串。
