-- =====================================================================================
--  V1.0.8：im_moment 新增 view_count 列（动态「我的动态」分页要按浏览量排序）
--
--  ★增加：列 `im_moment.view_count` INT NOT NULL DEFAULT 0（置于 comment_count 之后）
--  ★修改：无（不改任何既有列的定义，也不动索引）
--  ★清除：无
--
--  口径与配套：
--   · 浏览量口径与 `im_article.view_count` 逐字对齐：**详情页每次打开 +1，非去重**。
--     不做按访客去重，是因为动态的受众判定（好友 ∪ 匹配对象）本身就在服务端算，
--     再去重就得再多一张 im_moment_view 表；而这一列只服务「我发的动态谁看得多」
--     这种粗略热度。作者自己点开不计（否则一个自用账号就能把自己的动态顶到浏览量最前）。
--   · 自增走 UPDATE ... SET view_count = view_count + 1，不做读改写：并发下读改写会丢增量。
--     这一列不进任何业务判定，丢一两个也只是排序里换个位置，所以不加锁、不建计数中间表。
--   · 只给「详情页」加，不给 Feed 卡片加：Feed 一屏十几条，按卡片曝光计数会让最热的东西
--     全是滑过去没看一眼的，排序就废了。
--   · 内容结构与 sql/im_schema.sql 第 1647 行的 im_moment 建表块逐字一致；
--     全新库跑那两份全量脚本即可，不需要本脚本。本脚本给存量库用。
--   · 回滚：U1.0.8__moment_view_count.sql（先落前像备份表，再 DROP COLUMN）。
--   · 不需要清任何 Redis 键：不涉及 RBAC/权限，纯业务表加列。
--   · ★ 生产应用顺序：台账只应用「最小的待应用版本」，1.0.4 / 1.0.6 / 1.0.7 若都还没在页面
--     点过「应用」，顺序必须是 1.0.4 → 1.0.6 → 1.0.7 → 1.0.8（前三个脚本幂等，
--     对已手工 source 过的库只登记台账）。
--   · 幂等写法（口径见 md/开发/SQL版本管理.md：information_schema 守卫的 ADD COLUMN）。
--     守卫用的 SET @var + PREPARE 依赖「同一连接连续执行」：页面 apply 是 for(stmt) jdbc().execute，
--     每条语句各自借还一次连接，只要有并发请求在中间插一次借出，下一条语句就可能拿到别的连接，
--     会话变量变 NULL。IF(NULL = 0, ...) 取的是 else 分支，于是「跳过 ALTER」——页面报应用成功、
--     列却没加，接口查 view_count 就是 1054 Unknown column。所以守卫显式判 IS NULL 并指向一张
--     不存在的表，宁可让 apply 炸在流水里，也不静默空转。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. im_moment 补 view_count 列（放在 comment_count 之后，与 im_schema.sql 的列序一致）。
--    NOT NULL DEFAULT 0：存量行动态自动落 0，不需要额外的回填 UPDATE。
-- -------------------------------------------------------------------------------------
SET @col_view = (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'im_moment'
                   AND COLUMN_NAME = 'view_count');
SET @ddl_view = CASE
    -- 会话变量丢失 = 这两条 SET 不在同一个连接上，必须炸（见文件头「幂等写法」一段）
    WHEN @col_view IS NULL THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_v108_guard_conn_lost`'
    WHEN @col_view = 0 THEN 'ALTER TABLE `im_moment` ADD COLUMN `view_count` INT NOT NULL DEFAULT 0 COMMENT ''浏览量（详情页每次打开 +1，非去重）'' AFTER `comment_count`'
    ELSE 'SELECT ''im_moment.view_count 已存在，跳过'' AS msg' END;
PREPARE stmt FROM @ddl_view;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 2. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面应用时语句结果会被丢弃，
--    所以这几条刻意留成注释，不进 splitStatements 的语句列表）
--
--    2.1 列到位：应为 int(11) NOT NULL DEFAULT 0，且紧跟在 comment_count 之后
--      SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, ORDINAL_POSITION
--      FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_moment'
--      ORDER BY ORDINAL_POSITION;
--
--    2.2 存量行都有值（列刚加上时应全为 0）
--      SELECT COUNT(*) AS rows_null FROM `im_moment` WHERE `view_count` IS NULL;  -- 期望 0
-- =====================================================================================
