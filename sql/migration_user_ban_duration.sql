-- =====================================================================================
--  增量迁移：管理后台四期 · 第三批（封禁时长 + 自动解封）
--
--  用途：给「已经跑过二三期、现在要让禁用账号能定时自动解封」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那份建表），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 1 段那条只读预检，
--        已经数到 disable_until=1 就不用再跑第 2 段。
--
--  ★★ 本批有且仅有一处 DDL：给 im_user 加一列 disable_until 和一个索引。
--     不新建表、不动 RBAC 三张表、不动任何业务数据。
--
--  ⚠ 为什么要有这一份：后端 POST /api/admin/user/{id}/disable 新增了 minutes 参数，
--     实体 com.im.user.entity.User 新增字段 disableUntil，定时任务
--     im-user/task/UserBanExpireTask 每 30 秒按这三条件扫一轮：
--         status = 0 AND disable_until IS NOT NULL AND disable_until <= NOW()
--     列不存在时，MyBatis-Plus 生成的 SELECT 会带上 `disable_until`，
--     用户管理列表与登录校验会直接报 Unknown column 1054 —— 不是「少了个新功能」，
--     是整条登录链路一起挂。所以这一份不是可选增强，代码起来之前必须先跑。
--
--  ⚠ 这一列的语义由两个条件共同定义，别单独看它：
--       disable_until IS NULL     + status = 0  → 永久封禁（不会自动解封，只能手动恢复）
--       disable_until <= NOW()    + status = 0  → 限时封禁且已到期，等定时任务解封（分钟级误差）
--       status = 1                                → 正常账号，这一列必须为 NULL（恢复时代码显式清空）
--     不用 9999-12-31 之类「极大值」代指永久：那会让永久封禁被排序排到最后、
--     被自动解封任务扫到，还会在列表里显示成一个假的日期。
--
--  ★★★ 最要紧的一条陷阱（写在代码注释里，这里复述一遍）：
--     UserServiceImpl#cancel（用户自己注销账号）也把 status 置成 STATUS_DISABLED。
--     所以自动解封的扫描条件必须含 disable_until IS NOT NULL，否则一次全表扫就会把
--     「永久封禁的人」和「已注销的空壳账号」一并救活。@TableLogic(deleted) 是第二道
--     保险，但不能拿它当依据——注销是否软删行为后续可能调整，而这道闸门必须独立成立。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动只涉 im_user 一张表、只涉三列，
--     所以备份推荐【只存前像、不碰敏感列】（方案 A）。★ 只存 `id` 与 `status`，
--     不要在这条里写 `disable_until`：跑备份时这一列还不存在，写了会报 1054（当时它全是 NULL，也没得存）：
--        CREATE TABLE `im_user_ban_bak_20261026` AS
--        SELECT `id`, `status` FROM `im_user`;
--        SELECT (SELECT COUNT(*) FROM `im_user`) src,
--               (SELECT COUNT(*) FROM `im_user_ban_bak_20261026`) bak;   -- 只读，两个数应当相等
--      拿它恢复某一行的状态（只读先看、确认后再改）：
--        UPDATE `im_user` u JOIN `im_user_ban_bak_20261026` b ON b.`id` = u.`id`
--           SET u.`status` = b.`status`, u.`disable_until` = NULL WHERE u.`id` = <出问题的 ID>;
--      验收无误后回收：DROP TABLE `im_user_ban_bak_20261026`;
--
--     方案 B（整表 dump）可以用，但有两个坑得先知道：
--      ① 本表存着全部账号的 Argon2id 密码哈希与手机号，dump 出来就是一个明文密码同级文件，用完立刻删；
--      ② 【不要把产物放在仓库目录里】——.gitignore 只忽略了 *.txt 等产物，**没有忽略 *.sql**，
--        放在项目根目录的备份文件会被 `git add .` 顺手收走。输到仓库外：
--        mysqldump -u root -p --default-character-set=utf8mb4 im_db im_user > "$env:USERPROFILE\Desktop\backup_im_user_ban_duration.sql"
--        ★ 跑完拿 `Get-Item <那个路径> | Select-Object Length` 确认非 0：PowerShell 的 `>` 在命令失败时
--        也会先创建一个 0 字节空文件，只看「文件存在」等于没验（密码错、选项错都会留下空文件）。
--
--     为什么这里可以给备份“降级”：本批的 DDL 是 `ADD COLUMN ... DEFAULT NULL`，
--     不重写任何一行数据、不改任何现有列含义；真正要保护的事实只有 status（新列一上来必然全为 NULL）。
--
--  ★ 不跑会怎样：见上面 1054 那条。另外 im-admin 的用户列表、举报工单处置里的
--     「封禁账号（限时）」都不可用；旧版后端（不带该字段的 jar）不受影响。
--
--  怎么判断自己的库要不要跑（只读，不动数据）：
--      SELECT COUNT(*) cols_exist FROM information_schema.COLUMNS
--       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user' AND COLUMN_NAME = 'disable_until';
--    返回 0 就跑本脚本，返回 1 就别跑（再跑会报 1060 Duplicate column name，见下）。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_user_ban_duration.sql"
--
--  幂等说明：MySQL 的 ALTER TABLE 没有 ADD COLUMN IF NOT EXISTS（MariaDB 才有），
--     所以本脚本【不能】无脑重跑——重复执行第 2 段会报：
--         ERROR 1060 (42000) at line 6: Duplicate column name 'disable_until'
--     这个报错是无害的（第一列没加成，语句直接失败，不动数据），但后面的
--     ADD KEY 会被 mysql 客户端 source 中断而不执行。重跑前先做第 1 段的只读预检：
--     列有了而索引没有，就只单独执行第 2 段里那条 ADD KEY。
--
--  回滚（先退回不带该字段的后端版本，再删列；反了会让旧代码遇上新表结构，同样报错）：
--      ALTER TABLE `im_user` DROP KEY `idx_disable_until`;
--      ALTER TABLE `im_user` DROP COLUMN `disable_until`;
--    删列会丢掉「谁被判到什么时候」这一事实本身——正在生效的限时封禁会退化成
--    永久封禁（status 仍是 0，只是没了到期时间），需要管理员逐个手动恢复。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 预检（只读）：列与索引现在各有几个。0 才需要往下跑。
-- -------------------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user'
              AND COLUMN_NAME = 'disable_until')                    AS col_exists,
       (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user'
              AND INDEX_NAME = 'idx_disable_until')                  AS idx_exists;

-- -------------------------------------------------------------------------------------
-- 2. 加列 + 加索引（与 im_schema.sql 逐字一致，包括注释文案与列位置）
--    AFTER status：把「什么时候开」紧挨着「现在还关不关」放，DESC 出来的表读得通。
--    DEFAULT NULL：存量行全部自动为 NULL。语义正好是「永久封禁或未被封禁」——
--      升级前所有 status=0 的账号由此变成永久封禁，与升级前的实际行为一致
--      （旧代码根本没有自动解封，他们本来就不会自己开），不需要为存量补时间。
--    索引而不是全表扫：任务每 30 秒一轮，代价应与「被封过的人数」成正比而非用户总数
--      ——绝大多数行该列是 NULL，走 range 扫描直接跳过。
-- -------------------------------------------------------------------------------------
ALTER TABLE `im_user`
    ADD COLUMN `disable_until` DATETIME DEFAULT NULL COMMENT '封禁到期时间：NULL 为永久（或未被封禁），仅 status=0 时有意义；到期由定时任务自动解封' AFTER `status`;

ALTER TABLE `im_user`
    ADD KEY `idx_disable_until` (`disable_until`);

-- -------------------------------------------------------------------------------------
-- 3. 核对（只读）：应该看到 status 紧跟着 disable_until，且类型为 datetime、可空。
-- -------------------------------------------------------------------------------------
SELECT COLUMN_NAME,
       COLUMN_TYPE,
       IS_NULLABLE,
       COLUMN_DEFAULT,
       COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'im_user'
  AND COLUMN_NAME IN ('status', 'disable_until')
ORDER BY ORDINAL_POSITION;

-- 顺带看一眼现状：被封过且仍禁用的账号，这一批全部是「永久」。
-- 这是预期结果，不是漏跑——旧的禁用动作没有时长概念，要给他们定时就在新版后台
-- 对他再点一次「禁用」（同一写路径，已禁用账号再禁用等于改期，不需要先恢复）。
SELECT `status`,
       `disable_until`,
       COUNT(*) AS cnt
FROM `im_user`
WHERE `deleted` = 0
  AND `status` = 0
GROUP BY `status`, `disable_until`
ORDER BY `disable_until`;

-- -------------------------------------------------------------------------------------
-- 4. 本批不改 RBAC，所以【不需要】清 Sa-Token 的权限缓存（im:auth:perm:* 一个键都不动）。
--    /api/admin/user/{id}/disable 用的还是既有的 system:user:manage，
--    /api/admin/report/{id}/handle 用的还是既有的 system:report:handle ——两批之前就已经
--    落在库里了。加的是参数和列，不是权限点。
--
--    需要做的只有两件：重启后端（新列 + 新实体字段 + 新定时任务），
--    以及重新构建/刷新 im-admin-ui（禁用弹框多了时长下拉）。
--
--    多实例部署无需加分布式锁、也无需选主：UPDATE 的 WHERE 把三个条件原样重复了一遍，
--    第二个到达的节点更新 0 行，日志里也就不会重复报同一批人。
-- =====================================================================================
