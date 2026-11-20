# SQL 版本管理与一键回滚 —— 技术解析

> 自研 C 方案：`sql/versions/` V/U 配对脚本 + 两张台账表 + 后台「SQL 版本」可视化页。
> 对应 SQL 变更台账 1.0.5，权限点 `system:schema:manage`（id 128）。

---

## 一、是什么

这套机制把「改数据库」从一条隐性动作（谁在命令行里 source 了什么，没人知道）变成一条**有版本、有姓名、有时间、可反悔**的显性流程。

四个组成部分：

1. **脚本对目录 `sql/versions/`**：每次库变更成对提交两个文件——
   - `V<版本>__<描述>.sql`：向前脚本（建表/加列/种子）；
   - `U<版本>__<描述>.sql`：回滚脚本（把 V 干的事倒干净）。
   命名沿用 Flyway 约定，版本序列续用 `md/SQL变更文档.md` 的台账号（1.0.4、1.0.5…），不另起一套。
2. **台账表 `im_schema_migration`**：每个版本一行，记录状态（APPLIED / ROLLED_BACK / FAILED）、
   V 脚本的 SHA-256 校验和、执行人、应用/回滚时刻。`undo_name` 为 NULL 表示该版本不可一键回滚（历史基线就是这种）。
3. **流水表 `im_schema_migration_log`**：只 INSERT 不 UPDATE，每次应用/回滚/登记各写一行——
   这就是页面上「**谁、什么时候、什么时间、执行了哪个 SQL、结果如何**」的时间线数据源。
4. **后台「SQL 版本」页**（`SchemaView.vue`，权限 128）：上半屏是版本台账表（带「脚本已改动」漂移标、
   应用/回滚按钮），下半屏是变更流水时间线；失败原因、语句条数、耗时都摊开可见。
   脚本名列（V 与 U）可点击弹窗看 SQL 原文——点「应用」前先知道将要执行的是什么；
   弹窗页脚带一键复制与导出 SQL 文件（导出名沿用仓库真实脚本名，下载即得可入库的 V/U 对）。

核心代码位置：

| 层 | 文件 |
|---|---|
| SQL | `sql/migration_schema_version.sql`（引导）、`sql/versions/V1.0.4/U1.0.4`（首对样例）、`im_schema.sql` 第十八节、`im_data.sql` 种子 128/6028 |
| 后端 | `im-admin/.../service/SchemaMigrationService.java`（扫描/校验/应用/回滚/流水）、`AdminSchemaController.java`（4 端点） |
| 前端 | `im-admin-ui/src/views/SchemaView.vue` + `api/admin.js` + 路由菜单 |
| 解析 | `md/SQL变更文档.md` 1.0.5、`md/CHANGELOG.md` 未发布·新增 |

---

## 二、为什么

### 2.1 要解决的实际问题

项目此前有 27 个 `migration_*.sql` 散在 `sql/` 根下，暴露了三个老毛病：

- **不知道跑到哪了**：换台机器/新同事建库，只能靠记忆和 `md/SQL变更文档.md` 人肉台账判断哪些脚本已执行，跑重了报错、漏跑了缺表；
- **没人知道谁改的库**：线上表结构多了个列，查不到是谁、哪天、用哪个脚本加上去的；
- **没有反悔路径**：所有脚本只有向前没有向后，出错只能现场手写补救 SQL。

### 2.2 三方案对比与被淘汰的原因

| 方案 | 内容 | 结论 |
|---|---|---|
| A：Liquibase | XML/YAML changeset，原生 rollback | 被淘汰：全项目要改成 XML 写迁移，与现有「纯 SQL 脚本 + 人肉台账」习惯冲突；引入 Xerces 等依赖，对单人维护的项目太重 |
| B：Flyway | 就是本项目 V/U 命名的出处 | 被淘汰：开源版**不认 U 脚本**（回滚是 Teams 付费版功能），「一键回滚」等于还得自研；且它启动时自动 migrate，等于把「改库」放进应用启动流程，与本团队「MySQL 写操作必须人点头」的铁律相反 |
| C：自研轻量 | Spring JDBC 自己扫描/执行/记账 | **采用**（用户拍板） |

### 2.3 为什么 C 值得自己写（而不是继续人肉）

- **体量匹配**：需要的全部能力就是「扫目录 → 比对台账 → JdbcTemplate 逐条执行 → 记两笔账」，
  一个 Service 六百行搞定；上 Flyway/Liquibase 是为同样这点事引入一整个框架和它的版本升级债。
- **闸门语义要长在项目规矩上**：页面按钮触发（人点头才执行）、LIFO 只回最近一版、二次确认要逐字输版本号、
  进程内互斥——这些定制约束用开源版反而要绕过它的设计做二次开发。
- **命名兼容留了退路**：V/U 脚本对 Flyway 语法天然合法，哪天团队决定换 B 方案，脚本零改写，台账表结构也对得上（checksum/version/status 同款语义）。

### 2.4 诚实边界（必须知道的三个不行）

1. **一键回滚 ≠ 数据恢复**。MySQL 的 DDL 隐式提交、不可回卷，U 脚本还原的是**结构**；
   V 里 `DROP TABLE`/`DELETE` 掉的数据只能靠 mysqldump 备份找回。所以每个确认框都强制先确认已备份。
2. **失败的版本不能自动续跑**。V 脚本第 3 条挂了，前 2 条已经落库（DDL 没法回卷到脚本起点），
   台账标 FAILED；修法是人工补齐或改脚本后**重放整个版本**（因此 V 脚本必须写成幂等：
   `CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE` / information_schema 守卫的 ADD COLUMN）。
3. **不支持存储过程**。语句切分状态机明确拒绝 `DELIMITER`/`CREATE PROCEDURE`——本项目业务逻辑全在 Java 层，
   这是刻意收窄的适用范围，不是遗漏。

---

## 三、怎么做

### 3.1 数据模型

```
im_schema_migration          ← 当前态：一行一个版本，uk_version 收敛
  version / script_name / undo_name(NULL=不可回滚) / description
  checksum(CHAR64 SHA-256) / status(1应用 0回滚 2失败)
  operator_id / operator_name / applied_at / rolled_back_at

im_schema_migration_log      ← 历史态：只追加，页面时间线数据源
  version / action(APPLY|ROLLBACK|MARK) / result(SUCCESS|FAILED)
  statement_count / duration_ms / message / operator_* / create_time
```

两表分工：**台账回答「现在库是什么样」，流水回答「一路是怎么变成这样的」**。
流水只 INSERT，所以哪怕台账被人工改乱，审计线索也不丢。

### 3.2 基线策略（鸡生蛋问题的解）

引导脚本 `sql/migration_schema_version.sql` 是**全项目最后一份需要手动 source 的 DDL**——
因为它建的两张表无法被这套机制自己登记。它同时把 1.0.0~1.0.3 的 27 个历史脚本收拢成一行基线：

```
version=1.0.0, status=APPLIED, operator=SYSTEM, checksum=NULL, undo_name=NULL
```

checksum=NULL 不做漂移校验、undo_name=NULL 不给回滚按钮，页面从 1.0.4 起真正接管。
全新库不用单独跑它：该段 DDL + 基线种子已并回 `im_schema.sql` 第十八节。

### 3.3 应用（apply）一条版本的服务端流程

```
ReentrantLock.tryLock()            ← 进程内互斥，占用中直接拒绝不排队
  ├─ 状态校验：已 APPLIED 的版本拒绝重复应用
  ├─ 文件校验：sql/versions/V<版本>__*.sql 必须存在
  ├─ 升序校验：版本号更小的待应用项必须先应用（强制单向推进）
  ├─ splitStatements()             ← 逐字符状态机：处理 --/#/块注释、引号/反引号内的分号
  ├─ 逐条 jdbcTemplate.execute()   ← 失败即中断：流水记 FAILED + 台账标 FAILED + 抛「第 N/M 条失败：原因」
  ├─ upsert 台账行（APPLIED + 重算 checksum + 执行人/时刻）
  └─ 流水记 SUCCESS（语句数、耗时）
```

执行人姓名来自 `SecurityUtil.getUserId()` → 查 `im_user` 的 `IFNULL(nickname, username)`，查不到降级 `user:{id}`。

### 3.4 回滚（rollback）的三道闸

1. **LIFO**：只有「版本号最高且 status=APPLIED」的行才允许回滚（service 末尾统一把其余行的 canRollback 置 false）。
   允许跳着回中间版本 = 制造版本 3 依赖的列被版本 2 的回滚删掉的事故，故禁止。
2. **有退路才给按**：`undo_name` 为 NULL 或 U 文件不存在的版本，页面直接显示「不可一键回滚」。
3. **逐字输版本号确认**：`ElMessageBox.prompt` + inputPattern 精确匹配，防手快点错——这一页是全后台唯一直接改库结构的入口。

失败处理与 apply 相反：U 脚本半途挂了，台账**保持 APPLIED** 让重试（所以 U 脚本必须幂等，
`DROP TABLE IF EXISTS` 就是这个原因），流水记 FAILED 供追查。

### 3.5 漂移检测

台账存 V 脚本执行当时的 SHA-256；overview 每次重算目录里同名文件的哈希比对，
不一致就在状态列旁挂红色「脚本已改动」标——提醒「库里跑的」和「仓库里的」已经不是同一个脚本了。

### 3.6 接口与权限

| 端点 | 方法 | 语义 |
|---|---|---|
| `/api/admin/schema/overview` | GET | 台账行 × 目录文件合并视图（dirFound/tableReady/LIFO 队头都在标志位里） |
| `/api/admin/schema/logs?limit=` | GET | 变更流水（倒序，上限 200） |
| `/api/admin/schema/script?version=&undo=` | GET | 查看脚本原文（只读；文件名由服务端扫目录按版本号解析，不接受文件名入参，免疫路径穿越；历史基线无实体文件返回 404） |
| `/api/admin/schema/apply?version=` | POST | 应用（命令语义故用 POST，操作人取登录态不接受入参） |
| `/api/admin/schema/rollback?version=` | POST | 一键回滚 |

四个端点统一挂 `@SaCheckPermission("system:schema:manage")`（id 128，种子只授 admin 角色）；
knife4j 归入「16-管理后台」分组。脚本目录解析：`im.admin.schema-dir` 配置优先，
留空则从工作目录向上找 5 层 `sql/versions`（jar 部署时务必配绝对路径或把目录随部署带上）。

### 3.7 日常怎么用（改库的标准动作）

1. 写 `sql/versions/V1.0.6__xxx.sql`（幂等）+ `U1.0.6__xxx.sql`（倒干净），成对提交；
2. 头注释第一行写人话描述（会被抓进台账 description）；
3. 后台「SQL 版本」页对该行点「应用」（确认框里先勾备份）；
4. 出问题点「回滚」，逐字输版本号确认；
5. 1.0.6 起**不再手写** `md/SQL变更文档.md` 台账条目——流水自动进 `im_schema_migration_log`，页面即台账。

### 3.8 部署顺序（本次上线 1.0.5 要走的路）

```
① mysql -e "source sql/migration_schema_version.sql"   ← 本人执行（建两张表+基线+权限 128）
② 重启后端（新 Controller/Service 注册）
③ admin-ui npm run build 并部署
④ 清 Redis：DEL im:auth:perm:1000、im:auth:role:1000   ← 不清则菜单对 admin 也不显示（助手可代做）
```

---

## 四、一页总结

| 问题 | 答案 |
|---|---|
| 是什么 | V/U 配对脚本 + 台账/流水两张表 + 后台可视化应用与一键回滚 |
| 为什么不用 Flyway/Liquibase | 开源版回滚要付费或换 XML 写法；启动自动 migrate 与「人点头才改库」的团队铁律相悖；自研体量小且命名兼容留了迁移退路 |
| 谁/何时/哪个 SQL | `im_schema_migration_log` 每操作一行（操作人昵称、时刻、版本、结果、语句数、耗时），页面时间线直读 |
| 一键回滚的真相 | 执行配套 U 脚本还原**结构**，LIFO 只回最近一版；数据靠备份，DDL 不可回卷 |
| 失败怎么办 | 台账标 FAILED，人工修补后重放整个版本（V 脚本必须幂等） |
| 适用范围 | 纯 DDL/DML 脚本；不支持存储过程/DELIMITER；单实例部署（进程内锁足够） |
