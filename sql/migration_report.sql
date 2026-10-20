-- =====================================================================================
--  增量迁移：举报工单（管理后台 n5）
--
--  用途：给「已经跑过完整库、只想补管理后台」的环境使用。全新环境直接跑
--        sql/im_schema.sql 即可（本脚本内容已并入那份），无需本脚本。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。执行前建议先备份：
--      mysqldump -u "$env:MYSQL_USER" -p im_db > im_db_bak.sql
--    回滚：这张表是纯新增，删表即回滚，不影响既有数据：
--      DROP TABLE IF EXISTS `im_report`;
--
--  ★ 不跑会怎样：只有举报这条链报错，不影响任何既有功能。
--    POST /api/report（提交举报）与 GET /api/report/mine（我的举报）会直接抛
--    Table doesn't exist 并由全局异常处理器转成 5000 系统异常；后台的举报队列页同样取不到数。
--    用户 / 消息 / 直播 / 钱包等既有接口一概不碰这张表，所以「先跑后端、后补 SQL」
--    最多是举报入口报个错，不会像收藏那次一样把消息列表整片打挂。
--    即便如此仍建议按正序执行：先跑本脚本，再启动新版后端。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_report.sql"
-- =====================================================================================

USE `im_db`;

-- 幂等：本表专属本迁移，重复执行先删旧再建。
-- ⚠ 若已跑过本脚本并产生了举报记录，重复执行会把工单连历史一起删掉，
--    已上线环境请先 SELECT COUNT(*) FROM `im_report` 确认再决定是否 DROP。
DROP TABLE IF EXISTS `im_report`;

CREATE TABLE `im_report`
(
    `id`               BIGINT       NOT NULL COMMENT '举报 ID（雪花）',
    `reporter_id`      BIGINT       NOT NULL COMMENT '举报人用户 ID',
    `target_type`      VARCHAR(16)  NOT NULL COMMENT '被举报对象类型：user / message / room / group',
    `target_id`        BIGINT       NOT NULL COMMENT '被举报对象 ID（按 target_type 解释）',
    `reported_user_id` BIGINT                DEFAULT NULL COMMENT '被举报人 ID：受理时按对象反查后落库，供「同一个人被举报多少次」聚合与重复举报处置',
    `reason_code`      VARCHAR(32)  NOT NULL COMMENT '举报理由码（枚举，口径在服务端定，前端展示走同一份字典，不让客户端自定分类）',
    `reason_text`      VARCHAR(500)          DEFAULT NULL COMMENT '补充描述，可空；写入前按敏感词清洗与长度截断',
    `evidence`         JSON                  DEFAULT NULL COMMENT '举报当时抓的证据快照（标题/内容摘要/所属会话/开播信息等）：被举报对象事后被删也不影响处置与追溯',
    `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 已受理 2 已驳回 3 已处置',
    `disposition`      VARCHAR(32)           DEFAULT NULL COMMENT '处置动作：none / warn / ban_user / unban_user / recall_message / close_room，与 status 3 同时写入',
    `handle_remark`    VARCHAR(500)          DEFAULT NULL COMMENT '处置说明：回给举报人并留档，不写就是驳回后没人知道为何',
    `handler_id`       BIGINT                DEFAULT NULL COMMENT '处理人（管理员）用户 ID',
    `handle_time`      DATETIME              DEFAULT NULL COMMENT '处理时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_time` (`status`, `create_time`),
    KEY `idx_reporter_time` (`reporter_id`, `create_time`),
    KEY `idx_target` (`target_type`, `target_id`),
    KEY `idx_reported_user` (`reported_user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='举报工单（管理后台的处理队列，只改状态不删行）';
