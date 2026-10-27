-- =====================================================================================
--  增量迁移：管理后台四期 · 第四批（系统配置页：运行期配置 + 在线敏感词 + 全服公告）
--
--  用途：给「已经跑过二三期、现在要多出一个能改全服开关、加敏感词、发公告的后台页」的
--        环境使用。全新环境直接跑 sql/im_schema.sql + sql/im_data.sql 即可
--        （本脚本内容已并入那两份），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份先跑第 1 段那条只读预检，
--        tables_exist 数到 3、perm_118 与 admin_bind_118 都是 1，就不用再跑第 2、3 段。
--
--  ★★ 本批的 DDL 是【新建三张表】，一张都不改既有表：
--        im_config          运行期配置的覆盖项（最多 27 行，一行对应一个白名单旋钮）
--        im_sensitive_word  后台在线加的敏感词（文件词库之上的增量）
--        im_announcement    全服公告（后台第一次主动对全部在线连接喊话）
--      三张表都归 im-admin 模块持有，与既有的 im_report 同一先例：**不新增 SPI 契约、
--      不动任何 pom 依赖**。星型架构管的是跨模块取数，把一个模块自己的表拆成两半不算。
--
--  ⚠ 为什么要有这一份：后端新增 /api/admin/config/item（GET/PUT/DELETE）、
--     /api/admin/sensitive-word（page/POST/DELETE）、/api/admin/announcement（六个动作）
--     与用户侧 GET /api/announcement/active。前三组里公告那组要求一个**新权限点 118**；
--     而三组都要读这三张表——表不存在时它们不是「少了个新功能」，是一点开就 1146。
--
--  ★ im_config 不灌任何种子行，空表是正常状态：这张表只存「被后台改过的那一项」，
--     没改过的项在库里根本没有行，运行态就是 application.yml 与代码默认值。
--     所以跑完脚本后 SELECT COUNT(*) FROM im_config 数到 0 不是漏跑，别去补数据。
--     配置项的标题/说明/取值范围写死在 AdminConfigKey 枚举里，库里不重复一份——
--     两份真相迟早漂移成「界面上写的和代码里做的不一样」。
--
--  ★ 也不给 im_sensitive_word / im_announcement 灌演示行：与三期给 im_report 留空表
--     同一取向。后台里凭空长出两条「测试公告」，验收的人得先猜哪条是真的。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。本批要动的是既有 RBAC 两张表 + 三张新表，
--     新表无前像可存（不存在就是不存在），所以备份只需要覆盖 RBAC 那两行改得掉的东西：
--        mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > "$env:USERPROFILE\Desktop\backup_rbac_admin_system_config.sql"
--      ★ 别把产物放在仓库目录里——.gitignore 没有忽略 *.sql，放项目根目录会被 git add . 顺手收走。
--      ★ 跑完拿 Get-Item <那个路径> | Select-Object Length 确认非 0：PowerShell 的 > 在命令失败时
--        也会先创建一个 0 字节空文件，只看「文件存在」等于没验。
--
--  ★ 不跑会怎样（按影响面从大到小）：
--     ① GET /api/announcement/active（用户侧，只要登录）会报 1146 Table doesn't exist。
--        它在用户端是「登录成功后与 WS 重连后各拉一次」的补拉，前端把失败当可选步骤处理，
--        不会挡住登录，但这一屏的公告功能等于没有。
--     ② 后台「系统配置」「敏感词」「公告」三屏：列表查询与保存动作直接失败。
--     ③ 后端【启动不会失败】——这是刻意做的：配置与词库那两条读链路（启动快照 + 每 30 秒轮询）
--        各自 catch 住缺表并只记一条 warn，文件词库照常生效、全服开关照常跟随配置文件。
--        「表还没建」不该演变成停机事故，但也不能当成可以拖着不跑：②③ 那一软一硬要分清。
--
--  怎么判断自己的库要不要跑（只读，不动数据）：跑第 1 段。tables_exist = 3 且
--     max_sys_perm 到 118，就说明本脚本已经落过了。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_admin_system_config.sql"
--     ★ 想省掉交互输密码，密码参数必须整个加引号："-p$env:MYSQL_PASSWORD"。
--       写成裸的 -p$env:MYSQL_PASSWORD 会 1045——PowerShell 不对裸词里的 $var 做展开，
--       传给 mysql 的是字面串 "$env:MYSQL_PASSWORD"，报错还挂着 (using password: YES)，
--       看着像密码错了，其实是参数没展开（本批现场实测；同一条命令换成引号形式立刻通）。
--
--  幂等：本脚本【可以整份重跑】，与上一批不同。那批是 ALTER TABLE ADD COLUMN，MySQL 没有
--     ADD COLUMN IF NOT EXISTS，重跑会 1060 并中断后续语句；这批三条建表都带
--     IF NOT EXISTS（表在就整条跳过、不动结构不碰数据），权限行走 INSERT IGNORE
--     （uk_perm_code / uk_role_perm 兜着），所以重跑最坏是「Affected rows: 0」。
--
--  回滚（先退后端，再删表；反了会让新代码遇不上表、旧代码遇上新表，两边都报错）：
--      DROP TABLE `im_announcement`;
--      DROP TABLE `im_sensitive_word`;
--      DROP TABLE `im_config`;
--      DELETE FROM `im_role_permission` WHERE `id` = 6018;
--      DELETE FROM `im_permission`      WHERE `id` = 118;
--    DROP TABLE im_config 会丢掉「后台改过哪几项」这一事实本身：那几项会退回跟随配置文件，
--    而谁在什么时候改成了什么，只有应用日志里还留着。要留这份历史就先 RENAME 再删：
--      RENAME TABLE `im_config` TO `im_config_bak_20261026`;
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 预检（只读）：三张新表存在性 + 权限点现状。tables_missing=0 且 perm_118=1 就无需往下跑。
--    顺带看一眼 im_config 现在几行——空表是正常状态（见文件头），不要为它补数据。
-- -------------------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME IN ('im_config', 'im_sensitive_word', 'im_announcement')) AS tables_exist,
       (SELECT COUNT(*) FROM `im_permission` WHERE `perm_code` = 'system:announcement' AND `deleted` = 0) AS perm_118,
       (SELECT COUNT(*) FROM `im_role_permission` rp JOIN `im_role` r ON r.`id` = rp.`role_id`
            JOIN `im_permission` p ON p.`id` = rp.`permission_id`
        WHERE r.`role_code` = 'admin' AND p.`perm_code` = 'system:announcement')          AS admin_bind_118,
       (SELECT MAX(`id`) FROM `im_permission` WHERE `perm_code` LIKE 'system:%')          AS max_sys_perm;

-- -------------------------------------------------------------------------------------
-- 2. 建三张新表（与 sql/im_schema.sql 第十三、十四节逐字一致，包括注释文案与列顺序，
--    只多了 IF NOT EXISTS）。三份 DDL 彼此无依赖，顺序无所谓。
-- -------------------------------------------------------------------------------------

-- 2.1 运行期配置覆盖项
CREATE TABLE IF NOT EXISTS `im_config`
(
    `id`           BIGINT       NOT NULL COMMENT '配置 ID（雪花）',
    `config_key`   VARCHAR(128) NOT NULL COMMENT '配置键，与 AdminConfigKey 的 key 一一对应（如 message.recall-limit-seconds）；不在白名单里的键服务端直接拒写',
    `config_value` VARCHAR(255) NOT NULL COMMENT '配置值，一律以字符串存（"true" / "120"），按枚举声明的类型解释：布尔只认 true/false 两个字面量，数字必须落在枚举声明的 [min,max] 内',
    `update_by`    BIGINT                DEFAULT NULL COMMENT '最后修改人（后台账号 ID）：这一行是谁点出来的，日志之外只有这里查得到',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次被后台覆盖的时刻',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次覆盖的时刻（多实例轮询比对的签名里有它）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='运行期配置覆盖项（只存后台改过的项，恢复默认即物理删行，总行数不超过白名单项数）';

-- 2.2 在线敏感词
CREATE TABLE IF NOT EXISTS `im_sensitive_word`
(
    `id`          BIGINT      NOT NULL COMMENT '词条 ID（雪花）',
    `word`        VARCHAR(64) NOT NULL COMMENT '词条：已按过滤器口径折叠，只含汉字与小写字母数字、至少 2 个字符（校验在 im-common 的 SensitiveWordFilter 里，不在这里也不在前端重复一份）',
    `remark`      VARCHAR(200)         DEFAULT NULL COMMENT '加词原因，可空。词表没有审计表兜底，这一列是唯一能问出「为什么加它」的地方',
    `create_by`   BIGINT               DEFAULT NULL COMMENT '创建人（后台账号 ID）：词只能加和删，没有改，所以只有创建人',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（签名比对会看它）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='在线敏感词（静态文件词库之上的增量，物理删除才释放唯一键）';

-- 2.3 全服公告
CREATE TABLE IF NOT EXISTS `im_announcement`
(
    `id`           BIGINT        NOT NULL COMMENT '公告 ID（雪花）；用户端按它做本地已读去重，所以同一台设备不会重复弹同一条',
    `title`        VARCHAR(100)  NOT NULL COMMENT '标题：通知的标题位直接用它的原文，不让前端拿正文截断去猜。写入前按敏感词遮罩并截断',
    `content`      VARCHAR(2000) NOT NULL COMMENT '正文，与后端 @Size(max=2000) 同宽。公告也要过自己那套词库，否则「把敏感词写进公告」就成了绕过过滤器的正门',
    `tone`         VARCHAR(16)   NOT NULL DEFAULT 'info' COMMENT '通知样式：info / success / warning / error，与举报回执同一口径；收在服务端而不让前端猜，改一句中文文案不该把通知颜色改掉',
    `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0 草稿（只在后台可见） 1 已发布 2 已撤回（已弹出去的通知收不回来）',
    `publish_time` DATETIME               DEFAULT NULL COMMENT '发布时刻，草稿为空。用户端按它倒序取「当前生效的最新几条」',
    `expire_time`  DATETIME               DEFAULT NULL COMMENT '失效时刻，空为不失效；只影响补拉，不会自动改 status',
    `create_by`    BIGINT                 DEFAULT NULL COMMENT '创建人（后台账号 ID）',
    `update_by`    BIGINT                 DEFAULT NULL COMMENT '最后修改人（后台账号 ID）：发布与撤回都算修改，所以这一列总是比 create_by 新',
    `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（草稿时刻）',
    `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_publish` (`status`, `publish_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='全服公告（后台唯一主动广播通道，状态 0→1→2；无已读回执表，去重记在客户端本地）';

-- -------------------------------------------------------------------------------------
-- 3. ★必执行★ 一个新权限点 + 给 admin 补齐它
--    118 没有并进 116（system:config）：改一个全服旋钮改错了可以立刻改回来，而发公告是对着
--    所有在线连接喊话、弹出去的通知收不回来（撤回只影响之后的补拉）——可撤销性不是一个量级，
--    分成两个点才能让「能调开关」与「能发公告」是两个人。
--    文案与 im_data.sql 逐字一致，否则同一行数据会因为「走了哪份脚本」而长成两个样子。
--    少补第 3.2 段就是新页面把超级管理员拦在外面（返回 code=1003，HTTP 仍是 200）：
--    Sa-Token 按权限点串精确匹配，伞权限 system:manage 不是通配（只有 `*` 才是）。
--    在线敏感词那一屏【不另设点】，仍走 116：加一个词与改一个开关是同一类动作，
--    影响面也都是「之后的消息」，没有哪一边更大。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (118, 'system:announcement', '后台公告管理', 'system', '全服公告的编辑、发布与撤回（发布会向全部在线连接广播）', 1);

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6018, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:announcement' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

-- moderator / operator 两个示范角色刻意不动：发公告是「以官方名义对全服喊话」，先只给超级管理员。
-- 真要下放就走后台「角色分配」——那是唯一即时生效的入口（保存时服务端主动 evictCache），
-- 不必再回来改这份脚本。

-- -------------------------------------------------------------------------------------
-- 4. 核对（只读）
-- -------------------------------------------------------------------------------------
-- 4.1 三张表的结构都在：应该数到 6 / 6 / 11 列（im_config 六列、im_sensitive_word 六列、
--     im_announcement 十一列）。行数与列数是两回事：im_config 可以是 0 行而仍有这六列。
SELECT TABLE_NAME,
       (SELECT COUNT(*) FROM information_schema.COLUMNS c
        WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME) col_count,
       TABLE_COMMENT
FROM information_schema.TABLES t
WHERE t.TABLE_SCHEMA = DATABASE()
  AND t.TABLE_NAME IN ('im_config', 'im_sensitive_word', 'im_announcement')
ORDER BY t.TABLE_NAME;

-- 4.2 唯一键在不在（这三个约束是功能语义的一部分，少了就是另一回事）：
--     uk_config_key 少了 → 同一个 key 能长出两行，「恢复默认」删掉一行而另一行还在生效；
--     uk_word 少了 → 同一个词重复入库，且后台会显示成两条看起来不一样的记录；
--     idx_status_publish 少了 → 用户端每次登录都要全表扫公告。
SELECT TABLE_NAME,
       INDEX_NAME,
       NON_UNIQUE,
       COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('im_config', 'im_sensitive_word', 'im_announcement')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

-- 4.3 权限：admin 那一行必须在。空的就是第 3 段没落到库上（多半 role_code 不叫 'admin'），
--     此时先别启动新版后端，也别急着往下清缓存。
SELECT r.`role_code`,
       p.`id`,
       p.`perm_code`,
       p.`perm_name`
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`id` = 118
ORDER BY r.`role_code`;

-- 4.4 空表确认（只读）：这两张跑完就该是 0 行，im_announcement 也是 0 行。
--     im_config 有行意味着这台库以前已经有人动过旋钮，重启后库里的值会盖住配置文件——
--     那是设计如此（以库为准），不是脏数据，别顺手删。
SELECT (SELECT COUNT(*) FROM `im_config`)          AS config_rows,
       (SELECT COUNT(*) FROM `im_sensitive_word`)  AS word_rows,
       (SELECT COUNT(*) FROM `im_announcement`)    AS announce_rows;

-- -------------------------------------------------------------------------------------
-- 5. ★★★ 本批【必须清一次权限缓存】，这一条与上一批相反，别照抄「本批不需要清」的结论。
--    上一批（第三批、封禁时长）零 RBAC 改动，所以那一批不动 im:auth:* 也没事；
--    本批新开了 118，而 Sa-Token 的权限与角色缓存在 Redis 里、TTL 30 分钟
--    （im:auth:perm:{userId} / im:auth:role:{userId}）。直接写库缓存是不知道的，
--    「退出再登录」也不管用——登录动作本身既不写也不删这两个键。
--    不清的症状：admin 进了后台、菜单里也看不到「公告管理」那一项，硬调接口回 code=1003，
--    而第 4.3 段明明数到了那一行——看起来就像「脚本跑了没生效」，很容易误判成脚本问题。
--    （Redis 只是缓存，可直接执行，不会丢业务数据。三步，都在 PowerShell 下实测过：）
--      ① redis-cli keys "im:auth:*"      ← 先只读列出有哪些键（本仓库 dbsize 才几百，KEYS 直接用没关系）
--      ② redis-cli DEL "im:auth:perm:1000" "im:auth:role:1000" "…把 ① 列出的键名全写进同一条、空格分隔…"
--         返回值就是被删掉的个数；给所有后台账号清一遍，不只 admin
--      ③ redis-cli keys "im:auth:*"      ← 应回 (empty list or set)
--    ★ 三种「看着省事」的写法都不要用，全是实测踩过的坑：
--      - `redis-cli --scan | ForEach-Object { redis-cli DEL $_ }` 在 Windows PowerShell 下静默什么都不删
--        （第二批清 117 时实测踩过）；
--      - `redis-cli EVAL "...keys('im:auth:*')...del..." 0` 也不行：PowerShell 会把那条 lua 拆成多个参数，
--        直接回 `(error) ERR wrong number of arguments for 'eval' command`（第五批清缓存时实测；
--        这条原本就是为了绕开上面那个坑而写进这批文档的，结果它自己也跑不通，所以改回 ①②③ 三步先列后删）；
--      - `redis-cli --scan --pattern "im:auth:*" | Measure-Object -Line` 数出 0 **不代表清干净了**，
--        是管道吞了输出；复核残留只认第 ③ 步那条 keys。
--    另一条路是等 TTL 自然过期（30 分钟），但那样会把「缓存没清」和「脚本没跑」混在一起。
--
--    然后：重启后端（三张新表 + 新实体 + 新的每 30 秒同步任务），
--    重新构建/刷新 im-admin-ui（系统配置页扩三块 + 新增公告管理页）与 im-ui（公告补拉）。
--
--    多实例部署无需加分布式锁、也无需选主：配置与词库都靠「30 秒轮询 + 签名守卫」收敛，
--    所以已知边界是【其他节点最迟 30 秒后生效】，本节点立即生效（写路径当场应用）。
--    公告的广播只有一个节点会发（就是管理员连的那个），不需要去重。
-- =====================================================================================
