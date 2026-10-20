-- =====================================================================================
--  增量迁移：管理后台二期（多角色分级 + 角色分配）
--
--  用途：给「已经跑过完整库、只想升级后台权限分级」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那份种子），无需本脚本。
--
--  ★★ 零 DDL：本脚本不建表、不改表，只往既有的 im_role / im_permission /
--     im_role_permission 三张 RBAC 表里加行。这四张表的底座一期就在，二期只是把
--     「一个伞权限」拆成「四个细粒度点」，所以没有新增列的必要。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。执行前建议先备份：
--      mysqldump -u "$env:MYSQL_USER" -p im_db > im_db_bak.sql
--
--  ★★★ 不跑会怎样（这条务必先读）：**现有管理员会被全部拦在后台门外。**
--     二期把四个后台控制器的 @SaCheckPermission 从伞权限 system:manage 改到了
--     system:dashboard / system:user:manage / system:report:handle / system:role:manage。
--     Sa-Token 的权限判定是「按权限点串精确匹配」，伞权限字符串不再出现在任何一个控制器的
--     注解里，因此只持有 107 的 admin 角色在升级后打开后台会一路 403。
--     这不是「功能不可用」而是「进不去」——所以第 2 段（给 admin 补齐四个点）是本脚本的
--     必执行部分，别只种两个新角色就跳过它。
--     前端不受影响地报错：im-admin-ui 登录后拉不到看板数据，页面显示无权限而非白屏。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_admin_phase2.sql"
--
--  幂等：全部走 INSERT IGNORE（im_role 有 uk_role_code、im_permission 有 uk_perm_code、
--        im_role_permission 有 uk_role_perm），重复执行不会主键冲突，可以安全重跑。
--
--  回滚：删掉本脚本加进去的行即可，既有数据与表结构一律未动：
--      DELETE FROM `im_role_permission` WHERE `id` IN (6008,6009,6010,6011,6201,6202,6301,6302,6303);
--      DELETE FROM `im_role`            WHERE `id` IN (3,4);
--      DELETE FROM `im_permission`      WHERE `id` IN (108,109,110,111);
--    （回滚后必须同时把后端退回一期版本，否则就是上面那个「管理员全被拦」的状态。）
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 四个细粒度权限点（与 ImConstants.PERM_SYSTEM_* 一一对应，文案与 im_data.sql 逐字一致，
--    否则同一行数据会因为“走了哪份脚本”而长成两个样子）
--    107 system:manage 保留不删：它是「全权管理员」的历史标记，
--    但二期起不再被任何控制器引用，只拿 107 不等于能进后台。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (108, 'system:dashboard', '后台看板', 'system', '只读看板：平台数字与举报统计', 1),
       (109, 'system:user:manage', '后台用户管理', 'system', '检索 / 档案 / 禁用 / 恢复账号', 1),
       (110, 'system:report:handle', '后台举报处置', 'system', '查看与受理 / 驳回 / 处置举报工单', 1),
       (111, 'system:role:manage', '后台角色分配', 'system', '列出角色、查看与重设用户角色', 1);

-- 把 107 的描述刷成二期口径（不影响任何判定，只是把「它已不再是真门」这件事写进数据字典，
-- 免得以人看到表里还有一行 system:manage 以为自己拿着它就能进后台）
UPDATE `im_permission`
SET `description` = '后台伞权限，仅 admin 持有（二期保留作向后兼容）'
WHERE `id` = 107;

-- -------------------------------------------------------------------------------------
-- 2. ★必执行★ 给 admin 补齐四个点
--    少补任何一个，对应的后台页面就会把超级管理员拦在外面。
--    角色与权限一律按 code 子查询取 ID，不写死数字：既有环境若改过角色主键也能正确落上。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT x.`bind_id`, r.`id`, p.`id`
FROM (SELECT 6008 AS `bind_id`, 'system:dashboard' AS `perm_code`
      UNION ALL SELECT 6009, 'system:user:manage'
      UNION ALL SELECT 6010, 'system:report:handle'
      UNION ALL SELECT 6011, 'system:role:manage') x
         JOIN `im_role` r ON r.`role_code` = 'admin' AND r.`deleted` = 0
         JOIN `im_permission` p ON p.`perm_code` = x.`perm_code` AND p.`deleted` = 0;

-- -------------------------------------------------------------------------------------
-- 3. 两档示范角色（不要也能跑，要的就是「最小权限」这件事可被演示与直接使用）
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role` (`id`, `role_code`, `role_name`, `description`, `status`)
VALUES (3, 'moderator', '审核员', '后台最小权限示范：只看板 + 处置举报，不能管账号也不能配角色', 1),
       (4, 'operator', '运营', '看板 + 账号管理 + 角色分配，但不碰举报处置', 1);

-- moderator：看板 + 举报处置。
-- 它拿得到 system:report:handle 就能在处置时选「封禁用户」（内部经 SPI 落成禁用），
-- 但那是「对着工单动作」，与「打开用户列表随意禁人」不同档；它也进不了用户管理页。
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT x.`bind_id`, r.`id`, p.`id`
FROM (SELECT 6201 AS `bind_id`, 'system:dashboard' AS `perm_code`
      UNION ALL SELECT 6202, 'system:report:handle') x
         JOIN `im_role` r ON r.`role_code` = 'moderator' AND r.`deleted` = 0
         JOIN `im_permission` p ON p.`perm_code` = x.`perm_code` AND p.`deleted` = 0;

-- operator：看板 + 账号管理 + 角色分配，不给举报处置（碰不到封号/撤消息/关房间）。
-- 给它 system:role:manage 是因为「管人」与「给人配角色」在运营岗位上本就是同一件事；
-- 真要拆得更细（只禁人不配角色），删掉这一段里的 6303 那一行即可，后台菜单会自动少一项。
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT x.`bind_id`, r.`id`, p.`id`
FROM (SELECT 6301 AS `bind_id`, 'system:dashboard' AS `perm_code`
      UNION ALL SELECT 6302, 'system:user:manage'
      UNION ALL SELECT 6303, 'system:role:manage') x
         JOIN `im_role` r ON r.`role_code` = 'operator' AND r.`deleted` = 0
         JOIN `im_permission` p ON p.`perm_code` = x.`perm_code` AND p.`deleted` = 0;

-- -------------------------------------------------------------------------------------
-- 4.（可选）示范绑定：把既有演示账号挂上新角色，好立刻用不同身份登录看菜单差异。
--    生产环境请勿照抄——正式的做法是登录后在「用户管理 → 角色分配」里显式授予。
--    取消注释前请先确认 1001 / 1002 在你的库里确实是那两个演示账号。
-- -------------------------------------------------------------------------------------
-- INSERT IGNORE INTO `im_user_role` (`id`, `user_id`, `role_id`)
-- SELECT 5004, u.`id`, r.`id` FROM `im_user` u JOIN `im_role` r ON r.`role_code` = 'moderator' WHERE u.`username` = 'alice'
-- UNION ALL
-- SELECT 5005, u.`id`, r.`id` FROM `im_user` u JOIN `im_role` r ON r.`role_code` = 'operator'  WHERE u.`username` = 'bob';

-- -------------------------------------------------------------------------------------
-- 5. 核对（只读）：应该看到 admin 四个点、moderator 2 行、operator 3 行。
--    如果 admin 那一段是空的，说明第 2 段没落到库上（多半是 role_code 不叫 'admin'），
--    此时先别启动新版后端。
-- -------------------------------------------------------------------------------------
SELECT r.`role_code`,
       p.`perm_code`,
       p.`perm_name`
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`perm_code` LIKE 'system:%'
ORDER BY r.`role_code`, p.`id`;

-- -------------------------------------------------------------------------------------
-- 6. ⚠ 改完种子不会立刻作用于「已经登录着」的管理员。
--    Sa-Token 的权限与角色缓存在 Redis 里、TTL 30 分钟（im:auth:perm:{userId} /
--    im:auth:role:{userId}）。走后台界面的「角色分配」改动会由服务端主动失效缓存、即时生效；
--    但像本脚本这样直接写库，缓存是不知道的。两种办法：
--      a) 让相关管理员退出再登录（最省事）；
--      b) 清掉对应缓存键（Redis 只是缓存，可直接执行）：
--           redis-cli DEL "im:auth:perm:1000" "im:auth:role:1000"
--         或整片清（会影响所有在线用户，各自下次取值时重建）：
--           redis-cli --scan --pattern "im:auth:*" | ForEach-Object { redis-cli DEL $_ }
-- =====================================================================================
