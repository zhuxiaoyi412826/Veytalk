-- =====================================================================================
--  增量迁移：把存量库 im_gift.icon 加宽到与 sql/im_schema.sql 一致（VARCHAR(512) NULL）
--
--  用途：给「早就跑过 migration_linkmic_gift.sql 建了礼物目录」的存量库使用。
--        全新环境直接跑 sql/im_schema.sql + sql/im_data.sql 即可，无需本脚本。
--
--  为什么会有这份脚本（一次对账对出来的反向漂移）：
--        2026-10-27 用脚本把开发机 im_db 与 sql/im_schema.sql 逐表逐列逐索引比对过一遍，
--        37 张表双向无缺、列与索引只有一处不一致，且不一致的方向是**库落后于脚本**：
--          脚本（im_schema.sql L705 与 migration_linkmic_gift.sql L50）  varchar(512) NULL  DEFAULT NULL
--          实际库（information_schema 实测）                            varchar(64)  NOT NULL DEFAULT ''
--        git 历史里没有任何一条改这一列的 ALTER，说明那台库当初是按一份还没 widen 的
--        工作区版本建表的。当前 8 条种子礼物的 icon 都是 1~2 个字符的 emoji，所以一直没炸；
--        但运营在后台把图标换成图片 URL（`/api/file/download/{id}?ticket=...` 一条就
--        轻松超过 64 字符）时，MySQL 会直接报 `Data too long for column 'icon'`，
--        而前端拿到的是一个笼统的 1007「系统繁忙」——现象离根因很远，值得提前对齐。
--
--  ★★ 本批有且仅有一条 DDL：改 im_gift 一列的类型与可空性。
--     不新建表、不改任何既有列的含义、不动 RBAC 三张表、不动任何业务数据。
--     零权限点变更 → 跑完**不需要**清 `im:auth:*` 缓存（只有新开/收掉权限点才要清）。
--
--  ★ 与「送礼流水」无关：`im_gift_record.gift_icon` 早就是 varchar(512) NULL（实测已确认），
--     历史流水不会因为这个改动而截断或报错；本脚本只动目录表。
--
--  执行方式（Windows PowerShell。凭据走环境变量，脚本内不含明文密码）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_gift_icon_width.sql"
--    ⚠ 已知坑：把密码写成裸词 `-p$env:MYSQL_PASSWORD` 时 PowerShell **不展开**变量，
--      mysql 收到的是字面量 `-p$env:MYSQL_PASSWORD`，于是回 `ERROR 1045 ... (using password: YES)`
--      ——看着像密码错，其实是参数没展开。要么整体加引号 `"-p$env:MYSQL_PASSWORD"`，
--      要么按上面那样用 `-p` 交互输入。也别用 Get-Content | mysql，管道编码会把中文注释刷成 ?。
--
--  执行前建议先备份（只备份这一张表就够，体积是几十行）：
--      mysqldump -u "$env:MYSQL_USER" -p im_db im_gift > im_gift_bak.sql
--
--  可重跑：第 1 段预检会先判断当前列宽，已经是 512/NULL 就只打一行提示。
--  类型加宽（64 → 512 跨过 255 字节的长度前缀分界）走 COPY 算法重建表，
--  但这张表是运营目录表（当前 8 行），耗时以毫秒计，不需要停机窗口。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 只读预检：跑第 2 段之前先看清现状
--    期望（未对齐的库）：CHARACTER_MAXIMUM_LENGTH = 64、IS_NULLABLE = NO
--    已经对齐的库：len = 512、null = YES → 第 2 段可以整段跳过
-- -------------------------------------------------------------------------------------
SELECT COLUMN_NAME,
       COLUMN_TYPE,
       CHARACTER_MAXIMUM_LENGTH AS len,
       IS_NULLABLE,
       COLUMN_DEFAULT,
       COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'im_gift'
  AND COLUMN_NAME = 'icon';

-- 顺带确认改这一列不会撞上超长数据（当前期望是 0 行：最长的一条也只有几个字符）。
-- 若这里真返回了 len > 512 的行，说明库里有脏数据，先人工处理再跑 ALTER——
-- 加宽本身不会丢数据，但如果反过来要缩窄，这些行就会变成截断或报错。
SELECT id, name, CHAR_LENGTH(icon) AS icon_len, icon
FROM `im_gift`
WHERE CHAR_LENGTH(icon) > 64;

-- -------------------------------------------------------------------------------------
-- 2. 结构对齐：与 sql/im_schema.sql L705 逐字一致
-- -------------------------------------------------------------------------------------
ALTER TABLE `im_gift`
    MODIFY COLUMN `icon` VARCHAR(512) DEFAULT NULL COMMENT '图标（表情符号或图片地址）';

-- -------------------------------------------------------------------------------------
-- 3. 只读核对：跑完再查一次，应与 im_schema.sql 完全对上
--      期望：varchar(512) | len = 512 | IS_NULLABLE = YES | COLUMN_DEFAULT = NULL
--      并且第 1 段那条 icon_len 查询仍然为空集、8 条种子礼物一条没少
-- -------------------------------------------------------------------------------------
SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'im_gift'
  AND COLUMN_NAME = 'icon';

SELECT COUNT(*) AS gift_rows, IFNULL(MAX(CHAR_LENGTH(icon)), 0) AS max_icon_len
FROM `im_gift`
WHERE `deleted` = 0;

-- 与流水表那一列的口径对一遍（两边都该是 varchar(512) NULL，目录改完就一致了）
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'im_gift' AND COLUMN_NAME = 'icon')
    OR (TABLE_NAME = 'im_gift_record' AND COLUMN_NAME = 'gift_icon'))
ORDER BY TABLE_NAME;

-- -------------------------------------------------------------------------------------
-- 4. 回滚（只在「改完发现必须退回原状」时用；缩窄会把超过 64 字符的值截断或报错，
--    所以回滚前先重跑第 1 段那条 CHAR_LENGTH(icon) > 64 的查询确认没有超长的行）
--      ALTER TABLE `im_gift`
--          MODIFY COLUMN `icon` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '图标（表情符号或图片地址）';
--    注意：回滚成 NOT NULL 后，第 3 段那条 SELECT COLUMN_DEFAULT 会回 ''，
--    而 sql/im_schema.sql 写的是 DEFAULT NULL —— 那就是重新制造一次漂移，
--    下一轮对账又会把它捞出来。真要回滚请连同 im_schema.sql 一起改。
-- -------------------------------------------------------------------------------------
