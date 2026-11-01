-- =====================================================================================
--  系统保留联系人（客服 / 文件传输助手）存量回填脚本
--  适用：库里已有注册用户、但这些用户还没挂上「客服(1)」「文件传输助手(2)」两个保留号的场景。
--  全新库无需本文件——im_data.sql 已包含保留账号与关系种子，新用户注册时由后端钩子自动装配。
--
--  内容：① 建两个保留账号（im_user 的 1、2 两行）
--        ② 给所有存量用户回填与 1、2 的双向好友关系（im_friend，每人 4 行）
--        会话不在这里建：由 /api/conversation/assistant 或首次发消息时 getOrCreateSingle 懒建（幂等）。
--
--  执行（PowerShell 下用 -e "source"，不要用 < 或 Get-Content 管道，中文会被刷成 ?）：
--    mysql -u root -p --default-character-set=utf8mb4 -e "source sql/im_system_contact_patch.sql"
--
--  幂等：好友唯一键 uk_user_friend 去重；回填主键用 9000000000 段（运行期雪花 ID ~10^18，
--        种子 ID <10000，均不重叠）。重复执行时「已存在的成对关系」被 NOT EXISTS 过滤掉，不再插入。
--        ※ 极端情况：若两次执行之间有用户漏建、需再次补跑，请先把下方两处基数 9000000000 改大
--          （如 9100000000）再执行，避免新行的主键撞上上一批已占用的 id 而被 INSERT IGNORE 跳过。
-- =====================================================================================

USE `im_db`;

-- ① 两个保留账号 ----------------------------------------------------------------------
-- password 写 NO_PASSWORD 哨兵（= 后端 User.NO_PASSWORD），不可密码登录；仅作消息收发端。
INSERT IGNORE INTO `im_user` (`id`, `username`, `password`, `nickname`, `avatar`, `gender`, `signature`, `phone`,
                              `email`, `status`)
VALUES (1, 'service', '!NO_PASSWORD!', '客服', NULL, 0, '官方客服', NULL, NULL, 1),
       (2, 'filehelper', '!NO_PASSWORD!', '文件传输助手', NULL, 0, '手机发这里，电脑收', NULL, NULL, 1);

-- ② 回填存量用户 ↔ 保留账号的双向好友关系 --------------------------------------------
-- 对每个非保留、未删除的用户 u，补齐 u→1、1→u、u→2、2→u 四行；已存在的成对关系跳过。
INSERT IGNORE INTO `im_friend` (`id`, `user_id`, `friend_id`, `remark`, `group_name`, `status`)
SELECT 9000000000 + ROW_NUMBER() OVER (ORDER BY u.`id`, c.`contact_id`, d.`dir`),
       CASE WHEN d.`dir` = 0 THEN u.`id` ELSE c.`contact_id` END AS user_id,
       CASE WHEN d.`dir` = 0 THEN c.`contact_id` ELSE u.`id` END AS friend_id,
       NULL, '默认分组', 1
FROM `im_user` u
         CROSS JOIN (SELECT 1 AS `contact_id`
                     UNION ALL
                     SELECT 2) c
         CROSS JOIN (SELECT 0 AS `dir`
                     UNION ALL
                     SELECT 1) d
WHERE u.`id` NOT IN (1, 2)
  AND u.`deleted` = 0
  AND NOT EXISTS (SELECT 1
                  FROM `im_friend` f
                  WHERE f.`user_id` = CASE WHEN d.`dir` = 0 THEN u.`id` ELSE c.`contact_id` END
                    AND f.`friend_id` = CASE WHEN d.`dir` = 0 THEN c.`contact_id` ELSE u.`id` END);

-- =====================================================================================
--  只读核对（可选，取消注释后单独执行）
-- =====================================================================================
-- 两个保留账号是否就位（期望 2）：
-- SELECT COUNT(*) AS reserved_users FROM `im_user` WHERE `id` IN (1, 2);
-- 回填生成的好友行数（每个存量用户 4 行）：
-- SELECT COUNT(*) AS backfilled_friends FROM `im_friend` WHERE `id` >= 9000000000;
-- 尚未挂上「文件传输助手(2)」的用户（期望 0）：
-- SELECT u.`id` FROM `im_user` u
--   WHERE u.`id` NOT IN (1, 2) AND u.`deleted` = 0
--     AND NOT EXISTS (SELECT 1 FROM `im_friend` f WHERE f.`user_id` = u.`id` AND f.`friend_id` = 2);

-- =====================================================================================
--  回滚（危险，务必确认后再单独执行；会清掉保留账号与其相关的好友关系）
-- =====================================================================================
-- DELETE FROM `im_friend` WHERE `friend_id` IN (1, 2) OR `user_id` IN (1, 2);
-- DELETE FROM `im_user` WHERE `id` IN (1, 2);
