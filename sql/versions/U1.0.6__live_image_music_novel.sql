-- =====================================================================================
--  U1.0.6：回滚「图片 / 音乐 / 小说」三板块（配套 V1.0.6__live_image_music_novel.sql）
--
--  ★清除：表 im_live_image / im_live_music / im_live_novel（连同其中的作品数据）；
--         分类字典里 type 为 image/music/novel 的行（含 101~110 / 121~130 / 141~150 三批种子，
--         以及后台后来自己加的那些——它们离开 type 就没有归属，留着会串进视频板块的分类栏）；
--         权限点 129~131 与 admin 绑定 6029~6031
--  ★恢复：im_live_video_category 退回「没有 type 列 + idx_status_sort」的形状（= V1.0.4 的形状）
--  ★增加：前像备份表 im_live_image_bak_20261122 / im_live_music_bak_20261122 /
--         im_live_novel_bak_20261122 / im_live_video_category_bak_20261122
--         （★ 这四张只在回滚时存在，故意不并回 sql/im_schema.sql，先例见 im_user_ban_bak_20261026）
--
--  回滚的三个真相，点「回滚」之前要看清楚：
--   1. **MySQL 的 DDL 隐式提交、不可回卷**。本脚本还原的是结构；三张表里的作品行只存在于
--      第 1 段的前像备份表里，而备份表是回滚当场才建的——如果第 1 段没跑成（或跑之前库已经
--      被别的操作动过），数据就真没了。页面确认框强制先备份，与本脚本的第 1 段是同一件事。
--   2. **im_file 里的媒体本体不级联清理**（与视频板块同一口径）。回滚后那些
--      bizType=image_work / music / music_cover / novel_cover 的文件行成为无人引用的孤儿，
--      仍占磁盘。要清就先按 bizType 查一遍 im_file 再人工删，别顺手 DELETE。
--   3. **权限点删掉后必须清 im:auth:perm:* 与 im:auth:role:***（只 DEL、禁 FLUSHDB）。
--      不清的话后台三个页面在 TTL 内仍然进得去，但接口会 1003——半新半旧最难排。
--
--  幂等：每段都可重复执行，半途失败后可直接重试回滚（台账在 U 失败时保持 APPLIED）。
--        特别是第 1 段——若上一次已经把三张表 DROP 了、卡在后面的 DDL 上，重试时源表不存在，
--        CREATE ... LIKE / AS SELECT 都会报错，所以四张备份表都用 information_schema 守卫。
--        备份用 CREATE TABLE IF NOT EXISTS ... AS SELECT 一条搞定建表+拷数据：
--        备份表已存在时整条空转，不会二次 INSERT 出重复行。
--        代价是备份表没有主键与索引（CTAS 不带约束），它只用来找回数据，不参与任何查询。
--
--  ★ 守卫写法的一个硬要求：页面执行时每条语句各自借还一次连接，会话变量有可能丢失。
--     本脚本里 @has_src = NULL 如果不单独拦，IF(NULL = 0, 跳过备份, CREATE...) 会取 else
--     分支去建备份表（还算安全），但第 4/6 段的 @col_type = NULL 会反过来静默跳过拆列，
--     而第 3 段的 DROP TABLE 是无条件执行的——一旦变量丢失而备份未落成，数据就真没了。
--     所以每一处 IF(@var = 0, ...) 都改成 CASE 并先判 IS NULL，指向一张不存在的表让回滚
--     在备份阶段就失败退出（台账保持 APPLIED，可直接重试），绝不往下走到 DROP。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. 前像备份（必须第一步，且必须在第 4 段清分类行、第 6 段拆列之前）
--    im_live_video_category 也要备份：它带着 type 列被整表存下来，第 6 段会把这一列拆掉，
--    万一事后要恢复三板块，字典行的归属只能从这张备份表里查。
-- -------------------------------------------------------------------------------------
SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_image');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u106_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_live_image 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_live_image_bak_20261122` AS SELECT * FROM `im_live_image`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_music');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u106_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_live_music 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_live_music_bak_20261122` AS SELECT * FROM `im_live_music`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_novel');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u106_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_live_novel 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_live_novel_bak_20261122` AS SELECT * FROM `im_live_novel`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_video_category');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下拆列'' FROM `im_u106_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_live_video_category 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_live_video_category_bak_20261122` AS SELECT * FROM `im_live_video_category`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 2. 撤权限：一屏一个点，回滚就是把三个点与它们的 admin 绑定一起收回来。
--    删的是 id 而不是 perm_code：code 是唯一键，但按主键删能与 V 脚本第 4 段逐行对照。
-- -------------------------------------------------------------------------------------
DELETE FROM `im_role_permission` WHERE `id` IN (6029, 6030, 6031);
DELETE FROM `im_permission` WHERE `id` IN (129, 130, 131);

-- -------------------------------------------------------------------------------------
-- 3. 删三张作品表（数据已在第 1 段的前像备份里）
-- -------------------------------------------------------------------------------------
DROP TABLE IF EXISTS `im_live_novel`;
DROP TABLE IF EXISTS `im_live_music`;
DROP TABLE IF EXISTS `im_live_image`;

-- -------------------------------------------------------------------------------------
-- 4. 分类字典退回「只有视频」：先清非 video 的行，第 6 段再拆 type 列。
--    顺序不能反——一 DROP 列，这里就没法区分哪一行属于哪个板块了。
--    清的不只是三批种子：后台运营后来加的分类行同样靠 type 归属，留着会掉进 video 那一套
--    分类栏里（板块作品表已 DROP，这些名字成了无主的筛选项）。要连它们一起恢复就查备份表。
--
--    @col_type 是第 4、6 两段共用的判据：列已经不在，说明上一次跑到了这两段之后，两段都跳过。
-- -------------------------------------------------------------------------------------
SET @col_type = (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'im_live_video_category'
                   AND COLUMN_NAME = 'type');

SET @ddl = CASE
    WHEN @col_type IS NULL THEN 'SELECT ''会话变量丢失，拒绝猜状态'' FROM `im_u106_guard_conn_lost`'
    WHEN @col_type = 0 THEN 'SELECT ''im_live_video_category.type 已不在，分类清理跳过'' AS msg'
    ELSE 'DELETE FROM `im_live_video_category` WHERE `type` IN (''image'', ''music'', ''novel'')' END;
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 5. 索引退回 V1.0.4 的 idx_status_sort：分类栏主查询少了 type 这一列，最左前缀也要跟着退。
--    三种情况分开判（与 V 脚本第 2.2 段对称）：旧索引已在 → 跳过；新索引在 → 一条 ALTER 同时换；
--    两个都不在 → 只补旧的，不报 1091。
-- -------------------------------------------------------------------------------------
SET @idx_old = (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'im_live_video_category'
                  AND INDEX_NAME = 'idx_status_sort');
SET @idx_new = (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'im_live_video_category'
                  AND INDEX_NAME = 'idx_type_status_sort');
SET @ddl_idx = CASE
    WHEN @idx_old IS NULL OR @idx_new IS NULL THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_u106_guard_conn_lost`'
    WHEN @idx_old > 0 THEN 'SELECT ''idx_status_sort 已恢复，跳过'' AS msg'
    WHEN @idx_new > 0 THEN 'ALTER TABLE `im_live_video_category` DROP INDEX `idx_type_status_sort`, ADD INDEX `idx_status_sort` (`status`, `sort`)'
    ELSE 'ALTER TABLE `im_live_video_category` ADD INDEX `idx_status_sort` (`status`, `sort`)' END;
PREPARE stmt FROM @ddl_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 6. 拆掉 type 列（放在索引之后：DROP COLUMN 本身不会带走索引，但反过来先拆列会让第 5 段的
--    ADD INDEX (status, sort) 撞车时更难判断到底跑到了哪一步）
SET @ddl = CASE
    WHEN @col_type IS NULL THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_u106_guard_conn_lost`'
    WHEN @col_type = 0 THEN 'SELECT ''im_live_video_category.type 已不在，跳过'' AS msg'
    ELSE 'ALTER TABLE `im_live_video_category` DROP COLUMN `type`' END;
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 7. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面回滚时语句结果会被丢弃）
--
--    7.1 三张表已不在：期望 0
--      SELECT COUNT(*) AS still_there FROM information_schema.TABLES
--      WHERE TABLE_SCHEMA = DATABASE()
--        AND TABLE_NAME IN ('im_live_image', 'im_live_music', 'im_live_novel');
--
--    7.2 字典退回 V1.0.4 形状：type 期望 0 列；索引期望 PRIMARY + idx_status_sort(status,sort)
--      SELECT COUNT(*) AS type_col FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_video_category' AND COLUMN_NAME = 'type';
--      SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
--      FROM information_schema.STATISTICS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_live_video_category'
--      GROUP BY INDEX_NAME;
--
--    7.3 只剩视频分类，期望 17 行（运营后加的行按 id 递增，多于 17 行是正常的，只要没有别的板块）
--      SELECT COUNT(*) AS video_rows FROM `im_live_video_category`;
--
--    7.4 权限已收回：两个数都应为 0
--      SELECT COUNT(*) AS perm_rows FROM `im_permission` WHERE `id` IN (129, 130, 131);
--      SELECT COUNT(*) AS bound FROM `im_role_permission` WHERE `id` IN (6029, 6030, 6031);
--
--    7.5 前像备份还在（确认数据有地方找回，验收完再手工回收）：期望各表行数与回滚前一致
--      SELECT (SELECT COUNT(*) FROM `im_live_image_bak_20261122`)           AS image_rows,
--             (SELECT COUNT(*) FROM `im_live_music_bak_20261122`)           AS music_rows,
--             (SELECT COUNT(*) FROM `im_live_novel_bak_20261122`)           AS novel_rows,
--             (SELECT COUNT(*) FROM `im_live_video_category_bak_20261122`)  AS category_rows;
--
--    ★ 恢复三板块：重新在页面应用 V1.0.6（结构回来），数据再灌回去——
--      INSERT INTO `im_live_image` SELECT * FROM `im_live_image_bak_20261122`; 之类，
--      四张备份表都没有主键，重复执行会灌出重复行，所以先确认目标表是空的。
--      ★ 备份表不会被任何后续版本自动清理：确认不需要了就 DROP TABLE *_bak_20261122。
-- =====================================================================================
