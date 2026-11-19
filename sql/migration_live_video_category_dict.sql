-- =====================================================================================
--  增量迁移：视频板块二级分类字典表 im_live_video_category（后台可增删改分类栏）
--  适用于「已有数据库」，只建新表 + 灌初始 17 项分类，不动任何已有数据。
--  全新库直接执行 im_schema.sql 即可（该表定义与种子已并入），无需本脚本。
--  幂等：表已存在跳过、种子用 INSERT IGNORE 按主键去重，可反复执行。
--  执行方式：mysql --default-character-set=utf8mb4 -u<user> -p <database> -e "SOURCE sql/migration_live_video_category_dict.sql"
-- =====================================================================================

-- 建表（与 im_schema.sql 定义一致）
CREATE TABLE IF NOT EXISTS `im_live_video_category`
(
    `id`          BIGINT      NOT NULL COMMENT '分类 ID',
    `name`        VARCHAR(32) NOT NULL COMMENT '分类名称（与 im_live_video.category 取值对齐）',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序（升序，前端分类栏按此顺序展示）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用（停用不在前端分类栏出现）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页视频板块二级分类字典（后台可增删改）';

-- 初始分类：与视频板块抖音化上线时的硬编码 17 项一致，保证升级后前端分类栏行为不变
INSERT IGNORE INTO `im_live_video_category` (`id`, `name`, `sort`, `status`) VALUES
(1, '综合', 1, 1), (2, '游戏', 2, 1), (3, '二次元', 3, 1), (4, '音乐', 4, 1),
(5, '影视', 5, 1), (6, '美食', 6, 1), (7, '知识', 7, 1), (8, '小剧场', 8, 1),
(9, '生活vlog', 9, 1), (10, '体育', 10, 1), (11, '旅行', 11, 1), (12, '亲子', 12, 1),
(13, '动物', 13, 1), (14, '三农', 14, 1), (15, '汽车', 15, 1), (16, '美妆穿搭', 16, 1),
(17, '公开课', 17, 1);
