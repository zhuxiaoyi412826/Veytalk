-- =====================================================================================
--  增量迁移：消息引用/回复/转发功能
--  适用于「已有数据库」，只补新增列与索引，不会清空任何数据。
--  全新库直接执行 im_schema.sql 即可，无需本脚本。
--  执行方式：mysql -u<user> -p <database> < migration_quote_message.sql
-- =====================================================================================

-- im_message 增加引用原消息 ID 列（放在 seq 之后，与建表脚本顺序一致）
ALTER TABLE `im_message`
    ADD COLUMN `quote_msg_id` BIGINT DEFAULT NULL COMMENT '引用/回复的原消息 ID，转发时为空' AFTER `seq`;

-- 引用跳转/反查索引
ALTER TABLE `im_message`
    ADD INDEX `idx_quote_msg` (`quote_msg_id`);
