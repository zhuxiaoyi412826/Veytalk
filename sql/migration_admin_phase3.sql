-- =====================================================================================
--  增量迁移：管理后台三期（视频管理 + 直播管理 + 监控告警 + 日志管理）
--
--  用途：给「已经跑过二期、只想升级后台四个新页面」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那份种子），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 3 段那条只读核对，
--        已经看到 admin 四行 112~115 就不用再跑。
--
--  ★★ 零 DDL：本脚本不建表、不改表，只往既有的 im_permission / im_role_permission
--     两张 RBAC 表里加行。表底座一期就在、二期已验证这套加行方式，三期只是再补四个点。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动只涉及下面 2 张表，整表导出即可、不必全库：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_phase3.sql
--
--  ★★★ 不跑会怎样：三期四个新控制器（/api/admin/video|live|monitor|log）的
--     @SaCheckPermission 分别认 system:video:manage / system:live:manage / system:monitor /
--     system:log。Sa-Token 按权限点串精确匹配，伞权限 system:manage 不是通配（只有 `*` 才是），
--     因此不跑本脚本时老管理员登录后台一切如旧（二期的四个页面照常），但打开四个新页面
--     全部报「无权限」（接口返回 code=1003，HTTP 状态仍是 200）。
--     与二期不同，这次不会出现「管理员全被拦在门外」——老端点的注解一行没动，
--     新端点缺行只是新页面进不去，所以优先级比二期脚本低，但上线前务必补。
--
--  怎么判断自己的库要不要跑（只读，不动数据）：跑过二期的库 im_permission 行数 11、
--  最大 system 点只到 111；跑过本脚本后是 15 / 115：
--      SELECT (SELECT COUNT(*) FROM im_permission WHERE deleted = 0) perms,
--             (SELECT COUNT(*) FROM im_role_permission) binds;
--
--  【症状对照】库里停在二期数据时的典型表现：im-admin-ui 用 admin / 123456 登录，
--        老四个页面正常，视频管理 / 直播管理 / 监控告警 / 日志管理四个新页面全报「无权限」。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_admin_phase3.sql"
--
--  幂等：全部走 INSERT IGNORE（im_permission 有 uk_perm_code、im_role_permission 有
--        uk_role_perm），重复执行不会主键冲突，可以安全重跑。
--        授权行按 code 子查询取 role_id / permission_id，不写死数字；但绑定行本身的
--        id（6012~6015）与 im_data.sql 是同一批，两份脚本对同一行给的是同一个主键，
--        所以先后跑哪一份都不会长出两行重复授权。
--
--  回滚：删掉本脚本加进去的行即可，既有数据与表结构一律未动：
--      DELETE FROM `im_role_permission` WHERE `id` IN (6012,6013,6014,6015);
--      DELETE FROM `im_permission`      WHERE `id` IN (112,113,114,115);
--    （回滚后请同时把后端退回二期版本，否则四个新控制器会因为库里没有对应点而全员 1003。）
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 四个新权限点（与 ImConstants.PERM_SYSTEM_* 三期新增一一对应，文案与 im_data.sql
--    逐字一致，否则同一行数据会因为「走了哪份脚本」而长成两个样子）
--    system:log 单独设点而不是并入 system:monitor：日志原文可能含手机号、审核命中记录
--    等敏感内容，能看指标曲线的人不必能看日志正文——这是这两页分开的全部理由。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (112, 'system:video:manage', '后台视频管理', 'system', '检索视频、上/下架、删除（逻辑删除）', 1),
       (113, 'system:live:manage', '后台直播管理', 'system', '检索直播间、详情、强制关播', 1),
       (114, 'system:monitor', '后台监控', 'system', 'JVM/HTTP/连接池指标、健康检查与慢接口', 1),
       (115, 'system:log', '后台日志', 'system', '分档日志尾读与关键词过滤（含敏感内容）', 1);

-- -------------------------------------------------------------------------------------
-- 2. ★必执行★ 给 admin 补齐四个点
--    少补任何一个，对应的新页面就会把超级管理员拦在外面。
--    角色与权限一律按 code 子查询取 ID，不写死数字：既有环境若改过角色主键也能正确落上。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT x.`bind_id`, r.`id`, p.`id`
FROM (SELECT 6012 AS `bind_id`, 'system:video:manage' AS `perm_code`
      UNION ALL SELECT 6013, 'system:live:manage'
      UNION ALL SELECT 6014, 'system:monitor'
      UNION ALL SELECT 6015, 'system:log') x
         JOIN `im_role` r ON r.`role_code` = 'admin' AND r.`deleted` = 0
         JOIN `im_permission` p ON p.`perm_code` = x.`perm_code` AND p.`deleted` = 0;

-- 示范角色 moderator / operator 本脚本刻意不动：监控与日志是新能力，先只给超级管理员，
-- 观察一个迭代再决定要不要下放。要下放就在后台「角色分配」里显式授予（那是唯一即时
-- 生效的入口，保存时服务端会主动 evictCache），不要在这里加行。

-- -------------------------------------------------------------------------------------
-- 3. 核对（只读）：应该看到 admin 的 112~115 四行。
--    如果这一段是空的，说明第 2 段没落到库上（多半是 role_code 不叫 'admin'），
--    此时先别启动新版后端。
-- -------------------------------------------------------------------------------------
SELECT r.`role_code`,
       p.`perm_code`,
       p.`perm_name`
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`id` BETWEEN 112 AND 115
ORDER BY r.`role_code`, p.`id`;

-- -------------------------------------------------------------------------------------
-- 4. ⚠ 改完种子不会立刻作用于「已经登录着」的管理员。
--    Sa-Token 的权限与角色缓存在 Redis 里、TTL 30 分钟（im:auth:perm:{userId} /
--    im:auth:role:{userId}）。直接写库缓存是不知道的，「退出再登录」也不管用——
--    登录动作本身既不写也不删这两个键。有效的只有下面两条路：
--      a) 清掉对应缓存键（Redis 只是缓存，可直接执行，不会丢业务数据）：
--           redis-cli DEL "im:auth:perm:1000" "im:auth:role:1000"
--         给所有后台账号清一遍（不只 admin）：
--           redis-cli --scan --pattern "im:auth:perm:*" | ForEach-Object { redis-cli DEL $_ }
--           redis-cli --scan --pattern "im:auth:role:*" | ForEach-Object { redis-cli DEL $_ }
--      b) 等 TTL 自然过期（30 分钟）——这期间看起来像「补了没生效」，别误判成脚本没跑上。
--
--  不需要重启后端：表名与行内容都是每条 SQL 下发时才在 MySQL 侧解析的，连接池与 MyBatis
--  不缓存这些数据；删完 key 下一次请求就回源读到新权限。
-- =====================================================================================
