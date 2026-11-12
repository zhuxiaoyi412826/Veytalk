# SQL 变更文档（版本台账）

> 本文件是 **数据库脚本变更的唯一登记处**。
> 约定原文：**从今往后，任何对 `sql/*.sql` 的新增、修改、删除，都必须在本文档登记一条带版本号的记录，
> 并明确标注这次变更「清除 / 增加 / 修改」了哪些对象。** 没登记 = 没做完。
> 闸门写在 [`CONTRIBUTING.md`](../CONTRIBUTING.md) 的硬性门槛里，所有人（含 AI）适用。

---

## 目录

- [一、为什么要有这份文档](#一为什么要有这份文档)
- [二、版本号规则](#二版本号规则)
- [三、每条记录必须填的字段](#三每条记录必须填的字段)
- [四、动作分类口径：清除 / 增加 / 修改](#四动作分类口径清除--增加--修改)
- [五、台账（新在上方）](#五台账新在上方)
  - [1.0.0 起始基线（2026-11-08）](#100-起始基线2026-11-08)
    - [基线 A：全量脚本现状（实测数字，非估算）](#基线-a全量脚本现状实测数字非估算)
    - [基线 B：43 张表清单](#基线-b43-张表清单)
    - [基线 C：存量升级补丁归档（21 份）](#基线-c存量升级补丁归档21-份)
  - [1.0.1 模板（下一条照抄这个）](#101-模板下一条照抄这个)
- [六、两类环境的执行方式](#六两类环境的执行方式)
- [七、改完 SQL 必须清的 Redis 缓存](#七改完-sql-必须清的-redis-缓存)
- [八、验证 SQL 常用片段](#八验证-sql-常用片段)
- [九、提交前自查清单](#九提交前自查清单)

---

## 一、为什么要有这份文档

这个项目同时存在**两种库**：

1. **全新库**——靠 `sql/im_schema.sql`（建表）+ `sql/im_data.sql`（种子）一次到位；
2. **存量库**——里面已经有业务数据，不能重跑全量脚本（`im_schema.sql` 带 `DROP TABLE IF EXISTS`，
   重跑等于清库），只能跑 `migration_*.sql` / `*_patch.sql` 这类增量脚本。

两套并行就产生了一个长期风险：**增量脚本改了，全量脚本没跟上**。
后果是新环境（新同事、新服务器、CI、演示机）建出来的库**和现网结构不一致**——
少一列就报 `Unknown column`，少一条种子就出现「后台没有这个菜单」，而且这种问题
只在**新库**上暴露，往往等到上线才发现。

所以本台账承担两件事：

- **登记做了什么**（版本 + 清除/增加/修改的具体对象）；
- **盯住并回状态**（每条记录都要回答「两份全量脚本跟上没有」）。

---

## 二、版本号规则

起始版本 **`1.0.0`** = 本文档建立之日（2026-11-08）的库结构现状。此后每登记一次变更就递增。

| 位 | 名称 | 什么情况升这一位 | 例子 |
|---|---|---|---|
| 第 1 位 | **major**（不兼容） | 删表、删列、改主键/唯一键语义、字段类型收窄可能截断数据、枚举取值含义变更 | `ALTER TABLE im_gift DROP COLUMN icon` |
| 第 2 位 | **minor**（向后兼容的新增） | 新建表、加**可为空或有默认值**的列、加索引、新增种子数据（权限/菜单/礼物/法定文本） | `ALTER TABLE im_message ADD COLUMN quote_msg_id BIGINT NULL` |
| 第 3 位 | **patch**（只动内容不动结构） | 只改种子文案/正文（如协议正文改版）、修脚本自身的写法错误、补注释 | 改 `im_data.sql` 里 `im_agreement` 的 Markdown 正文 |

> **与代码版本号的关系**：`pom.xml` 里的 `im.version` 至今是 `1.0.0`，仓库**没有任何 git tag**，
> 本台账的版本号**只描述数据库脚本**，与代码版本、发布版本彼此独立，不要混用。
> 「一次代码发布」可能对应「零到多条 SQL 版本」。

---

## 三、每条记录必须填的字段

一条记录 = 一个三级标题 `### x.y.z 标题（日期）`，正文固定这几栏，**不许省略**（无就写「无」）：

| 栏 | 写什么 |
|---|---|
| **背景** | 为什么动库（对应哪个功能/哪个 bug），一句话 |
| **脚本文件** | 新增/修改了 `sql/` 下哪个文件；新建的增量脚本要写全名 |
| **★ 清除** | 被 `DROP TABLE` / `DROP COLUMN` / `DELETE` / `TRUNCATE` 干掉的**表名.列名 / 行范围**。没有就写「无」 |
| **★ 增加** | 被 `CREATE TABLE` / `ADD COLUMN` / `ADD INDEX` / `INSERT` 新增的**表名.列名 / 索引名 / 种子 id 区间** |
| **★ 修改** | 被 `ALTER TABLE ... MODIFY` / `UPDATE` 改动的**列、类型、取值范围**（要写清影响多少行、怎么算出来的） |
| **是否并回全量脚本** | `im_schema.sql` ☑/☐、`im_data.sql` ☑/☐，并给出**行号**（便于复核） |
| **需清的 Redis 键** | 见[第七节](#七改完-sql-必须清的-redis-缓存)，无则写「无」 |
| **回滚方式** | 结构类：反向前置备份表 + `ALTER` 语句；种子类：`DELETE` 掉新增 id；**必须给出可直接粘贴的 SQL** |
| **验证 SQL** | 一条能证明「变更确实生效」的查询（例如查 `information_schema.COLUMNS`） |

---

## 四、动作分类口径：清除 / 增加 / 修改

「清除 / 增加 / 修改」不是形容词，是**按 SQL 语句类型归类**的硬口径，照下表判：

| 标注 | 涵盖的语句 | 登记时要写到多细 |
|---|---|---|
| **★ 清除** | `DROP TABLE`、`ALTER ... DROP COLUMN`、`DROP INDEX`、`TRUNCATE`、`DELETE FROM` | 表名 / `表.列` / 索引名；`DELETE` 必须写**范围**（如 `id BETWEEN 1300 AND 1399`）和预估行数 |
| **★ 增加** | `CREATE TABLE`、`ALTER ... ADD COLUMN`、`ADD INDEX` / `ADD UNIQUE KEY`、`INSERT INTO` | 表名 / `表.列（类型 NULL 与否 AFTER 谁）` / 索引名；`INSERT` 必须写**种子 id 区间** |
| **★ 修改** | `ALTER ... MODIFY/CHANGE COLUMN`、`UPDATE`、改列长度、改默认值、改注释语义 | `表.列：旧定义 → 新定义`；`UPDATE` 要写 WHERE 条件与受影响行数 |

三条容易踩的空档，提前堵住：

1. **回滚段不算「清除」**。增量脚本末尾普遍有一段被注释掉的退路（`-- DROP TABLE ...` / `-- DELETE FROM ...` /
   `-- ALTER TABLE ... DROP COLUMN xxx;`），它们是**给人手工执行的注释**，不是本脚本的动作，
   登记时**不要**写进 ★清除，写进「回滚方式」。（2026-11-08 逐行核过：除 `im_data.sql` 外，
   `sql/` 下所有 `DELETE` 与绝大多数 `DROP TABLE` 都是注释态，只有 `migration_linkmic_gift.sql` /
   `migration_message_favorite.sql` / `migration_report.sql` 里有**活跃**的 `DROP TABLE IF EXISTS`，
   且只删自己即将重建的那几张表。）
2. **`DELETE FROM ... ; INSERT INTO ...` 这种「先清后插」是幂等写法**（典型就是 `im_data.sql`），实际净效果是「重建种子」。
   登记时两条都要标（★清除写了范围、★增加写了新 id 区间），并在背景里注明「幂等重建，非真删业务数据」。
3. **只存在于增量脚本、刻意不并回全量的对象**必须在「是否并回」一栏写明原因
   （现有唯一一例：`im_user_ban_bak_20261026` 备份表，见 1.0.0 基线）。

---

## 五、台账（新在上方）

### 1.0.0 起始基线（2026-11-08）

- **背景**：本文档建立。此前所有 SQL 变更散落在 21 份增量脚本与 `md/CHANGELOG.md` 里，
  从今天起统一收拢到本台账；这一版**不引入任何新变更**，只是给「当前库结构」拍一张带编号的照片。
- **脚本文件**：`sql/im_schema.sql`、`sql/im_data.sql`（两份全量）+ `sql/` 下 21 份增量脚本。

#### 基线 A：全量脚本现状（实测数字，非估算）

| 项 | 实测值 | 取数方式 |
|---|---|---|
| `im_schema.sql` 建表数 | **43** | `SELECT` 前用正则数 `CREATE TABLE`；与库内 `information_schema` 一致 |
| `im_data.sql` 的 `im_permission` 种子 | **id 101–125，共 25 行** | 目视核对 `sql/im_data.sql` 该区段 |
| `im_data.sql` 的 admin 权限绑定 | **`im_role_permission` id 6001–6025（25 条）**；user 角色从 **6101** 起 | 同上 |
| `im_data.sql` 的法定文本种子 | **`im_agreement` id 1301–1303（用户协议 / 隐私政策 / 服务条款）**，清理区间写作 `DELETE FROM im_agreement WHERE id BETWEEN 1300 AND 1399` | 同上 |
| `im_gift.icon` | **`VARCHAR(512) DEFAULT NULL`**（`im_schema.sql` 第 803 行） | 直接查建表语句 |

#### 基线 B：43 张表清单

```
im_user, im_role, im_permission, im_user_role, im_role_permission,
im_friend, im_friend_request,
im_conversation, im_conversation_member,
im_message, im_message_read, im_message_delete,
im_message_reaction, im_message_vote, im_message_essence, im_message_favorite,
im_group, im_group_member, im_group_join_request, im_group_robot,
im_file, im_moderation_log,
im_remote_device, im_remote_session, im_remote_audit_log,
im_interview_session, im_interview_message, im_interview_event,
im_ai_usage, im_ai_guard,
im_live_room, im_live_video, im_article,
im_user_wallet, im_gift, im_wallet_transaction, im_gift_record,
im_report,
im_config, im_sensitive_word, im_announcement,
im_agreement, im_user_agreement
```

#### 基线 C：存量升级补丁归档（21 份）

> 这 21 份脚本**都只服务于存量库升级**；全新库一律**不需要**跑（内容已并回两份全量脚本）。
> 每份的产生日期见 [`md/CHANGELOG.md`](./CHANGELOG.md) 对应批次，本表不重复登记，避免两处打架。
> 下表最后一列「并回」= 已核对 `im_schema.sql` / `im_data.sql` 里确实存在对应定义（结论见第八节复核语句）。

| 脚本 | ★ 清除 | ★ 增加 | ★ 修改 | 并回 |
|---|---|---|---|---|
| `migration_agreement.sql` | 无（末尾的 `DROP TABLE` / `DELETE` 都是**回滚段注释**） | 表 `im_agreement`、`im_user_agreement`；种子 `im_agreement` 三份法定文本；权限点「协议管理」 | 无 | ☑ schema + data |
| `migration_admin_system_config.sql` | 无（`DROP` / `DELETE` 均为回滚段注释） | 表 `im_config`、`im_sensitive_word`、`im_announcement`；权限点 | 无 | ☑ schema + data |
| `migration_ai_guard.sql` | 无（`DROP` / `DELETE` 均为回滚段注释） | 表 `im_ai_usage`、`im_ai_guard`；权限点 | 无 | ☑ schema + data |
| `migration_linkmic_gift.sql` | **活跃** `DROP TABLE IF EXISTS`：`im_gift_record`、`im_wallet_transaction`、`im_gift`、`im_user_wallet`（只删自己紧接着重建的四张表，不碰存量业务表） | 表 `im_user_wallet`、`im_gift`、`im_wallet_transaction`、`im_gift_record`；种子 `im_gift` 礼物目录、`im_user_wallet` | 无 | ☑ schema + data |
| `migration_gift_icon_width.sql` | 无 | 无 | `im_gift.icon`：长度放宽为 `VARCHAR(512) NULL`（旧 255），避免长图标 URL 截断 | ☑ schema |
| `migration_message_favorite.sql` | 活跃 `DROP TABLE IF EXISTS im_message_favorite`（自建自删的重建前置） | 表 `im_message_favorite` | 无 | ☑ schema |
| `migration_report.sql` | 活跃 `DROP TABLE IF EXISTS im_report`（同上） | 表 `im_report` | 无 | ☑ schema |
| `migration_missing_tables.sql` | 无 | 表 `im_message_reaction`、`im_message_vote`、`im_message_essence`、`im_message_favorite`、`im_group_join_request`、`im_group_robot`、`im_report` | `im_group` 加列 `invite_code VARCHAR(32) NULL`、`join_approval TINYINT NOT NULL DEFAULT 0`，并加唯一键 `uk_invite_code` | ☑ schema |
| `migration_quote_message.sql` | 无 | 索引 `im_message.idx_quote_msg (quote_msg_id)` | `im_message` 加列 `quote_msg_id BIGINT NULL`（引用/回复的原消息） | ☑ schema |
| `migration_moderation_log.sql` | 无 | 表 `im_moderation_log` | 无 | ☑ schema |
| `migration_moderation_evidence.sql` | 无 | 无 | `im_moderation_log` 加列 `evidence_file_id BIGINT NULL`（隔离区图片 → `im_file.id`） | ☑ schema |
| `migration_user_ban_duration.sql` | 无 | 表 `im_user_ban_bak_20261026`（**仅回滚用的备份表，故意不并回全量脚本**） | `im_user` 加列 `disable_until DATETIME NULL`（定时解封点）+ 索引 `idx_disable_until` | ☑ schema（除备份表，理由见左） |
| `migration_admin_phase2.sql` | 无 | 角色分级种子（`im_role` / `im_permission` / `im_role_permission`） | 无 | ☑ data |
| `migration_admin_phase3.sql` | 无 | 权限点：视频管理 / 直播管理 / 监控告警 / 日志管理 | 无 | ☑ data |
| `migration_admin_config.sql` | 无 | 权限点：系统配置 | 无 | ☑ data |
| `migration_admin_remote.sql` | 无 | 权限点：远程控制审计 | 无 | ☑ data |
| `migration_admin_wallet_interview_group_message.sql` | 无 | 权限点：钱包与礼物 / AI 面试记录 / 群组管理 / 消息管理 | 无 | ☑ data |
| `im_article_patch.sql` | 无（脚本自述「不 DROP / 不清任何既有表」） | 表 `im_article` | 无 | ☑ schema |
| `im_system_contact_patch.sql` | 无（两条 `DELETE` 在回滚段注释里） | 种子：保留联系人「客服(1)」「文件传输助手(2)」及 `im_friend` 关系回填 | 无 | ☑ data |
| `sql/im_schema.sql` | 开头 `DROP TABLE IF EXISTS` 全套（**因此禁止在有数据的库上重跑**） | 43 张表 + 全部索引/生成列 | — | 本体 |
| `sql/im_data.sql` | **活跃** `DELETE`：各种子表的自身 id 区间（先删后插的幂等写法） | 管理员/角色/权限 101–125 / 绑定 6001–6025 / 演示用户 / 礼物 / 钱包 / 协议 1301–1303 | — | 本体 |

- **是否并回全量脚本**：☑ 已核对。核对方式见第八节；核对日期 2026-11-08；
  **结论：21 份增量脚本的全部前向变更都已并回两份全量脚本**，唯一刻意不并回的是备份表
  `im_user_ban_bak_20261026`（它是 `migration_user_ban_duration.sql` 的回滚前置，
  新库根本没有「旧封禁数据」要保，并回去只会凭空多一张垃圾表）。
- **需清的 Redis 键**：无（本版不改任何东西）。
- **回滚方式**：无（本版不改任何东西）。
- **验证 SQL**：

```sql
-- 1) 表数量应为 43
SELECT COUNT(*) AS tbl_cnt FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'im_db';
-- 2) 增量脚本新增的列都应在
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'im_db'
  AND (TABLE_NAME, COLUMN_NAME) IN
      (('im_message','quote_msg_id'), ('im_group','invite_code'), ('im_group','join_approval'),
       ('im_user','disable_until'), ('im_moderation_log','evidence_file_id'), ('im_gift','icon'));
-- 3) 权限种子 25 条、admin 绑定 25 条
SELECT COUNT(*) FROM im_permission;                -- 期望 25（id 101..125）
SELECT COUNT(*) FROM im_role_permission WHERE id BETWEEN 6001 AND 6025;  -- 期望 25
-- 4) 三份法定文本在位
SELECT id, code, version, status FROM im_agreement WHERE id BETWEEN 1301 AND 1303;
```

### 1.0.1 模板（下一条照抄这个）

```markdown
### 1.0.1 标题写清「给哪张表动了什么」（YYYY-MM-DD）

- **背景**：
- **脚本文件**：`sql/xxx.sql`
- **★ 清除**：无
- **★ 增加**：表 `im_x.y ...`；种子 id `n0–n1`
- **★ 修改**：无
- **是否并回全量脚本**：`im_schema.sql` ☐ 第 __ 行；`im_data.sql` ☐ 第 __ 行
- **需清的 Redis 键**：`im:auth:perm:*` / 无
- **回滚方式**：
- **验证 SQL**：
```

> 复制时**别删栏目**：写「无」也必须留字，因为「空白」和「确实没有」在复核时是两回事。

---

## 六、两类环境的执行方式

**全新库**（空库，两条命令搞定，顺序不能颠倒）：

```powershell
mysql -u root -p --default-character-set=utf8mb4 -e "source sql/im_schema.sql"
mysql -u root -p --default-character-set=utf8mb4 im_db -e "source sql/im_data.sql"
```

- 禁止用 `<` 重定向导入（Windows 下会走 GBK，中文种子全变乱码），**必须** `-e "source 文件"`。
- 第二个命令带库名 `im_db`，因为 `im_data.sql` 不建库；`im_schema.sql` 自带 `CREATE DATABASE`。

**存量库**（有业务数据）：

- **绝不**跑 `im_schema.sql`（开头有整套 `DROP TABLE IF EXISTS`）。
- 从 `sql/` 里挑**没跑过的增量脚本**按功能顺序执行；每个脚本执行前先 `SELECT` 确认目标列/表不存在，
  避免 `Duplicate column` 报错（多数脚本已写成幂等，但 `ALTER ... ADD COLUMN` 不幂等）。
- 脚本**末尾注释里那段 `-- DROP / -- DELETE / -- ALTER ... DROP COLUMN` 是回滚退路**，不是要执行的内容，
  别顺手把它去掉注释跑了。

---

## 七、改完 SQL 必须清的 Redis 键

| 动的是什么 | 要清的键 | 为什么 |
|---|---|---|
| `im_permission` / `im_role_permission` / `im_user_role` 的种子 | `im:auth:perm:{userId}`、`im:auth:role:{userId}`（`im:auth:perm:*`） | 权限缓存 TTL 30 分钟且**登录不刷新**，不清就会出现「库里加了权限、后台菜单还是不显示」的假 bug |
| `im_config` / `im_sensitive_word` 这类运行期配置 | 对应的配置缓存键（按 `RedisKeys` 里的前缀） | 配置读的是缓存快照 |
| `im_agreement` 正文/版本 | 无需清（清单每次实时查） | 但**版本号一变，老用户的待同意状态会由后端判定为需重签**，这是业务行为不是缓存问题 |

> **共享 Redis 实例上的纪律**：只 `DEL` 明确列出的那几个键，
> **禁止 `FLUSHDB` / `FLUSHALL`**——这台实例上还挂着别的项目的库，一把清掉会把别人打挂。

---

## 八、验证 SQL 常用片段

```sql
-- 看某张表的完整列定义（比看脚本可靠）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='im_db' AND TABLE_NAME='im_message'
ORDER BY ORDINAL_POSITION;

-- 看索引
SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA='im_db' AND TABLE_NAME='im_group';

-- 库与脚本对不上时，先列出所有表再和[基线 B]逐行比
SHOW TABLES;
```

---

## 九、提交前自查清单

改 SQL 的那一轮，四个勾都打完才算完成：

- [ ] 本台账**新增一条记录**并按[第二节](#二版本号规则)升好版本号，七栏填满（无也写「无」）；
- [ ] 增量脚本的变更**已并回** `im_schema.sql` / `im_data.sql`（并在「是否并回」栏写上行号），
      或者**明确写出为什么不并**；
- [ ] 需要清缓存的话，把要清的键写进记录，并在 [md/CHANGELOG.md](./CHANGELOG.md) 提醒执行的人（MySQL 的写操作由本人执行，见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)）；
- [ ] [md/CHANGELOG.md](./CHANGELOG.md) 里有对应批次的描述（两份文档各司其职：CHANGELOG 讲「这一轮整体做了什么」，本文档讲「库结构怎么变」）。
