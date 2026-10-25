# 贡献指南（Contributing）

> 本文规定**这个仓库怎么改代码、怎么提 PR、怎么发版**。动手前先通读一遍，能省掉一轮返工。
>
> 它是 [`README.md`](README.md)「开发指南与贡献」那一节的**细则版**：README 里只给概览，冲突时以本文为准。项目全貌与功能清单看 [`README详细开发指南.md`](README详细开发指南.md)，变更历史看 [`md/CHANGELOG.md`](md/CHANGELOG.md)。

**三条不容商量的硬性约定**（不管你是谁、改动多小）：

1. **每一次更新都要在 [`md/CHANGELOG.md`](md/CHANGELOG.md) 登记一条**，无论改动大小；
2. **改动若改变了「既有事实描述」，同轮必须把描述性文档一并同步**（改 `README详细开发指南.md`，不要往 `README.md` 里搬技术细节）；
3. **任何凭据都不许进仓库**——数据库密码只从环境变量读，明文密钥 / token / 授权码一律禁止。

## 目录

- [一、分支模型](#一分支模型)
- [二、开发流程](#二开发流程)
  - [做一个普通功能](#做一个普通功能)
  - [非紧急 bug 修复](#非紧急-bug-修复)
  - [紧急生产修复（已发布版本上的严重问题）](#紧急生产修复已发布版本上的严重问题)
  - [发版（MINOR / MAJOR）](#发版minor--major)
- [三、Commit 规范](#三commit-规范)
- [四、PR 规范](#四pr-规范)
- [五、Tag 与版本号](#五tag-与版本号)
- [六、测试](#六测试)
- [七、提交说明与注释](#七提交说明与注释)
- [八、外部贡献者](#八外部贡献者)
- [九、安全问题](#九安全问题)

---

## 一、分支模型

主干只有一条，其余都是短命分支。

| 分支 | 来源 | 命名 | 合并方式 | 存活期 |
| --- | --- | --- | --- | --- |
| `main` | — | — | 唯一长期分支，**任何时刻都必须可编译、可发布** | 永久 |
| `feature/xxx` | `main` | kebab-case，如 `feature/group-call-sfu` | PR + rebase 合并 | 合并即删 |
| `fix/xxx` | `main` | 如 `fix/recall-timeout-toast` | PR + rebase 合并 | 合并即删 |
| `hotfix/xxx` | **最新 tag** | 如 `hotfix/1.0.1-ws-origin-null` | PR 合并后 **cherry-pick 回 `main`** | 合并即删 |
| `docs/xxx` | `main` | 如 `docs/contributing` | PR 合并 | 合并即删 |

现状说明（别照抄别人的仓库治理）：

- 本项目目前是**单人开发**，日常直接提交 `main` 是可接受的；一旦有第二个人参与，或改动属于「不确定要不要合」的性质，就走分支 + PR。
- 本地可能残留 `pr-1` 这类分支，那是校验别人 PR 时留下的临时分支，**确认合并后删掉**：`git branch -d pr-1`。
- **禁止对 `main` 做 `push --force`**。已经推出去的历史只能往前修正（`revert` 或追加提交）。
- 一个分支只做一件事。功能里顺手发现的 bug，另开 `fix/xxx`，不要混在同一个分支里。

## 二、开发流程

### 做一个普通功能

```powershell
cd d:\daima\Lianshi\spring-boot-duomokuia
git switch main; git pull        # 1）从最新 main 起步
git switch -c feature/xxx        # 2）切特性分支
# 3）编码（分层与 SPI 约定见「七、提交说明与注释」）
mvn clean package -DskipTests    # 4）后端编译过一遍
cd im-ui; npm run build          #    前端改动则 build 不报错
# 5）登记 CHANGELOG + 同步描述性文档（这两步不是可选项）
git add -A; git commit           # 6）提交信息见「三、Commit 规范」
git push -u origin feature/xxx   # 7）提 PR
```

动手前的三个自检：

- **影响哪几个模块？** 14 个 Maven 子模块 + 3 个前端工程（`im-ui` / `im-admin-ui` / `im-website`）+ `electron` 桌面壳。跨模块只能经 `im-common` 的 SPI 契约调用，**不许直接依赖别的业务模块实现**。
- **要不要动数据库？** 要的话见下一节「四、PR 规范」的 SQL 条款。
- **要不要动配置？** 新增环境变量必须同时写进 `application.yml`（带默认值）和 `README.md` 的环境变量表，否则部署方无从得知。

### 非紧急 bug 修复

1. **先复现**，把复现步骤写进 commit / PR 描述。复现不出来的问题不要凭猜改代码——先加日志或 `md/已踩坑.md` 记一条排查范式。
2. **最小改动**。修 bug 的分支里不夹带重构和格式化，否则回滚时会被一起带走。
3. 判断根因层次：是代码逻辑错、配置错、还是环境差异（本项目大量坑属于后两类，尤其 HTTPS / 自签证书 / 防火墙 / 打包产物错配）。根因写在 CHANGELOG 里，**比修法更有价值**。
4. 归入 CHANGELOG 的「修复 Fixed」，并同步订正描述性文档里那段已经说错的旧描述。

### 紧急生产修复（已发布版本上的严重问题）

1. 从**出问题的 tag** 切分支：`git switch -c hotfix/xxx v1.0.0`（还没有 tag 时从出问题的那次提交切）。
2. 只打最小补丁，不顺手改别的。验证后打 tag（`v1.0.1`，见「五」）。
3. **必须把补丁同步回 `main`**：`git cherry-pick <hotfix 提交>`，否则下个版本会回归。
4. CHANGELOG 里单独成段（`## [1.0.1] - 2026-xx-xx`），写清影响面与是否需要现场执行 SQL。
5. 事后补一条排查范式到 `md/已踩坑.md`——生产事故不复用就白踩了。

### 发版（MINOR / MAJOR）

发版是一次**清单动作**，按顺序执行：

1. 确认所有待发布改动都在 `md/CHANGELOG.md` 的「未发布 Unreleased」区里，且分类正确（新增 / 变更 / 修复 / 移除 / 废弃 / 安全）。
2. 把 Unreleased 的内容整体挪到新标题下：`## [1.1.0] - 2026-11-01`，并在其上重建一个空的 `## 未发布（Unreleased）`。
3. 更新版本号：改根 `pom.xml` 的 `<im.version>`（**一处即可**，14 个子模块全继承 `${im.version}`）；`electron/package.json`、各前端 `package.json` 的 `version` 同步对齐。
4. 若本次含数据库变更，确认对应 migration 脚本已进 `sql/`，并在发布说明里写明"已有库需执行哪几个脚本"。
5. `mvn clean package -DskipTests` 全量构建，产物 `im-bootstrap/target/im-server.jar`。
6. 打 tag：`git tag -a v1.1.0 -m "release 1.1.0"; git push origin v1.1.0`。
7. 需要桌面安装包时重打 Electron 包，并把产物喂给官网下载区：`cd im-website; npm run fetch:downloads`（它从 `electron/release` 收集改名）。
   > ⚠️ 重打包前先退出正在运行的 `release\win-unpacked\IM通讯.exe`，否则 `electron-builder` 会在 unlink `app.asar` 处报 `EBUSY`。

## 三、Commit 规范

格式沿用 **Conventional Commits + 中文描述**（仓库现有历史就是这个风格，保持一致优先于个人偏好）：

```
<type>(<scope>): <subject>

<body：为什么这么改，可选>

<footer：BREAKING CHANGE / issue 引用，可选>
```

- **type**：`feat` 新功能｜`fix` 修 bug｜`docs` 文档｜`refactor` 重构（不改行为）｜`perf` 性能｜`style` 纯格式｜`test` 测试｜`build` 构建/依赖｜`chore` 杂项｜`revert` 回滚。
- **scope**：模块名或功能域名，小写 kebab-case。仓库里在用的有 `chat-window` `voice` `reaction` `call` `notify` `ai` `live` `remote` `admin` `message` `friend` `group` `file` `sql` `readme` `build`。
- **subject**：中文、动词开头、说清"改了什么"，**不超过约 50 字、结尾不加句号**。
- **破坏性变更**：type 后加 `!`（如 `feat(ai)!: ...`），并在 footer 写 `BREAKING CHANGE: 说明调用方要怎么改`。

真实例子（可直接对照）：

```
feat(voice): 实现微信式滑动转文字与屏幕共享观看优化
fix(call): 调整通话画面默认布局为中间显示自己
docs(readme): 添加目录导航到README
```

反面教材：仓库里有一条提交信息就叫 `1`（`05bd6e8`）——**这种历史不要重演**。

禁忌与现状：

- **未配置** husky / commitlint / commitizen，规范靠自觉，工具不会拦你。
- 不要提交：`target/`、`node_modules/`、`dist/`、`electron/release/`、`*.log`（如误留的 `mvn_ai.log`）、`.env`、被控端 `config.properties`、模型文件（`im-ui/public/models/**`，83MB 那类由 `.gitignore` 挡着）。提交前 `git status --short` 扫一眼。
- 不要把「一次改 300 个文件的格式化」和「一个功能」塞进同一个 commit。
- ⚠ `.gitignore` 里有一条**全局 `*.txt`**：新增 `.txt` 文档时 `git add` 会**静默跳过它**，不报任何错（仓库里现有的 `md/待开发功能.txt`、`md/综合.txt`、`electron/bin/ffmpeg放置说明.txt` 都是当年 `git add -f` 强推进去的）。写新文档优先用 `.md`；非用 `.txt` 不可就显式 `git add -f`，并记得这个规则也适用于日志、清单类文本。

## 四、PR 规范

**标题**沿用 commit 规范（`feat(scope): ...`），**描述**按下面五段写，缺一段会被要求补：

```markdown
## 动机
为什么做这件事（用户诉求 / 线上现象 / 待办清单项）。

## 改动点
按文件列，一句话说清每个文件改了什么、为什么这么改。

## 影响范围
涉及的模块、接口、数据库、配置项、旧行为是否被推翻。
若推翻了此前某轮已合并的决策，明写「与 X 相反，本轮以 Y 为准」。

## 验证方式
跑了什么命令、EXIT 码、手动串测了哪些路径、截图/录屏。

## 数据库变更
无 / 贴出 DDL 与对应 `sql/` 脚本，并说明「已有库如何增量升级」。
```

硬性门槛（不满足就不进合并流程）：

1. **含 CHANGELOG 条目**——本项目最常被漏的一条。
2. **描述性文档同步**：改了架构链路、交互布局、命令与配置指引、踩坑结论，就要同步 `README详细开发指南.md`。`README.md` 只维护仓库门面（徽章 / 特性 / 截图 / 快速开始 / 路线图），且**它的「目录」是手写锚点，增删改标题必须一并维护**。
3. **SQL 规矩**：`sql/im_schema.sql` 是全新库的完整超集（建库 + 34 表），`sql/im_data.sql` 只灌种子数据。给已有库的变更**新增 migration 脚本**，不回头改历史脚本；migration 里给 `ALTER` 加幂等护栏，并注明老库与新库分别怎么执行。
4. 合并前 **rebase 到最新 `main`** 并重新编译一次。
5. 一个 PR 只做一件事，小步提交。

**模板已经就位，不用手抄**：

- **PR**：[`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md)——新建 PR 时自动带出上面那五段 + 一张提交前自检清单。
- **Issue**：[`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/) 下两份 **issue form**（`bug_report.yml`：现象 / 复现步骤 / 功能域 / 客户端类型 / profile / 版本 / 日志；`feature_request.yml`：动机 / 期望 / 替代方案 / 影响层面 / 兼容代价）+ `config.yml`（`blank_issues_enabled: true` 保留自由提单，另给 4 条 contact_links，其中一条就是「安全漏洞请私报」入口）。
- 两条 GitHub 的脾气要知道：① `labels` 里写的标签**必须在仓库里已存在**，不存在就静默不打（`bug` / `enhancement` 是新仓库自带的）；② issue form 的 YAML 语法错了是**整份模板静默不显示**，改完本地用 js-yaml 解析一遍；并且 `render` 一旦设置，那个输入框**就不能再拖附件**（所以「复现步骤」故意不设 `render`，好让它能贴图与写 Markdown；「相关日志」才用 `render: shell` 走代码块）。
- 仍然**没有** CI workflow（`.github/workflows/`）——编译与串测全靠人跑，不要以为提了 PR 就会自动验证。

## 五、Tag 与版本号

版本号遵循[语义化版本](https://semver.org/lang/zh-CN/) `MAJOR.MINOR.PATCH`：

| 位 | 什么时候加 | 本项目的典型例子 |
| --- | --- | --- |
| **MAJOR** | 不兼容的 API / 数据结构变更 | REST 路径或 `Result` 契约破坏性调整；跨模块 SPI 接口签名变更；数据库不兼容且需现场迁移 |
| **MINOR** | 向后兼容的新增功能 | 新增一个 Maven 模块、新增接口、新增前端板块 |
| **PATCH** | 向后兼容的问题修复 | 单点 bug 修正、文案 / 样式修正 |

**现状（重要）**：仓库**还没有任何 tag**，`pom.xml` 的 `<im.version>` 是 `1.0.0`，CHANGELOG 里的历史版本标题用的是**日期**（如 `## 2026-10-12`）。因此：

- 从下一个版本起，版本标题统一写成 `## [1.1.0] - 2026-11-01`（日期跟在方括号版本后），不再只用日期当标题。
- 第一个 tag 建议在下次发版时补上：`git tag -a v1.0.0 -m "baseline"; git push origin v1.0.0`。
- tag 一律 **annotated**（`-a`），不用轻量 tag——发版说明要能 `git show v1.1.0` 看到。
- tag 名 = `v` + 版本号，与 `im.version` 严格一致；打 tag 与改版本号在**同一次提交**里完成。

## 六、测试

**现状要摆明**：项目当前**不含自动化测试用例**，打包统一 `-DskipTests`，验收靠手动串测（清单见 `README详细开发指南.md` 的「验证清单」章节）。补测试的 PR 欢迎，且**不需要先开 Issue 讨论**。

提 PR 前至少过这几道：

```powershell
# 后端：必须能编译
mvn clean package -DskipTests
# 前端：改动哪个就 build 哪个
cd im-ui; npm run build
cd ../im-admin-ui; npm run build
cd ../im-website; npm run build
```

按改动区域对应的手动验证要点：

- **改过跨模块代码（如 `im-common`）后要用 `spring-boot:run` 跑**：必须先 `mvn install` 到本地仓库 `~/.m2`，否则会**静默跑旧 jar**——`spring-boot:run` 只编译 `im-bootstrap` 一个模块，跨模块依赖从 `~/.m2` 解析。
- **消息 / 会话**：收发、撤回（超时要有提示）、已读回执、引用回复、离线补推、本地缓存分页。
- **通话 / 面试**：桌面 + 手机真机各一遍；电话面试要验长回答不被截断、切标签页不中断通话。
- **文件**：秒传 / 分片 / 断点续传、一次性票据下载。
- **直播 / 连麦**：推流端与媒体服务器不同机时注意 `IM_LIVE_PUSH_BASE_URL` 与 `remapPushUrl()`；黑屏先查 `init.mp4` 是否落盘在 ffmpeg 的 cwd。
- **远程控制**：三档直连阶梯（中继 WS → 局域网 TCP → UDP 打洞）逐档验，被控端 `im-remote-agent` 是独立 fat jar，要单独起。
- **Electron**：改了 `im-ui` 要确认渲染 bundle 真的重建进了包（`electron/dist`），否则测的是旧壳；打包报 `EBUSY` 见「二、发版」的警告。
- **手机真机联调**：`npm run gen:cert` 出自签证书，手机端要**完全信任根 CA**，否则 wss / 媒体会被静默拒绝（表现为"网络异常"而不是证书错误）。

## 七、提交说明与注释

注释解释**为什么**，不解释**是什么**——代码自己已经说了是什么。

- 中文注释是本项目的既有主流，保持即可；但不要写「这里循环遍历列表」这类复述。
- **不要删既有注释和 TODO**，除非确认它已失效；失效了就在 CHANGELOG 里说明为什么失效。
- 踩出来的坑要沉淀：影响后人的写进 `md/已踩坑.md`（格式「N. 标题」+ 现象 / 根因 / 修复模式 / 可复用边界），局部陷阱就地写在相关代码上方。
- **反直觉的实现必须留注释**。例：`yml` 会覆盖 Java 字段默认值，所以改配置项要同时改两处——这种不写下来下次一定踩。
- 后端分层：`controller / service / mapper` 三层严格分开；接口统一返回 `Result`（失败也是 HTTP 200，靠 `code` 区分）；错误码按段位分配（1xxx 通用 … 9xxx 远程控制）。
- 日志：`password` / `token` / `ticket` 一类敏感参数在日志与慢 SQL 里按字段名打码；不用 `System.out.println`，走 logback 分档。
- 前端：组合式 API + `<script setup>`；网络请求收敛到 `src/api/`，全局状态收敛到 `src/stores/` 的 Pinia，不在组件里散着调 axios。
- **不要为了让工具闭嘴而改配置**（关 lint、降日志级别、`catch {}` 吞异常）——这是本仓库最贵的四类假修复。

## 八、外部贡献者

欢迎参与，先把路径走对，别浪费彼此时间：

1. **先开 [Issue](https://github.com/zhuxiaoyi412826/Veytalk/issues) 讨论，再动手**的情形：新增模块 / 引入新依赖 / 破坏性变更 / 架构级重构（例如把群通话换成 SFU）。这类改动方向不对就是白干。
2. Fork 到自己账号 → 切 `feature/xxx` → 按「二、开发流程」走 → 提 PR。
3. 第一次贡献建议从 [`md/待开发功能.txt`](md/待开发功能.txt) 里标 🚧（代码就绪待接外部依赖）的项入手，边界清楚、不用讨论方向。
4. 提 Issue 请带：**现象 + 复现步骤 + 环境**（生效 profile、JDK 版本、浏览器 / 客户端类型）+ **相关日志**（`IM_LOG_HOME` 下的 `error.log` / `warn.log`，必要时附 `DEBUG.log` 片段）。

会被直接打回的 PR：

- 没有 CHANGELOG 条目；
- 纯格式化 / 全文件重排刷屏，夹带在功能改动里；
- 引入明文密钥、把 `config.properties` / `.env` / 大体积模型提交进来；
- 改了 `README详细开发指南.md` 的事实描述却没同步 CHANGELOG（或反之，两处口径打架）；
- 修一个 bug 顺手"优化"了十个不相干文件。

其它：

- 许可证是 **Apache-2.0**，提交即代表你授权项目在该许可证下使用你的贡献，并保留你署名的权利。
- 贡献者头像墙由 [contrib.rocks](https://contrib.rocks) 依据 GitHub 提交记录自动生成，**不需要手动改 `README.md` 的名单**。
- 合并权归维护者。合并前会 rebase 到最新 `main` 并确认能编译。

## 九、安全问题

- **不要开公开 Issue 报漏洞**。请先通过私密渠道联系维护者：QQ `2927883590` / 微信 `zhuxiaoyizxyZXY`，或使用 GitHub 仓库的「Report a vulnerability」（私有安全报告，若已开启）。给出复现步骤与影响面，维护者承诺 72 小时内响应。
- **凭据一律走环境变量**。数据库密码只从 `MYSQL_USER` / `MYSQL_PASSWORD` 读取，代码与配置文件里不得出现任何明文密码。`MAIL_PASSWORD` 填的是 SMTP **授权码**（不是登录密码），`ALI_BABA_API_KEY` 等第三方 key 同理。
- 不得入库的清单：`.env`、被控端 `config.properties`（含 `server.url` / `account` / `accessCode`）、MinIO 密钥、JWT 签名密钥 `IM_JWT_SECRET`（生产必须替换内置开发值）、任何真实手机号 / 身份证 / 截图原件。
- **远程控制接入码是短期凭据**：日志、截图、录屏、Issue 附件里都要遮挡；不要把可用识别码直接贴在公开讨论里。
- 生产部署必须显式改掉的不安全默认值：`SPRING_PROFILES_ACTIVE=prod`（`dev` 会在响应里回显验证码明文）、`IM_JWT_SECRET` 换掉、`IM_SMS_PROVIDER` 从 `mock` 换成真实通道、`IM_LOG_HOME` 从 `D:/rizi1/IM` 改成对应平台路径（Linux 如 `/data/logs/im`）。
- **一旦密钥已经提交过**（哪怕只有一次、后来删了）：视为已泄露，立即轮换；用 `git filter-repo` 清理历史并协调所有协作者重新克隆；同时通知维护者。仅"再提交一次删掉"没有任何作用，历史里仍在。

---

> 本文与代码同版本演进。改了流程 / 版本号口径 / 硬性门槛，请顺手更新本文并登记 CHANGELOG，别让它变成一份好看的摆设。
