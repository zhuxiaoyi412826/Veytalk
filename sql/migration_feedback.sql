-- =====================================================================================
--  增量迁移：意见反馈批 · 新表 im_feedback + 权限点 127
--
--  用途：给「已经跑过前几批、现在要上意见反馈功能」的存量环境使用。
--        全新环境直接跑 sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那两份），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 0 段的两条只读预检。
--
--  ★ 本批有一张新表 + 一个新权限点：
--       im_feedback   意见反馈（设置页提交、后台回复处置；状态 0 待处理 → 1 已回复）
--       权限点 127 system:feedback 与它的 admin 绑定 6027
--
--  ⚠ 表没建会怎样：用户在设置页提交反馈 500、后台「意见反馈」页一进去就报错。
--     「先上代码后跑 SQL」不停机，但这一段时间里这两处不可用。权限点不跑则是新页面
--     把超级管理员也拦在外面（接口回 code=1003，HTTP 仍 200），im-admin-ui 的菜单项
--     按同一个点判，会自动少一项。
--
--  ⚠ Sa-Token 按权限点串精确匹配，伞权限 system:manage 不是通配（只有 `*` 才是）。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动 = 一张新表 + RBAC 两张既有表各加一行：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_feedback.sql
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_feedback.sql"
--      容器形态： bash Docker/scripts/import-sql.sh migration_feedback.sql
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
SELECT COUNT(*) AS feedback_table
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME = 'im_feedback';

-- -------------------------------------------------------------------------------------
--    0.2 当前 RBAC 形状：期望 perms 最大 system 点 = 126（已跑过版本管理批的库）。
--        如果已经是 127，说明本脚本已跑过，不必再跑。
-- -------------------------------------------------------------------------------------
SELECT COUNT(*) AS perm_rows, MAX(`id`) AS max_perm_id
FROM `im_permission`
WHERE `perm_code` LIKE 'system:%';

-- -------------------------------------------------------------------------------------
-- 1. 建表：意见反馈（结构与 im_schema.sql 第十七节完全一致）
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_feedback`
(
    `id`          BIGINT        NOT NULL COMMENT '反馈 ID（雪花）',
    `user_id`     BIGINT        NOT NULL COMMENT '提交人用户 ID。不建外键：账号注销后反馈行仍是有效台账（提交人显示为已注销）',
    `type`        VARCHAR(16)   NOT NULL COMMENT '反馈类型码：bug 功能异常 / suggestion 产品建议 / experience 体验问题 / other 其他，白名单在服务端（FeedbackType）',
    `content`     VARCHAR(1000) NOT NULL COMMENT '反馈正文，与后端 @Size(max=1000) 同宽；入库前 trim，空串拒收',
    `contact`     VARCHAR(64)            DEFAULT NULL COMMENT '联系方式（选填）：用户愿意被回访时才填；空串在写入前归一成 NULL',
    `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 已回复（reply 非空即置 1）',
    `reply`       VARCHAR(1000)          DEFAULT NULL COMMENT '后台回复原文；写入即把 status 置 1 并记 reply_by/reply_time，再次回复覆盖旧文并刷新两列',
    `reply_by`    BIGINT                 DEFAULT NULL COMMENT '回复人（后台账号 ID）',
    `reply_time`  DATETIME               DEFAULT NULL COMMENT '最近一次回复时刻',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='意见反馈（设置页提交、后台回复处置；状态 0 待处理 → 1 已回复，物理删除）';

-- -------------------------------------------------------------------------------------
-- 2. 新权限点：后台意见反馈。
--    单设一个点而不并进 system:report:handle：举报是「处置违规」（带封禁等危险动作），
--    反馈是「客服台账」（只有回复与删除），两者应当可以授权给不同的人。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (127, 'system:feedback', '后台意见反馈', 'system', '用户反馈的检索/查看/回复/删除', 1);

-- -------------------------------------------------------------------------------------
-- 3. ★必执行★ 给 admin 补上这个点：少补就是新页面把超级管理员拦在外面。
--    按 code 子查询取 ID，不写死数字。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6027, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:feedback' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

-- -------------------------------------------------------------------------------------
-- 4. 核对（只读）。三段都看完才算跑成。
-- -------------------------------------------------------------------------------------
--    4.1 表结构到位：期望 11 列、idx_status_create 索引在。
SELECT COUNT(*) AS col_count
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME = 'im_feedback';

SELECT INDEX_NAME, NON_UNIQUE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME = 'im_feedback'
  AND INDEX_NAME = 'idx_status_create';

--    4.2 RBAC 到位：期望 perm_rows=1、bound=1。
SELECT COUNT(*) AS perm_rows
FROM `im_permission`
WHERE `perm_code` = 'system:feedback';

SELECT COUNT(*) AS bound
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE r.`role_code` = 'admin'
  AND p.`perm_code` = 'system:feedback';

--    4.3 反馈表是空的，这是对的：跑完本脚本不该凭空多出任何一行反馈，
--        第一条由用户在设置页提交，那才是这个功能的正常现场。
SELECT COUNT(*) AS feedback_rows FROM `im_feedback`;

-- -------------------------------------------------------------------------------------
-- 5. 回滚（单独按需执行，不要跟着主流程跑）。只删本脚本加进去的东西：
--
--    DELETE FROM `im_role_permission` WHERE `id` = 6027;
--    DELETE FROM `im_permission`      WHERE `id` = 127;
--    DROP TABLE IF EXISTS `im_feedback`;
--
--    ★ DROP TABLE 前想清楚：表里若有用户提交过的反馈，删表就把「谁提过什么、回复过什么」
--      的台账全抹掉。只删 RBAC 那两行而留表，是唯一安全的「部分回滚」：新页面从菜单上
--      消失，用户提交端点照旧能写（只是没人看）。反过来只删表留权限点，
--      会做出一个「点了就 500」的菜单项。
-- -------------------------------------------------------------------------------------
