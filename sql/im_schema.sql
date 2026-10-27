-- =====================================================================================
--  IM 即时通讯系统 —— 建库建表脚本
--  适用：MySQL 8.0+（InnoDB / utf8mb4）
--
--  执行方式（凭据从环境变量读取，脚本内不含任何明文密码）：
--    mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 -e "source sql/im_schema.sql"
--    或在客户端中直接 source 本文件；Windows PowerShell 下不要用 `<` 重定向，
--    管道会把中文刷成 ?（表注释、列注释跟着坏掉）。
--
--  约定：
--    1. 所有主键为 BIGINT，由 MyBatis-Plus 雪花算法生成，不使用自增，便于将来分库分表
--    2. 逻辑删除字段统一命名 deleted（0 未删除 / 1 已删除），只对需要保留历史的主表启用；
--       关系表（好友、群成员等）采用物理删除，避免与唯一键冲突导致无法重新建立关系
--    3. 时间列统一 DATETIME，由 MyBatis-Plus 自动填充，同时给出数据库默认值兜底
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS `im_db`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `im_db`;

-- 重复执行时按依赖倒序清理，保证脚本幂等
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS `im_announcement`;
DROP TABLE IF EXISTS `im_sensitive_word`;
DROP TABLE IF EXISTS `im_config`;
DROP TABLE IF EXISTS `im_report`;
DROP TABLE IF EXISTS `im_live_room`;
DROP TABLE IF EXISTS `im_gift_record`;
DROP TABLE IF EXISTS `im_wallet_transaction`;
DROP TABLE IF EXISTS `im_gift`;
DROP TABLE IF EXISTS `im_user_wallet`;
DROP TABLE IF EXISTS `im_live_video`;
DROP TABLE IF EXISTS `im_interview_event`;
DROP TABLE IF EXISTS `im_interview_message`;
DROP TABLE IF EXISTS `im_interview_session`;
DROP TABLE IF EXISTS `im_remote_audit_log`;
DROP TABLE IF EXISTS `im_remote_session`;
DROP TABLE IF EXISTS `im_remote_device`;
DROP TABLE IF EXISTS `im_file`;
DROP TABLE IF EXISTS `im_group_member`;
DROP TABLE IF EXISTS `im_group_join_request`;
DROP TABLE IF EXISTS `im_group_robot`;
DROP TABLE IF EXISTS `im_group`;
DROP TABLE IF EXISTS `im_message_reaction`;
DROP TABLE IF EXISTS `im_message_vote`;
DROP TABLE IF EXISTS `im_message_favorite`;
DROP TABLE IF EXISTS `im_message_essence`;
DROP TABLE IF EXISTS `im_message_delete`;
DROP TABLE IF EXISTS `im_message_read`;
DROP TABLE IF EXISTS `im_message`;
DROP TABLE IF EXISTS `im_conversation_member`;
DROP TABLE IF EXISTS `im_conversation`;
DROP TABLE IF EXISTS `im_friend_request`;
DROP TABLE IF EXISTS `im_friend`;
DROP TABLE IF EXISTS `im_role_permission`;
DROP TABLE IF EXISTS `im_user_role`;
DROP TABLE IF EXISTS `im_permission`;
DROP TABLE IF EXISTS `im_role`;
DROP TABLE IF EXISTS `im_user`;
SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================================
--  一、用户与权限（im-user）
-- =====================================================================================

CREATE TABLE `im_user`
(
    `id`              BIGINT       NOT NULL COMMENT '用户 ID（雪花）',
    `username`        VARCHAR(32)  NOT NULL COMMENT '登录账号，全局唯一',
    `password`        VARCHAR(255) NOT NULL COMMENT '密码密文（Argon2id / BCrypt）',
    `nickname`        VARCHAR(32)  NOT NULL COMMENT '昵称',
    `avatar`          VARCHAR(512)          DEFAULT NULL COMMENT '头像访问地址',
    `gender`          TINYINT      NOT NULL DEFAULT 0 COMMENT '性别：0 未知 1 男 2 女',
    `signature`       VARCHAR(255)          DEFAULT NULL COMMENT '个性签名',
    `phone`           VARCHAR(20)           DEFAULT NULL COMMENT '手机号，可空但非空时唯一',
    `email`           VARCHAR(64)           DEFAULT NULL COMMENT '邮箱',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 禁用',
    `disable_until`   DATETIME              DEFAULT NULL COMMENT '封禁到期时间：NULL 为永久（或未被封禁），仅 status=0 时有意义；到期由定时任务自动解封',
    `last_login_time` DATETIME              DEFAULT NULL COMMENT '最近登录时间',
    `last_login_ip`   VARCHAR(64)           DEFAULT NULL COMMENT '最近登录 IP',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_nickname` (`nickname`),
    KEY `idx_disable_until` (`disable_until`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

CREATE TABLE `im_role`
(
    `id`          BIGINT      NOT NULL COMMENT '角色 ID',
    `role_code`   VARCHAR(64) NOT NULL COMMENT '角色编码，如 admin / user',
    `role_name`   VARCHAR(64) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(255)         DEFAULT NULL COMMENT '角色说明',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色表';

CREATE TABLE `im_permission`
(
    `id`          BIGINT       NOT NULL COMMENT '权限 ID',
    `perm_code`   VARCHAR(128) NOT NULL COMMENT '权限标识，如 message:send',
    `perm_name`   VARCHAR(64)  NOT NULL COMMENT '权限名称',
    `module`      VARCHAR(32)           DEFAULT NULL COMMENT '所属模块：user/friend/message/group/file/system',
    `description` VARCHAR(255)          DEFAULT NULL COMMENT '权限说明',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_code` (`perm_code`),
    KEY `idx_module` (`module`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='权限表';

CREATE TABLE `im_user_role`
(
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户角色关联表';

CREATE TABLE `im_role_permission`
(
    `id`            BIGINT   NOT NULL COMMENT '主键',
    `role_id`       BIGINT   NOT NULL COMMENT '角色 ID',
    `permission_id` BIGINT   NOT NULL COMMENT '权限 ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_perm` (`role_id`, `permission_id`),
    KEY `idx_permission` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='角色权限关联表';

-- =====================================================================================
--  二、好友关系（im-friend）
-- =====================================================================================

CREATE TABLE `im_friend`
(
    `id`          BIGINT      NOT NULL COMMENT '主键',
    `user_id`     BIGINT      NOT NULL COMMENT '用户 ID',
    `friend_id`   BIGINT      NOT NULL COMMENT '好友用户 ID',
    `remark`      VARCHAR(32)          DEFAULT NULL COMMENT '我对好友的备注名',
    `group_name`  VARCHAR(32) NOT NULL DEFAULT '默认分组' COMMENT '好友分组名',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已拉黑（我拉黑了 friend_id）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '建立时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 好友关系双向各存一行，唯一键保证同一方向不会重复
    UNIQUE KEY `uk_user_friend` (`user_id`, `friend_id`),
    KEY `idx_friend` (`friend_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='好友关系表（双向各一行）';

CREATE TABLE `im_friend_request`
(
    `id`             BIGINT       NOT NULL COMMENT '主键',
    `from_user_id`   BIGINT       NOT NULL COMMENT '申请人 ID',
    `to_user_id`     BIGINT       NOT NULL COMMENT '被申请人 ID',
    `verify_message` VARCHAR(255)          DEFAULT NULL COMMENT '验证消息',
    `source`         VARCHAR(32)           DEFAULT NULL COMMENT '来源：search / qrcode / group',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 已同意 2 已拒绝 3 已过期',
    `handle_time`    DATETIME              DEFAULT NULL COMMENT '处理时间',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    -- 仅当 status=0 时该列为 1，其余情况为 NULL；MySQL 唯一索引允许多个 NULL，
    -- 因此可以既保证「同一对用户最多一条待处理申请」，又保留全部历史处理记录
    `pending_flag`   TINYINT GENERATED ALWAYS AS (IF(`status` = 0, 1, NULL)) VIRTUAL COMMENT '待处理标记（生成列）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_from_to_pending` (`from_user_id`, `to_user_id`, `pending_flag`),
    KEY `idx_to_status` (`to_user_id`, `status`, `create_time`),
    KEY `idx_from_status` (`from_user_id`, `status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='好友申请表';

-- =====================================================================================
--  三、会话（im-conversation）
-- =====================================================================================

CREATE TABLE `im_conversation`
(
    `id`               BIGINT      NOT NULL COMMENT '会话 ID',
    `type`             TINYINT     NOT NULL COMMENT '类型：1 单聊 2 群聊',
    `target_id`        BIGINT               DEFAULT NULL COMMENT '群聊为群 ID；单聊为 NULL（对方通过 im_conversation_member 解析）',
    `biz_key`          VARCHAR(64) NOT NULL COMMENT '业务唯一键：单聊 s:{小ID}:{大ID}，群聊 g:{groupId}',
    `last_msg_id`      BIGINT               DEFAULT NULL COMMENT '最后一条消息 ID',
    `last_msg_content` VARCHAR(512)         DEFAULT NULL COMMENT '最后一条消息摘要',
    `last_msg_type`    TINYINT              DEFAULT NULL COMMENT '最后一条消息类型',
    `last_msg_time`    DATETIME             DEFAULT NULL COMMENT '最后一条消息时间，会话列表排序依据',
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- 保证同一对用户 / 同一个群只会存在一个会话，是 getOrCreate 幂等的基础
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_last_msg_time` (`last_msg_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话表';

CREATE TABLE `im_conversation_member`
(
    `id`              BIGINT   NOT NULL COMMENT '主键',
    `conversation_id` BIGINT   NOT NULL COMMENT '会话 ID',
    `user_id`         BIGINT   NOT NULL COMMENT '成员用户 ID',
    `unread_count`    INT      NOT NULL DEFAULT 0 COMMENT '未读消息数',
    `last_ack_seq`    BIGINT   NOT NULL DEFAULT 0 COMMENT '已确认接收位点：离线拉取完成也推进，用于计算离线消息',
    `last_read_seq`   BIGINT   NOT NULL DEFAULT 0 COMMENT '已读位点：仅用户打开会话推进，群聊已读人数推算依据（不存逐条已读行）',
    `is_top`          TINYINT  NOT NULL DEFAULT 0 COMMENT '是否置顶：1 是 0 否',
    `top_time`        DATETIME          DEFAULT NULL COMMENT '置顶时间',
    `is_muted`        TINYINT  NOT NULL DEFAULT 0 COMMENT '是否免打扰：1 是 0 否',
    `is_deleted`      TINYINT  NOT NULL DEFAULT 0 COMMENT '本端是否隐藏该会话：1 隐藏 0 显示',
    `at_flag`         TINYINT  NOT NULL DEFAULT 0 COMMENT '是否有未读的 @ 提醒：1 有 0 无',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_user` (`conversation_id`, `user_id`),
    -- 会话列表主查询路径：按用户过滤未删除会话，再按置顶与最新消息排序
    KEY `idx_user_deleted` (`user_id`, `is_deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='会话成员表（用户维度的会话状态）';

-- =====================================================================================
--  四、消息（im-message）
-- =====================================================================================

CREATE TABLE `im_message`
(
    `id`              BIGINT      NOT NULL COMMENT '消息 ID（雪花）',
    `client_msg_id`   VARCHAR(64) NOT NULL COMMENT '客户端消息 ID，与发送者一起构成幂等键',
    `conversation_id` BIGINT      NOT NULL COMMENT '所属会话 ID',
    `from_user_id`    BIGINT      NOT NULL COMMENT '发送者 ID，0 表示系统通知',
    `msg_type`        TINYINT     NOT NULL COMMENT '类型：1 文本 2 图片 3 文件 4 语音 5 系统通知',
    `content`         TEXT COMMENT '消息内容：文本为正文，附件类为文件 ID',
    `extra`           JSON                 DEFAULT NULL COMMENT '扩展信息：附件元数据、@ 列表等',
    `seq`             BIGINT      NOT NULL COMMENT '会话内自增序列，游标分页与未读计算依据',
    `quote_msg_id`    BIGINT               DEFAULT NULL COMMENT '引用/回复的原消息 ID，转发时为空',
    `is_recalled`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已撤回：1 是 0 否',
    `recall_time`     DATETIME             DEFAULT NULL COMMENT '撤回时间',
    `send_time`       DATETIME    NOT NULL COMMENT '发送时间（服务端落库时间）',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 幂等核心约束：同一客户端消息 ID 只会落库一次，重复提交触发唯一键冲突
    UNIQUE KEY `uk_from_client` (`from_user_id`, `client_msg_id`),
    -- 历史消息游标分页：WHERE conversation_id=? AND seq<? ORDER BY seq DESC
    KEY `idx_conv_seq` (`conversation_id`, `seq`),
    KEY `idx_send_time` (`send_time`),
    KEY `idx_quote_msg` (`quote_msg_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息表（同时充当离线消息持久化队列）';

CREATE TABLE `im_message_read`
(
    `id`             BIGINT   NOT NULL COMMENT '主键',
    `message_id`     BIGINT   NOT NULL COMMENT '消息 ID',
    `user_id`        BIGINT   NOT NULL COMMENT '接收者 ID',
    `delivered_time` DATETIME          DEFAULT NULL COMMENT '送达时间',
    `read_time`      DATETIME          DEFAULT NULL COMMENT '已读时间',
    `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user` (`message_id`, `user_id`),
    KEY `idx_user_msg` (`user_id`, `message_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息送达与已读回执表（仅单聊写入；群聊用 im_conversation_member.last_read_seq 推算，避免逐条写爆表）';

CREATE TABLE `im_message_delete`
(
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `message_id`  BIGINT   NOT NULL COMMENT '消息 ID',
    `user_id`     BIGINT   NOT NULL COMMENT '执行删除的用户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '删除时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user` (`message_id`, `user_id`),
    KEY `idx_user_msg` (`user_id`, `message_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息单端删除表（仅对本人不可见，不影响其他成员）';

CREATE TABLE `im_message_reaction`
(
    `id`          BIGINT      NOT NULL COMMENT '主键（雪花）',
    `message_id`  BIGINT      NOT NULL COMMENT '被回应的消息 ID',
    `user_id`     BIGINT      NOT NULL COMMENT '回应者 ID',
    `emoji`       VARCHAR(32) NOT NULL COMMENT '表情字符，如 👍 ❤️ 😄',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回应时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user_emoji` (`message_id`, `user_id`, `emoji`),
    KEY `idx_message` (`message_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息表情回应表（一人对一条消息的同一表情只计一次，再点为取消）';

CREATE TABLE `im_message_vote`
(
    `id`           BIGINT   NOT NULL COMMENT '主键（雪花）',
    `message_id`   BIGINT   NOT NULL COMMENT '投票消息 ID',
    `user_id`      BIGINT   NOT NULL COMMENT '投票人 ID',
    `option_index` INT      NOT NULL COMMENT '所选选项下标（从 0 开始）',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '投票时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user_opt` (`message_id`, `user_id`, `option_index`),
    KEY `idx_message` (`message_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息投票选项记录表（一人对一条投票的每个选项至多一行；重新投票先清后写）';

CREATE TABLE `im_message_essence`
(
    `id`              BIGINT   NOT NULL COMMENT '主键（雪花）',
    `conversation_id` BIGINT   NOT NULL COMMENT '所属群会话 ID（精华是群维度沉淀，按会话聚合查询）',
    `message_id`      BIGINT   NOT NULL COMMENT '被标记为精华的消息 ID',
    `mark_user_id`    BIGINT   NOT NULL COMMENT '标记人 ID（群主/管理员）',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '标记时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message` (`message_id`),
    KEY `idx_conversation` (`conversation_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='群精华消息表（一条消息至多一行，取消即删；按会话查询群内全部精华）';

CREATE TABLE `im_message_favorite`
(
    `id`              BIGINT   NOT NULL COMMENT '主键（雪花）',
    `user_id`         BIGINT   NOT NULL COMMENT '收藏人 ID（收藏是用户维度私有数据，按本人聚合查询，只有本人可见）',
    `conversation_id` BIGINT   NOT NULL COMMENT '所属会话 ID（冗余列：供「只看本会话的收藏」过滤，以及清空聊天记录处级联清理）',
    `message_id`      BIGINT   NOT NULL COMMENT '被收藏的消息 ID',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间（列表按此倒序）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_message` (`user_id`, `message_id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息收藏表（一行 = 某人收藏了某条消息；同一条消息可被多人各自收藏，取消只删本人那行）';

-- =====================================================================================
--  五、群组（im-group）
-- =====================================================================================

CREATE TABLE `im_group`
(
    `id`          BIGINT      NOT NULL COMMENT '群 ID',
    `name`        VARCHAR(64) NOT NULL COMMENT '群名称',
    `avatar`      VARCHAR(512)         DEFAULT NULL COMMENT '群头像',
    `notice`      VARCHAR(512)         DEFAULT NULL COMMENT '群公告',
    `owner_id`    BIGINT      NOT NULL COMMENT '群主用户 ID',
    `max_member`  INT         NOT NULL DEFAULT 200 COMMENT '最大成员数',
    `mute_all`    TINYINT     NOT NULL DEFAULT 0 COMMENT '全员禁言：1 是 0 否',
    `invite_code` VARCHAR(32)          DEFAULT NULL COMMENT '群邀请码（邀请链接 / 二维码使用，懒生成）',
    `join_approval` TINYINT   NOT NULL DEFAULT 0 COMMENT '入群是否需要审批：1 需要（邀请链接与二维码入口提交后进入待审批），0 不需要（扫码链接直接入群）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 已解散',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_owner` (`owner_id`),
    -- 邀请码全局唯一，MySQL 的 UNIQUE 允许多个 NULL，未生成的群不占位
    UNIQUE KEY `uk_invite_code` (`invite_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='群组表';

CREATE TABLE `im_group_member`
(
    `id`              BIGINT      NOT NULL COMMENT '主键',
    `group_id`        BIGINT      NOT NULL COMMENT '群 ID',
    `user_id`         BIGINT      NOT NULL COMMENT '成员用户 ID',
    `role`            TINYINT     NOT NULL DEFAULT 3 COMMENT '群内角色：1 群主 2 管理员 3 普通成员',
    `nickname_in_group` VARCHAR(32)        DEFAULT NULL COMMENT '群内昵称',
    `is_muted`        TINYINT     NOT NULL DEFAULT 0 COMMENT '是否被单独禁言：1 是 0 否',
    `mute_end_time`   DATETIME             DEFAULT NULL COMMENT '禁言结束时间，为空表示无限期',
    `join_time`       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入群时间',
    `status`          TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 在群 0 已退群/被移除',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 一行一成员，退群置 status=0 而非物理删除，重新入群复用同一行以保留历史
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='群成员表';

CREATE TABLE `im_group_join_request`
(
    `id`              BIGINT   NOT NULL COMMENT '主键（雪花）',
    `group_id`        BIGINT   NOT NULL COMMENT '目标群 ID',
    `user_id`         BIGINT   NOT NULL COMMENT '申请人用户 ID',
    `status`          TINYINT  NOT NULL DEFAULT 0 COMMENT '状态：0 待审批 1 已通过 2 已拒绝',
    `message`         VARCHAR(255)      DEFAULT NULL COMMENT '申请留言',
    `source`          VARCHAR(32)       DEFAULT NULL COMMENT '申请来源：link / qrcode',
    `handle_user_id`  BIGINT            DEFAULT NULL COMMENT '处理人用户 ID（群主/管理员）',
    `handle_time`     DATETIME          DEFAULT NULL COMMENT '处理时间',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 同一人同一群只留一行：拒绝后重新提交会复用行并把状态拉回 0，不堆积历史记录
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),
    KEY `idx_group_status` (`group_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='入群申请表（仅当群开启入群审批时写入）';

CREATE TABLE `im_group_robot`
(
    `id`             BIGINT      NOT NULL COMMENT '机器人 ID（雪花）',
    `group_id`       BIGINT      NOT NULL COMMENT '所属群 ID',
    `name`           VARCHAR(64) NOT NULL COMMENT '机器人展示名（发送时快照进消息 extra）',
    `avatar`         VARCHAR(512)         DEFAULT NULL COMMENT '机器人头像',
    `description`    VARCHAR(255)         DEFAULT NULL COMMENT '用途说明',
    `webhook_token`  VARCHAR(64) NOT NULL COMMENT 'Webhook 密钥（回调 URL 凭证，全局唯一）',
    `creator_id`     BIGINT      NOT NULL COMMENT '创建者用户 ID（群主/管理员）',
    `status`         TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- token 即访问凭证，全局唯一；删除走逻辑删除，软删后不再命中但保留历史可审计
    UNIQUE KEY `uk_webhook_token` (`webhook_token`),
    KEY `idx_group` (`group_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='群机器人表（外部系统 Webhook 入站通道）';

-- =====================================================================================
--  六、文件（im-file）
-- =====================================================================================

CREATE TABLE `im_file`
(
    `id`            BIGINT       NOT NULL COMMENT '文件 ID',
    `uploader_id`   BIGINT       NOT NULL COMMENT '上传者用户 ID',
    `biz_type`      VARCHAR(32)  NOT NULL COMMENT '业务类型：avatar / chat_image / chat_file / chat_voice / video / video_cover',
    `storage_type`  VARCHAR(16)  NOT NULL DEFAULT 'local' COMMENT '存储实现：local / minio',
    `bucket`        VARCHAR(64)           DEFAULT NULL COMMENT '存储桶，本地实现为空',
    `object_key`    VARCHAR(512) NOT NULL COMMENT '对象键 / 相对路径',
    `original_name` VARCHAR(255)          DEFAULT NULL COMMENT '原始文件名',
    `url`           VARCHAR(1024)         DEFAULT NULL COMMENT '访问地址（受控下载地址或预签名直链）',
    `size`          BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `content_type`  VARCHAR(128)          DEFAULT NULL COMMENT 'MIME 类型',
    `ext`           VARCHAR(32)           DEFAULT NULL COMMENT '扩展名，不含点',
    `md5`           VARCHAR(64)           DEFAULT NULL COMMENT '文件 MD5，用于秒传',
    `duration`      INT                   DEFAULT NULL COMMENT '语音时长（秒）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_md5` (`md5`),
    KEY `idx_uploader` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='文件元数据表';

-- =====================================================================================
--  七、远程控制（im-remote）
--
--  三张表均继承 AuditEntity（只有 create_time / update_time，无逻辑删除列）：
--  设备与会话采用物理更新而非删除，审计日志只增不改不删。
--  识别码模式的设备不归属任何账号，user_id 固定为 0，只凭 access_code 被路由。
--  注意：控制端一次性 ticket、会话级 aesKey、路由用的 device 都是实体里的
--  @TableField(exist=false) 内存字段，故意不落库——密钥入库会让审计库变成能解密历史流量的地方。
-- =====================================================================================

CREATE TABLE `im_remote_device`
(
    `id`               BIGINT      NOT NULL COMMENT '主键（雪花）',
    `user_id`          BIGINT      NOT NULL COMMENT '设备归属用户 ID',
    `device_id`        VARCHAR(64) NOT NULL COMMENT 'Agent 安装时生成的设备唯一标识，重装系统前不变',
    `device_name`      VARCHAR(128)         DEFAULT NULL COMMENT '设备名（计算机名），列表展示用',
    `os`               VARCHAR(128)         DEFAULT NULL COMMENT '操作系统描述（os.name + os.arch）',
    `access_code`      VARCHAR(16)          DEFAULT NULL COMMENT '识别码（ToDesk 式接入码，Agent 本地设置上报）；非空时任意登录用户可凭码发起邀请（仍需弹窗同意）',
    `status`           TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0 离线 1 空闲 2 忙 3 拒绝接入',
    `last_online_time` DATETIME             DEFAULT NULL COMMENT '最近一次心跳在线时间',
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 一台机器一行：同一 (user_id, device_id) 重新上线只更新这行，不会越积越多
    UNIQUE KEY `uk_user_device` (`user_id`, `device_id`),
    KEY `idx_device` (`device_id`),
    KEY `idx_access_code` (`access_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='被控端设备表';

CREATE TABLE `im_remote_session`
(
    `id`               BIGINT      NOT NULL COMMENT '会话 ID（雪花）',
    `invitee_user_id`  BIGINT      NOT NULL COMMENT '被控方（设备归属人）用户 ID',
    `device_id`        VARCHAR(64) NOT NULL COMMENT '被控设备标识',
    `inviter_user_id`  BIGINT      NOT NULL COMMENT '控制方用户 ID',
    `permission`       VARCHAR(16) NOT NULL DEFAULT 'operate' COMMENT '权限：readonly 只读 / operate 可操作，被控端授权时可降档',
    `status`           VARCHAR(16) NOT NULL DEFAULT 'inviting' COMMENT '状态：inviting 待授权 / active 进行中 / rejected 被拒绝 / ended 已结束',
    `start_time`       DATETIME             DEFAULT NULL COMMENT '会话开始（中继建立）时间',
    `end_time`         DATETIME             DEFAULT NULL COMMENT '会话结束时间',
    `end_reason`       VARCHAR(32)          DEFAULT NULL COMMENT '结束原因：inviter-end / invitee-end / timeout / rejected / offline / control-offline',
    `bytes`            BIGINT      NOT NULL DEFAULT 0 COMMENT '中继转发总字节数（双向合计），内存累加收尾落库',
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '邀请创建时间',
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 会话历史按控制方 / 被控方两个视角查询，各建一条联合索引覆盖「我的会话列表」倒序分页
    KEY `idx_inviter_time` (`inviter_user_id`, `create_time`),
    KEY `idx_invitee_time` (`invitee_user_id`, `create_time`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='远程会话表';

CREATE TABLE `im_remote_audit_log`
(
    `id`          BIGINT   NOT NULL COMMENT '主键（雪花）',
    `session_id`  BIGINT   NOT NULL COMMENT '所属远程会话 ID',
    `action`      VARCHAR(32) NOT NULL COMMENT '动作标识：session-start / session-end / file-rm / ps-kill / exec / power / input-blocked ...',
    `detail`      VARCHAR(1000)   DEFAULT NULL COMMENT '动作详情（路径、命令、拦截原因等），写入前截断到 1000 字符',
    `actor`       VARCHAR(16)     DEFAULT NULL COMMENT '触发方：inviter 控制端 / invitee 被控端 Agent / system 服务端流程事件；加列前的历史记录为 NULL',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_time` (`session_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='远程会话审计日志（只增不改不删）';

-- =====================================================================================
--  八、RAG 智能面试官（im-ai）
--
--  面试原本是全无状态的：对话历史由前端 Interview.vue 持有，刷新即丢，事后无法复盘，
--  也没地方记「候选人中途切了几次屏」。这三张表把它变成可审计的业务：
--    会话元信息 + 逐轮问答 + 操作与违规事件流水。
--
--  问答单独成表而不是往会话表塞一个 JSON 大字段：审计要能回答「他是在答第几题时
--  切屏的」，整段 JSON 既撑爆行又只能全量读回。
--  会话表上的几个计数是事件表的冗余汇总：列表页要一次把「切屏 3 次」显示出来，
--  不能为 20 行列表跑 20 次 GROUP BY。冗余可能与流水不平（上报丢失、并发累加），
--  所以审计定性一律以事件表为准，计数只用于展示与阈值判断。
--
--  问答表与事件表继承 AuditEntity（无 deleted）：流水只增不删；会话表是主数据，
--  用 BaseEntity 逻辑删除，便于清理演示数据时不连带抹掉审计历史。
--
--  幂等键为什么是 (session_id, turn_no, role) 而不是 (session_id, seq)：
--  seq 由服务端 maxSeq+1 现算，重传一次就会得到一个新 seq，拿它当幂等键等于没加；
--  而且并发上报时两个请求可能算出同一个 seq，若给 seq 建唯一键，撞键的那一行会被
--  当成「已存在」静默丢弃——顺序略微受影响远比丢一条问答好。turn_no 是前端报的
--  「这是第几题」，同一题的提问行与作答行各自唯一，重试才真能被拦住。
-- =====================================================================================

CREATE TABLE `im_interview_session`
(
    `id`                 BIGINT      NOT NULL COMMENT '面试会话 ID（雪花）',
    `user_id`            BIGINT      NOT NULL COMMENT '候选人用户 ID',
    `title`              VARCHAR(64) NOT NULL DEFAULT '后端 Java 全栈面试' COMMENT '面试题目/岗位名称',
    `status`             TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0 进行中 1 正常结束 2 违规达阈值强制结束 3 未完成（刷新/关闭页面/中途离开）',
    `start_time`         DATETIME             DEFAULT NULL COMMENT '开始时间（服务端收到开始请求的时刻）',
    `end_time`           DATETIME             DEFAULT NULL COMMENT '结束时间',
    `duration_seconds`   INT         NOT NULL DEFAULT 0 COMMENT '面试时长（秒）：服务端拿 end-start 算，不信客户端自报的计时',
    `turn_count`         INT         NOT NULL DEFAULT 0 COMMENT '问答轮数（面试官提问数，等同于候选人作答数）',
    `violation_count`    INT         NOT NULL DEFAULT 0 COMMENT '违规总次数（下行四类合计 + 未单独列类的违规事件）',
    `blur_count`         INT         NOT NULL DEFAULT 0 COMMENT '切屏次数：标签页/窗口被最小化或切到其它应用（window.blur 与 visibilitychange 只计一次）',
    `copy_count`         INT         NOT NULL DEFAULT 0 COMMENT '复制次数：面试中复制题目去搜答案',
    `paste_count`        INT         NOT NULL DEFAULT 0 COMMENT '粘贴次数：最典型的作弊信号——答案不是当场敲的',
    `fullscreen_exit_count` INT      NOT NULL DEFAULT 0 COMMENT '退出全屏次数（仅当面试处于全屏监考模式时才会产生）',
    `end_reason`         VARCHAR(32)          DEFAULT NULL COMMENT '结束原因：user-end 自行结束 / violation-limit 违规达上限 / reload / close / timeout',
    `screen`             VARCHAR(32)          DEFAULT NULL COMMENT '客户端屏幕分辨率（宽x高）；排「切屏误报」时用，分屏/双显会拉高误报率',
    `client_info`        VARCHAR(255)         DEFAULT NULL COMMENT '浏览器 UA 摘要（只留引擎与版本，不落完整 UA）',
    `create_time`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- 「我的面试记录」倒序分页：WHERE user_id=? ORDER BY create_time DESC
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- 进行中的会话需要被巡检收尾（刷新/断电会留下永久 status=0 的行）
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 面试会话表';

CREATE TABLE `im_interview_message`
(
    `id`          BIGINT      NOT NULL COMMENT '主键（雪花）',
    `session_id`  BIGINT      NOT NULL COMMENT '所属面试会话 ID',
    `seq`         INT         NOT NULL COMMENT '会话内序号，从 1 递增；排序以此列为准，不用 create_time（同毫秒会并列）',
    `turn_no`     INT         NOT NULL DEFAULT 0 COMMENT '属于第几轮（面试官出到第几题为第几轮）；与 role 共同构成幂等键',
    `role`        VARCHAR(16) NOT NULL COMMENT '角色：assistant 面试官提问 / user 候选人作答',
    `content`     TEXT        NOT NULL COMMENT '正文，写入前按 im.ai.max-message-chars 截断',
    `char_count`  INT         NOT NULL DEFAULT 0 COMMENT '正文字符数：审计时看作答长度分布，不必把正文读回来',
    `elapsed_ms`  BIGINT      NOT NULL DEFAULT 0 COMMENT '候选人从收到本题到提交的耗时（毫秒）；role=assistant 固定为 0',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 幂等：同一会话里「某一轮 + 某一角色」只能有一行，前端重试（网络抖动后再发一次）不会多出一条问答
    UNIQUE KEY `uk_session_turn` (`session_id`, `turn_no`, `role`),
    KEY `idx_session` (`session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 面试问答记录表（逐轮留存，事后复盘用）';

CREATE TABLE `im_interview_event`
(
    `id`          BIGINT      NOT NULL COMMENT '主键（雪花）',
    `session_id`  BIGINT      NOT NULL COMMENT '所属面试会话 ID',
    `user_id`     BIGINT      NOT NULL COMMENT '候选人 ID（冗余一份：审计常按人查，不必先 join 会话表）',
    `event_type`  VARCHAR(32) NOT NULL COMMENT '事件类型：visibility-hidden 切标签页/最小化 / blur 窗口失焦 / copy / paste / cut / contextmenu / fullscreen-exit / session-start / turn-submit / session-end',
    `violation`   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否计入违规：1 计入会话表计数并参与阈值判断，0 仅记录现象（如 session-start）',
    `turn_no`     INT         NOT NULL DEFAULT 0 COMMENT '当时进行到第几轮，0 表示尚未开始问答；回答「他在答哪题时切屏」',
    `detail`      VARCHAR(500) DEFAULT NULL COMMENT '补充信息（离开多久、粘贴多少字符、从哪个元素粘的等），写入前截断到 500 字符',
    `event_time`  DATETIME(3) NOT NULL COMMENT '事件发生时刻（客户端上报的本地时间，带毫秒）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端落库时间：与 event_time 的差就是上报延迟或客户端时钟偏差',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 审计详情页的主查询：一个会话的全部事件按时间排开
    KEY `idx_session_time` (`session_id`, `event_time`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- 只看违规：把非违规的流程事件（start/submit/end）排除在索引之外
    KEY `idx_session_violation` (`session_id`, `violation`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 面试操作与违规事件流水（只增不改不删）';

-- =====================================================================================
--  十、直播（im-live）
-- =====================================================================================

CREATE TABLE `im_live_room`
(
    `id`            BIGINT       NOT NULL COMMENT '房间 ID（一行 = 一场直播，非「频道」）',
    `anchor_id`     BIGINT       NOT NULL COMMENT '主播用户 ID',
    `title`         VARCHAR(60)  NOT NULL COMMENT '房间标题',
    `cover`         VARCHAR(512)          DEFAULT NULL COMMENT '封面图访问地址，可空（前端退化成占位图）',
    `notice`        VARCHAR(512)          DEFAULT NULL COMMENT '房间公告，进房时随弹幕通道下发一次',
    `source_type`   VARCHAR(16)           DEFAULT 'screen' COMMENT '推流源：screen 屏幕分享 / camera 摄像头，仅前端展示图标用',
    `resolution`    VARCHAR(16)           DEFAULT NULL COMMENT '分辨率档位标签（如 720p/1080p），推流端声明，服务端只存不校验',
    `bitrate_kbps`  INT                   DEFAULT NULL COMMENT '视频码率（kbps），推流端声明，用于列表展示与带宽估算',
    `stream_key`    VARCHAR(64)           DEFAULT NULL COMMENT '本场推流密钥：既是 Nginx 目录名也是能力凭证，随机不可猜，关播后置空作废本场地址',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 直播中 2 已结束 3 已封禁',
    `peak_online`   INT          NOT NULL DEFAULT 0 COMMENT '峰值在线人数，关播时定格（直播途中的实时值在 Redis）',
    `viewer_total`  INT          NOT NULL DEFAULT 0 COMMENT '累计观看人次（连接建立次数，同一人反复进出重复计），关播时定格',
    `start_time`    DATETIME              DEFAULT NULL COMMENT '开播时间',
    `end_time`      DATETIME              DEFAULT NULL COMMENT '关播时间，直播中为空',
    `end_reason`    VARCHAR(16)           DEFAULT NULL COMMENT '关播原因：stop 主播主动 / timeout 心跳超时 / ban 管理封禁，为空表示仍在直播',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- 「我的直播」面板与「该主播是否已有进行中房间」都按 anchor_id + status 查
    KEY `idx_anchor_status` (`anchor_id`, `status`),
    -- 房间列表主查询：直播中恒排最前（status 升序），其次按开播时间倒序
    KEY `idx_status_start` (`status`, `start_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播房间表（一行一场直播）';

-- 直播页「视频」板块的短视频信息流：一行一个视频。视频与封面本体存 im_file（bizType=video / video_cover，
-- 对所有登录用户公开），本表只存元数据与受控访问地址，供大厅分页展示与播放。
CREATE TABLE `im_live_video`
(
    `id`            BIGINT        NOT NULL COMMENT '视频 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '上传用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '视频标题',
    `file_id`       BIGINT        NOT NULL COMMENT '视频文件 ID（im_file.id，bizType=video）',
    `file_url`      VARCHAR(1024) NOT NULL COMMENT '视频受控访问地址（/api/file/download/{fileId}，前端经 mediaUrl 取 blob 播放）',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面图文件 ID（im_file.id，bizType=video_cover），可空',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面图受控访问地址，可空（前端退化为占位图）',
    `duration`      INT           NOT NULL DEFAULT 0 COMMENT '时长（秒），上传前由客户端读取元数据后上报，缺省 0',
    `width`         INT                    DEFAULT NULL COMMENT '视频宽（像素），客户端上报',
    `height`        INT                    DEFAULT NULL COMMENT '视频高（像素），客户端上报',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `ext`           VARCHAR(32)            DEFAULT NULL COMMENT '扩展名，不含点',
    `content_type`  VARCHAR(128)           DEFAULT NULL COMMENT 'MIME 类型',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '播放次数（每次点开 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- 信息流主查询：按状态过滤后按创建时间倒序分页
    KEY `idx_status_create` (`status`, `create_time`),
    -- 「我上传的视频」按上传者查
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页视频板块（短视频信息流）';

-- =====================================================================================
--  十一、钱包与礼物（im-user 持有钱包、im-live 持有礼物）
--  虚拟积分/币体系：不涉真实货币，充值走模拟支付桩（预留 PaymentSpi），每日签到赠送。
--  连麦本身是瞬时态，走 Redis 不建表。
-- =====================================================================================

-- 用户钱包：一行一个用户，主键即 user_id（与 im_user.id 同值，非雪花）。
-- balance 非负，扣款走条件 UPDATE（balance >= 扣款额）在数据库层兜住并发超扣，应用层再校验一次。
CREATE TABLE `im_user_wallet`
(
    `user_id`         BIGINT   NOT NULL COMMENT '用户 ID（与 im_user.id 同值）',
    `balance`         BIGINT   NOT NULL DEFAULT 0 COMMENT '积分/币余额（非负，扣款用条件 UPDATE balance>=? 兜底防超扣）',
    `total_recharged` BIGINT   NOT NULL DEFAULT 0 COMMENT '累计充值到账',
    `total_consumed`  BIGINT   NOT NULL DEFAULT 0 COMMENT '累计消费（送礼等）',
    `sign_last_date`  DATE              DEFAULT NULL COMMENT '最近签到自然日，按天去重',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户钱包（虚拟积分/币，一行一个用户）';

-- 礼物目录：运营可增删改，前端礼簿按 status=1 且 sort 升序渲染。
CREATE TABLE `im_gift`
(
    `id`          BIGINT      NOT NULL COMMENT '礼物 ID',
    `name`        VARCHAR(32) NOT NULL COMMENT '礼物名称',
    `icon`        VARCHAR(512)         DEFAULT NULL COMMENT '图标（表情符号或图片地址）',
    `price`       INT         NOT NULL DEFAULT 0 COMMENT '单价（积分）',
    `kind`        VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '类型：normal 普通 / effect 特效',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序，小的在前',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 上架 0 下架',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='礼物目录';

-- 钱包账本：逐条流水（只增，供对账与明细页），流水表继承 AuditEntity 不带 deleted。
-- 转账/红包不建新表：资金变动全落这张表，只是 type 多了 transfer/packet/refund 三个取值。
-- 下面两处 COMMENT 只影响全新建库（纯注释）：存量库的列定义与索引一字未改，不需要跑 ALTER。
CREATE TABLE `im_wallet_transaction`
(
    `id`            BIGINT      NOT NULL COMMENT '流水 ID（雪花）',
    `user_id`       BIGINT      NOT NULL COMMENT '钱包归属用户 ID',
    `type`          VARCHAR(16) NOT NULL COMMENT '类型：recharge 充值 / gift 送礼 / checkin 签到 / adjust 后台调整 / transfer 转账 / packet 红包 / refund 红包过期退回',
    `amount`        BIGINT      NOT NULL COMMENT '变动额（有符号，支出为负）',
    `balance_after` BIGINT      NOT NULL COMMENT '交易后余额快照',
    `ref_id`        BIGINT               DEFAULT NULL COMMENT '关联业务 ID（语义随 type 变：送礼存记录/房间 ID，transfer 存对方用户 ID，packet/refund 存红包 ID）',
    `remark`        VARCHAR(128)         DEFAULT NULL COMMENT '备注',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='钱包账本流水（只增不改）';

-- 送礼流水：一行一笔送礼，冗余礼物名/图标/成交单价，礼物后续下架或改价也不影响历史展示与对账。
CREATE TABLE `im_gift_record`
(
    `id`          BIGINT      NOT NULL COMMENT '送礼记录 ID（雪花）',
    `sender_id`   BIGINT      NOT NULL COMMENT '送礼人用户 ID',
    `receiver_id` BIGINT      NOT NULL COMMENT '收礼主播用户 ID',
    `room_id`     BIGINT      NOT NULL COMMENT '直播间 ID',
    `gift_id`     BIGINT      NOT NULL COMMENT '礼物 ID',
    `gift_name`   VARCHAR(32) NOT NULL COMMENT '冗余：送礼时的礼物名',
    `gift_icon`   VARCHAR(512)         DEFAULT NULL COMMENT '冗余：送礼时的礼物图标',
    `count`       INT         NOT NULL DEFAULT 1 COMMENT '数量',
    `unit_price`  INT         NOT NULL COMMENT '冗余：成交单价',
    `total_price` BIGINT      NOT NULL COMMENT '总价 = unit_price * count',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_room_time` (`room_id`, `create_time`),
    KEY `idx_receiver_time` (`receiver_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='送礼流水（账本性质，只增不改）';

-- =====================================================================================
--  十二、举报工单（im-admin）
-- =====================================================================================

-- 举报工单：用户举报「用户 / 消息 / 直播间 / 群」，管理后台的待处理队列就落在这一张表上。
-- 为什么必须建表（而不是像房管/PK/红包那样进 Redis）：举报是**用来追责的持久记录**——
-- 举报人三天后回来看处理结果、运营事后复盘“这个主播被投诉了几次”，都不能接受
-- “Redis Flush 就没”；而「按状态取待处理」与「按举报人查自己的记录」两种访问都要索引。
-- 不设唯一键：同一对象可被多人各自举报（也允许同一人隔一段时间后重复举报，靠 Redis 做 24h 去重）。
-- 不启 deleted：处置即改 status，历史全部保留。
CREATE TABLE `im_report`
(
    `id`               BIGINT       NOT NULL COMMENT '举报 ID（雪花）',
    `reporter_id`      BIGINT       NOT NULL COMMENT '举报人用户 ID',
    `target_type`      VARCHAR(16)  NOT NULL COMMENT '被举报对象类型：user / message / room / group',
    `target_id`        BIGINT       NOT NULL COMMENT '被举报对象 ID（按 target_type 解释）',
    `reported_user_id` BIGINT                DEFAULT NULL COMMENT '被举报人 ID：受理时按对象反查后落库，供「同一个人被举报多少次」聚合与重复举报处置',
    `reason_code`      VARCHAR(32)  NOT NULL COMMENT '举报理由码（枚举，口径在服务端定，前端展示走同一份字典，不让客户端自定分类）',
    `reason_text`      VARCHAR(500)          DEFAULT NULL COMMENT '补充描述，可空；写入前按敏感词清洗与长度截断',
    `evidence`         JSON                  DEFAULT NULL COMMENT '举报当时抓的证据快照（标题/内容摘要/所属会话/开播信息等）：被举报对象事后被删也不影响处置与追溯',
    `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 已受理 2 已驳回 3 已处置',
    `disposition`      VARCHAR(32)           DEFAULT NULL COMMENT '处置动作：none / warn / ban_user / unban_user / recall_message / close_room，与 status 3 同时写入',
    `handle_remark`    VARCHAR(500)          DEFAULT NULL COMMENT '处置说明：回给举报人并留档，不写就是驳回后没人知道为何',
    `handler_id`       BIGINT                DEFAULT NULL COMMENT '处理人（管理员）用户 ID',
    `handle_time`      DATETIME              DEFAULT NULL COMMENT '处理时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 后台队列主查询：按状态取待处理并按时间倒序
    KEY `idx_status_time` (`status`, `create_time`),
    -- 举报人查自己那几条的进展
    KEY `idx_reporter_time` (`reporter_id`, `create_time`),
    -- 反查「这个对象被举报过几次」，受理时的重复判定靠它
    KEY `idx_target` (`target_type`, `target_id`),
    KEY `idx_reported_user` (`reported_user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='举报工单（管理后台的处理队列，只改状态不删行）';

-- =====================================================================================
--  十三、系统配置与内容过滤（im-admin）
-- =====================================================================================

-- 后台运行期配置的「覆盖项」表：整表最多 27 行（27 是 AdminConfigKey 枚举里的项数），
-- 而且**只存被后台改过的那一项**——没改过的配置在库里根本没有行，运行态就是
-- application.yml 与代码默认值。为什么不在库里存全量、也不存标题与说明：那些元数据
-- 一旦两边各存一份，迟早漂移成「界面上写的和代码里做的不一样」，所以目录写死在枚举里。
-- 这张表不是配置中心，是一道白名单：只有 AdminConfigKey 点名的 key 能写，其余一律回
-- 14010，为的是不让 spring.datasource.password 与 sa-token.jwt-secret-key 变成
-- 「一个后台账号一句请求」就能改掉的东西。
-- 不启 deleted：「恢复默认」是把这一行**物理删掉**，软删的行会一直占着 uk_config_key，
-- 同一个 key 从此再也写不进来，而那正是本表唯一的存在意义。
CREATE TABLE `im_config`
(
    `id`           BIGINT       NOT NULL COMMENT '配置 ID（雪花）',
    `config_key`   VARCHAR(128) NOT NULL COMMENT '配置键，与 AdminConfigKey 的 key 一一对应（如 message.recall-limit-seconds）；不在白名单里的键服务端直接拒写',
    `config_value` VARCHAR(255) NOT NULL COMMENT '配置值，一律以字符串存（"true" / "120"），按枚举声明的类型解释：布尔只认 true/false 两个字面量，数字必须落在枚举声明的 [min,max] 内',
    `update_by`    BIGINT                DEFAULT NULL COMMENT '最后修改人（后台账号 ID）：这一行是谁点出来的，日志之外只有这里查得到',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次被后台覆盖的时刻',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次覆盖的时刻（多实例轮询比对的签名里有它）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='运行期配置覆盖项（只存后台改过的项，恢复默认即物理删行，总行数不超过白名单项数）';

-- 后台在线维护的敏感词：classpath 那份静态词库（im-common 的 wordlists/sensitive_words.txt，
-- 生效 19643 条）之上的**增量**，不是替代。装载时两者求并集重建字典树，所以删掉这里的一行
-- 只撤掉后台加的那一个词，文件里的词一个都不会少；反过来想摘掉文件里的某个词（误伤）
-- 这个页面做不到，那要改词库资源重新打包——这条边界写在实体注释与开发指南里，
-- 而不是等到有人删了三行发现「还在遮」再来问。
-- word 存的是**折叠后**的形式（全角转半角、统一小写），与字典树里的键一致：这样 uk_word
-- 才真挡得住重复——ＵＲ 与 ur 在库里是同一行，而不是两行看着不同、匹配时完全等价的记录。
-- 不启 deleted：物理删除才立刻释放 uk_word，否则删过的词永远加不回来，界面上还会
-- 显示成「这个词已经存在」。
-- 除了唯一键不加别的索引：定时任务每 30 秒一次只读 COUNT(*) 与 MAX(update_time) 拼签名，
-- 后台列表按主键倒序翻几页，两个都用不上第二份索引，而这表的行数是「加过多少词」的量级。
CREATE TABLE `im_sensitive_word`
(
    `id`          BIGINT      NOT NULL COMMENT '词条 ID（雪花）',
    `word`        VARCHAR(64) NOT NULL COMMENT '词条：已按过滤器口径折叠，只含汉字与小写字母数字、至少 2 个字符（校验在 im-common 的 SensitiveWordFilter 里，不在这里也不在前端重复一份）',
    `remark`      VARCHAR(200)         DEFAULT NULL COMMENT '加词原因，可空。词表没有审计表兜底，这一列是唯一能问出「为什么加它」的地方',
    `create_by`   BIGINT               DEFAULT NULL COMMENT '创建人（后台账号 ID）：词只能加和删，没有改，所以只有创建人',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（签名比对会看它）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_word` (`word`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='在线敏感词（静态文件词库之上的增量，物理删除才释放唯一键）';

-- =====================================================================================
--  十四、全服公告（im-admin）
-- =====================================================================================

-- 公告是后台第一次「主动对所有在线连接喊话」：发布那一刻经 PushSpi.broadcast() 推一帧
-- notify（data.action=announce），报文不新增 WsMessageType——notify 下面早已是按 action
-- 分流的形状（connected / report-result / admin-warn / 群事件）。
-- 为什么必须建表（而不是像红包、PK 那样只进 Redis）：推送只覆盖发布那一刻在线的连接，
-- 错过的人靠这张表补拉（用户端 GET /api/announcement/active）。Redis Flush 就没了的话，
-- 「公告」就退化成「正好在线的人才知道的事」。
-- 状态机只有三条边：草稿 0 → 已发布 1 → 已撤回 2。已发布不可编辑（改一条已广播出去的正文，
-- 等于让在线的人看旧文本、之后登录的人看新文本），物理删除只允许对草稿，
-- 已发布与已撤回的行留下——它们是「什么时候说过什么话」的台账。
-- expire_time 只影响「补拉还能不能拉到」，到期不会自动把状态改成已撤回：
-- 到期与状态是两件事，库里那行仍然是已发布的历史事实。
-- 只给 idx_status_publish 一条索引：用户端每次登录都要跑这个查询，而后台那条列表带
-- LIKE '%关键词%'（用不上索引）、行数就是「运营发过多少条公告」的量级，全扫不亏。
CREATE TABLE `im_announcement`
(
    `id`           BIGINT        NOT NULL COMMENT '公告 ID（雪花）；用户端按它做本地已读去重，所以同一台设备不会重复弹同一条',
    `title`        VARCHAR(100)  NOT NULL COMMENT '标题：通知的标题位直接用它的原文，不让前端拿正文截断去猜。写入前按敏感词遮罩并截断',
    `content`      VARCHAR(2000) NOT NULL COMMENT '正文，与后端 @Size(max=2000) 同宽。公告也要过自己那套词库，否则「把敏感词写进公告」就成了绕过过滤器的正门',
    `tone`         VARCHAR(16)   NOT NULL DEFAULT 'info' COMMENT '通知样式：info / success / warning / error，与举报回执同一口径；收在服务端而不让前端猜，改一句中文文案不该把通知颜色改掉',
    `status`       TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0 草稿（只在后台可见） 1 已发布 2 已撤回（已弹出去的通知收不回来）',
    `publish_time` DATETIME               DEFAULT NULL COMMENT '发布时刻，草稿为空。用户端按它倒序取「当前生效的最新几条」',
    `expire_time`  DATETIME               DEFAULT NULL COMMENT '失效时刻，空为不失效；只影响补拉，不会自动改 status',
    `create_by`    BIGINT                 DEFAULT NULL COMMENT '创建人（后台账号 ID）',
    `update_by`    BIGINT                 DEFAULT NULL COMMENT '最后修改人（后台账号 ID）：发布与撤回都算修改，所以这一列总是比 create_by 新',
    `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（草稿时刻）',
    `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status_publish` (`status`, `publish_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='全服公告（后台唯一主动广播通道，状态 0→1→2；无已读回执表，去重记在客户端本地）';
