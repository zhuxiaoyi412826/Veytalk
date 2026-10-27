-- =====================================================================================
--  增量迁移：管理后台四期 · 第二批（远程控制审计页）
--
--  用途：给「已经跑过二三期、现在要多出一个远程控制审计页」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那份种子），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 3 段那条只读核对，
--        已经看到 admin 那一行 117 就不用再跑。
--
--  ★★ 零 DDL：本脚本不建表、不改表，只往既有的 im_permission / im_role_permission
--     两张 RBAC 表里各加一行。远程功能用到的三张表（im_remote_device /
--     im_remote_session / im_remote_audit_log）在二三期就已经建好，这一屏只是把它们
--     读出来给管理员看，一个新字段都不加。
--
--  ⚠ 为什么要有这一份：后端新增 /api/admin/remote/** 四个只读端点，类上
--     @SaCheckPermission("system:remote")。Sa-Token 按权限点串精确匹配，伞权限
--     system:manage 不是通配（只有 `*` 才是），所以没有这一点，超级管理员也进不了新页面。
--
--  ⚠ 为什么单独设点、不并进看板（system:dashboard）或直播（system:live:manage）：
--     im_remote_audit_log.detail 里存的是别人机器上的文件路径与命令行原文，敏感度与
--     日志页同一级——三期把 system:log 从监控里拆出来就是同一条判断。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动只涉及下面 2 张表，整表导出即可、不必全库：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_admin_remote.sql
--
--  ★★★ 不跑会怎样：老九个后台页面一切如旧，只有新出现的「远程控制」页报无权限
--     （接口返回 code=1003，HTTP 状态仍是 200）。菜单项也会自动少一项——
--     im-admin-ui 的路由守卫与 <el-menu-item v-if="auth.can(...)"> 都按同一个点判。
--
--  ★ 这一屏没有写动作，所以不跑本脚本也不会「少了一半功能」：不存在中断会话、
--     删除流水之类的按钮需要权限点支撑，缺的只是整页读权限。
--
--  怎么判断自己的库要不要跑（只读，不动数据）：跑过四期第一批的库 im_permission 是 16 行、
--  最大 system 点到 116；跑过本脚本后是 17 / 117：
--      SELECT (SELECT COUNT(*) FROM im_permission WHERE deleted = 0) perms,
--             (SELECT MAX(id) FROM im_permission WHERE perm_code LIKE 'system:%') max_sys_id;
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_admin_remote.sql"
--
--  幂等：全部走 INSERT IGNORE（im_permission 有 uk_perm_code、im_role_permission 有
--        uk_role_perm），重复执行不会主键冲突，可以安全重跑。
--        授权行按 code 子查询取 role_id / permission_id，不写死数字；但绑定行本身的
--        id（6017）与 im_data.sql 是同一批，两份脚本对同一行给的是同一个主键，
--        所以先后跑哪一份都不会长出两行重复授权。
--
--  回滚：删掉本脚本加进去的两行即可，既有数据与表结构一律未动：
--      DELETE FROM `im_role_permission` WHERE `id` = 6017;
--      DELETE FROM `im_permission`      WHERE `id` = 117;
--    （回滚后请同时把后端退回不含 AdminRemoteController 的版本，否则「远程控制」页全员 1003。）
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 1. 一个新权限点（与 ImConstants.PERM_SYSTEM_REMOTE 一一对应，文案与 im_data.sql
--    逐字一致，否则同一行数据会因为「走了哪份脚本」而长成两个样子）
--    一个点覆盖整屏（台账 + 单会话档案 + 审计流水 + 概览）：这四件事是同一个能力
--    「看远程控制发生过什么」，拆成四个点只会让授权表变长、不会让边界变清楚。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (117, 'system:remote', '后台远程审计', 'system', '远程控制会话台账与操作流水（只读，含命令与路径原文）', 1);

-- -------------------------------------------------------------------------------------
-- 2. ★必执行★ 给 admin 补齐这个点
--    少补就是新页面把超级管理员拦在外面。角色与权限一律按 code 子查询取 ID，
--    不写死数字：既有环境若改过角色主键也能正确落上。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6017, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:remote' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

-- moderator / operator 两个示范角色刻意不动：这一屏的流水里能读出别人机器上的路径、
-- 命令与文件名，先只给超级管理员。要下放就走后台「角色分配」（保存时服务端主动 evictCache，
-- 是唯一即时生效的入口）。

-- -------------------------------------------------------------------------------------
-- 3. 核对（只读）：应该看到 admin 的 117 一行。
--    如果这一段是空的，说明第 2 段没落到库上（多半是 role_code 不叫 'admin'），
--    此时先别启动新版后端。
-- -------------------------------------------------------------------------------------
SELECT r.`role_code`,
       p.`perm_code`,
       p.`perm_name`
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`id` = 117
ORDER BY r.`role_code`, p.`id`;

-- -------------------------------------------------------------------------------------
-- 4. ⚠ 改完种子不会立刻作用于「已经登录着」的管理员。
--    Sa-Token 的权限与角色缓存在 Redis 里、TTL 30 分钟（im:auth:perm:{userId} /
--    im:auth:role:{userId}）。直接写库缓存是不知道的，「退出再登录」也不管用——
--    登录动作本身既不写也不删这两个键。有效的只有下面两条路：
--      a) 清掉对应缓存键（Redis 只是缓存，可直接执行，不会丢业务数据）。三步，都在 PowerShell 下实测过：
--           ① redis-cli keys "im:auth:*"      ← 先只读列出有哪些键（本仓库 dbsize 才几百，KEYS 直接用没关系）
--           ② redis-cli DEL "im:auth:perm:1000" "im:auth:role:1000" "…把 ① 列出的键名全写进同一条、空格分隔…"
--              返回值就是被删掉的个数；给所有后台账号清一遍，不只 admin
--           ③ redis-cli keys "im:auth:*"      ← 应回 (empty list or set)
--         ★ 三种「看着省事」的写法都不要用，全是实测踩过的坑：
--           - `redis-cli --scan | ForEach-Object { redis-cli DEL $_ }` 在 Windows PowerShell 下静默什么都不删（本批实测踩过，
--             当时的现象是删完再 scan 键全在、看起来像「脚本跑了没生效」）；
--           - `redis-cli EVAL "...keys('im:auth:*')...del..." 0` 也不行：PowerShell 会把那条 lua 拆成多个参数，
--             直接回 `(error) ERR wrong number of arguments for 'eval' command`（第五批清缓存时实测；
--             这一条原本就是为了绕开上面那个坑而补进本文档的，结果它自己也跑不通，所以口径改回 ①②③ 三步先列后删）；
--           - `redis-cli --scan --pattern "im:auth:*" | Measure-Object -Line` 数出 0 **不代表清干净了**，
--             是管道吞了输出；复核残留只认第 ③ 步那条 keys。
--      b) 等 TTL 自然过期（30 分钟）——这期间看起来像「补了没生效」，别误判成脚本没跑上。
--
--  不需要重启后端：权限是每次请求回源时从库里读的，MyBatis 不缓存这张表。
-- =====================================================================================
