-- =====================================================================================
--  U1.0.8：回滚「im_moment.view_count」（配套 V1.0.8__moment_view_count.sql）
--
--  ★清除：列 `im_moment.view_count`（连同已累计的浏览量数字）
--  ★增加：前像备份表 `im_moment_view_count_bak_20261125`（只存 id + view_count 两列）
--         （★ 这张表只在回滚时存在，故意不并回 sql/im_schema.sql，先例见 im_user_ban_bak_20261026）
--  ★修改：无
--
--  回滚之前要看清楚的三件事：
--   1. **MySQL 的 DDL 隐式提交、不可回卷**。DROP COLUMN 一执行，浏览量就没了，唯一的找回
--      途径是第 1 段的备份表。备份表是回滚当场才建的——第 1 段没跑成就不许往下走，
--      所以守卫在 @col_view IS NULL（会话变量丢失）时直接炸，绝不静默跳到 DROP。
--   2. **只回滚浏览量这一列，动态行本身一张不删**。V1.0.8 只加了一列，删这列才是回滚；
--      删 im_moment 表属于回滚整个交友域（U1.0.7），别在这里顺手 DROP TABLE。
--   3. **回滚后代码必须同步退回**：Moment.viewCount / MomentVO.viewCount / 详情自增 /
--      「我的动态」分页里按浏览量排序那一路都会引用这一列，列拆了而代码没退，
--      接口直接 1054 Unknown column。列与代码同批回退，没有「只回库不回码」的安全做法。
--
--  幂等：两段都可重复执行，半途失败后可直接重试回滚（台账在 U 失败时保持 APPLIED）。
--        特别是第 1 段——若上一次已经把列拆了、卡在别处，重试时源列不存在，
--        CTAS 会多备份一列或报 1054，所以备份与拆列都用 information_schema 守卫。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. 前像备份（必须第一步，且必须在第 2 段拆列之前）：只存 id 与 view_count 两列，
--    整表拷一遍是浪费——动态正文与媒体本体都还在 im_moment / im_moment_media 里，
--    这次回滚唯一会丢的就是这一个数字。
-- -------------------------------------------------------------------------------------
SET @col_view = (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'im_moment'
                   AND COLUMN_NAME = 'view_count');
SET @bak = CASE
    WHEN @col_view IS NULL THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u108_guard_conn_lost`'
    WHEN @col_view = 0 THEN 'SELECT ''im_moment.view_count 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_moment_view_count_bak_20261125` AS SELECT `id`, `view_count` FROM `im_moment`' END;
PREPARE stmt FROM @bak;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 2. 拆列
-- -------------------------------------------------------------------------------------
SET @ddl = CASE
    WHEN @col_view IS NULL THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_u108_guard_conn_lost`'
    WHEN @col_view = 0 THEN 'SELECT ''im_moment.view_count 已不在，跳过'' AS msg'
    ELSE 'ALTER TABLE `im_moment` DROP COLUMN `view_count`' END;
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 3. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面回滚时语句结果会被丢弃）
--
--    3.1 列已不在：期望 0
--      SELECT COUNT(*) AS still_there FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment' AND COLUMN_NAME = 'view_count';
--
--    3.2 备份表在位且行数等于动态行数（万一要恢复浏览量，从这里 UPDATE 回去）
--      SELECT COUNT(*) AS bak_rows FROM `im_moment_view_count_bak_20261125`;
--
--    3.3 真要恢复浏览量时的手工 SQL（不在本脚本内执行）
--      -- ALTER TABLE `im_moment` ADD COLUMN `view_count` INT NOT NULL DEFAULT 0
--      --   COMMENT '浏览量（详情页每次打开 +1，非去重）' AFTER `comment_count`;
--      -- UPDATE `im_moment` m JOIN `im_moment_view_count_bak_20261125` b ON b.id = m.id
--      --   SET m.view_count = b.view_count;
-- =====================================================================================
