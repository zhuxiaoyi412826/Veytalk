-- =====================================================================================
--  V1.0.6：直播页新增「图片 / 音乐 / 小说」三个内容板块（三张表 + 分类字典加 type + 三个权限点）
--
--  ★增加：表 im_live_image / im_live_music / im_live_novel（各两条索引）
--  ★修改：表 im_live_video_category 加列 type（video/image/music/novel），索引
--         idx_status_sort 换成 idx_type_status_sort；分类种子 +30 行（图片 101~110、
--         音乐 121~130、小说 141~150，与 im_schema.sql 的实际 id 段一致）；
--         权限点 129 system:image:manage / 130 system:music:manage / 131 system:novel:manage，
--         admin（role 1）绑定 6029~6031
--  ★清除：无（本版不删任何东西）
--
--  口径与配套：
--   · 三个板块与视频板块同一形态——媒体与封面本体在 im_file（bizType=image_work / music /
--     music_cover / novel_cover，对所有登录用户公开），三张表只存元数据与受控访问地址；
--     发布即可见（status 只有 1 正常 / 2 已下架），不走文章的审核状态机。
--   · 内容结构与 sql/im_schema.sql、种子与 sql/im_data.sql 逐字一致；全新库跑那两份即可，
--     不需要本脚本。本脚本给存量库用。
--   · 回滚：U1.0.6__live_image_music_novel.sql（第一步先落三张前像备份表，再反向拆除）。
--   · ★ 跑完必须清一次权限缓存：DEL im:auth:perm:*（按 pattern 逐键删，禁 FLUSHDB）与
--     im:auth:role:*。不清的话三个新菜单页对 admin 也返回 1003（TTL 30 分钟且登录不刷新）。
--   · ★ 生产应用顺序：台账只允许应用「最小的待应用版本」，而 1.0.4 当年是手工 source 的、
--     台账里仍是待应用——必须先在页面点 1.0.4「应用」（脚本幂等，空转只登记台账），再点 1.0.6。
--   · 幂等写法（口径见 md/开发/SQL版本管理.md：CREATE TABLE IF NOT EXISTS / INSERT IGNORE /
--     information_schema 守卫的 ADD COLUMN 与索引替换），所以重复应用、跑一半失败后重放都安全。
--     守卫用的 SET @var + PREPARE 依赖「同一连接连续执行」：页面 apply 是 for(stmt) jdbc().execute，
--     每条语句各自借还一次连接。实测 HikariCP 7.0.2 + minimumIdle=5 的池在连续 10 次借还里
--     都归还同一条连接（2026-11-22 探针），空闲时是安全的；但只要有并发请求在中间插一次借出，
--     下一条语句就可能拿到别的连接，会话变量变 NULL。IF(NULL = 0, ...) 取的是 else 分支，
--     于是「跳过 ALTER」——页面报应用成功、列却没加，下一次点开板块就是 1007。所以两个守卫
--     都显式判 IS NULL 并指向一张不存在的表，宁可让 apply 炸在流水里，也不静默空转。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. 三张作品表（与 im_schema.sql 定义逐字一致）
-- -------------------------------------------------------------------------------------

-- 图片板块：一行一个图集（1~9 张，首图作封面）。image_ids / image_urls 逗号并列而不建子表：
-- 两边的元素分别是雪花 ID 与 /api/file/download/{id}，都不含逗号，拆分无歧义；上限 9 张使
-- 字段长度可控，省一张子表与一次 join。两个字段同序，第 N 张图的 ID 与地址一一对应。
CREATE TABLE IF NOT EXISTS `im_live_image`
(
    `id`            BIGINT        NOT NULL COMMENT '图片作品 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '上传用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '作品标题',
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（图片板块二级分类标签），缺省 综合',
    `image_ids`     VARCHAR(1024) NOT NULL COMMENT '图集文件 ID 逗号并列（im_file.id，bizType=image_work，顺序即展示顺序，1~9 张）',
    `image_urls`    TEXT          NOT NULL COMMENT '图集受控访问地址逗号并列（与 image_ids 同序，服务端现拼，不采信前端传来的 URL）',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面文件 ID，等于首图 ID（冗余一份便于卡片与后台列表直接取）',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面受控访问地址，等于首图地址',
    `image_count`   INT           NOT NULL DEFAULT 1 COMMENT '图集张数（1~9）',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '各图字节数合计',
    `ext`           VARCHAR(32)            DEFAULT NULL COMMENT '首图扩展名，不含点',
    `content_type`  VARCHAR(128)           DEFAULT NULL COMMENT '首图 MIME 类型',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '打开次数（每次点开图集 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页图片板块（图集信息流，一行一个作品）';

-- 音乐板块：一行一首。与 im_live_video 同构，只是不放 width/height（音频没有画面尺寸）。
CREATE TABLE IF NOT EXISTS `im_live_music`
(
    `id`            BIGINT        NOT NULL COMMENT '音乐作品 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '上传用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '作品标题',
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（音乐板块二级分类标签），缺省 综合',
    `file_id`       BIGINT        NOT NULL COMMENT '音频文件 ID（im_file.id，bizType=music）',
    `file_url`      VARCHAR(1024) NOT NULL COMMENT '音频受控访问地址（/api/file/download/{fileId}，前端经 mediaUrl 取 blob 后交给 <audio>）',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面图文件 ID（im_file.id，bizType=music_cover），可空',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面图受控访问地址，可空（前端退化为占位图）',
    `duration`      INT           NOT NULL DEFAULT 0 COMMENT '时长（秒），上传前由客户端读取元数据后上报，缺省 0',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `ext`           VARCHAR(32)            DEFAULT NULL COMMENT '扩展名，不含点',
    `content_type`  VARCHAR(128)           DEFAULT NULL COMMENT 'MIME 类型',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '播放次数（每次点开 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页音乐板块（音频信息流，一行一首）';

-- 小说板块：一行一篇 Markdown 正文。content 是 LONGTEXT，列表查询永远不选它（服务层显式
-- 排除，只有详情接口取），否则一页 15 条就能拖几百 MB。
CREATE TABLE IF NOT EXISTS `im_live_novel`
(
    `id`            BIGINT        NOT NULL COMMENT '小说作品 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '作者用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '作品标题（与图片/音乐同长，保证卡片一行放得下）',
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（小说板块二级分类标签），缺省 综合',
    `summary`       VARCHAR(255)           DEFAULT NULL COMMENT '列表摘要，空则服务端从正文截取前 80 字',
    `content`       LONGTEXT      NOT NULL COMMENT 'Markdown 正文（列表接口不返回，只在详情取）',
    `word_count`    INT           NOT NULL DEFAULT 0 COMMENT '字数，服务端按正文去空白后的字符数现算，不采信前端上报',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面图文件 ID（im_file.id，bizType=novel_cover），可空',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面图受控访问地址，可空（前端退化为占位图）',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '正文字节数（UTF-8）',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '阅读次数（每次打开详情 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页小说板块（Markdown 长篇，一行一篇）';

-- -------------------------------------------------------------------------------------
-- 2. 分类字典：一张表吃四个板块，靠 type 区分归属
--
--    2.0 先兜底建表：本版本依赖 im_live_video_category，而台账上 1.0.4 有可能是「已回滚」
--        或库是在 1.0.4 之前手工建的。表在则空转，表不在就按新结构（已含 type 与新索引）
--        建出来，第 2.1 / 2.2 段的守卫随后自动跳过，不必先补跑 1.0.4。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_live_video_category`
(
    `id`          BIGINT      NOT NULL COMMENT '分类 ID',
    `type`        VARCHAR(16) NOT NULL DEFAULT 'video' COMMENT '所属板块：video 视频 / image 图片 / music 音乐 / novel 小说',
    `name`        VARCHAR(32) NOT NULL COMMENT '分类名称（与各板块作品表的 category 列取值对齐）',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序（升序，前端分类栏按此顺序展示）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用（停用不在前端分类栏出现）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_type_status_sort` (`type`, `status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页内容板块二级分类字典（按 type 分板块，后台可增删改）';

-- 2.1 存量表补 type 列（放在 id 之后，与 im_schema.sql 的列序一致）。
--     NOT NULL DEFAULT 'video'：存量 17 行自动落 video，不需要额外的回填 UPDATE。
SET @col_type = (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'im_live_video_category'
                   AND COLUMN_NAME = 'type');
SET @ddl_type = CASE
    -- 会话变量丢失 = 这两条 SET 不在同一个连接上，必须炸（见文件头「幂等写法」一段）
    WHEN @col_type IS NULL THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_v106_guard_conn_lost`'
    WHEN @col_type = 0 THEN 'ALTER TABLE `im_live_video_category` ADD COLUMN `type` VARCHAR(16) NOT NULL DEFAULT ''video'' COMMENT ''所属板块：video 视频 / image 图片 / music 音乐 / novel 小说'' AFTER `id`'
    ELSE 'SELECT ''im_live_video_category.type 已存在，跳过'' AS msg' END;
PREPARE stmt FROM @ddl_type;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2.2 索引换名：分类栏主查询从「status + sort」变成「先按板块圈定，再按状态与排序」，
--     旧索引的最左列已被 type 顶替，留着只多一次写入开销，所以一并换掉。
--     三种情况分开判：新索引已在 → 跳过；旧索引在 → 一条 ALTER 同时换；两个都不在
--     （被人工删过）→ 只补新的，不报 1091。
SET @idx_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'im_live_video_category'
                  AND INDEX_NAME = 'idx_type_status_sort');
SET @idx_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'im_live_video_category'
                  AND INDEX_NAME = 'idx_status_sort');
SET @ddl_idx = CASE
    WHEN @idx_new IS NULL OR @idx_old IS NULL THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_v106_guard_conn_lost`'
    WHEN @idx_new > 0 THEN 'SELECT ''idx_type_status_sort 已存在，跳过'' AS msg'
    WHEN @idx_old > 0 THEN 'ALTER TABLE `im_live_video_category` DROP INDEX `idx_status_sort`, ADD INDEX `idx_type_status_sort` (`type`, `status`, `sort`)'
    ELSE 'ALTER TABLE `im_live_video_category` ADD INDEX `idx_type_status_sort` (`type`, `status`, `sort`)' END;
PREPARE stmt FROM @ddl_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 3. 分类种子：id 按板块分段预留（图片 101~110、音乐 121~130、小说 141~150），
--    每段首项固定「综合」，与三张表 category 的缺省值同一口径。
--    视频那 17 行（id 1~17）不写 type、靠列默认落 video，这里连它一起兜底重放：
--    表存在时 17 行全部主键冲突被 IGNORE 掉，等于空转。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_live_video_category` (`id`, `name`, `sort`, `status`) VALUES
(1, '综合', 1, 1), (2, '游戏', 2, 1), (3, '二次元', 3, 1), (4, '音乐', 4, 1),
(5, '影视', 5, 1), (6, '美食', 6, 1), (7, '知识', 7, 1), (8, '小剧场', 8, 1),
(9, '生活vlog', 9, 1), (10, '体育', 10, 1), (11, '旅行', 11, 1), (12, '亲子', 12, 1),
(13, '动物', 13, 1), (14, '三农', 14, 1), (15, '汽车', 15, 1), (16, '美妆穿搭', 16, 1),
(17, '公开课', 17, 1);

INSERT IGNORE INTO `im_live_video_category` (`id`, `type`, `name`, `sort`, `status`) VALUES
(101, 'image', '综合', 1, 1), (102, 'image', '风景', 2, 1), (103, 'image', '人像', 3, 1),
(104, 'image', '二次元', 4, 1), (105, 'image', '美食', 5, 1), (106, 'image', '动物', 6, 1),
(107, 'image', '建筑', 7, 1), (108, 'image', '插画', 8, 1), (109, 'image', '摄影教程', 9, 1),
(110, 'image', '生活记录', 10, 1);

INSERT IGNORE INTO `im_live_video_category` (`id`, `type`, `name`, `sort`, `status`) VALUES
(121, 'music', '综合', 1, 1), (122, 'music', '流行', 2, 1), (123, 'music', '摇滚', 3, 1),
(124, 'music', '说唱', 4, 1), (125, 'music', '电子', 5, 1), (126, 'music', '古典', 6, 1),
(127, 'music', '民谣', 7, 1), (128, 'music', '纯音乐', 8, 1), (129, 'music', '翻唱', 9, 1),
(130, 'music', '有声朗读', 10, 1);

INSERT IGNORE INTO `im_live_video_category` (`id`, `type`, `name`, `sort`, `status`) VALUES
(141, 'novel', '综合', 1, 1), (142, 'novel', '玄幻', 2, 1), (143, 'novel', '都市', 3, 1),
(144, 'novel', '科幻', 4, 1), (145, 'novel', '悬疑推理', 5, 1), (146, 'novel', '历史', 6, 1),
(147, 'novel', '言情', 7, 1), (148, 'novel', '武侠', 8, 1), (149, 'novel', '短篇', 9, 1),
(150, 'novel', '同人', 10, 1);

-- -------------------------------------------------------------------------------------
-- 4. 权限点与 admin 绑定：一屏一个点，伞权限 system:manage（107）不通配这些新点，
--    所以必须显式绑给 role 1，否则后台三个新页面只有重新分配过角色的人进得去。
--    ★ 这一步落库后不清 im:auth:perm:* / im:auth:role:* 就等于没做（见文件头）。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (129, 'system:image:manage', '后台图片管理', 'system', '图片图集的检索/新建/编辑/上下架/删除，以及图片板块分类栏维护', 1),
       (130, 'system:music:manage', '后台音乐管理', 'system', '音频的检索/新建/编辑/上下架/删除，以及音乐板块分类栏维护', 1),
       (131, 'system:novel:manage', '后台小说管理', 'system', '小说的检索/新建/编辑/上下架/删除，以及小说板块分类栏维护', 1);

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
VALUES (6029, 1, 129),
       (6030, 1, 130),
       (6031, 1, 131);

-- -------------------------------------------------------------------------------------
-- 5. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面应用时语句结果会被丢弃，
--    所以这几条刻意留成注释，不进 splitStatements 的语句列表）
--
--    5.1 三张表到位：期望列数 17 / 17 / 15（与 im_schema.sql 逐列一致）
--      SELECT TABLE_NAME,
--             (SELECT COUNT(*) FROM information_schema.COLUMNS c
--               WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = t.TABLE_NAME) AS col_count
--      FROM information_schema.TABLES t
--      WHERE t.TABLE_SCHEMA = DATABASE()
--        AND t.TABLE_NAME IN ('im_live_image', 'im_live_music', 'im_live_novel')
--      ORDER BY t.TABLE_NAME;
--
--    5.2 type 列与新索引到位：type 应为 varchar(16) NOT NULL DEFAULT 'video'；
--        索引应只剩 PRIMARY 与 idx_type_status_sort(type,status,sort)，idx_status_sort 已不在
--      SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_video_category' AND COLUMN_NAME = 'type';
--      SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
--      FROM information_schema.STATISTICS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_video_category'
--      GROUP BY INDEX_NAME;
--
--    5.3 分类行数按板块数：期望 video 17、image 10、music 10、novel 10
--      SELECT `type`, COUNT(*) AS rows_per_board FROM `im_live_video_category` GROUP BY `type`;
--
--    5.4 RBAC 到位：两个数都应为 3。不是 3 就先确认第 4 段落库了，再清缓存——
--        缓存没清时页面表现与没授权一模一样（1003），极易误判成脚本没跑
--      SELECT COUNT(*) AS perm_rows FROM `im_permission` WHERE `id` IN (129, 130, 131) AND `deleted` = 0;
--      SELECT COUNT(*) AS bound FROM `im_role_permission`
--      WHERE `id` IN (6029, 6030, 6031) AND `role_id` = 1;
-- =====================================================================================
