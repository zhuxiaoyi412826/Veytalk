-- =====================================================================================
--  引导脚本（台账 1.0.5）：SQL 版本管理底座 —— 两张表 + 基线登记 + 一个新权限点
--
--  用途：把「手写 SQL 人肉执行」升级为「版本脚本配对（V/U）+ 后台可视化 + 一键回滚」。
--        本脚本是全项目最后一个需要手动 source 的 DDL——它建完自己的台账表之后，
--        1.0.6 起的增量都进 sql/versions/，由后台「SQL 版本管理」页应用与回滚。
--
--  ★为什么它自己不能走页面：先有台账表才能记台账，鸡生蛋问题，所以它保持手工执行一次。
--
--  ★增加：im_schema_migration（版本台账）、im_schema_migration_log（操作流水）两张新表；
--         各登 1 行基线（version 1.0.0，operator SYSTEM，代表 1.0.0~1.0.3 全部存量变更）；
--         im_permission 加 1 行（id 128，system:schema:manage）；im_role_permission 加 1 行
--         （id 6028，授给 admin 角色，按 role_code 子查询取 ID 不写死数字）。
--  ★清除：无。  ★修改：无（既有表一行一列都不动）。
--
--  ★数据库写操作一律由你本人执行（团队约定）。改动只涉及新建 2 表 + RBAC 2 表加行，
--    前像备份（RBAC 两表整表导出即可，新表无前像）：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_schema_ver.sql
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u root -p --default-character-set=utf8mb4 im_db -e "source sql/migration_schema_version.sql"
--
--  幂等：两张表 CREATE TABLE IF NOT EXISTS；全部种子走 INSERT IGNORE
--        （im_schema_migration 的 uk_version、im_permission 的 uk_perm_code、
--         im_role_permission 的 uk_role_perm 兜底），重复执行安全。
--
--  回滚（本脚本自身）：
--      DROP TABLE IF EXISTS `im_schema_migration_log`;
--      DROP TABLE IF EXISTS `im_schema_migration`;
--      DELETE FROM `im_role_permission` WHERE `id` = 6028;
--      DELETE FROM `im_permission`      WHERE `id` = 128;
--    （回滚后把后端一起退回上一版，否则 /api/admin/schema/** 因缺表整体报错、
--      菜单里的「SQL 版本管理」页打不开——其余页面不受影响。）
--
--  验证（只读）：
--      SELECT version, script_name, status, operator_name FROM im_schema_migration;         -- 应看到 1.0.0 基线行
--      SELECT COUNT(*) FROM im_schema_migration_log;                                        -- 应为 1
--      SELECT (SELECT COUNT(*) FROM im_permission WHERE deleted=0) p,
--             (SELECT COUNT(*) FROM im_role_permission) b;                                  -- 跑过三期后 p+1、b+1
--      SELECT id, perm_code FROM im_permission WHERE id = 128;                              -- 应返回 system:schema:manage
--
--  ★执行完必须清 Redis 权限缓存（im:auth:perm:1000 / im:auth:role:1000），
--    否则 admin 登录后菜单里看不到「SQL 版本管理」（缓存 TTL 30 分钟，这一步助手代做）。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 版本台账表：一行 = 一个版本号当前状态。checksum 用于发现「已应用的脚本后来被人改过」
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_schema_migration`
(
    `id`             BIGINT       NOT NULL COMMENT '记录 ID（雪花；基线行固定 1）',
    `version`        VARCHAR(32)  NOT NULL COMMENT '版本号，与 md/SQL变更文档.md 台账同一序列（如 1.0.4）',
    `script_name`    VARCHAR(128) NOT NULL COMMENT '向前脚本文件名（sql/versions/V*.sql）；基线行写合集说明文本',
    `undo_name`      VARCHAR(128)          DEFAULT NULL COMMENT '回滚脚本文件名（U*.sql）；NULL 表示该版本不可一键回滚',
    `description`    VARCHAR(255)          DEFAULT NULL COMMENT '一句话说明（与台账条目对应）',
    `checksum`       CHAR(64)              DEFAULT NULL COMMENT '向前脚本文件 SHA-256 小写；基线行为 NULL，重算不一致=脚本被偷改',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 已应用 0 已回滚 2 应用失败（半截脚本，人工修补后重放）',
    `operator_id`    BIGINT                DEFAULT NULL COMMENT '执行人用户 ID；SYSTEM 引导行为 NULL',
    `operator_name`  VARCHAR(64)           DEFAULT NULL COMMENT '执行人昵称快照（昵称后来改了不影响历史可读）；基线行写 SYSTEM',
    `applied_at`     DATETIME              DEFAULT NULL COMMENT '最近一次应用成功时刻',
    `rolled_back_at` DATETIME              DEFAULT NULL COMMENT '最近一次回滚成功时刻',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_version` (`version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SQL 版本台账（谁/何时应用了哪个版本；一键回滚与漂移检测的依据）';

-- -------------------------------------------------------------------------------------
-- 2. 操作流水表：一次 apply/rollback 一行、只 INSERT——后台时间线的数据源。
--    与台账表分开的原因：台账要按 version 唯一收敛当前态，流水要原样留着每次尝试
--    （含失败），两张表的读写形状不一样，塞一张表两边都别扭。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_schema_migration_log`
(
    `id`              BIGINT       NOT NULL COMMENT '日志 ID（雪花；基线行固定 1）',
    `version`         VARCHAR(32)  NOT NULL COMMENT '被操作的版本号',
    `action`          VARCHAR(16)  NOT NULL COMMENT '动作：APPLY 应用 / ROLLBACK 回滚 / MARK 手工登记基线',
    `result`          VARCHAR(16)  NOT NULL COMMENT '结果：SUCCESS / FAILED（失败也记行，半途炸掉的脚本正是最需要留痕的）',
    `statement_count` INT          NOT NULL DEFAULT 0 COMMENT '成功执行的语句条数（FAILED 时为挂掉前跑掉的条数）',
    `duration_ms`     BIGINT       NOT NULL DEFAULT 0 COMMENT '整个脚本耗时（毫秒）',
    `message`         VARCHAR(1000)         DEFAULT NULL COMMENT '失败原因或备注，写入前截到列宽',
    `operator_id`     BIGINT                DEFAULT NULL COMMENT '操作人用户 ID',
    `operator_name`   VARCHAR(64)           DEFAULT NULL COMMENT '操作人昵称快照',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时刻（本表只 INSERT，这一列就是事件时间）',
    PRIMARY KEY (`id`),
    KEY `idx_version` (`version`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SQL 版本操作流水（变更过程时间线；只 INSERT 不更新不删除）';

-- -------------------------------------------------------------------------------------
-- 3. 基线登记：1.0.0~1.0.3 的存量变更（历史 migration_*.sql 合集）此前已人肉执行完毕，
--    在这里补登为「已应用」，让页面从 1.0.4 起接管。不做成逐行登记的原因：那些脚本
--    不在 sql/versions/ 里、没有配 U 脚本，登记成文件行反而会让页面展示不存在的按钮。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_schema_migration` (`id`, `version`, `script_name`, `undo_name`, `description`, `checksum`, `status`,
                                          `operator_name`, `applied_at`)
VALUES (1, '1.0.0', '历史脚本合集（im_schema.sql + im_data.sql + migration_*.sql）', NULL,
        '2026-11-08 库结构现状起至台账 1.0.3 的全部存量变更，引导时一次性补登', NULL, 1, 'SYSTEM', NOW());

INSERT IGNORE INTO `im_schema_migration_log` (`id`, `version`, `action`, `result`, `statement_count`, `duration_ms`,
                                              `message`, `operator_name`)
VALUES (1, '1.0.0', 'MARK', 'SUCCESS', 0, 0, '引导脚本补登基线，详见 md/SQL变更文档.md 1.0.0~1.0.3 条目', 'SYSTEM');

-- -------------------------------------------------------------------------------------
-- 4. 新权限点 system:schema:manage（id 128）+ admin 角色绑定（id 6028）。
--    能改库结构 = 最高危的后台能力，只给超级管理员，演示角色 moderator/operator 刻意不给。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (128, 'system:schema:manage', 'SQL版本管理', 'system', '查看库版本台账与变更流水，应用/一键回滚 sql/versions 脚本（最高危）', 1);

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT x.`bind_id`, r.`id`, p.`id`
FROM (SELECT 6028 AS `bind_id`, 'system:schema:manage' AS `perm_code`) x
         JOIN `im_role` r ON r.`role_code` = 'admin' AND r.`deleted` = 0
         JOIN `im_permission` p ON p.`perm_code` = x.`perm_code` AND p.`deleted` = 0;
