-- =====================================================================================
--  增量迁移：管理后台四期 · 第五批（钱包与礼物 / AI 面试记录 / 群组管理 / 消息管理）
--
--  用途：给「已经跑过二三四期、现在要多出这四屏」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那份种子），无需本脚本。
--
--  状态：尚未在任何环境实跑（新交付脚本）。拿到这份脚本先跑第 0 段的两条只读预检。
--
--  ★★ 零 DDL：本脚本不建表、不改表、不加字段，只往既有的 im_permission /
--     im_role_permission 两张 RBAC 表里各加四行。四屏读的全是早已存在的业务表：
--       119 钱包与礼物 -> im_user_wallet / im_wallet_transaction / im_gift（im-user）
--                      与 im_gift_record（im-live）
--       120 面试记录   -> im_interview_session / im_interview_message / im_interview_event（im-ai）
--       121 群组管理   -> im_group / im_group_member / im_group_join_request / im_group_robot（im-group）
--       122 消息管理   -> im_message / im_message_read（im-message）
--     连「这条消息是后台管理员撤的」那个问责标记都没有补列——它写在 im_message.extra
--     （本来就是 JSON 列）里的 recalledByAdmin 键上，由 force 分支用 JSON_SET 落笔，
--     并且 MessageConvert 在已撤回的消息上一律不把 extra 下发给客户端，所以它只进后台。
--
--  ⚠ 为什么要四个点而不并进 109（system:user:manage）：
--     119 是本批唯一能改别人余额的写权限。管一个人的「能不能登录」和管他的「余额是多少」
--        是两种风险：前者错了可以恢复，后者错了是凭空造出货币；
--     120 读的是 im_interview_message.content，那是候选人的作答原文，还可能被第三方招聘方看到；
--     121 挂着「强制解散一个百人群」，禁一个人的号与关掉一个群是两个影响面，
--        后者会把没被举报的人一起挡在门外；
--     122 能按关键词模糊搜 im_message.content，也就是读任意两个人的聊天正文，
--        与 115（system:log）同级对待——给了 110（举报处置）或 121（群管理）都不顺带得到它。
--
--  ⚠ Sa-Token 按权限点串精确匹配，伞权限 system:manage 不是通配（只有 `*` 才是）。
--     所以不跑本脚本，超级管理员也进不了这四个新页面。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。改动只涉及下面 2 张表，整表导出即可、不必全库：
--      mysqldump -u root -p --default-character-set=utf8mb4 im_db im_permission im_role_permission > backup_rbac_admin_wallet_interview_group_message.sql
--
--  ★★★ 不跑会怎样：既有后台页面一切如旧，只有新出现的四个页面报无权限
--     （接口返回 code=1003，HTTP 状态仍是 200）。菜单项也会自动少四项——
--     im-admin-ui 的路由守卫与 <el-menu-item v-if="auth.can(...)"> 都按同一个点判。
--
--  怎么判断自己的库要不要跑：跑过四期第四批（全服公告）的库 im_permission 是 18 行、
--  最大 system 点到 118；跑完本脚本后应当是 22 / 122。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_admin_wallet_interview_group_message.sql"
--
--  幂等：全部走 INSERT IGNORE（im_permission 有 uk_perm_code、im_role_permission 有
--        uk_role_perm），重复执行不会主键冲突，可以安全重跑。
--        授权行按 code 子查询取 role_id / permission_id，不写死数字；但绑定行本身的
--        id（6019~6022）与 im_data.sql 是同一批，两份脚本对同一行给的是同一个主键，
--        所以先后跑哪一份都不会长出两行重复授权。
--
--  回滚见文末第 5 段，只删本脚本加进去的 8 行，既有数据与表结构一律未动。
-- =====================================================================================

USE `im_db`;

-- -------------------------------------------------------------------------------------
-- 0. 预检（只读，不动数据）。两条都看完再决定要不要往下跑。
--
--    0.1 当前 RBAC 形状：期望 perms=18、max_sys_id=118（已经跑过公告那批的库）。
--        如果 max_sys_id 已经是 122，说明本脚本已经跑过，不必再跑。
--        ★ 实测一例：本机跑前是 perms=17、max_sys_id=117（第四批那份还没执行），
--          本脚本照样插成功，插完是 21 而不是这里的 22。差额不是本脚本的问题
--          （119~122 不依赖 118），但它意味着 **`im_config` / `im_sensitive_word` / `im_announcement`
--          三张表还不存在、118 也没插**，必须补跑 `sql/migration_admin_system_config.sql`；
--          否则后端代码里那些控制器与每 30 秒一轮的配置同步任务会持续因「表不存在」报错，
--          而公告屏因为 118 没授给任何角色而根本不会出现在菜单里。
-- -------------------------------------------------------------------------------------
SELECT (SELECT COUNT(*) FROM `im_permission` WHERE `deleted` = 0)                          AS perms,
       (SELECT MAX(`id`) FROM `im_permission` WHERE `perm_code` LIKE 'system:%')          AS max_sys_id,
       (SELECT COUNT(*) FROM `im_role_permission` WHERE `id` BETWEEN 6019 AND 6022)        AS bound_new;

--    0.2 四个主键位是否空闲：两条都必须返回空集。
--        有返回行说明这个库自己占用过 119~122 / 6019~6022（例如手工插过别的点），
--        此时不要硬跑本脚本——INSERT IGNORE 会静默跳过那一行，你会得到「跑了但没生效」
--        这种最难查的现象。先手工把冲突行改掉或换一批 ID，再回来跑。
SELECT `id`, `perm_code`, `perm_name`
FROM `im_permission`
WHERE `id` IN (119, 120, 121, 122);

SELECT `id`, `role_id`, `permission_id`
FROM `im_role_permission`
WHERE `id` IN (6019, 6020, 6021, 6022);

-- -------------------------------------------------------------------------------------
-- 1. 四个新权限点（与 ImConstants 的 PERM_SYSTEM_WALLET / PERM_SYSTEM_INTERVIEW /
--    PERM_SYSTEM_GROUP_MANAGE / PERM_SYSTEM_MESSAGE_MANAGE 一一对应，
--    文案与 im_data.sql 逐字一致，否则同一行数据会因为「走了哪份脚本」而长成两个样子）
--
--    每个点覆盖自己那一整屏（含该屏的概览、档案与写动作）：四屏是四种不同的能力，
--    所以是四个点；同一屏内部再拆只会让授权表变长、不会让边界变清楚。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (119, 'system:wallet', '后台钱包管理', 'system', '钱包台账与流水、余额调整、礼物上下架（唯一能改别人余额的点）', 1),
       (120, 'system:interview', '后台面试记录', 'system', 'AI 面试场次、作答原文与违规审计（只读）', 1),
       (121, 'system:group:manage', '后台群组管理', 'system', '群台账与成员、强制解散（不可逆）与全员禁言', 1),
       (122, 'system:message:manage', '后台消息管理', 'system', '全库消息检索（含聊天正文）与强制撤回', 1);

-- -------------------------------------------------------------------------------------
-- 2. ★必执行★ 给 admin 补齐这四个点
--    少补就是新页面把超级管理员拦在外面。角色与权限一律按 code 子查询取 ID，
--    不写死数字：既有环境若改过角色主键也能正确落上。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6019, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:wallet' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6020, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:interview' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6021, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:group:manage' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
SELECT 6022, r.`id`, p.`id`
FROM `im_role` r
         JOIN `im_permission` p ON p.`perm_code` = 'system:message:manage' AND p.`deleted` = 0
WHERE r.`role_code` = 'admin' AND r.`deleted` = 0;

-- moderator / operator 两个示范角色刻意不动：这四屏分别能改别人的余额、读应聘者的答案、
-- 解散一个群、搜全站聊天正文，先只给超级管理员。要下放就走后台「角色分配」
-- （保存时服务端主动 evictCache，是唯一即时生效的入口）。

-- -------------------------------------------------------------------------------------
-- 3. 核对（只读）：应该看到 admin 的四行（119 / 120 / 121 / 122）。
--    少于四行说明第 2 段没落到库上（多半是 role_code 不叫 'admin'），
--    此时先别启动新版后端。
-- -------------------------------------------------------------------------------------
SELECT r.`role_code`,
       p.`id`,
       p.`perm_code`,
       p.`perm_name`
FROM `im_role_permission` rp
         JOIN `im_role` r ON r.`id` = rp.`role_id`
         JOIN `im_permission` p ON p.`id` = rp.`permission_id`
WHERE p.`id` IN (119, 120, 121, 122)
ORDER BY r.`role_code`, p.`id`;

-- 收尾再看一眼形状，期望 perms=22、max_sys_id=122：
SELECT (SELECT COUNT(*) FROM `im_permission` WHERE `deleted` = 0)                    AS perms,
       (SELECT MAX(`id`) FROM `im_permission` WHERE `perm_code` LIKE 'system:%')     AS max_sys_id;

-- -------------------------------------------------------------------------------------
-- 4. ⚠ 改完种子不会立刻作用于「已经登录着」的管理员——这一步不能省。
--    Sa-Token 的权限与角色缓存在 Redis 里、TTL 30 分钟（im:auth:perm:{userId} /
--    im:auth:role:{userId}）。直接写库缓存是不知道的，「退出再登录」也不管用——
--    登录动作本身既不写也不删这两个键。有效的只有下面两条路：
--      a) 清掉对应缓存键（Redis 只是缓存，可直接执行，不会丢业务数据）。三步，都在 PowerShell 下实测过：
--           ① redis-cli keys "im:auth:*"      ← 先只读列出有哪些键（本仓库 dbsize 才几百，KEYS 直接用没关系）
--           ② redis-cli DEL "im:auth:perm:1000" "im:auth:role:1000" "…把 ① 列出的键名全写进同一条、空格分隔…"
--              返回值就是被删掉的个数；给所有后台账号清一遍，不只 admin
--           ③ redis-cli keys "im:auth:*"      ← 应回 (empty list or set)
--         ★ 三种「看着省事」的写法都不要用，全是实测踩过的坑：
--           - `redis-cli --scan | ForEach-Object { redis-cli DEL $_ }` 在 Windows PowerShell 下静默什么都不删（前几批踩过）；
--           - `redis-cli EVAL "...keys('im:auth:*')...del..." 0` 也不行：PowerShell 会把那条 lua 拆成多个参数，
--             直接回 `(error) ERR wrong number of arguments for 'eval' command`（第五批清缓存时实测；
--             这一条原本就是为了绕开上面那个坑写的，结果它自己也跑不通，所以口径改回 ①②③ 三步先列后删）；
--           - `redis-cli --scan --pattern "im:auth:*" | Measure-Object -Line` 数出 0 **不代表清干净了**，
--             是管道吞了输出；复核残留只认第 ③ 步那条 keys。
--      b) 等 TTL 自然过期（30 分钟）——这期间看起来像「补了没生效」，别误判成脚本没跑上。
--
--  不需要重启后端：权限是每次请求回源时从库里读的，MyBatis 不缓存这张表。
--
--  顺带一条与本脚本无关但同批的提醒：im_message.extra 里那个 recalledByAdmin 键
--  只有跑完新版后端之后的强撤才会写上；历史撤回记录读出来是「不是后台撤的」，
--  概览里「今日后台强撤」这个数因此从部署当天开始算，不回溯。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 5. 回滚：删掉本脚本加进去的 8 行即可。先删绑定再删权限点（外键方向上更干净）。
--      DELETE FROM `im_role_permission` WHERE `id` IN (6019, 6020, 6021, 6022);
--      DELETE FROM `im_permission`      WHERE `id` IN (119, 120, 121, 122);
--    回滚后记得再清一次 im:auth:* 缓存（同样的 30 分钟 TTL，方向相反），
--    并把后端退回不含这四个 AdminXxxController 的版本，否则四页全员 1003。
--    业务数据不受任何影响：本批从未写过 im_message / im_group / im_user_wallet 的任何一行。
-- =====================================================================================
