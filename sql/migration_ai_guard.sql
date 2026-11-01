-- =====================================================================================
--  增量迁移：管理后台四期 · 第六批（AI 用量护栏）
--
--  用途：给「已经跑过前五批、现在要多出 AI 用量管控这一屏」的环境使用。
--        全新环境直接跑 sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那两份），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 0 段的两条只读预检。
--
--  ★ 本批有两张新表（与第五批的「零 DDL」不同）：
--       im_ai_usage  AI 付费模型调用用量流水（只增不改不删，im-ai 写、后台读）
--       im_ai_guard  AI 功能封禁状态（一行一人、主键即 user_id，没封禁的人没有行）
--     以及一个新权限点（124 system:ai:guard）与它的 admin 绑定（6024）。
--
--  ⚠ 表没建会怎样：护栏是 fail-open 的——查询异常按「未封禁 / 已用量 0」放行并记 warn，
--     业务照跑，只是这一批的用量还没开始记账。所以「先上代码后跑 SQL」不会停机，
--     但 SQL 晚一天，账本就少一天，建议与发版同批执行。
--     权限点不跑则是新页面把超级管理员也拦在外面（接口回 code=1003，HTTP 仍 200），
--     im-admin-ui 的菜单项按同一个点判，会自动少一项。
--
--  ⚠ Sa-Token 按权限点串精确匹配，伞权限 system:manage 不是通配（只有 `*` 才是）。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动 = 两张新表 + 往 RBAC 两张既有表各加一行：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_ai_guard.sql
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_ai_guard.sql"
--
--  幂等：建表走 CREATE TABLE IF NOT EXISTS；RBAC 全部 INSERT IGNORE
--        （im_permission 有 uk_perm_code、im_role_permission 有 uk_role_perm），
--        重复执行不会主键冲突，可以安全重跑。授权行按 code 子查询取 role_id /
--        permission_id，不写死数字；绑定行本身的 id（6024）与 im_data.sql 同一批，
--        两份脚本对同一行给的是同一个主键，先后跑哪份都不会长出两行重复授权。
--
--  ★★ 跑完之后必做：清一次 Redis 的鉴权缓存，否则已登录会话还拿着旧权限列表，
--     新页面会一直 403 似拦（code=1003）：
--      mysql 无需动，在 Redis 里执行： KEYS im:auth:* 逐个 DEL（或 FLUSHDB 仅限开发库）
--
--  回滚见文末第 4 段：删两张新表 + 删本脚本加进去的 2 行 RBAC，其余数据一律未动。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 0. 预检（只读，不动数据）。两条都看完再决定要不要往下跑。
--
--    0.1 当前 RBAC 形状：期望 perms 最大 system 点 = 123（已跑过文章那批的库）。
--        如果已经是 124，说明本脚本已跑过，不必再跑。
-- -------------------------------------------------------------------------------------
SELECT COUNT(*) AS perm_rows, MAX(`id`) AS max_perm_id
FROM `im_permission`
WHERE `perm_code` LIKE 'system:%';

-- -------------------------------------------------------------------------------------
--    0.2 两张新表在不在：都应当报 0（存在即已建过，第 1 段的 IF NOT EXISTS 会跳过）。
-- -------------------------------------------------------------------------------------
SELECT COUNT(*) AS ai_guard_tables
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME IN ('im_ai_usage', 'im_ai_guard');

-- -------------------------------------------------------------------------------------
-- 1. 建表：AI 用量流水 + AI 封禁状态（结构与 im_schema.sql 完全一致）
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_ai_usage`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花）',
    `user_id`     BIGINT       NOT NULL COMMENT '发起人用户 ID',
    `scene`       VARCHAR(32)  NOT NULL COMMENT '计费场景：interview_chat 面试对话 / interview_asr 面试语音转文字 / interview_tts 文字合成朗读 / message_to_text 聊天语音转文字',
    `duration_ms` BIGINT       NOT NULL DEFAULT 0 COMMENT '模型调用实际耗时（毫秒，服务端单调时钟）：护栏的计费口径',
    `success`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否成功：1 成功（扣预算） 0 失败（只留痕不扣）',
    `error_msg`   VARCHAR(255)          DEFAULT NULL COMMENT '失败原因摘要（成功为空），写入前截到 255',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用完成时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（流水不更，列形同虚设，与面试事件表同构）',
    PRIMARY KEY (`id`),
    -- 按人重建 Redis 预算计数器与后台聚合：WHERE user_id=? [AND success=1] SUM(duration_ms)
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- 明细页按场景筛
    KEY `idx_scene_time` (`scene`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 付费模型调用用量流水（只增不改不删）';

CREATE TABLE IF NOT EXISTS `im_ai_guard`
(
    `user_id`     BIGINT      NOT NULL COMMENT '被管控的用户 ID，同时是主键：一行一人，没封禁的人根本没有这一行',
    `banned`      TINYINT     NOT NULL DEFAULT 1 COMMENT '是否封禁 AI 功能：0 未封禁（解封写 0 不删行，留 reason 供追溯） 1 已封禁',
    `reason`      VARCHAR(200)         DEFAULT NULL COMMENT '封禁原因（展示给被封者与管理台）',
    `update_by`   BIGINT               DEFAULT NULL COMMENT '最后一次封/解操作的后台操作人 ID（问责用）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次封禁时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后一次封/解时间',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 功能封禁状态表（只停付费模型调用，不动账号登录）';

-- -------------------------------------------------------------------------------------
-- 2. 新权限点：后台 AI 用量管控（单设一个点，理由与前面每屏一点相同——
--    这一屏能看到「谁在什么时间调了模型多久、失败原因是什么」的明细，
--    还能封禁个人的 AI 能力，不该顺带进「系统配置」或「面试记录」任何一个点）
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (124, 'system:ai:guard', '后台 AI 用量管控', 'system', '付费大模型调用的用量账本与封禁闸门（封 AI 不动登录）', 1);

-- -------------------------------------------------------------------------------------
-- 3. ★必执行★ 给 admin 补上这个点：少补就是新页面把超级管理员拦在外面。
--    按 code 子查询取 ID，不写死数字。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6024, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:ai:guard' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

-- -------------------------------------------------------------------------------------
-- 4. 回滚（单独按需执行，不要跟着主流程跑）。只删本脚本加进去的东西：
--
--    DELETE FROM `im_role_permission` WHERE `id` = 6024;
--    DELETE FROM `im_permission`      WHERE `id` = 124;
--    DROP TABLE IF EXISTS `im_ai_guard`;
--    DROP TABLE IF EXISTS `im_ai_usage`;
--
--    ★ 回滚前先想清楚：im_ai_usage 是账本，删表等于把「谁烧了多少」的历史全抹掉。
--      代码侧对表不存在的容忍是 fail-open（放行 + warn），删表不会让后端起不来。
-- -------------------------------------------------------------------------------------
