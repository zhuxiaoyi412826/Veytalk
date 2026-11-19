-- =====================================================================================
--  增量迁移：视频板块抖音式信息流——im_live_video 增加分类列
--  适用于「已有数据库」，只补新增列，不动任何已有数据。
--  全新库直接执行 im_schema.sql 即可（该表定义已含本列），无需本脚本。
--  幂等：列已存在时跳过，可反复执行。
--  执行方式：mysql --default-character-set=utf8mb4 -u<user> -p <database> -e "SOURCE sql/migration_live_video_category.sql"
-- =====================================================================================

-- im_live_video 增加 category 列（放在 title 之后，与建表脚本顺序一致）；NOT NULL + 默认「综合」，存量行自动回填
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_video' AND COLUMN_NAME = 'category'
);
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `im_live_video` ADD COLUMN `category` VARCHAR(32) NOT NULL DEFAULT ''综合'' COMMENT ''分类（视频板块二级分类标签，如 游戏/音乐/美食…），缺省 综合'' AFTER `title`',
    'SELECT ''im_live_video.category 已存在，跳过'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
