-- =====================================================================================
--  U1.0.7：回滚「交友域」（配套 V1.0.7__dating_core.sql）
--
--  ★清除：16 张表 im_user_profile / im_user_photo / im_tag / im_user_tag / im_match_like /
--         im_match_pair / im_match_preference / im_match_exposure / im_profile_visit /
--         im_intimacy / im_moment / im_moment_media / im_moment_visible / im_moment_like /
--         im_moment_comment / im_icebreaker（连同资料、照片墙索引、滑动记录、配对、动态与评论）；
--         权限点 132~134 与 admin 绑定 6032~6034
--  ★修改：无（V1.0.7 没有对任何既有表加过列或索引，这里也就没有要退回的形状）
--  ★增加：前像备份表 im_*_bak_20261122 共 16 张
--         （★ 这十六张只在回滚时存在，故意不并回 sql/im_schema.sql，先例见 im_user_ban_bak_20261026）
--
--  回滚前必须看清的四件事：
--   1. **MySQL 的 DDL 隐式提交、不可回卷**。本脚本还原的是结构；资料卡、照片墙索引、配对与
--      动态的数据只存在于第 1 段的十六张前像备份表里，而备份表是回滚当场才建的——第 1 段没跑成
--      （或跑之前库已被别的操作动过），数据就真没了。页面确认框强制先备份与第 1 段是同一件事。
--   2. **配对产生的会话不跟着删**。im_match_pair.conversation_id 指向的私聊会话属于
--      im_conversation，回滚交友域不等于把两人聊天记录抹掉（与「删好友不清会话」同一口径）。
--      回滚后这些会话成为无配对关系的普通私聊，功能上没坏，只是没人能解释它们从哪来。
--   3. **im_file 里的媒体本体不级联清理**（与三板块回滚同一口径）。照片墙与动态的
--      bizType=dating_photo / moment_image / moment_video 的文件行会成为无人引用的孤儿，
--      仍占磁盘。要清就先按 bizType 查一遍 im_file 再人工删，别顺手 DELETE。
--   4. **权限点删掉后必须清 im:auth:perm:* 与 im:auth:role:***（只 DEL、禁 FLUSHDB）。
--      不清的话后台三个交友页面在 TTL 内仍然进得去，但接口会 1003——半新半旧最难排。
--
--  幂等：每段都可重复执行，半途失败后可直接重试回滚（台账在 U 失败时保持 APPLIED）。
--        特别是第 1 段——若上一次已经把某张表 DROP 了、卡在后面的语句上，重试时源表不存在，
--        CREATE ... AS SELECT 会直接报错，所以十六张备份表都用 information_schema 守卫。
--        备份用 CREATE TABLE IF NOT EXISTS ... AS SELECT 一条搞定建表+拷数据：
--        备份表已存在时整条空转，不会二次 INSERT 出重复行。
--        代价是备份表没有主键与索引（CTAS 不带约束），它只用来找回数据，不参与任何查询。
--
--  ★ 守卫写法的硬要求（与 U1.0.6 同一条）：页面执行时每条语句各自借还一次连接，会话变量有可能丢失。
--     @has_src = NULL 如果不单独拦，CASE 会走 ELSE 去建备份表；而紧随其后的第 3 段 DROP 是无条件
--     执行的——一旦变量丢失而备份未落成，数据就真没了。所以每一处都先判 IS NULL，指向一张不存在
--     的表让回滚在备份阶段就失败退出（台账保持 APPLIED，可直接重试），绝不往下走到 DROP。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. 前像备份（必须第一步，且在第三段 DROP 之前）
--    十六段结构完全一样，只是表名不同；按 V1.0.7 的建表顺序排，方便逐段对照。
-- -------------------------------------------------------------------------------------
SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user_profile');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_user_profile 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_user_profile_bak_20261122` AS SELECT * FROM `im_user_profile`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user_photo');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_user_photo 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_user_photo_bak_20261122` AS SELECT * FROM `im_user_photo`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_tag');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_tag 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_tag_bak_20261122` AS SELECT * FROM `im_tag`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user_tag');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_user_tag 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_user_tag_bak_20261122` AS SELECT * FROM `im_user_tag`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_match_like');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_match_like 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_match_like_bak_20261122` AS SELECT * FROM `im_match_like`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_match_pair');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_match_pair 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_match_pair_bak_20261122` AS SELECT * FROM `im_match_pair`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_match_preference');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_match_preference 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_match_preference_bak_20261122` AS SELECT * FROM `im_match_preference`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_match_exposure');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_match_exposure 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_match_exposure_bak_20261122` AS SELECT * FROM `im_match_exposure`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_profile_visit');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_profile_visit 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_profile_visit_bak_20261122` AS SELECT * FROM `im_profile_visit`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_intimacy');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_intimacy 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_intimacy_bak_20261122` AS SELECT * FROM `im_intimacy`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_moment 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_moment_bak_20261122` AS SELECT * FROM `im_moment`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment_media');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_moment_media 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_moment_media_bak_20261122` AS SELECT * FROM `im_moment_media`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment_visible');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_moment_visible 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_moment_visible_bak_20261122` AS SELECT * FROM `im_moment_visible`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment_like');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_moment_like 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_moment_like_bak_20261122` AS SELECT * FROM `im_moment_like`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment_comment');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_moment_comment 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_moment_comment_bak_20261122` AS SELECT * FROM `im_moment_comment`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_src = (SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_icebreaker');
SET @bak = CASE
    WHEN @has_src IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u107_guard_conn_lost`'
    WHEN @has_src = 0 THEN 'SELECT ''im_icebreaker 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_icebreaker_bak_20261122` AS SELECT * FROM `im_icebreaker`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 2. 撤权限：一屏一个点，回滚就是把三个点与它们的 admin 绑定一起收回来。
--    先删绑定再删权限点（与 V 脚本第 15 段反向）；删的是 id 而不是 perm_code，
--    code 是唯一键，但按主键删能与 V 脚本逐行对照。
-- -------------------------------------------------------------------------------------
DELETE FROM `im_role_permission` WHERE `id` IN (6032, 6033, 6034);
DELETE FROM `im_permission` WHERE `id` IN (132, 133, 134);

-- -------------------------------------------------------------------------------------
-- 3. 删十六张表（数据已在第 1 段的前像备份里）。倒序删：先删依赖别人的（媒体/评论/白名单），
--    最后删 im_user_profile——它是 1:1 挂在 im_user 上的扩展表，删它不影响 im_user 任何一行。
-- -------------------------------------------------------------------------------------
DROP TABLE IF EXISTS `im_icebreaker`;
DROP TABLE IF EXISTS `im_moment_comment`;
DROP TABLE IF EXISTS `im_moment_like`;
DROP TABLE IF EXISTS `im_moment_visible`;
DROP TABLE IF EXISTS `im_moment_media`;
DROP TABLE IF EXISTS `im_moment`;
DROP TABLE IF EXISTS `im_intimacy`;
DROP TABLE IF EXISTS `im_profile_visit`;
DROP TABLE IF EXISTS `im_match_exposure`;
DROP TABLE IF EXISTS `im_match_preference`;
DROP TABLE IF EXISTS `im_match_pair`;
DROP TABLE IF EXISTS `im_match_like`;
DROP TABLE IF EXISTS `im_user_tag`;
DROP TABLE IF EXISTS `im_tag`;
DROP TABLE IF EXISTS `im_user_photo`;
DROP TABLE IF EXISTS `im_user_profile`;

-- -------------------------------------------------------------------------------------
-- 4. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面回滚时语句结果会被丢弃）
--
--  4.1 十六张表已不在：期望 0
--    SELECT COUNT(*) AS still_there FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (
--      'im_user_profile','im_user_photo','im_tag','im_user_tag','im_match_like','im_match_pair',
--      'im_match_preference','im_match_exposure','im_profile_visit','im_intimacy','im_moment',
--      'im_moment_media','im_moment_visible','im_moment_like','im_moment_comment','im_icebreaker');
--
--  4.2 既有表没被牵连：im_user 行数应与回滚前一致，且没有多出任何列
--    SELECT COUNT(*) AS users FROM `im_user` WHERE `deleted` = 0;
--    SELECT COUNT(*) AS stray_cols FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user'
--      AND COLUMN_NAME IN ('birthday','height_cm','geohash','profile_score');
--
--  4.3 权限已收回：两个数都应为 0
--    SELECT COUNT(*) AS perm_rows FROM `im_permission` WHERE `id` IN (132, 133, 134);
--    SELECT COUNT(*) AS bound FROM `im_role_permission` WHERE `id` IN (6032, 6033, 6034);
--
--  4.4 前像备份还在（确认数据有地方找回，验收完再手工回收）
--    SELECT (SELECT COUNT(*) FROM `im_user_profile_bak_20261122`)   AS profile_rows,
--           (SELECT COUNT(*) FROM `im_user_photo_bak_20261122`)     AS photo_rows,
--           (SELECT COUNT(*) FROM `im_match_pair_bak_20261122`)     AS pair_rows,
--           (SELECT COUNT(*) FROM `im_moment_bak_20261122`)         AS moment_rows,
--           (SELECT COUNT(*) FROM `im_tag_bak_20261122`)            AS tag_rows;
--
--  ★ 恢复交友域：重新在页面应用 V1.0.7（结构与三份字典种子回来），数据再灌回去——
--    INSERT INTO `im_user_profile` SELECT * FROM `im_user_profile_bak_20261122`; 之类。
--    十六张备份表都没有主键，重复执行会灌出重复行，所以先确认目标表是空的；
--    另外 V1.0.7 第 13/14 段的标签与破冰文案是 INSERT IGNORE，先灌备份再应用版本更省事
--    （反过来说：后台运营后来改过这些字典行的话，改后的值只可能在备份表里）。
--    ★ 备份表不会被任何后续版本自动清理：确认不需要了就 DROP TABLE *_bak_20261122。
-- =====================================================================================
