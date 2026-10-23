-- =====================================================================================
--  增量迁移：消息收藏（n2）
--
--  用途：给「已经跑过完整库、只想补收藏功能」的环境使用。全新环境直接跑
--        sql/im_schema.sql 即可（本脚本内容已并入那份），无需本脚本。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。执行前建议先备份：
--      mysqldump -u "$env:MYSQL_USER" -p im_db > im_db_bak.sql
--    回滚：这张表是纯新增，删表即回滚，不影响既有数据：
--      DROP TABLE IF EXISTS `im_message_favorite`;
--
--  ★ 不跑会怎样（重要，请先看完再决定何时执行）：消息列表接口会整片 500，不是「只有收藏不好用」。
--    因为历史分页 / 离线拉取 / 消息检索 / 群相册都会走 toViewList -> attachFavorites，
--    那一步要对新表做一次 IN 查询，表不存在就直接抛 SQLException。所以正确顺序是：
--    先跑本脚本，再启动新版后端（旧版后端不受影响，可以先跑 SQL 再发版）。
--    回滚也一样：先把后端退回旧版，再 DROP 这张表。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_message_favorite.sql"
-- =====================================================================================

USE `im_db`;

-- 幂等：本表专属本迁移，重复执行先删旧再建。
DROP TABLE IF EXISTS `im_message_favorite`;

CREATE TABLE `im_message_favorite`
(
    `id`              BIGINT   NOT NULL COMMENT '主键（雪花）',
    `user_id`         BIGINT   NOT NULL COMMENT '收藏人 ID（收藏是用户维度私有数据，按本人聚合查询，只有本人可见）',
    `conversation_id` BIGINT   NOT NULL COMMENT '所属会话 ID（冗余列：供「只看本会话的收藏」过滤，以及清空聊天记录处级联清理）',
    `message_id`      BIGINT   NOT NULL COMMENT '被收藏的消息 ID',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间（列表按此倒序）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_message` (`user_id`, `message_id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息收藏表（一行 = 某人收藏了某条消息；同一条消息可被多人各自收藏，取消只删本人那行）';
