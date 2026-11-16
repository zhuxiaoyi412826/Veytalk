-- =====================================================================================
--  增量迁移：客户端版本管理批 · 新表 im_client_release + 权限点 126
--
--  用途：给「已经跑过前几批、现在要上后台客户端版本管理」的存量环境使用。
--        全新环境直接跑 sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那两份），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 0 段的两条只读预检。
--
--  ★ 本批有一张新表 + 一个新权限点：
--       im_client_release   客户端安装包版本（一行一个包，字节在文件模块；同平台同时只启用一个）
--       权限点 126 system:release 与它的 admin 绑定 6026
--
--  ⚠ 表没建会怎样：后台「版本管理」页一进去就报错、官网下载页 GET /api/release/list 500
--     （官网前端对失败有回落：仍显示静态卡片，不会白屏）。「先上代码后跑 SQL」不停机，
--     但这一段时间里后台这一屏不可用。权限点不跑则是新页面把超级管理员也拦在外面
--     （接口回 code=1003，HTTP 仍 200），im-admin-ui 的菜单项按同一个点判，会自动少一项。
--
--  ⚠ Sa-Token 按权限点串精确匹配，伞权限 system:manage 不是通配（只有 `*` 才是）。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动 = 一张新表 + RBAC 两张既有表各加一行：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_client_release.sql
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_client_release.sql"
--
--  幂等：建表走 CREATE TABLE IF NOT EXISTS；RBAC 两行 INSERT IGNORE
--        （im_permission 有 uk_perm_code、im_role_permission 有 uk_role_perm），可以安全重跑。
--        授权行按 code 子查询取 role_id / permission_id，不写死数字。
--
--  ★★ 跑完之后必做：清一次 Redis 的鉴权缓存，否则已登录会话还拿着旧权限列表，
--     新页面会一直 403 拦截（code=1003；这一档缓存 TTL 30 分钟且「登录」这个动作不刷新它，
--     所以退登重登不管用）：
--        容器形态： bash Docker/scripts/clear-perm-cache.sh   （已封装「先数→批量删→再数确认 0」）
--        Windows  ： redis-cli KEYS "im:auth:*"  取清单，再整份贴进 redis-cli DEL k1 k2 ...
--        ★ 不要 FLUSHDB / FLUSHALL：这台实例常年与其他项目共用，一把清掉会连别人的会话一起清。
--
--  回滚见文末第 5 段：删新表 + 删本脚本加进去的 2 行 RBAC，其余数据一律未动。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 0. 预检（只读，不动数据）。两条都看完再决定要不要往下跑。
--
--    0.1 新表在不在：应当报 0（存在即已建过，第 1 段的 IF NOT EXISTS 会跳过）。
-- -------------------------------------------------------------------------------------
SELECT COUNT(*) AS release_table
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME = 'im_client_release';

-- -------------------------------------------------------------------------------------
--    0.2 当前 RBAC 形状：期望 perms 最大 system 点 = 125（已跑过合规批的库）。
--        如果已经是 126，说明本脚本已跑过，不必再跑。
-- -------------------------------------------------------------------------------------
SELECT COUNT(*) AS perm_rows, MAX(`id`) AS max_perm_id
FROM `im_permission`
WHERE `perm_code` LIKE 'system:%';

-- -------------------------------------------------------------------------------------
-- 1. 建表：客户端安装包版本（结构与 im_schema.sql 第十六节完全一致）
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_client_release`
(
    `id`             BIGINT       NOT NULL COMMENT '版本 ID（雪花）',
    `platform`       VARCHAR(32)  NOT NULL COMMENT '平台码：windows_setup / windows_portable / android / macos / linux，白名单在服务端（ReleasePlatform），启用互斥按它分组',
    `version`        VARCHAR(32)  NOT NULL COMMENT '版本号（如 1.0.1）。只做长度校验不做语义比较：1.10 与 1.9 谁新由发版的人知道，代码不猜',
    `release_note`   VARCHAR(500)          DEFAULT NULL COMMENT '更新说明，可空；官网卡片与后台列表原样展示，空串在写入前归一成 NULL',
    `file_id`        BIGINT       NOT NULL COMMENT '安装包字节所在的 im_file 主键。不建外键：文件行被人工清理时本行照常可读（列表带 filePresent 提示），外键会把这个降级变成报错',
    `file_name`      VARCHAR(255) NOT NULL COMMENT '安装包展示名（含扩展名），公开下载时 Content-Disposition 的文件名取它',
    `file_size`      BIGINT       NOT NULL DEFAULT 0 COMMENT '安装包字节数，登记时从文件行抄来；下载响应的 Content-Length 取它',
    `md5`            CHAR(32)              DEFAULT NULL COMMENT '整文件 MD5：官网展示供用户校验下载完整性，也是分片上传秒传命中的依据',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 停用（官网不返回、公开下载拒绝） 1 启用（该平台官网唯一下载入口，同平台至多一行）',
    `download_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '累计下载次数：公开下载端点每成功流式输出一次加 1（setSql 原子自增），运营数字不是账，累加失败只记日志',
    `create_by`      BIGINT                DEFAULT NULL COMMENT '创建人（后台账号 ID）',
    `update_by`      BIGINT                DEFAULT NULL COMMENT '最后修改人（后台账号 ID）：启用/停用/换包都算修改',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_version` (`platform`, `version`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='客户端安装包版本（一行一个包，字节在文件模块；同平台同时只启用一个，官网下载页只递启用行）';

-- -------------------------------------------------------------------------------------
-- 2. 新权限点：后台版本管理。
--    单设一个点而不并进 system:config：这一屏动的是「往公网递一个可执行文件」，
--    启用的那一行会成为官网的匿名下载口。完整理由见 ImConstants.PERM_SYSTEM_RELEASE 的注释。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (126, 'system:release', '后台版本管理', 'system', '客户端安装包的上传/启用/停用/删除（启用即官网匿名可下载）', 1);

-- -------------------------------------------------------------------------------------
-- 3. ★必执行★ 给 admin 补上这个点：少补就是新页面把超级管理员拦在外面。
--    按 code 子查询取 ID，不写死数字。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6026, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:release' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

-- -------------------------------------------------------------------------------------
-- 4. 核对（只读）。三段都看完才算跑成。
-- -------------------------------------------------------------------------------------
--    4.1 表结构到位：期望 14 列、uk_platform_version 唯一键在。
SELECT COUNT(*) AS col_count
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME = 'im_client_release';

SELECT INDEX_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME = 'im_client_release'
  AND INDEX_NAME = 'uk_platform_version';

--    4.2 RBAC 到位：期望 perm_rows=1、bound=1。
SELECT COUNT(*) AS perm_rows
FROM `im_permission`
WHERE `perm_code` = 'system:release';

SELECT COUNT(*) AS bound
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE r.`role_code` = 'admin'
  AND p.`perm_code` = 'system:release';

--    4.3 版本表是空的，这是对的：跑完本脚本不该凭空多出任何一行版本，
--        第一个安装包由后台「版本管理」页上传登记，那才是这个功能的正常现场。
SELECT COUNT(*) AS release_rows FROM `im_client_release`;

-- -------------------------------------------------------------------------------------
-- 5. 回滚（单独按需执行，不要跟着主流程跑）。只删本脚本加进去的东西：
--
--    DELETE FROM `im_role_permission` WHERE `id` = 6026;
--    DELETE FROM `im_permission`      WHERE `id` = 126;
--    DROP TABLE IF EXISTS `im_client_release`;
--
--    ★ DROP TABLE 前想清楚：表里若有已登记的版本行，删表就把「哪个平台当前启用哪个包」
--      的记录全抹掉（安装包字节仍在 im_file/MinIO 里，不随本表消失）。
--    ★ 只删 RBAC 那两行而留表，是唯一安全的「部分回滚」：新页面从菜单上消失，
--      官网 /api/release/list 与下载端点照旧能用。反过来只删表留权限点，
--      会做出一个「点了就 500」的菜单项。
-- -------------------------------------------------------------------------------------
