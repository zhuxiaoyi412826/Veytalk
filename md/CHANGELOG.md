# 更新日志 CHANGELOG

本文件记录 **IM 即时通讯系统** 的所有重要变更（代码、配置、文档均含）。

**约定（务必遵守）**：

- 从现在起，**本项目每一次更新都要在本文件里留一条描述**，无论改动大小。
- 采用 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 风格，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
- 变更按类型分组：`新增 Added` / `变更 Changed` / `修复 Fixed` / `移除 Removed` / `废弃 Deprecated` / `安全 Security`。
- 每条写清「改了什么、为什么、影响范围」，涉及文件时给出路径。
- **最新的版本放在最上面**；未发布的改动先归到顶部的 `未发布 Unreleased` 区。

---

## 未发布（Unreleased）

### 新增 Added

- 无。

### 变更 Changed

- **`README.md`「五、数据库初始化」**：由「两个脚本按顺序执行即可」一句话扩写为直观结构——新增脚本对照表（作用 / 规模 / 重跑安全性）、执行步骤（含凭据设置）、**22 张表按 9 个模块归类清单**、以及 `im_data.sql` 演示数据明细（3 账号 / 2 角色 / 7 权限点 / 12 绑定 / 一对好友 / 1 会话 3 消息 / 4 场面试会话），并点明其余 8 张表建好即空。表清单与真实库 `information_schema` 核对一致（22 = 22，无漂移）。
- **`md/已踩坑.md`**：新增第 11 条，记录 `im_schema.sql` 的 DROP 块漏掉 `im_live_room` 导致重跑中断的坑（症状 / 根因 / 修复模式 / 可复用边界），与本次 Fixed 项呼应。
- **`README.md`「六、启动后端」**：把原本混在一个代码块里的两套流程拆为「方式 A：打 jar 运行（仓库根目录 `mvn clean package` + `java -jar`）」与「方式 B：开发期热迭代（`im-bootstrap/` 下 `mvn -f ..\pom.xml install` + `mvn spring-boot:run`）」，并把「为何必须先 install」与「运行中不要加 clean」提为独立引用块。命令与 `im-bootstrap/pom.xml`（finalName=im-server、spring-boot-maven-plugin）、`application.yml`（server.port 默认 8080）核对一致。
- **`README.md`「七、启动前端」**：顶部新增「开发 dev server / 生产构建 / 桌面客户端」三形态总览表，dev 启动补上「有证书自动切 https」「`npm run dev:lan` 强制回落 http 联调」两条说明，与 `vite.config.js` 的 `https: mode==='lan'?undefined:devHttps()` 一致。
- **`README.md`「六、启动后端」**：新增子节「直播媒体服务器（可选，仅用直播时启动）」，给出 `node electron/live-media-server.js`（监听 0.0.0.0:8088、`PORT` 可覆盖）启动命令，并说明它是直播媒体面的本机自测替身（生产换 Nginx）、与 Java 后端是两个独立进程、不启动不影响其它功能。命令与端口、落盘路径、`im.live` 配置对应关系均核 `electron/live-media-server.js` 源码一致。
- **`README.md`「九、接口一览」**：模块前缀表补上遗漏的直播 `/api/live`（类级 `@SaCheckLogin`）；修正错误码描述——「权限不足 code=403」实为 `ResultCode.FORBIDDEN` = **1003**（`GlobalExceptionHandler` 映射 `NotPermissionException`），「8xxx WS 票据」改为官方名「8xxx 长连接」，并注明直播无独立错误码段（复用 1xxx）。均核 `ResultCode.java` / `GlobalExceptionHandler.java` / `LiveController.java` 属实。
- **`README.md`「十、WebSocket」**：把原来只提远程控制两端点的段落，扩为含直播弹幕 `/ws/live` 的**四端点总览表**（端点 / 用途 / 握手凭证 / 详见），补上之前漏掉的 `/ws/live`。端点值与握手凭证核 `ImConstants`（WS_ENDPOINT / WS_LIVE_ENDPOINT / WS_REMOTE_*）与各 WebSocketConfig 一致。
- **`README.md`「九、接口一览」重构**：正名实相符——原节主体是一张「请求链路时序图」（讲一个请求怎么流转，不是接口清单）+ 只到前缀级（`/api/user`）的模块表，读者看不到「具体有哪些接口、各干什么」。现按模块拆为 **15 张接口表**（认证 / 验证码 / 用户 / 好友 / 好友申请 / 会话 / 消息 / 群组 / 文件 / WS 票据 / AI 面试 / 全网检索 / 远程控制 / 直播），逐条列「方法 + 路径 + 功能」，相近端点合并成行（如分片上传 `init`·`chunk`·`merge`、面试会话 `start`·`turn`·`events`·`end`）控制篇幅，并指向 `http://localhost:8080/doc.html` 看含入参 / 出参的完整清单。接口路径与功能摘要逐个核 15 个 `*Controller.java` 的 `@Operation(summary)` + `@XxxMapping` 属实。同时**删掉本节重复的请求链路时序图**：它与 `md/架构与请求链路图.md` 第二节（HTTP 请求链路）逐行相同，改为一句话链接指向该文件（三层架构 / 登录鉴权 / 文件上传下载 / 远程控制 / 直播等专项链路图同在该处），消除同一张图两处维护的隐患。
- **`README.md`「十、WebSocket」→「十、协议」**：把只讲 WebSocket 的一节升格为**全项目协议总览**——新增协议一览表（HTTP/HTTPS+JSON、WebSocket、SSE、HLS、HTTP PUT 上行、DXP 自定义直连），逐条标「用在哪 / 承载格式 / 传输层 / 详见」；补「两处刻意的不用」（直播不用 RTMP/SRT、远程不用 WebRTC，附选型对比文档链接）与「跨协议复用的安全编码约定」（JWT / AES-256-GCM / HMAC-SHA256 / H.264·AAC）。原 WebSocket 内容整体降为 `### WebSocket` 三级标题，其下「消息链路」顺延为 `####`；同步修「九」节里指向「十、WebSocket」的交叉引用为「十、协议」。协议清单核 `InterviewController`（SSE / `SseEmitter`）、`DirectUdpServer.java`·`electron/direct.js`（DXP / UDP 打洞 / Go-Back-N）、`live-media-server.js`（HTTP PUT 上行）与 README「十二」直连三档表属实；WebRTC 经全库检索确认项目**未使用**（仅 `md/直播.md` 等文档作选型对比），故不列入已用协议。
- **`README.md`「十、协议」补全 SSE / HLS / DXP 三个重点协议的详细介绍**：此前只有 WebSocket 有详细子节，现给另外三个各补一个 `###` 子节，与 WebSocket 同等分量——**SSE**（端点 `POST /api/ai/interview/chat`、`SseEmitter` 300 秒、限流 20/分钟、前端用 `fetch + ReadableStream` 而非 `EventSource`（只支持 GET、带不了自定义头）、虚拟线程推流 + 单行 JSON、`delta/done/error` 三事件表、abort 止损）；**HLS**（媒体面 / 控制面分离、ffmpeg `gdigrab/dshow` → H.264 硬编 → `-hls_time 1` fMP4 切片、`fs.watch` → `PUT /hls/{roomId}/{streamKey}/{file}`、`.part` → rename 原子落盘回 201、下行 hls.js/iOS、`m3u8 no-store` + 分片 `max-age=3600`、`sign = base64url(md5(uri+expire+secret))` 对应 Nginx `secure_link_md5`、自测靠 streamKey 兜底）；**DXP**（三档阶梯表 tcp 18924 / udp 18925 / relay、`[12B 头][body]` + seq 在 8–11 字节、AES-256-GCM、画面不可靠 / 指令可靠、Go-Back-N `WINDOW=64`·`RTO 250→1000ms`、UDP 8947 反射打洞、HMAC-SHA256 proof + nonce、失败退回 relay）。大链路图不重复内嵌，各指向 `md/架构与请求链路图.md` 第八 / 十一 / 七节。参数逐个核 `InterviewController`·`api/ai.js`、`live-media-server.js`·`live.js`·`LiveRoomServiceImpl`·`LiveProperties`、`electron/direct.js` 属实。保留用户把「消息链路」由 `####` 改为加粗的编辑。
- **`README.md` 开头按标准开源 README 格式重排 + 新增 Apache-2.0 `LICENSE`**：把「## 一」之前的部分重排为——① 标题 + 一句话简介 + 6 枚静态徽章（Spring Boot 4.0.8 / JDK 21 / Vue 3.5.42 / MySQL 8.x / Redis 5+ / License Apache-2.0，均 shields.io 静态徽章；无 CI/包仓库故不加构建状态类）+「工程形态」说明；② 原有按模块罗列的功能清单**原样保留**、只加「## 特性」标题；③「## 截图 / Demo」留 TODO 占位（仓库无 UI 截图，`md/` 下仅一张缓存架构图，不引用）；④「## 快速开始」给最短跑通命令（后端 `mvn clean package -DskipTests` + `java -jar im-bootstrap/target/im-server.jar`、前端 `cd im-ui; npm install; npm run dev`）并指向「三~七」章与 `md/项目启动流程.md`；⑤「## 文档」链接表汇总各 md 文档 + doc.html + CHANGELOG。原开头两段引用块（前端启动 / 如何启动、架构图集）内容全部并入上述新节，无信息丢失。**新建 `LICENSE`（Apache License 2.0 全文 201 行）**，并在文末追加「## 十八、许可证」指向它。现有「一~十七」编号章节一字未动。启动命令逐条核「六、启动后端」「七、启动前端」属实。
- **`README.md` 文末补齐模板缺失的四节**：在「十八、许可证」之后新增——**十九、开发指南与贡献**（源码构建：后端 `mvn clean package -DskipTests`、跨模块改动须先 `mvn install` 否则静默跑旧 jar；前端 dev/build/build:electron/gen:cert 脚本；测试：如实写明当前无自动化测试、验收靠「十七」手动串测；代码规范：如实写明未配 ESLint/Prettier/Checkstyle/Spotless，改列后端三层 + SPI + Result + 错误码段位 + 敏感字段打码、前端组合式 API + api/stores 收敛等人工约定；提 Issue/PR 流程、分支策略）；**二十、社区 / 联系方式**（GitHub Issues/Discussions 链接 + 如实标注暂无 QQ/微信/Discord 群）；**二十一、贡献者名单**（contrib.rocks 头像墙 + 真实 git 作者 @zhuxiaoyi412826）；**二十二、致谢**（按「一、技术栈」据实列后端/前端/桌面端/基础设施各开源项目并附官网/仓库链接）。事实逐个核 `im-ui/package.json`（无 lint 依赖、脚本清单）、根 `pom.xml`（仅 compiler/clean/surefire 插件、无质量插件）、`git branch/shortlog/remote`（单 main 分支、唯一作者 zhuxiaoyi412826、仓库 github.com/zhuxiaoyi412826/Veytalk）属实，未编造工具/渠道/贡献者。现有「一~十八」不动。
- **`README.md` 新增「路线图 / Roadmap」节**：把 `md/待开发功能.txt`（混合已完成 / 待开发 / 想法的原始草稿）整理成按状态分组的 Roadmap，插入「开发指南与贡献」与「社区 / 联系方式」之间（符合模板 8→10→12 顺序）。分三档——**✅ 已完成**（账号认证与密码找回双通道、短信 `SmsSender`/`MockSmsSender` 可插拔、AI 面试官 RAG+SSE、好友备注 `PUT /api/friend/{friendId}/remark`）、**🚧 代码就绪待接服务商 / 依赖服务器**（真实短信服务商、事务邮箱、腾讯防水墙、腾讯 TRTC）、**📋 规划中**（第三方 OAuth、TRTC 语音/视频面试官、语音视频通话、钱包、图片敏感内容过滤、闪照+马赛克、直播右侧内容面板、Tauri2 打包、日志系统完善）。**修正草稿过时项**：第 14 条「修改备注」经核 `FriendController` 已有 `PUT /{friendId}/remark` + `FriendServiceImpl.updateRemark`、实际已完成，归入 ✅ 而非待办；短信下发经核 `im-user/sms/` 下确有 `SmsSender`+`MockSmsSender`、OAuth 代码确无。适配用户最新重排（许可证/开发指南/社区/贡献者/致谢已去编号上移至开头、新增 `# 详细开发内容` 分隔），故本节也用无编号 `## 路线图 / Roadmap`、未重编号。
- **`README.md`「路线图 / Roadmap」的「📋 规划中」新增三组功能建议**：把 `md/待开发功能.txt` L35-58（上一轮梳理、用户已存入草稿）整理进 Roadmap，按优先级分——**🔴 关键短板**（系统级离线推送 APNs/FCM、管理后台 Admin Console、举报+内容审核闭环、聊天 E2EE、2FA/TOTP、账号注销/数据删除、Actuator+Prometheus 监控告警）、**🟡 沟通与群组体验**（消息 Reaction、消息编辑、位置/名片/合并转发/投票等类型、语音转文字/翻译、群文件/相册/精华、进群审批/邀请链接/群二维码、群机器人/Webhook、聊天记录导出、草稿箱/常用语）、**🟢 前端体验 & AI/直播延伸**（i18n/深色模式/a11y、AI 未读摘要/智能回复/AI 客服、直播连麦PK/礼物打赏/回放存档/付费直播/弹幕房管）。原有 9 条规划项保留不动，新组以加粗子标题追加其后；据 txt L35-58 逐条转写、去掉「现状/缺」列冗余，未编造。

### 修复 Fixed

- **`sql/im_schema.sql` 幂等性缺陷**：头部 `DROP TABLE IF EXISTS` 块原只列 21 张表，漏掉 `im_live_room`（第 507 行 `CREATE TABLE` 且无 `IF NOT EXISTS`），导致对已有库重跑脚本时会在建直播表处报「表已存在」中断，与注释「保证脚本幂等」矛盾。已在 DROP 块首行补 `DROP TABLE IF EXISTS im_live_room;`（符合倒序清理约定）。
- **`README.md` Electron 打包步骤的过时拷贝指令**：第 525 行 `Copy-Item ".\dist\*" "..\electron\dist"` 已删除。自 `im-ui/vite.config.js` 改为 electron 模式 `outDir: '../electron/dist'`（`emptyOutDir: true`）后，`npm run build:electron` 直接产出到 `electron/dist` 且不再产生 `im-ui/dist`；保留这句会把上一次 `npm run build` 遗留的旧 web 产物盖回 `electron/dist`，正好触发「重新打包了还是旧 UI」（见 `md/已踩坑.md` 第 9 条）。已同时把行内注释改为说明「直接产出到 ../electron/dist，无需手动拷贝」。

---

## 2026-10-12

### 新增 Added

- **新建 `md/CHANGELOG.md`**（本文件）：作为项目统一的更新日志入口，约定此后所有变更都在此登记。
- **新建 `md/项目启动流程.md`**：从零把项目跑起来的最短路径（环境准备 → 环境变量 → 建库灌数据 → 起后端 :8080 → 起前端 :5173 → 可选打包客户端），含整体启动顺序流程图、可选附加进程（直播 8088 / 被控端 Agent / MinIO / AI Key）与延伸文档索引。

### 变更 Changed

- **`README.md`「功能覆盖」**：把原先「连写段落 + 半成品列表」混杂的排版，统一成「**每个功能域一个加粗标题 + 要点列表**」的一致结构，补齐缺失标题（账号与鉴权 / 群组 / 文件 / 远程控制 / AI 能力），删除重复残片与对话残留。
- **`README.md`「公共基础设施」**：标题由 `（im-common）` 改为 `（im-common / im-bootstrap）`，新增 im-bootstrap（唯一启动模块，repackage 成 `im-server.jar`）说明。
- **`README.md` 前端启动引用**：原先只提「Electron 打包为 Windows 桌面客户端」，扩写为「前端 `npm run dev` 开发启动 / 打包为客户端」，并分别引用 `md/打包/Electron打包指南.md`（Windows）与 `md/打包/打包为EXE和Android指南.md`（EXE + 安卓）；另加一条「如何启动该项目」引用指向 `md/项目启动流程.md`。
- **`README.md`「一、技术栈与版本」**：修正「版本全部锁定在 pom.xml」的表述（前端在 `im-ui/package.json`、桌面端在 `electron/package.json`）；前端由 2 行空说明扩为 12 行完整清单（Vue / Vite / Element Plus / Pinia / Vue Router / axios / sql.js / hls.js / @ffmpeg / spark-md5 / marked+dompurify / dayjs），并新增 Electron + electron-builder、node-forge 行。后端 9 行经核对 `pom.xml` 全部准确，未改。
- **`README.md`「二、模块结构与依赖关系」**：im-file 补秒传/断点续传/分片与票据下载；im-remote 补三档直连与端到端 AES-GCM；im-live 区分 ENDED 聊天模式（`ended-chat-minutes`）与列表可见性（`ended-room-visible-minutes`）两个配置并补 abort；模块树补 `electron/` 桌面端壳。（其后用户又将模块清单重构为「业务 / 公共装配 / 独立程序」三张表格。）
- **`README.md`「三、环境准备」**：由单张裸表重构为「必需 / 可选」两张表并补用途列；**新增原先缺失的 `ffmpeg`**（直播推流 / 远程控制 H.264 硬编依赖，Electron 端已内置）与 Android Studio + SDK（打包 APK 用）；补 Redis 用途与「没起后端会启动失败」提示、OS/PowerShell 说明。
- **`README.md`「十六、目录结构」**：`md/` 行同步补上 `项目启动流程.md` 与 `打包/` 子目录，纠正「Electron 打包指南」已移入 `md/打包/` 的实际位置。

### 修复 Fixed

- **`README.md` 断链**：打包文档移入 `md/打包/` 子目录后，正文第 451 / 453 / 912 行仍指向旧路径 `md/Electron打包指南.md`，已全部更正为 `md/打包/Electron打包指南.md`。
