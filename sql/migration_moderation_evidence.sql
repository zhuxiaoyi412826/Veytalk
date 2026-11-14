-- =====================================================================================
--  增量迁移：审核记录挂上「隔离区图片」（五期·路线 2 的续做）
--
--  用途：给「已经跑过 migration_moderation_log.sql 建了 im_moderation_log」的存量库使用。
--        全新环境直接跑 sql/im_schema.sql + sql/im_data.sql 即可（这一列已并入那份建表脚本），
--        无需本脚本。
--
--  背景：后台「审核记录」页原来只有判定结果没有图——因为识别跑在发送端浏览器里，
--        被拦下的图片当场就没上传，服务端连原图长什么样都拿不到。现在改成：命中后
--        发送端把原图复制一份上传到隔离区（im_file 里 biz_type=moderation_quarantine 的一行），
--        本列存那个 fileId，后台点「查看图片」经 /api/admin/log/moderation/{id}/image 取字节。
--
--  ★★ 本批有且仅有一条 DDL：给 im_moderation_log 加一列，不动任何既有列、不建表、
--     不动 RBAC 三张表、不动任何业务数据。零权限点变更 → 跑完**不需要**清 `im:auth:*` 缓存。
--
--  ★ 这一列是对判定结果的「补写」而不是修改：只有 NULL → 有值一个方向，
--     类别 / 概率 / 阈值 / 评分这些判定字段永远不回改，流水表「只增不改」的口径不受影响。
--     代价是挂上证据时 update_time 会动一次，所以该列注释从「流水无更新业务」改成了
--     「仅挂证据时被动一次」——别拿 update_time 当「这条记录被篡改过」的信号。
--
--  不跑会怎样：后端启动与既有功能全部正常，命中上报也照常落库（MyBatis-Plus 只把非 null
--  字段写进 INSERT，而新建时 evidence_file_id 总是 null，所以报表不会报 Unknown column）。
--  真正碎的是隔离区那一步：挂证据的 UPDATE 会因列不存在而失败（发送端对它是尽力而为、
--  不重试也不提示），于是后台「审核记录」页每一行都显示「无图」，而隔离区一张也存不下来。
--  ★ 本列不补，「查看图片」就是一个永远点不开的按钮。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_moderation_evidence.sql"
--
--  幂等：MySQL 的 ALTER TABLE 没有 ADD COLUMN IF NOT EXISTS，重复执行会报 1060。
--  先跑下面的只读预检，已经存在就整段跳过第 2 段。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 只读预检：期望 cols_exist = 0（未升级）或 1（已升级，第 2 段可跳过）
-- -------------------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'im_moderation_log'
          AND COLUMN_NAME = 'evidence_file_id') AS cols_exist,
       (SELECT COUNT(*) FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'im_moderation_log')  AS table_exists;

-- -------------------------------------------------------------------------------------
-- 2. 结构对齐：与 sql/im_schema.sql 的 im_moderation_log 逐字一致
--    可空、无默认值：没有隔离图是常态（开关关掉 / 图超大 / 上传失败），NULL 就是「无图可看」。
--    不建索引：这一列只按主键取单行时用，没有任何「按 fileId 反查命中记录」的查询。
-- -------------------------------------------------------------------------------------
ALTER TABLE `im_moderation_log`
    ADD COLUMN `evidence_file_id` BIGINT DEFAULT NULL COMMENT '隔离区图片的 im_file.id（biz_type=moderation_quarantine）：未开启/超大/上传失败为 NULL' AFTER `scores`;

-- -------------------------------------------------------------------------------------
-- 3. 只读核对：跑完再查一次，应与 im_schema.sql 完全对上
--      期望：bigint | IS_NULLABLE = YES | COLUMN_DEFAULT = NULL
-- -------------------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'im_moderation_log'
  AND COLUMN_NAME = 'evidence_file_id';

-- 顺带看一眼存量命中记录有多少已经能挂上图（本列刚建时必然全是 NULL，历史命中不会再补图）
SELECT COUNT(*) AS total_rows, COUNT(`evidence_file_id`) AS with_evidence
FROM `im_moderation_log`;

-- -------------------------------------------------------------------------------------
-- 4. 回滚（只在「必须退回原状」时用；删列会连带丢掉证据指向，隔离区里的字节变成没人引用的垃圾）
--      ALTER TABLE `im_moderation_log` DROP COLUMN `evidence_file_id`;
--    注意：回滚后 sql/im_schema.sql 与代码都还带着这一列，那就是重新制造漂移；
--    真要退得连同建表脚本、实体与后端一起退。
-- -------------------------------------------------------------------------------------
