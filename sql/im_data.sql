-- =====================================================================================
--  IM 即时通讯系统 —— 初始化种子数据
--  依赖：必须先执行 sql/im_schema.sql
--
--  执行方式：
--    mysql -u "$env:MYSQL_USER" -p im_db < sql/im_data.sql
--
--  演示账号（密码统一为 123456）：
--    admin / 123456  —— 超级管理员，拥有全部权限点，用于演示 @SaCheckPermission 通过场景
--    alice / 123456  —— 普通用户，userId=1001
--    bob   / 123456  —— 普通用户，userId=1002，与 alice 互为好友并已有演示会话
--
--  密码密文由 org.springframework.security.crypto.argon2.Argon2PasswordEncoder
--  （Spring Security v5.8 默认参数：m=16384,t=2,p=1）离线生成，
--  校验时参数从密文自身解析，因此与 im.security.password-encoder 的运行期取值无关。
-- =====================================================================================

USE `im_db`;

-- 所有 INSERT 前先清理，保证脚本可重复执行
DELETE FROM `im_message`;
DELETE FROM `im_conversation_member`;
DELETE FROM `im_conversation`;
DELETE FROM `im_friend_request`;
DELETE FROM `im_friend`;
DELETE FROM `im_role_permission`;
DELETE FROM `im_user_role`;
DELETE FROM `im_permission`;
DELETE FROM `im_role`;
DELETE FROM `im_user`;

-- -------------------------------------------------------------------------------------
-- 1. 角色
-- -------------------------------------------------------------------------------------
INSERT INTO `im_role` (`id`, `role_code`, `role_name`, `description`, `status`)
VALUES (1, 'admin', '超级管理员', '拥有系统全部权限点', 1),
       (2, 'user', '普通用户', '注册即默认绑定的角色', 1);

-- -------------------------------------------------------------------------------------
-- 2. 权限点（与 com.im.common.constant.ImConstants 中的常量一一对应）
-- -------------------------------------------------------------------------------------
INSERT INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (101, 'user:profile:update', '修改个人资料', 'user', '修改昵称、头像、签名、性别、密码', 1),
       (102, 'friend:apply', '发起好友申请', 'friend', '搜索用户并发起好友申请', 1),
       (103, 'message:send', '发送消息', 'message', '发送文本、图片、文件、语音消息', 1),
       (104, 'message:recall:any', '撤回任意消息', 'message', '群主/管理员撤回群内他人消息', 1),
       (105, 'group:create', '创建群组', 'group', '创建群聊', 1),
       (106, 'file:upload', '上传文件', 'file', '上传头像与聊天附件', 1),
       (107, 'system:manage', '系统管理', 'system', '后台管理能力，仅管理员持有', 1);

-- admin 拥有全部权限点
INSERT INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
VALUES (6001, 1, 101),
       (6002, 1, 102),
       (6003, 1, 103),
       (6004, 1, 104),
       (6005, 1, 105),
       (6006, 1, 106),
       (6007, 1, 107);

-- user 拥有除「撤回任意消息」「系统管理」之外的权限点
INSERT INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
VALUES (6101, 2, 101),
       (6102, 2, 102),
       (6103, 2, 103),
       (6104, 2, 105),
       (6105, 2, 106);

-- -------------------------------------------------------------------------------------
-- 3. 演示用户
-- -------------------------------------------------------------------------------------
INSERT INTO `im_user` (`id`, `username`, `password`, `nickname`, `avatar`, `gender`, `signature`, `phone`, `email`,
                       `status`)
VALUES (1000, 'admin',
        '$argon2id$v=19$m=16384,t=2,p=1$Sf5L7MQjD4P12TiiFtHtEw$0U0n0WoV1+Oovrb2CumTshLOYPBSwd+zZQSjF+uKR1I',
        '系统管理员', NULL, 1, '保持系统稳定运行', '13800000000', 'admin@im.local', 1),
       (1001, 'alice',
        '$argon2id$v=19$m=16384,t=2,p=1$Sf5L7MQjD4P12TiiFtHtEw$0U0n0WoV1+Oovrb2CumTshLOYPBSwd+zZQSjF+uKR1I',
        '爱丽丝', NULL, 2, '今天也要元气满满呀', '13800000001', 'alice@im.local', 1),
       (1002, 'bob',
        '$argon2id$v=19$m=16384,t=2,p=1$Sf5L7MQjD4P12TiiFtHtEw$0U0n0WoV1+Oovrb2CumTshLOYPBSwd+zZQSjF+uKR1I',
        '鲍勃', NULL, 1, '代码写不完', '13800000002', 'bob@im.local', 1);

INSERT INTO `im_user_role` (`id`, `user_id`, `role_id`)
VALUES (5001, 1000, 1),
       (5002, 1001, 2),
       (5003, 1002, 2);

-- -------------------------------------------------------------------------------------
-- 4. 好友关系（双向各一行）与一条已同意的历史申请
-- -------------------------------------------------------------------------------------
INSERT INTO `im_friend` (`id`, `user_id`, `friend_id`, `remark`, `group_name`, `status`)
VALUES (7001, 1001, 1002, NULL, '默认分组', 1),
       (7002, 1002, 1001, 'Alice', '同事', 1);

INSERT INTO `im_friend_request` (`id`, `from_user_id`, `to_user_id`, `verify_message`, `source`, `status`,
                                 `handle_time`)
VALUES (8001, 1001, 1002, '你好，我是 Alice，加个好友吧', 'search', 1, '2024-01-01 10:00:00');

-- -------------------------------------------------------------------------------------
-- 5. 演示会话：alice 与 bob 的单聊
--    biz_key 规则 s:{较小ID}:{较大ID}，保证同一对用户只有一个会话
-- -------------------------------------------------------------------------------------
INSERT INTO `im_conversation` (`id`, `type`, `target_id`, `biz_key`, `last_msg_id`, `last_msg_content`, `last_msg_type`,
                              `last_msg_time`)
VALUES (2001, 1, NULL, 's:1001:1002', 3003, '收到，我这边马上处理', 1, '2024-01-02 09:02:00');

-- alice 已读完全部消息；bob 还有 1 条未读（seq=3）
INSERT INTO `im_conversation_member` (`id`, `conversation_id`, `user_id`, `unread_count`, `last_ack_seq`, `is_top`,
                                      `top_time`, `is_muted`, `is_deleted`)
VALUES (4001, 2001, 1001, 0, 3, 1, '2024-01-02 09:05:00', 0, 0),
       (4002, 2001, 1002, 1, 2, 0, NULL, 0, 0);

INSERT INTO `im_message` (`id`, `client_msg_id`, `conversation_id`, `from_user_id`, `msg_type`, `content`, `extra`,
                          `seq`, `is_recalled`, `send_time`)
VALUES (3001, 'seed-alice-0001', 2001, 1001, 1, '在吗？昨天的需求文档看了没', NULL, 1, 0, '2024-01-02 09:00:00'),
       (3002, 'seed-bob-0001', 2001, 1002, 1, '看了，第三节的会话排序有点疑问', NULL, 2, 0, '2024-01-02 09:01:00'),
       (3003, 'seed-bob-0002', 2001, 1002, 1, '收到，我这边马上处理', NULL, 3, 0, '2024-01-02 09:02:00');

-- -------------------------------------------------------------------------------------
-- 6. 送达/已读回执：bob 已收到并读完 seq 1、2；seq 3 尚未被 alice 读取
-- -------------------------------------------------------------------------------------
INSERT INTO `im_message_read` (`id`, `message_id`, `user_id`, `delivered_time`, `read_time`)
VALUES (9001, 3001, 1002, '2024-01-02 09:00:05', '2024-01-02 09:00:30'),
       (9002, 3002, 1001, '2024-01-02 09:01:05', '2024-01-02 09:01:20'),
       (9003, 3003, 1001, '2024-01-02 09:02:05', NULL);
