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

-- ★ 强制会话字符集为 utf8mb4：客户端默认字符集不对（latin1 / GBK）时，表注释、列注释里的
--   中文会被按错误字符集解释、存成双重编码乱码。这一行让 source 本文件时会话字符集恒为
--   utf8mb4，与客户端默认值解耦。注意它救不了 PowerShell 管道（Get-Content | mysql 在 shell
--   层就把中文刷成 ?），那种仍须用 -e "source" 或 scripts/import-sql.sh。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `im_db`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `im_db`;

-- 重复执行时按依赖倒序清理，保证脚本幂等
-- ★ 加表必须回头补这里：本块的 DROP 与后面的 CREATE 条数要相等，少一条就会在重跑时
--   撞「表已存在」中断，库变成半新半旧（先例：im_live_room 漏在其中一次）。
--   开头这两张是 V1.0.9 登录设备与流水，最后建、最先删；
--   紧接着的十六张是 V1.0.7 交友域。
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS `im_login_log`;
DROP TABLE IF EXISTS `im_user_device`;
DROP TABLE IF EXISTS `im_icebreaker`;
DROP TABLE IF EXISTS `im_moment_comment`;
DROP TABLE IF EXISTS `im_moment_like`;
DROP TABLE IF EXISTS `im_moment_visible`;
DROP TABLE IF EXISTS `im_moment_media`;
DROP TABLE IF EXISTS `im_moment`;
DROP TABLE IF EXISTS `im_intimacy`;
DROP TABLE IF EXISTS `im_profile_visit`;
DROP TABLE IF EXISTS `im_match_exposure`;
DROP TABLE IF EXISTS `im_match_preference`;
DROP TABLE IF EXISTS `im_match_pair`;
DROP TABLE IF EXISTS `im_match_like`;
DROP TABLE IF EXISTS `im_user_tag`;
DROP TABLE IF EXISTS `im_tag`;
DROP TABLE IF EXISTS `im_user_photo`;
DROP TABLE IF EXISTS `im_user_profile`;
DROP TABLE IF EXISTS `im_schema_migration_log`;
DROP TABLE IF EXISTS `im_schema_migration`;
DROP TABLE IF EXISTS `im_feedback`;
DROP TABLE IF EXISTS `im_client_release`;
DROP TABLE IF EXISTS `im_user_agreement`;
DROP TABLE IF EXISTS `im_agreement`;
DROP TABLE IF EXISTS `im_announcement`;
DROP TABLE IF EXISTS `im_sensitive_word`;
DROP TABLE IF EXISTS `im_config`;
DROP TABLE IF EXISTS `im_report`;
DROP TABLE IF EXISTS `im_live_room`;
DROP TABLE IF EXISTS `im_gift_record`;
DROP TABLE IF EXISTS `im_wallet_transaction`;
DROP TABLE IF EXISTS `im_gift`;
DROP TABLE IF EXISTS `im_user_wallet`;
DROP TABLE IF EXISTS `im_live_novel`;
DROP TABLE IF EXISTS `im_live_music`;
DROP TABLE IF EXISTS `im_live_image`;
DROP TABLE IF EXISTS `im_live_video_category`;
DROP TABLE IF EXISTS `im_live_video`;
DROP TABLE IF EXISTS `im_article`;
DROP TABLE IF EXISTS `im_ai_guard`;
DROP TABLE IF EXISTS `im_ai_usage`;
DROP TABLE IF EXISTS `im_interview_event`;
DROP TABLE IF EXISTS `im_interview_message`;
DROP TABLE IF EXISTS `im_interview_session`;
DROP TABLE IF EXISTS `im_remote_audit_log`;
DROP TABLE IF EXISTS `im_remote_session`;
DROP TABLE IF EXISTS `im_remote_device`;
DROP TABLE IF EXISTS `im_moderation_log`;
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
    `biz_type`      VARCHAR(32)  NOT NULL COMMENT '业务类型：avatar / chat_image / chat_file / chat_voice / video / video_cover / article_cover / article_img / call_record / moderation_quarantine（图片审核隔离区），全集见 FileBizType',
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

-- 图片审核命中流水（五期）：审计文件 MODERATION/moderation.log 只留 30 天且不能按维度检索，
-- 追责数据挪进表里才能按「谁 / 哪个场景 / 哪一类」翻页。继承 AuditEntity（流水表只增不改不删，
-- 没有 deleted 列）。概率与阈值用 DECIMAL(5,4)：客户端上报已收敛到 0~1，定长小数事后排序省事。
-- 排序固定按主键倒序（雪花 ID 即时间序），所以二级索引都带 id 收尾，不再单建 create_time 索引。
-- ★ evidence_file_id（五期·查看图片）：命中后发送端把原图复制一份进隔离区（biz_type=moderation_quarantine），
--   这一列指向那行 im_file。它是对判定结果的「补写」而不是修改：只有 NULL → 有值这一个方向，
--   判定字段（类别/概率/阈值/评分）永远不再被改，所以「只增不改」的口径不受影响。
CREATE TABLE `im_moderation_log`
(
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花，兼时间排序键）',
    `user_id`         BIGINT       NOT NULL COMMENT '上报用户 ID（发图人 / 设头像的人）',
    `scene`           VARCHAR(32)  NOT NULL COMMENT '业务场景：avatar / chat_image / video_cover 等图片类 FileBizType',
    `hit_class`       VARCHAR(32)  NOT NULL COMMENT '命中类别名（模型输出原名）：Porn / Hentai / Violent 等',
    `hit_probability` DECIMAL(5, 4) NOT NULL DEFAULT 0 COMMENT '命中类别概率（0~1，服务端夹后落库）',
    `threshold`       DECIMAL(5, 4)         DEFAULT NULL COMMENT '上报时前端实际使用的阈值，事后核对阈值改动的影响',
    `file_name`       VARCHAR(200)          DEFAULT NULL COMMENT '原始文件名（客户端报的，已洗成单行）；图片本体见 evidence_file_id',
    `file_size`       BIGINT                DEFAULT NULL COMMENT '文件字节数',
    `conversation_id` BIGINT                DEFAULT NULL COMMENT '会话 ID，聊天场景非空',
    `scores`          JSON                  DEFAULT NULL COMMENT '模型完整评分 [{className,probability}]，上限 16 条，仅留痕',
    `evidence_file_id` BIGINT               DEFAULT NULL COMMENT '隔离区图片的 im_file.id（biz_type=moderation_quarantine）：未开启/超大/上传失败为 NULL',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '命中时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（仅挂证据时被动一次，判定字段不回改）',
    PRIMARY KEY (`id`),
    -- 「这个人试过几次」：按用户过滤后天然按主键倒序取一页
    KEY `idx_user` (`user_id`, `id`),
    -- 按场景看分布（比如头像位有没有人试毒）
    KEY `idx_scene` (`scene`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='图片审核命中流水（追责数据落库，判定字段只增不改；与审计文件双写）';

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
--  九之二、AI 用量护栏（im-ai · 四期第六批）
-- =====================================================================================

CREATE TABLE `im_ai_usage`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花）',
    `user_id`     BIGINT       NOT NULL COMMENT '发起人用户 ID',
    `scene`       VARCHAR(32)  NOT NULL COMMENT '计费场景：interview_chat 面试对话 / interview_asr 面试语音转文字 / interview_tts 文字合成朗读 / message_to_text 聊天语音转文字',
    `duration_ms` BIGINT       NOT NULL DEFAULT 0 COMMENT '模型调用实际耗时（毫秒，服务端单调时钟）：护栏的计费口径',
    `success`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否成功：1 成功（扣预算） 0 失败（只留痕不扣）',
    `error_msg`   VARCHAR(255)          DEFAULT NULL COMMENT '失败原因摘要（成功为空），写入前截到 255',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用完成时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（流水不更，列形同虚设，与面试事件表同构）',
    PRIMARY KEY (`id`),
    -- 按人重建 Redis 预算计数器与后台聚合：WHERE user_id=? [AND success=1] SUM(duration_ms)
    KEY `idx_user_time` (`user_id`, `create_time`),
    -- 明细页按场景筛
    KEY `idx_scene_time` (`scene`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 付费模型调用用量流水（只增不改不删）';

CREATE TABLE `im_ai_guard`
(
    `user_id`     BIGINT      NOT NULL COMMENT '被管控的用户 ID，同时是主键：一行一人，没封禁的人根本没有这一行',
    `banned`      TINYINT     NOT NULL DEFAULT 1 COMMENT '是否封禁 AI 功能：0 未封禁（解封写 0 不删行，留 reason 供追溯） 1 已封禁',
    `reason`      VARCHAR(200)         DEFAULT NULL COMMENT '封禁原因（展示给被封者与管理台）',
    `update_by`   BIGINT               DEFAULT NULL COMMENT '最后一次封/解操作的后台操作人 ID（问责用）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次封禁时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后一次封/解时间',
    PRIMARY KEY (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='AI 功能封禁状态表（只停付费模型调用，不动账号登录）';

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
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（视频板块二级分类标签，如 游戏/音乐/美食…），缺省 综合',
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

-- 直播页四个内容板块（视频 / 图片 / 音乐 / 小说）共用的二级分类字典，驱动前端顶部分类栏与发布分类下拉，
-- 后台可增删改（不硬编码在前端）。靠 `type` 区分归属板块：每个板块只拉自己那一套启用项。
-- name 与各板块作品表的 category 列取值对齐（无外键，停用/删除分类都不动历史作品的 category 文本）；
-- sort 升序即前端展示顺序；status=0 停用不在前端出现。
CREATE TABLE `im_live_video_category`
(
    `id`          BIGINT      NOT NULL COMMENT '分类 ID',
    `type`        VARCHAR(16) NOT NULL DEFAULT 'video' COMMENT '所属板块：video 视频 / image 图片 / music 音乐 / novel 小说',
    `name`        VARCHAR(32) NOT NULL COMMENT '分类名称（与各板块作品表的 category 列取值对齐）',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序（升序，前端分类栏按此顺序展示）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用（停用不在前端分类栏出现）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- 分类栏主查询：先按板块圈定，再按启用状态过滤后按 sort 升序
    KEY `idx_type_status_sort` (`type`, `status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页内容板块二级分类字典（按 type 分板块，后台可增删改）';

-- 初始分类：与视频板块抖音化上线时的硬编码 17 项一致，保证升级后前端分类栏行为不变。
-- 这些行不写 type，靠列默认值落 'video'（板块归属与上线时序一致，读起来也比重复 17 次 'video' 清爽）。
INSERT INTO `im_live_video_category` (`id`, `name`, `sort`, `status`) VALUES
(1, '综合', 1, 1), (2, '游戏', 2, 1), (3, '二次元', 3, 1), (4, '音乐', 4, 1),
(5, '影视', 5, 1), (6, '美食', 6, 1), (7, '知识', 7, 1), (8, '小剧场', 8, 1),
(9, '生活vlog', 9, 1), (10, '体育', 10, 1), (11, '旅行', 11, 1), (12, '亲子', 12, 1),
(13, '动物', 13, 1), (14, '三农', 14, 1), (15, '汽车', 15, 1), (16, '美妆穿搭', 16, 1),
(17, '公开课', 17, 1);

-- 三个新板块的初始分类：id 按板块分段预留（图片 101~110、音乐 121~130、小说 141~150），每段首项固定「综合」，
-- 与作品表 category 的缺省值同一口径。后台可自由增删改，这里只是让新装环境一打开分类栏就不是空的。
INSERT INTO `im_live_video_category` (`id`, `type`, `name`, `sort`, `status`) VALUES
(101, 'image', '综合', 1, 1), (102, 'image', '风景', 2, 1), (103, 'image', '人像', 3, 1),
(104, 'image', '二次元', 4, 1), (105, 'image', '美食', 5, 1), (106, 'image', '动物', 6, 1),
(107, 'image', '建筑', 7, 1), (108, 'image', '插画', 8, 1), (109, 'image', '摄影教程', 9, 1),
(110, 'image', '生活记录', 10, 1);

INSERT INTO `im_live_video_category` (`id`, `type`, `name`, `sort`, `status`) VALUES
(121, 'music', '综合', 1, 1), (122, 'music', '流行', 2, 1), (123, 'music', '摇滚', 3, 1),
(124, 'music', '说唱', 4, 1), (125, 'music', '电子', 5, 1), (126, 'music', '古典', 6, 1),
(127, 'music', '民谣', 7, 1), (128, 'music', '纯音乐', 8, 1), (129, 'music', '翻唱', 9, 1),
(130, 'music', '有声朗读', 10, 1);

INSERT INTO `im_live_video_category` (`id`, `type`, `name`, `sort`, `status`) VALUES
(141, 'novel', '综合', 1, 1), (142, 'novel', '玄幻', 2, 1), (143, 'novel', '都市', 3, 1),
(144, 'novel', '科幻', 4, 1), (145, 'novel', '悬疑推理', 5, 1), (146, 'novel', '历史', 6, 1),
(147, 'novel', '言情', 7, 1), (148, 'novel', '武侠', 8, 1), (149, 'novel', '短篇', 9, 1),
(150, 'novel', '同人', 10, 1);

-- =====================================================================================
--  直播页「图片 / 音乐 / 小说」三个内容板块（im-live 持有）：与视频板块同一形态——
--  媒体与封面本体存 im_file（bizType=image_work / music / music_cover / novel_cover，对所有登录用户公开），
--  三张表只存元数据与受控访问地址；发布即可见（status 只有 1 正常 / 2 已下架），不走文章的审核状态机。
--  三表的列口径刻意与 im_live_video 对齐（同名同义），包括计数列统一叫 play_count：
--  前端一个信息流组件吃三个板块，字段名不一致就得在模板里写三套分支。
-- =====================================================================================

-- 图片板块：一行一个图集（1~9 张，首图作封面）。image_ids / image_urls 逗号并列而不是建子表：
-- 两边的元素分别是雪花 ID 与 /api/file/download/{id}，都不含逗号，拆分无歧义；上限 9 张使字段长度可控，
-- 省一张子表与一次 join。两个字段同序，第 N 张图的 ID 与地址一一对应。
CREATE TABLE `im_live_image`
(
    `id`            BIGINT        NOT NULL COMMENT '图片作品 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '上传用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '作品标题',
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（图片板块二级分类标签），缺省 综合',
    `image_ids`     VARCHAR(1024) NOT NULL COMMENT '图集文件 ID 逗号并列（im_file.id，bizType=image_work，顺序即展示顺序，1~9 张）',
    `image_urls`    TEXT          NOT NULL COMMENT '图集受控访问地址逗号并列（与 image_ids 同序，服务端现拼，不采信前端传来的 URL）',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面文件 ID，等于首图 ID（冗余一份便于卡片与后台列表直接取）',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面受控访问地址，等于首图地址',
    `image_count`   INT           NOT NULL DEFAULT 1 COMMENT '图集张数（1~9）',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '各图字节数合计',
    `ext`           VARCHAR(32)            DEFAULT NULL COMMENT '首图扩展名，不含点',
    `content_type`  VARCHAR(128)           DEFAULT NULL COMMENT '首图 MIME 类型',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '打开次数（每次点开图集 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页图片板块（图集信息流，一行一个作品）';

-- 音乐板块：一行一首。与 im_live_video 同构（只是不放 width/height：音频没有画面尺寸）。
CREATE TABLE `im_live_music`
(
    `id`            BIGINT        NOT NULL COMMENT '音乐作品 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '上传用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '作品标题',
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（音乐板块二级分类标签），缺省 综合',
    `file_id`       BIGINT        NOT NULL COMMENT '音频文件 ID（im_file.id，bizType=music）',
    `file_url`      VARCHAR(1024) NOT NULL COMMENT '音频受控访问地址（/api/file/download/{fileId}，前端经 mediaUrl 取 blob 后交给 <audio>）',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面图文件 ID（im_file.id，bizType=music_cover），可空',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面图受控访问地址，可空（前端退化为占位图）',
    `duration`      INT           NOT NULL DEFAULT 0 COMMENT '时长（秒），上传前由客户端读取元数据后上报，缺省 0',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `ext`           VARCHAR(32)            DEFAULT NULL COMMENT '扩展名，不含点',
    `content_type`  VARCHAR(128)           DEFAULT NULL COMMENT 'MIME 类型',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '播放次数（每次点开 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页音乐板块（音频信息流，一行一首）';

-- 小说板块：一行一篇 Markdown 正文。与文章那一张表的区别：不带审核状态机（发布即可见）、带分类与阅读计数。
-- content 是 LONGTEXT，列表查询永远不选它（服务层显式排除，只有详情接口取），否则一页 15 条就能拖几百 MB。
CREATE TABLE `im_live_novel`
(
    `id`            BIGINT        NOT NULL COMMENT '小说作品 ID',
    `uploader_id`   BIGINT        NOT NULL COMMENT '作者用户 ID',
    `title`         VARCHAR(60)   NOT NULL COMMENT '作品标题（与图片/音乐同长，保证卡片一行放得下）',
    `category`      VARCHAR(32)   NOT NULL DEFAULT '综合' COMMENT '分类（小说板块二级分类标签），缺省 综合',
    `summary`       VARCHAR(255)           DEFAULT NULL COMMENT '列表摘要，空则服务端从正文截取前 80 字',
    `content`       LONGTEXT      NOT NULL COMMENT 'Markdown 正文（列表接口不返回，只在详情取）',
    `word_count`    INT           NOT NULL DEFAULT 0 COMMENT '字数，服务端按正文去空白后的字符数现算，不采信前端上报',
    `cover_file_id` BIGINT                 DEFAULT NULL COMMENT '封面图文件 ID（im_file.id，bizType=novel_cover），可空',
    `cover_url`     VARCHAR(1024)          DEFAULT NULL COMMENT '封面图受控访问地址，可空（前端退化为占位图）',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '正文字节数（UTF-8）',
    `play_count`    INT           NOT NULL DEFAULT 0 COMMENT '阅读次数（每次打开详情 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_uploader_create` (`uploader_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页小说板块（Markdown 长篇，一行一篇）';

-- =====================================================================================
--  直播页「文章」板块（im-live 持有）：Markdown 文章 + 审核状态机。
--  正文里的图片与封面本体存 im_file（bizType=article_img/article_cover，公开），本表只存原文与元数据。
-- =====================================================================================
CREATE TABLE `im_article`
(
    `id`            BIGINT       NOT NULL COMMENT '文章 ID',
    `author_id`     BIGINT       NOT NULL COMMENT '作者用户 ID',
    `title`         VARCHAR(120) NOT NULL COMMENT '文章标题',
    `summary`       VARCHAR(255)          DEFAULT NULL COMMENT '列表摘要，空则服务端从正文截取',
    `content`       LONGTEXT     NOT NULL COMMENT 'Markdown 原文',
    `cover_file_id` BIGINT                DEFAULT NULL COMMENT '封面图文件 ID（im_file.id，bizType=article_cover），可空',
    `cover_url`     VARCHAR(1024)         DEFAULT NULL COMMENT '封面图受控访问地址，可空（前端退化为占位图）',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 草稿 1 待审核 2 已发布 3 已驳回 4 已下架',
    `reject_reason` VARCHAR(255)          DEFAULT NULL COMMENT '驳回事由，仅 status=3 有值',
    `view_count`    INT          NOT NULL DEFAULT 0 COMMENT '浏览量（详情页每次打开 +1，非去重）',
    `publish_time`  DATETIME              DEFAULT NULL COMMENT '审核通过/发布时间，未发布为 NULL',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    -- 广场主查询：按状态过滤后按创建时间倒序分页
    KEY `idx_status_create` (`status`, `create_time`),
    -- 「我的文章」按作者查
    KEY `idx_author_create` (`author_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页文章板块（Markdown + 审核状态机）';

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

-- =====================================================================================
--  十五、法定文本与同意留痕（im-user）
-- =====================================================================================

-- 面向公众的三份法定文本（用户协议 / 隐私政策 / 服务条款），一个版本一行。
-- 为什么建表而不是把 md 文件塞进 im-website：改一份对外生效的合同是一次**有操作人、有生效时刻、
-- 需要留下变更说明**的行为，而这些正好是文件系统给不了的三样；同时官网页脚、App 内公示页、
-- 注册勾选行必须读到同一份文本，两处各存一份 markdown 迟早出现「弹窗里是旧的、官网上是新的」。
-- ★ 不启 deleted：这一列一旦生效就是一份对用户履行过的合同原件，销毁原件不是「删除」能表达的动作。
--   法务要改文案只能发一个版本号更大的新版本，旧行转 status=2 归档，文本永久按 code+version 可查
--   （一个已经注销的账号当初点开的链接，今天仍然要能打开）——所以后台控制器压根没有删除端点。
-- version 由服务端取「该 code 下历史最大值 + 1」（含草稿与归档，号段永不复用），客户端传来的版本号
--   一律不认：它是 im_user_agreement 里那条留痕的对账依据，可写就等于允许留痕和账对不上。
-- required 分两档不是随便定的：隐私政策与用户协议不签就不能用（法定门槛），而服务条款只覆盖钱包 /
--   打赏 / 远程协助这类增值服务，拒签只该关掉这些入口，不该拦住注册——拒签同样要落一行留痕。
-- 索引两条就够：status 全表扫那一下命中行数就是「发布过的版本数」（个位到十几），而
--   idx_code_version 这个唯一键同时服务「按 code 取生效版」与「按 code+version 取历史版」两种查法。
CREATE TABLE `im_agreement`
(
    `id`             BIGINT        NOT NULL COMMENT '协议版本 ID（雪花）；im_user_agreement.agreement_id 指向的就是这一行，它一旦发布过就不可被替换',
    `code`           VARCHAR(32)   NOT NULL COMMENT '协议标识：terms 用户协议 / privacy 隐私政策 / service 服务条款。白名单在服务端（Agreement#isKnownCode），不建 ENUM——将来加一份新的法定文本不该动表结构',
    `version`        INT           NOT NULL COMMENT '同一 code 内单调递增的版本号，服务端算，客户端传来的值一律不认',
    `title`          VARCHAR(120)  NOT NULL COMMENT '文档标题，公示页的 h1；发布后不可改，标题也是那份被同意的文本的一部分',
    `summary`        VARCHAR(500)           DEFAULT NULL COMMENT '一句话摘要：注册页勾选行下面与再同意弹窗里那行小字',
    `content`        MEDIUMTEXT    NOT NULL COMMENT 'Markdown 正文原文，渲染端 marked + DOMPurify 净化（与文章同一口径）。用 MEDIUMTEXT 而不是 VARCHAR(65535)：后者上限按字节算，utf8mb4 下中文实际只装得下两万多字，一份带信息清单表格的隐私政策轻松过线',
    `required`       TINYINT       NOT NULL DEFAULT 1 COMMENT '是否必签：1 必须同意才能注册与继续使用 0 可拒（只影响对应增值服务的入口）',
    `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '状态（单向，没有撤回这条边）：0 草稿（仅后台可见，对外等于不存在） 1 已发布（同一 code 至多一行） 2 已归档（被新版本顶替，文本仍永久可查）',
    `effective_time` DATETIME               DEFAULT NULL COMMENT '生效时刻，发布时盖；公示页那行「本版生效于」取它，也是判断「某次同意是否发生在文本改版之前」的唯一依据',
    `publish_time`   DATETIME               DEFAULT NULL COMMENT '发布时刻，草稿为空',
    `publish_by`     BIGINT                 DEFAULT NULL COMMENT '发布人（后台账号 ID）：出了争议要能问出「这一版是谁放出去的」',
    `remark`         VARCHAR(255)           DEFAULT NULL COMMENT '本版变更说明：「v3 比 v2 改了什么」唯一能直接答上的字段，发布时可补写一次',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '起草时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（只有草稿阶段会被改，已发布行的这两列应当相等或只差一次发布动作）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_version` (`code`, `version`),
    KEY `idx_code_status` (`code`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='法定文本版本表（发布后正文不可改、无删除端点，改文案等于发新版本）';

-- 同意留痕：一行 = 某个用户在某个时刻对某个**具体版本**的一次表态。整个合规链路里唯一拿得出证据的
-- 就是这张表——官网上挂一份公示文本不构成「取得单独同意」，监管和对方律师要看的是能逐行对上的记录：
-- 谁、什么时候、从哪个入口、对着哪一版、点了同意还是拒绝、从哪个 IP 与设备。少任何一列，
-- 举证时都要现编说辞。三个 source 的举证强度并不相同：register 是账号本身就是那次点击的产物，
-- login 是验证码登录自动建号（他从未勾过东西，靠之后那次弹窗补齐），dialog 是文本改版后的再同意。
-- ★ 不启 deleted 且后台无删除端点：法定留存期内没有任何角色有权删这一行，清理只跟日志留存策略走、
--   不跟业务走。注销账号也不连带删它——「他当初同意过什么」与「账号还在不在」是两件事，
--   所以这里刻意不建外键（建了就得允许级联删，或者让注销报错，两个都错）。
-- ★ 刻意不加唯一键：同一版重复同意在技术上能插两条，但服务层每次先查「这一版表过态没有」再决定写不写；
--   换成唯一键会把「先拒了服务条款、后来改主意同意」这条正常路径撞成主键冲突。
-- code 与 version 是从主表抄下来的冗余快照：留痕要能整表导出成一份自足的材料，不该依赖主表当时
--   长成什么样（也不必为了补这两个字段去 join 一次几百万行的表）。
CREATE TABLE `im_user_agreement`
(
    `id`           BIGINT      NOT NULL COMMENT '留痕 ID（雪花）',
    `user_id`      BIGINT      NOT NULL COMMENT '表态的用户 ID。注销后仍保留，且不建外键：级联删等于销毁证据',
    `agreement_id` BIGINT      NOT NULL COMMENT '指向 im_agreement 的具体版本行：点进去能看到他当时同意的那份原文',
    `code`         VARCHAR(32) NOT NULL COMMENT '协议标识快照（冗余），整表导出时不必 join 主表',
    `version`      INT         NOT NULL COMMENT '版本号快照（冗余），「他同意的是 v2」全靠这一列，不看主表当前值',
    `action`       TINYINT     NOT NULL COMMENT '动作：1 同意 0 拒绝。0 同样是有价值的证据——它证明这个人明确没同意过，也是「必签项没勾」与「勾了不同意」在服务端合成的同一种失败',
    `source`       VARCHAR(16) NOT NULL COMMENT '场景：register 注册勾选 / login 验证码登录自动建号 / dialog 登录后强制再同意弹窗。三个入口的举证强度不同，出了争议要先问这一行是怎么产生的',
    `ip`           VARCHAR(64)          DEFAULT NULL COMMENT '来源 IP，与登录日志同一口径（含反代头，写入前截到列宽）',
    `user_agent`   VARCHAR(255)         DEFAULT NULL COMMENT 'User-Agent，写入前截到列宽：判断「是不是本人设备点的」的唯一线索',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '表态时刻（本表只有 INSERT，这一列就是事件时间）',
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（流水不更，列形同虚设，与 im_ai_usage 同一口径）',
    PRIMARY KEY (`id`),
    -- 每次登录后判断「要不要弹框」与「同一版是否已表过态」都是这个组合：WHERE user_id=? AND agreement_id IN (...)
    KEY `idx_user_agreement` (`user_id`, `agreement_id`),
    -- 后台反查「这一版有多少人同意过」（发布效果与同意率统计）
    KEY `idx_agreement` (`agreement_id`),
    -- 留痕按时间范围翻页（监管取证常给一个起止日期）；个人同意链按 user_id 排序取，行数是个位量级
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='同意留痕（一次表态一行、只 INSERT；无删除端点、无唯一键、无外键）';

-- =====================================================================================
--  十六、客户端版本管理（im-admin）
-- =====================================================================================

-- 一行 = 某平台某版本的一个安装包。字节不在本表：安装包经文件模块的分片上传通道
-- （bizType=client_release，2GB 上限、MD5 秒传）落在 MinIO/本地存储，本表只持 file_id 引用，
-- 并把展示要用的文件名/大小/MD5 冗余一份——后台列表与官网下载页都不该为每行回查一次
-- im_file（N+1），而这三列在上传完成那一刻就已定死，冗余没有一致性代价。
-- platform 收在服务端码表（ReleasePlatform）不建 ENUM：平台是启用互斥的分组键，
--   允许自造平台码会出现 windows 与 windows_setup 各自「启用一个」的哑巴故障；
--   加平台改代码而不改表，与 FileBizType 同一条规矩。
-- 同一 platform 至多一行 status=1，由 ClientReleaseService.enable 在同一事务里
--   「先压平同平台、再立起本行」保证；不建触发器也不建唯一索引去凑——
--   「停用行可以多条、启用行只有一条」这种部分唯一约束 MySQL 表达不了。
-- 删除是物理删行且只允许停用态（启用中的先停用，防「官网卡片还在、引用已消失」的半截态）；
--   删行不删字节：同 MD5 的对象可能被另一行秒传复用，删字节会连坐。
-- ★ 不启 deleted（继承 AuditEntity 而非 BaseEntity）：物理删行后 uk_platform_version
--   立刻释放，同平台同版本号才能重新登记；逻辑删除会让唯一键被已删行占着。
CREATE TABLE `im_client_release`
(
    `id`             BIGINT       NOT NULL COMMENT '版本 ID（雪花）',
    `platform`       VARCHAR(32)  NOT NULL COMMENT '平台码：windows_setup / windows_portable / android / macos / linux，白名单在服务端（ReleasePlatform），启用互斥按它分组',
    `version`        VARCHAR(32)  NOT NULL COMMENT '版本号（如 1.0.1）。只做长度校验不做语义比较：1.10 与 1.9 谁新由发版的人知道，代码不猜',
    `release_note`   VARCHAR(500)          DEFAULT NULL COMMENT '更新说明，可空；官网卡片与后台列表原样展示，空串在写入前归一成 NULL',
    `file_id`        BIGINT       NOT NULL COMMENT '安装包字节所在的 im_file 主键。不建外键：文件行被人工清理时本行照常可读（列表带 filePresent 提示），外键会把这个降级变成报错',
    `file_name`      VARCHAR(255) NOT NULL COMMENT '安装包展示名（含扩展名），公开下载时 Content-Disposition 的文件名取它',
    `file_size`      BIGINT       NOT NULL DEFAULT 0 COMMENT '安装包字节数，登记时从文件行抄来；下载响应的 Content-Length 取它',
    `md5`            CHAR(32)              DEFAULT NULL COMMENT '整文件 MD5：官网展示供用户校验下载完整性，也是分片上传秒传命中的依据',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 停用（官网不返回、公开下载拒绝） 1 启用（该平台官网唯一下载入口，同平台至多一行）',
    `download_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '累计下载次数：公开下载端点每成功流式输出一次加 1（setSql 原子自增），运营数字不是账，累加失败只记日志',
    `create_by`      BIGINT                DEFAULT NULL COMMENT '创建人（后台账号 ID）',
    `update_by`      BIGINT                DEFAULT NULL COMMENT '最后修改人（后台账号 ID）：启用/停用/换包都算修改',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 同平台版本号不可重复（服务层预检 + 这里兜底并发）；前缀 platform 同时服务列表页的平台过滤
    UNIQUE KEY `uk_platform_version` (`platform`, `version`),
    -- 官网公开列表只认启用行：WHERE status=1，行数就是「启用中的平台数」（个位数）
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='客户端安装包版本（一行一个包，字节在文件模块；同平台同时只启用一个，官网下载页只递启用行）';

-- =====================================================================================
--  十七、意见反馈（im-admin）
-- =====================================================================================

-- 用户在设置页提交、后台在「意见反馈」屏处置的一张工单表。表归 im-admin 所有
-- （与公告、版本管理同一先例：不新增 SPI 契约、不动任何 pom 依赖）；
-- 提交人昵称不冗余进本表，后台列表用 AdminUserSpi.listByIds 批量取——
-- 昵称是会改的，冗余一份就会出现「台账里的名字和现在对不上」。
-- user_id 不建外键：账号注销后反馈行仍是有效台账（提交人显示为已注销）。
-- 状态机只有两条边：0 待处理 → 1 已回复（reply 写入即置 1，记 reply_by/reply_time）。
-- 删除是物理删行（后台清理已处置完的台账）；反馈不是合同原件，不需要归档级保护。
CREATE TABLE `im_feedback`
(
    `id`          BIGINT        NOT NULL COMMENT '反馈 ID（雪花）',
    `user_id`     BIGINT        NOT NULL COMMENT '提交人用户 ID。不建外键：账号注销后反馈行仍是有效台账（提交人显示为已注销）',
    `type`        VARCHAR(16)   NOT NULL COMMENT '反馈类型码：bug 功能异常 / suggestion 产品建议 / experience 体验问题 / other 其他，白名单在服务端（FeedbackType）',
    `content`     VARCHAR(1000) NOT NULL COMMENT '反馈正文，与后端 @Size(max=1000) 同宽；入库前 trim，空串拒收',
    `contact`     VARCHAR(64)            DEFAULT NULL COMMENT '联系方式（选填）：用户愿意被回访时才填；空串在写入前归一成 NULL',
    `status`      TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 已回复（reply 非空即置 1）',
    `reply`       VARCHAR(1000)          DEFAULT NULL COMMENT '后台回复原文；写入即把 status 置 1 并记 reply_by/reply_time，再次回复覆盖旧文并刷新两列',
    `reply_by`    BIGINT                 DEFAULT NULL COMMENT '回复人（后台账号 ID）',
    `reply_time`  DATETIME               DEFAULT NULL COMMENT '最近一次回复时刻',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 后台按状态过滤 + 创建时间倒序翻页；前缀 status 同时服务「待处理有多少条」的看板口径
    KEY `idx_status_create` (`status`, `create_time`),
    -- 「这个人提过什么」：后台处置时反查同一用户的历史反馈，判断是不是重复提交
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='意见反馈（设置页提交、后台回复处置；状态 0 待处理 → 1 已回复，物理删除）';

-- =====================================================================================
--  十八、SQL 版本管理（im-admin）
-- =====================================================================================

-- 把「手写 SQL 人肉执行」升级为「版本脚本配对（sql/versions 的 V/U）+ 后台可视化 + 一键回滚」。
-- 台账表一行 = 一个版本号的当前态（按 version 唯一收敛）；checksum 用于发现「已应用的脚本
-- 后来被人改过」的漂移。undo_name 为 NULL 表示不可一键回滚（基线行就是 NULL：它对应的是一批
-- 历史脚本的合集，没有配对的 U 文件，页面不给它画回滚按钮）。
-- 操作人存 ID + 昵称快照：昵称会改，台账里的名字必须定格在当时，否则「谁执行的」会随改名失真。
CREATE TABLE `im_schema_migration`
(
    `id`             BIGINT       NOT NULL COMMENT '记录 ID（雪花；基线行固定 1）',
    `version`        VARCHAR(32)  NOT NULL COMMENT '版本号，与 md/SQL变更文档.md 台账同一序列（如 1.0.4）',
    `script_name`    VARCHAR(128) NOT NULL COMMENT '向前脚本文件名（sql/versions/V*.sql）；基线行写合集说明文本',
    `undo_name`      VARCHAR(128)          DEFAULT NULL COMMENT '回滚脚本文件名（U*.sql）；NULL 表示该版本不可一键回滚',
    `description`    VARCHAR(255)          DEFAULT NULL COMMENT '一句话说明（与台账条目对应）',
    `checksum`       CHAR(64)              DEFAULT NULL COMMENT '向前脚本文件 SHA-256 小写；基线行为 NULL，重算不一致=脚本被偷改',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 已应用 0 已回滚 2 应用失败（半截脚本，人工修补后重放）',
    `operator_id`    BIGINT                DEFAULT NULL COMMENT '执行人用户 ID；SYSTEM 引导行为 NULL',
    `operator_name`  VARCHAR(64)           DEFAULT NULL COMMENT '执行人昵称快照（昵称后来改了不影响历史可读）；基线行写 SYSTEM',
    `applied_at`     DATETIME              DEFAULT NULL COMMENT '最近一次应用成功时刻',
    `rolled_back_at` DATETIME              DEFAULT NULL COMMENT '最近一次回滚成功时刻',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_version` (`version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SQL 版本台账（谁/何时应用了哪个版本；一键回滚与漂移检测的依据）';

-- 流水与台账分两张表的原因：台账按 version 唯一收敛当前态，流水原样保留每一次尝试（含失败）——
-- 半途炸掉的脚本正是最需要留痕的，两张表读写形状不一样，塞一张两边都别扭。只 INSERT。
CREATE TABLE `im_schema_migration_log`
(
    `id`              BIGINT       NOT NULL COMMENT '日志 ID（雪花；基线行固定 1）',
    `version`         VARCHAR(32)  NOT NULL COMMENT '被操作的版本号',
    `action`          VARCHAR(16)  NOT NULL COMMENT '动作：APPLY 应用 / ROLLBACK 回滚 / MARK 手工登记基线',
    `result`          VARCHAR(16)  NOT NULL COMMENT '结果：SUCCESS / FAILED（失败也记行）',
    `statement_count` INT          NOT NULL DEFAULT 0 COMMENT '成功执行的语句条数（FAILED 时为挂掉前跑掉的条数）',
    `duration_ms`     BIGINT       NOT NULL DEFAULT 0 COMMENT '整个脚本耗时（毫秒）',
    `message`         VARCHAR(1000)         DEFAULT NULL COMMENT '失败原因或备注，写入前截到列宽',
    `operator_id`     BIGINT                DEFAULT NULL COMMENT '操作人用户 ID',
    `operator_name`   VARCHAR(64)           DEFAULT NULL COMMENT '操作人昵称快照',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时刻（本表只 INSERT，这一列就是事件时间）',
    PRIMARY KEY (`id`),
    KEY `idx_version` (`version`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='SQL 版本操作流水（变更过程时间线；只 INSERT 不更新不删除）';

-- 基线行：1.0.0~1.0.3 的存量变更（历史 migration_*.sql 合集）已由人肉执行完毕，一次性补登，
-- 页面从 1.0.4 起接管。与 sql/migration_schema_version.sql 第 3 段逐字一致。
INSERT INTO `im_schema_migration` (`id`, `version`, `script_name`, `undo_name`, `description`, `checksum`, `status`,
                                   `operator_name`, `applied_at`)
VALUES (1, '1.0.0', '历史脚本合集（im_schema.sql + im_data.sql + migration_*.sql）', NULL,
        '2026-11-08 库结构现状起至台账 1.0.3 的全部存量变更，引导时一次性补登', NULL, 1, 'SYSTEM', NOW());

INSERT INTO `im_schema_migration_log` (`id`, `version`, `action`, `result`, `statement_count`, `duration_ms`,
                                       `message`, `operator_name`)
VALUES (1, '1.0.0', 'MARK', 'SUCCESS', 0, 0, '引导脚本补登基线，详见 md/SQL变更文档.md 1.0.0~1.0.3 条目', 'SYSTEM');

-- =====================================================================================
--  十九、交友域（资料卡扩展 / 照片墙 / 兴趣标签 / 滑动匹配 / 附近的人 / 访客 / 亲密度 / 动态 / 破冰）
--
--  不新建 Maven 模块：im_user_profile / im_user_photo / im_tag / im_user_tag 归 im-user
--  （都是「这个人是什么」），其余 12 张归 im-friend（都是「人与人之间的动作」：滑动、配对、
--  访客、亲密度、动态）。
--  本节与 sql/versions/V1.0.7__dating_core.sql 逐字同构：这里靠文件头的 DROP 块保证幂等，
--  那里靠 CREATE TABLE IF NOT EXISTS（存量库走后台「SQL 版本」页应用它，不需要重跑本文件）。
--  标签与破冰文案字典的种子跟建表放在一起（与分类栏种子同一处理），其余种子与权限点在 im_data.sql。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. im_user_profile：资料卡扩展（1:1 挂 im_user，含位置、隐私开关、完资料度与质量分）
--
--    主键直接取 user_id 而不再发一个雪花 ID：这是一张「一个人的另一份档案」，
--    1:1 且永远按 user_id 取，多一个代理键只会让每次 join 都多绕一步。
--    profile_score 与 quality_flag 是<b>冗余计算值</b>：推荐流与筛选要按它们排序过滤，
--    实时算就得每次把标签数、照片数、字段填充度全 join 一遍；由服务端在保存资料/照片/标签
--    后重算写入，代价是「改完照片到分数更新之间有几秒不一致」，可接受。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_user_profile`
(
    `user_id`             BIGINT        NOT NULL COMMENT '用户 ID（= im_user.id，1:1 扩展，主键即外键）',
    `birthday`            DATE                   DEFAULT NULL COMMENT '出生日期；年龄由服务端按当天现算，不存冗余年龄列',
    `height_cm`           SMALLINT               DEFAULT NULL COMMENT '身高 cm（合法 100~250，越界按未填处理）',
    `education`           TINYINT       NOT NULL DEFAULT 0 COMMENT '学历：0 未填 1 初中及以下 2 高中/中专 3 大专 4 本科 5 硕士 6 博士',
    `occupation`          VARCHAR(32)            DEFAULT NULL COMMENT '职业（下拉建议 + 可自填，长度与 nickname 同档）',
    `income`              TINYINT       NOT NULL DEFAULT 0 COMMENT '收入区间：0 未填 1 3千以下 2 3千~5千 3 5千~1万 4 1万~2万 5 2万~5万 6 5万以上',
    `marriage`            TINYINT       NOT NULL DEFAULT 0 COMMENT '婚恋状况：0 未填 1 未婚 2 单身离异 3 丧偶 4 不愿透露',
    `dating_goal`         TINYINT       NOT NULL DEFAULT 0 COMMENT '交友目的：0 未填 1 认真交往 2 先聊聊看 3 交朋友 4 找线下活动搭子',
    `province`            VARCHAR(32)            DEFAULT NULL COMMENT '所在省份（「同城」筛选取 city，province 只用于展示与省级筛选）',
    `city`                VARCHAR(32)            DEFAULT NULL COMMENT '所在城市，精确到市；同城与推荐流的地区筛选按它等值匹配',
    `district`            VARCHAR(32)            DEFAULT NULL COMMENT '区县（选填，只参与展示，不参与任何计算）',
    `lng`                 DECIMAL(10,6)          DEFAULT NULL COMMENT '经度 WGS84（-180~180）。附近的人按它算距离；只服务端使用，绝不下发给陌生人',
    `lat`                 DECIMAL(10,6)          DEFAULT NULL COMMENT '纬度 WGS84（-90~90）。同上',
    `geohash`             VARCHAR(12)            DEFAULT NULL COMMENT '经纬度的 geohash（服务端算，6 位约 ±0.6 km）。附近的人先用它做前缀检索圈候选池，再算精确距离，避免全表扫描',
    `location_time`       DATETIME               DEFAULT NULL COMMENT '定位写入时间；超过 7 天视为过期，附近的人不再采用（旧坐标会把人说成在别处）',
    `moment_visible_days` TINYINT       NOT NULL DEFAULT 0 COMMENT '动态可见范围偏好：0 全部 3 三天 7 一周 30 一个月（是全局偏好，不是单条属性）',
    `show_age`            TINYINT       NOT NULL DEFAULT 1 COMMENT '资料卡是否显示年龄：1 显示 0 隐藏（隐藏时列表位置留空而不是显示 0 岁）',
    `show_distance`       TINYINT       NOT NULL DEFAULT 1 COMMENT '是否显示距离：1 显示 0 隐藏（隐藏时仍参与「附近」排序，只是不显数字）',
    `show_online`         TINYINT       NOT NULL DEFAULT 1 COMMENT '是否对陌生人显示在线状态：1 显示 0 隐藏（好友与配对不受影响）',
    `discoverable`        TINYINT       NOT NULL DEFAULT 1 COMMENT '是否出现在推荐流/附近的人/动态流：0 隐身（已配对与好友关系不受影响，只是不再被陌生人刷到）',
    `profile_score`       SMALLINT      NOT NULL DEFAULT 0 COMMENT '完资料度 0~100：字段填充度 + 照片数 + 标签数加权，服务端重算写入；推荐流按它排序，低完资料度等于低质量曝光',
    `last_active_time`    DATETIME               DEFAULT NULL COMMENT '最近活跃时间（登录/发消息/滑动时刷新），「活跃度优先」排序取它',
    `quality_flag`        TINYINT       NOT NULL DEFAULT 0 COMMENT '推荐质量标记：0 正常 1 低质降权 2 已升权（服务端按完资料度与行为自动落，用户不可改；2 留给后续真人认证）',
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`             TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除（随账号注销一并置 1，不物理删，便于申诉期恢复）',
    PRIMARY KEY (`user_id`),
    KEY `idx_geohash` (`geohash`),
    KEY `idx_city` (`city`),
    KEY `idx_birthday` (`birthday`),
    KEY `idx_discover_score` (`discoverable`, `profile_score`),
    KEY `idx_active` (`last_active_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友资料卡扩展（1:1 挂 im_user：结构化条件 + 位置 + 隐私开关 + 完资料度）';

-- -------------------------------------------------------------------------------------
-- 2. im_user_photo：照片墙（多张 + 排序 + 单张审核状态 + 主封面）
--
--    审核状态按<b>单张</b>存而不是整份资料一个状态：一张违规不该把其余八张一起下架，
--    否则用户只能重传全部，而违规那张往往正是他不想删的那张。
--    is_main 全表内同一用户最多一行为 1，由服务层保证（MySQL 不带部分唯一索引，
--    用「同一用户的多行不能同时为 1」这种约束表达不了，交给业务层收敛更直白）。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_user_photo`
(
    `id`            BIGINT        NOT NULL COMMENT '照片 ID（雪花）',
    `user_id`       BIGINT        NOT NULL COMMENT '所属用户 ID',
    `file_id`       BIGINT        NOT NULL COMMENT '图片文件 ID（im_file.id，bizType=dating_photo）',
    `url`           VARCHAR(1024) NOT NULL COMMENT '受控访问地址（/api/file/download/{fileId}，服务端现拼，不采信前端传来的 URL）',
    `sort`          INT           NOT NULL DEFAULT 0 COMMENT '展示顺序（升序；「设为封面」= 把它挪到 0 并置 is_main）',
    `is_main`       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否主封面：1 是 0 否（同一用户最多一行 1，服务层保证；资料卡与匹配卡片取它）',
    `audit_status`  TINYINT       NOT NULL DEFAULT 0 COMMENT '单张审核状态：0 待审 1 通过 2 驳回（只有 1 对外可见，2 保留行与驳回原因供申诉）',
    `reject_reason` VARCHAR(255)           DEFAULT NULL COMMENT '驳回原因（audit_status=2 时写，只给本人看）',
    `width`         INT           NOT NULL DEFAULT 0 COMMENT '像素宽（客户端上报，用于版式占位，避免图片加载时抖动）',
    `height`        INT           NOT NULL DEFAULT 0 COMMENT '像素高',
    `size`          BIGINT        NOT NULL DEFAULT 0 COMMENT '字节数',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_sort` (`user_id`, `sort`),
    KEY `idx_user_audit` (`user_id`, `audit_status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友照片墙（一行一张，含排序、单张审核状态与主封面标记）';

-- -------------------------------------------------------------------------------------
-- 3. im_tag + im_user_tag：兴趣标签库与用户自选
--
--    标签必须是<b>字典 + 关联表</b>而不是 im_user_profile 上一个逗号串：标签体系要反查
--    「同好的人」（推荐流与破冰话题都靠它），逗号串只能全表 LIKE，且改名要刷全表。
--    use_count 是冗余计数，供标签库排序与「热门标签」展示，由绑定/解绑时增减。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_tag`
(
    `id`          BIGINT      NOT NULL COMMENT '标签 ID',
    `name`        VARCHAR(32) NOT NULL COMMENT '标签名（全局唯一，前端 chip 文案）',
    `category`    VARCHAR(16) NOT NULL DEFAULT '其他' COMMENT '所属分类：运动/美食/影视音乐/阅读/旅行/生活/游戏/社交/其他（选择器按分类分组）',
    `icon`        VARCHAR(32)          DEFAULT NULL COMMENT '图标名（前端 emoji 或 element 图标名，可空则吃分类默认图标）',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序（升序，同分类内）',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用（停用不再出现在选择器，但已绑定的用户标签保留）',
    `use_count`   INT         NOT NULL DEFAULT 0 COMMENT '被多少用户选用（冗余计数，热门标签排序与运营看数用）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_category_status_sort` (`category`, `status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='兴趣标签库（后台可维护，交友选择器与破冰话题的公共字典）';

CREATE TABLE `im_user_tag`
(
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `tag_id`      BIGINT   NOT NULL COMMENT '标签 ID（im_tag.id）',
    `sort`        INT      NOT NULL DEFAULT 0 COMMENT '用户自己拖的顺序（资料卡按它展示，最重要的兴趣放前面）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 物理删除 + 唯一键：解绑即删行，同一标签才能再被选上（与 im_friend 同一口径）
    UNIQUE KEY `uk_user_tag` (`user_id`, `tag_id`),
    KEY `idx_tag` (`tag_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户兴趣标签关联（一行一个绑定，idx_tag 供「同好的人」反查）';

-- -------------------------------------------------------------------------------------
-- 4. im_match_like：滑动喜欢/跳过流水
--
--    跳过也要落行：推荐流要按「不再出现」过滤，不落跳过就只能靠曝光池的时间衰减去猜，
--    用户划过的会反复回来。UK(from,to) 让「同一对方向只记一次」，重复滑动改的是这一行的
--    action（先喜欢后跳过 = 改成 2），而不是插第二行。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_match_like`
(
    `id`           BIGINT   NOT NULL COMMENT '主键',
    `from_user_id` BIGINT   NOT NULL COMMENT '操作方用户 ID',
    `to_user_id`   BIGINT   NOT NULL COMMENT '被操作用户 ID',
    `action`       TINYINT  NOT NULL DEFAULT 1 COMMENT '动作：1 喜欢 2 跳过（撤销喜欢就改成 2，不删行，保留「他喜欢过我」的痕迹给推荐流降权用）',
    `source`       TINYINT  NOT NULL DEFAULT 1 COMMENT '来源场景：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好（运营看转化，也决定配对后的会话提示语）',
    `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次操作时间',
    `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次改动作的时间（「谁喜欢我」按它倒序）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_from_to` (`from_user_id`, `to_user_id`),
    KEY `idx_to_action_time` (`to_user_id`, `action`, `update_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友滑动记录（单向一行，双向都 1 即配对；含跳过，推荐流靠它过滤已划过的人）';

-- -------------------------------------------------------------------------------------
-- 5. im_match_pair：配对关系（双向喜欢成会话，不自动加好友）
--
--    user_a_id / user_b_id 约定<b>a < b</b>（服务层写入前排序），这样一对人在表里只可能
--    有一行，UK 才有意义；否则 (1,2) 与 (2,1) 会各存一行，查询要写两个方向。
--    解除配对置 status=2 而不删行：再配回来复用同一行并置回 1，conversation_id 也留着
--    （会话本身不删，删聊天记录的语义归 im_conversation，不由配对决定）。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_match_pair`
(
    `id`              BIGINT   NOT NULL COMMENT '配对 ID（雪花）',
    `user_a_id`       BIGINT   NOT NULL COMMENT '较小的一方用户 ID（服务层排序后写入，保证一对人只有一行）',
    `user_b_id`       BIGINT   NOT NULL COMMENT '较大的一方用户 ID',
    `conversation_id` BIGINT            DEFAULT NULL COMMENT '配对时由 ConversationSpi 建好的私聊会话 ID（不写 im_friend，用户选定口径）',
    `status`          TINYINT  NOT NULL DEFAULT 1 COMMENT '状态：1 配对中 2 已解除（解除不删行，重新配对复用本行置回 1）',
    `like_source`     TINYINT  NOT NULL DEFAULT 1 COMMENT '在哪成对的：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好',
    `msg_count`       INT      NOT NULL DEFAULT 0 COMMENT '配对后累计消息数（冗余，用于「三天无互聊降权」与僵尸配对提示）',
    `last_msg_time`   DATETIME          DEFAULT NULL COMMENT '配对后最近一条消息时间（由消息链路顺手更新；NULL 表示成对后一句没说）',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配对时间',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pair` (`user_a_id`, `user_b_id`),
    KEY `idx_a_status` (`user_a_id`, `status`),
    KEY `idx_b_status` (`user_b_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友配对关系（双向喜欢成一行，带私聊会话 ID；与 im_friend 完全独立）';

-- -------------------------------------------------------------------------------------
-- 6. im_match_preference：筛选与偏好（一人一行）
--
--    年龄与身高存<b>区间两端</b>而不是「最小/最大」两列语义混着存：条件筛选要按 birthday
--    反算，服务端把「想要 24~32 岁」换算成 birthday BETWEEN 两个日期，偏好表只存岁数。
--    target_* 前缀统一表示「我对对方的要求」，与 im_user_profile 里「我自己是什么」区分开，
--    两个 education 混在一列里迟早写错。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_match_preference`
(
    `id`             BIGINT   NOT NULL COMMENT '主键（雪花）',
    `user_id`        BIGINT   NOT NULL COMMENT '归属用户 ID（一人一份偏好）',
    `target_gender`  TINYINT  NOT NULL DEFAULT 0 COMMENT '对方性别：0 不限 1 男 2 女（默认 0，与 im_user.gender 同一取值）',
    `age_min`        TINYINT  NOT NULL DEFAULT 18 COMMENT '对方年龄下限（18~80，服务端夹取；小于 18 一律按 18——未成年人不进交友池）',
    `age_max`        TINYINT  NOT NULL DEFAULT 45 COMMENT '对方年龄上限（18~80，且不小于 age_min，服务层校验）',
    `distance_km`    INT      NOT NULL DEFAULT 0 COMMENT '距离上限 km：0 不限（有坐标时生效；无坐标的候选人只在「同城」口径下参与）',
    `target_city`    VARCHAR(32)       DEFAULT NULL COMMENT '只看指定城市（空 = 不限；与 distance_km 是两条独立通道，同城筛选不依赖定位权限）',
    `height_min`     SMALLINT NOT NULL DEFAULT 0 COMMENT '对方身高下限 cm：0 不限',
    `height_max`     SMALLINT NOT NULL DEFAULT 0 COMMENT '对方身高上限 cm：0 不限',
    `target_education` TINYINT NOT NULL DEFAULT 0 COMMENT '最低学历：0 不限，其余同 im_user_profile.education 取值',
    `target_marriage`  TINYINT NOT NULL DEFAULT 0 COMMENT '婚恋状况：0 不限，其余同 im_user_profile.marriage 取值',
    `online_first`   TINYINT  NOT NULL DEFAULT 0 COMMENT '在线优先：1 把在线的排前面（在线态来自 Redis，不落库）',
    `sort_by`        VARCHAR(16) NOT NULL DEFAULT 'recommend' COMMENT '排序口径：recommend 综合 hot 热度 new 新鲜 distance 距离 active 活跃',
    `exclude_passed` TINYINT  NOT NULL DEFAULT 1 COMMENT '是否排除已跳过的人：1 排除（默认，与「不再出现」一致） 0 允许重新出现',
    `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友筛选与偏好（一人一行：性别/年龄/距离/城市/身高/学历/婚恋 + 排序口径）';

-- -------------------------------------------------------------------------------------
-- 7. im_match_exposure：曝光池（冷启动与「别把同一个人推 20 遍」）
--
--    与 im_match_like 分开存：喜欢/跳过是<b>用户表态</b>，曝光是<b>系统行为</b>，
--    混在一张表里就没法区分「推了 5 次没表态」与「推了 5 次划走 5 次」，而这两种人在
--    推荐流里该得到的待遇相反（前者继续推、后者降权）。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_match_exposure`
(
    `id`              BIGINT   NOT NULL COMMENT '主键',
    `user_id`         BIGINT   NOT NULL COMMENT '被推送一方（看的人）',
    `candidate_id`    BIGINT   NOT NULL COMMENT '被推的人（候选）',
    `expose_count`    INT      NOT NULL DEFAULT 1 COMMENT '累计曝光次数（同一次会话内重复出现只加一次，靠前端去重）',
    `last_expose_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次曝光时间（超过 7 天允许重新进池，冷启动补量就靠这条）',
    `create_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次曝光时间',
    `update_time`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_candidate` (`user_id`, `candidate_id`),
    KEY `idx_user_last` (`user_id`, `last_expose_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友曝光记录（系统行为，与用户表态分开；推荐流按它去重与做时间衰减）';

-- -------------------------------------------------------------------------------------
-- 8. im_profile_visit：访客记录「谁看过我」
--
--    一人一天一行（visit_date 进唯一键）而不是每次访问一行：访客列表要的是「谁来过」，
--    一天刷二十次就出二十条同名记录既没用又撑爆表；times 累加保留热度信息。
--    这张表是后续会员权益（「看完整访客列表」）的变现点，所以字段现在就按不可少的设计：
--    view_type 记下他看了资料卡还是照片墙，权益拆分时不用再改表。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_profile_visit`
(
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `visitor_id`  BIGINT   NOT NULL COMMENT '访问者用户 ID',
    `visited_id`  BIGINT   NOT NULL COMMENT '被访问用户 ID（访客列表按它查）',
    `visit_date`  DATE     NOT NULL COMMENT '访问日期（服务端按当天写入，与 UK 一起把「一天多次」压成一行）',
    `view_type`   TINYINT  NOT NULL DEFAULT 1 COMMENT '看的什么：1 资料卡 2 照片墙 3 动态列表 4 匹配卡片（取最近一次的类型）',
    `times`       INT      NOT NULL DEFAULT 1 COMMENT '当天访问次数',
    `first_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '当天首次访问时间',
    `last_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '当天最近访问时间（列表按它倒序）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_visitor_visited_date` (`visitor_id`, `visited_id`, `visit_date`),
    KEY `idx_visited_last` (`visited_id`, `last_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='资料卡访客记录（一人一天一行，「谁看过我」与后续会员权益的数据源）';

-- -------------------------------------------------------------------------------------
-- 9. im_intimacy：亲密度与火花（streak）
--
--    同样 a < b（与 im_match_pair 同一约定）。火花按<b>自然日</b>算：last_chat_date 存日期，
--    今天与昨天差 1 天则 streak+1、差 >1 天则清零重起——存时间戳的话「昨天 23:50 与今天
--    00:10」会被算成隔一天，火花在跨零点必断，这是这类功能最常见的投诉来源。
--    更新走消息链路（发消息时顺手 upsert），不做定时扫描：没有「过期」概念，只有「断没断」。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_intimacy`
(
    `id`             BIGINT   NOT NULL COMMENT '主键',
    `user_a_id`      BIGINT   NOT NULL COMMENT '较小的一方用户 ID（服务层排序写入，与 im_match_pair 同一约定）',
    `user_b_id`      BIGINT   NOT NULL COMMENT '较大的一方用户 ID',
    `score`          INT      NOT NULL DEFAULT 0 COMMENT '亲密度值：聊天/互赞/评论/送礼累计，上限与档位由服务层常量定义',
    `streak_days`    INT      NOT NULL DEFAULT 0 COMMENT '当前连续聊天天数（火花）：按自然日算，中断即归零',
    `max_streak`     INT      NOT NULL DEFAULT 0 COMMENT '历史最高连续天数（中断后仍显示「曾经 30 天」，比只显当前值更留人）',
    `last_chat_date` DATE              DEFAULT NULL COMMENT '最近一次互聊的自然日（判定 +1 还是清零的唯一依据）',
    `msg_count`      INT      NOT NULL DEFAULT 0 COMMENT '累计消息条数（双向合计）',
    `interact_count` INT      NOT NULL DEFAULT 0 COMMENT '累计互动数（动态互赞/互评/送礼/连麦等，一次互动加一）',
    `create_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pair` (`user_a_id`, `user_b_id`),
    KEY `idx_a_score` (`user_a_id`, `score`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='亲密度与火花（一行一对人，按自然日算 streak；由消息链路顺手 upsert）';

-- -------------------------------------------------------------------------------------
-- 10. im_moment + 三张附属表：短时动态（朋友圈式 Feed）
--
--     与 im_article 的分工：文章是长内容、走审核状态机、进文章板块信息流；动态是
--     社交信号，可带图、可点赞评论、有可见范围，只在好友与匹配对象之间流转。
--     visibility 存<b>单条</b>而「三天可见」存 profile：前者是发这条时的决定，
--     后者是对全部历史动态的口径，两者混成一个字段就会出现「改了偏好把三年前动态也藏了」。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_moment`
(
    `id`            BIGINT        NOT NULL COMMENT '动态 ID（雪花）',
    `user_id`       BIGINT        NOT NULL COMMENT '发布用户 ID',
    `content`       VARCHAR(1024)          DEFAULT NULL COMMENT '动态正文（纯文本，上限 500 字，服务端按字符数校验；可为空即纯图动态）',
    `visibility`    TINYINT       NOT NULL DEFAULT 1 COMMENT '可见范围：1 公开（好友与匹配对象可见） 2 仅好友 3 部分人可见（读 im_moment_visible） 4 仅自己',
    `media_count`   INT           NOT NULL DEFAULT 0 COMMENT '媒体条数（冗余，卡片决定画一格还是九宫格）',
    `like_count`    INT           NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，写时增减，与 im_moment_like 行数最终一致）',
    `comment_count` INT           NOT NULL DEFAULT 0 COMMENT '评论数（冗余计数，含一级与子回复）',
    `view_count`    INT           NOT NULL DEFAULT 0 COMMENT '浏览量（详情页每次打开 +1，非去重）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架（后台或本人下架，行保留）',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间（Feed 排序按它，三天可见也按它算）',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_status_time` (`user_id`, `status`, `create_time`),
    KEY `idx_status_time` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友动态（短时朋友圈内容，一行一条，正文 + 九宫格 + 可见范围）';

CREATE TABLE `im_moment_media`
(
    `id`          BIGINT        NOT NULL COMMENT '媒体 ID（雪花）',
    `moment_id`   BIGINT        NOT NULL COMMENT '所属动态 ID',
    `file_id`     BIGINT        NOT NULL COMMENT '文件 ID（im_file.id，bizType=moment_image / moment_video）',
    `url`         VARCHAR(1024) NOT NULL COMMENT '受控访问地址（服务端现拼）',
    `type`        TINYINT       NOT NULL DEFAULT 1 COMMENT '类型：1 图片 2 视频',
    `sort`        INT           NOT NULL DEFAULT 0 COMMENT '九宫格内顺序（升序，首图兼作列表卡片缩略图）',
    `width`       INT           NOT NULL DEFAULT 0 COMMENT '像素宽',
    `height`      INT           NOT NULL DEFAULT 0 COMMENT '像素高',
    `duration`    INT           NOT NULL DEFAULT 0 COMMENT '视频时长秒（图片为 0）',
    `size`        BIGINT        NOT NULL DEFAULT 0 COMMENT '字节数',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除（随动态删除一起置 1）',
    PRIMARY KEY (`id`),
    KEY `idx_moment_sort` (`moment_id`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='动态附图/附视频（一行一个媒体，最多 9 图或 1 视频，上限由服务层校验）';

CREATE TABLE `im_moment_visible`
(
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `moment_id`   BIGINT   NOT NULL COMMENT '动态 ID（visibility=3 时才有行）',
    `user_id`     BIGINT   NOT NULL COMMENT '被允许看到这条动态的用户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_moment_user` (`moment_id`, `user_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='动态「部分人可见」白名单（一行一个人；改可见范围时整批重写）';

CREATE TABLE `im_moment_like`
(
    `id`          BIGINT   NOT NULL COMMENT '主键',
    `moment_id`   BIGINT   NOT NULL COMMENT '动态 ID',
    `user_id`     BIGINT   NOT NULL COMMENT '点赞用户 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 取消点赞删行以释放唯一键，同一人才能再点赞（与 im_friend 同一口径）
    UNIQUE KEY `uk_moment_user` (`moment_id`, `user_id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='动态点赞（一行一次，取消即删；idx_user_time 查「我赞过哪些」）';

CREATE TABLE `im_moment_comment`
(
    `id`             BIGINT       NOT NULL COMMENT '评论 ID（雪花）',
    `moment_id`      BIGINT       NOT NULL COMMENT '所属动态 ID',
    `user_id`        BIGINT       NOT NULL COMMENT '评论人 ID',
    `parent_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '父评论 ID：0 一级评论，非 0 为某条一级评论下的回复（两层封顶，不做无限嵌套）',
    `reply_user_id`  BIGINT                DEFAULT NULL COMMENT '被回复人 ID（只有 parent_id 非 0 时有值，前端显「回复 xxx」）',
    `content`        VARCHAR(500) NOT NULL COMMENT '评论内容（服务端按字符数校验）',
    `like_count`     INT          NOT NULL DEFAULT 0 COMMENT '评论点赞数（评论点赞本身不建表，交友场景里它带来的写入远大于价值）',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_moment_status_time` (`moment_id`, `status`, `create_time`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='动态评论（两层结构：一级 + 回复，靠 parent_id 串起来）';

-- -------------------------------------------------------------------------------------
-- 11. im_icebreaker：打招呼模板 / 话题卡 / 标签开场白字典（后台可维护）
--
--     三种文案同表存、靠 type 区分：它们都是「服务端下发给前端的现成话术」，
--     读写路径完全一样（按 type 取启用行、按 sort 排），为三种文案开三张表是自找三倍代码。
--     tag_id 非 0 表示这条只在「双方共有该标签」时出现——这是「基于对方标签的开场白建议」
--     的数据侧；AI 生成的那一路走 im-ai，不落表（生成结果缓存归 Redis）。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_icebreaker`
(
    `id`          BIGINT       NOT NULL COMMENT '文案 ID',
    `type`        TINYINT      NOT NULL DEFAULT 1 COMMENT '类型：1 打招呼模板 2 话题卡 3 标签定制开场白',
    `tag_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '绑定兴趣标签 ID：0 通用，非 0 只在双方共有该标签时出现（type=3 基本都非 0）',
    `content`     VARCHAR(255) NOT NULL COMMENT '文案内容（{nickname}/{city}/{tag} 三个占位符由前端或服务端替换）',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序（升序；同 type 内）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 停用',
    `use_count`   INT          NOT NULL DEFAULT 0 COMMENT '被点击使用次数（运营据此淘汰没人用的文案）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_type_status_sort` (`type`, `status`, `sort`),
    KEY `idx_tag` (`tag_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友破冰文案字典（打招呼模板 / 话题卡 / 按标签的开场白，后台维护）';

INSERT INTO `im_tag` (`id`, `name`, `category`, `sort`, `status`) VALUES
(1, '健身', '运动', 1, 1), (2, '跑步', '运动', 2, 1), (3, '骑行', '运动', 3, 1),
(4, '游泳', '运动', 4, 1), (5, '篮球', '运动', 5, 1), (6, '羽毛球', '运动', 6, 1),
(7, '滑雪', '运动', 7, 1), (8, '露营', '运动', 8, 1), (9, '徒步', '运动', 9, 1),
(10, '瑜伽', '运动', 10, 1),
(11, '烹饪', '美食', 11, 1), (12, '咖啡', '美食', 12, 1), (13, '烘焙', '美食', 13, 1),
(14, '探店', '美食', 14, 1), (15, '美酒', '美食', 15, 1), (16, '火锅', '美食', 16, 1),
(17, '电影', '影视音乐', 17, 1), (18, '追剧', '影视音乐', 18, 1), (19, '演唱会', '影视音乐', 19, 1),
(20, '民谣', '影视音乐', 20, 1), (21, '摇滚', '影视音乐', 21, 1), (22, '说唱', '影视音乐', 22, 1),
(23, '吉他', '影视音乐', 23, 1), (24, '钢琴', '影视音乐', 24, 1),
(25, '读书', '阅读', 25, 1), (26, '历史', '阅读', 26, 1), (27, '心理学', '阅读', 27, 1),
(28, '编程', '阅读', 28, 1), (29, '外语', '阅读', 29, 1), (30, '金融投资', '阅读', 30, 1),
(31, '自驾', '旅行', 31, 1), (32, '出境游', '旅行', 32, 1), (33, '摄影', '旅行', 33, 1),
(34, '民宿', '旅行', 34, 1), (35, '潜水', '旅行', 35, 1),
(36, '穿搭', '生活', 36, 1), (37, '美妆', '生活', 37, 1), (38, '家居', '生活', 38, 1),
(39, '宠物', '生活', 39, 1), (40, '手工', '生活', 40, 1), (41, '茶道', '生活', 41, 1),
(42, '游戏', '游戏', 42, 1), (43, '电竞', '游戏', 43, 1), (44, '二次元', '游戏', 44, 1),
(45, '桌游', '社交', 45, 1), (46, '剧本杀', '社交', 46, 1), (47, '密室逃脱', '社交', 47, 1),
(48, '志愿者', '社交', 48, 1);

INSERT INTO `im_icebreaker` (`id`, `type`, `tag_id`, `content`, `sort`, `status`) VALUES
(1, 1, 0, '你好呀，{nickname}，看到你在{city}，想认识一下', 1, 1),
(2, 1, 0, '你的资料卡里有一处让我很好奇，能聊聊吗？', 2, 1),
(3, 1, 0, '嗨，我们好像有共同好友，先打个招呼', 3, 1),
(4, 1, 0, '周末一般怎么安排？我在{city}，想找点事做', 4, 1),
(5, 1, 0, '你好，我不太会开场，但很想认识你', 5, 1),
(6, 1, 0, '{nickname}，你的笑容挺有感染力的', 6, 1),
(7, 1, 0, '刚刷到你，觉得我们的节奏可能合得来', 7, 1),
(8, 1, 0, '在{city}吗？这阵子天气不错，适合出门', 8, 1),
(9, 1, 0, '想问一句，你更爱安静聊天还是出去走走？', 9, 1),
(10, 1, 0, '你好，我是刚来这个平台的，先熟悉一下', 10, 1),
(11, 1, 0, '你的兴趣里有我熟悉的，聊两句？', 11, 1),
(12, 1, 0, '不催不赶，有空再回，先认识一下', 12, 1),
(13, 1, 0, '你好呀，今天过得怎么样？', 13, 1),
(14, 1, 0, '看到你的交友目的也是想认真聊聊，我也是', 14, 1),
(15, 1, 0, '{nickname}，你的照片拍得挺自然，在哪儿取的景？', 15, 1),
(16, 1, 0, '我在这儿没什么熟人，你要不要当第一个', 16, 1),
(17, 1, 0, '你好，先自我介绍：我在{city}，做的工作比较普通', 17, 1),
(18, 1, 0, '刷到你几次了，打个招呼不算唐突吧', 18, 1),
(19, 1, 0, '如果只能问一个问题，我想问你最近在读什么', 19, 1),
(20, 1, 0, '不聊条件，先聊点轻松的？', 20, 1),
(21, 2, 0, '最近有没有什么让你特别期待的事？', 21, 1),
(22, 2, 0, '你理想中的一个周末是怎么度过的？', 22, 1),
(23, 2, 0, '有没有一家你反复去的店？', 23, 1),
(24, 2, 0, '你上一次旅行去了哪儿，最记得什么？', 24, 1),
(25, 2, 0, '工作之外，你把时间花在哪里？', 25, 1),
(26, 2, 0, '有没有什么技能是你一直想学的？', 26, 1),
(27, 2, 0, '你更相信感觉还是更相信聊得来的判断？', 27, 1),
(28, 2, 0, '最近循环的一首歌是什么？', 28, 1),
(29, 2, 0, '如果明天完全空闲，你会做什么？', 29, 1),
(30, 2, 0, '你觉得自己身上最像家人的一点是什么？', 30, 1),
(31, 2, 0, '有没有一本书或一部片子改变了你的想法？', 31, 1),
(32, 2, 0, '你平时怎么解压？', 32, 1),
(33, 2, 0, '在{city}生活，你觉得最舒服和最麻烦的分别是什么？', 33, 1),
(34, 2, 0, '你会为了一件小事专门跑一趟吗？', 34, 1),
(35, 2, 0, '你养过宠物吗，或者想养什么？', 35, 1),
(36, 2, 0, '最近学会的一道菜是什么？', 36, 1),
(37, 2, 0, '你更喜欢热闹还是两个人安静待着？', 37, 1),
(38, 2, 0, '有没有什么爱好是你坚持了三年以上的？', 38, 1),
(39, 2, 0, '你最近一次笑出来是因为什么？', 39, 1),
(40, 2, 0, '如果聊完觉得合得来，你更接受吃饭还是散步？', 40, 1),
(41, 3, 1, '你也练健身？我最近卡在深蹲重量上，怎么突破的？', 41, 1),
(42, 3, 2, '跑步党握个手，你在{city}有推荐的路线吗？', 42, 1),
(43, 3, 11, '会做饭的人都很靠谱。你的拿手菜是什么？', 43, 1),
(44, 3, 12, '咖啡爱好者？我正想找家不吵的咖啡馆办公，有推荐吗', 44, 1),
(45, 3, 17, '最近看了什么片？我需要一份不踩雷的片单', 45, 1),
(46, 3, 25, '你也在读书，最近在读哪本？我想换换口味', 46, 1),
(47, 3, 28, '同行打招呼：最近在啃什么技术，我帮你排雷', 47, 1),
(48, 3, 31, '自驾党，你在{city}周边有没有值得跑一趟的地方？', 48, 1),
(49, 3, 33, '摄影的同好，你平时拍人像还是拍风景？', 49, 1),
(50, 3, 39, '有宠物的人聊得来，你家那位什么脾气？', 50, 1),
(51, 3, 42, '游戏搭子难找，你常玩哪个时间段？', 51, 1),
(52, 3, 44, '二次元的同好，最近有在追什么吗？', 52, 1),
(53, 3, 45, '桌游局缺人，你常去哪家店？', 53, 1),
(54, 3, 46, '剧本杀玩家，你偏情感本还是硬核本？', 54, 1),
(55, 3, 10, '练瑜伽的人体态都不差，你是从什么时候开始的？', 55, 1);

-- =====================================================================================
--  二十、登录设备与账号安全流水（im-user）
--
--  本节与 sql/versions/V1.0.9__login_device.sql 逐字同构：这里靠文件头的 DROP 块保证幂等，
--  那里靠 CREATE TABLE IF NOT EXISTS（存量库走后台「SQL 版本」页应用它，不需要重跑本文件）。
--
--  历史上请求字段 deviceId 装的一直是设备类型码，后端因此分不清「同一个 android 是几台手机」，
--  同端顶号根本没法实现（当年 kickSameDevice 被删就是因为它）。这两张表把「设备位 = 类型:实例」
--  落进库，同时接管 Sa-Token 终端列表存不下的那些展示字段（设备名 / IP / UA / 累计次数）。
--  只记 IP 不记城市：全仓没有离线 GeoIP 库，不能凭空编一个地名给用户看。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. im_user_device：登录设备登记（一台登录实例一行，多端策略与「下线其他设备」的数据来源）
--
--    为什么不能只靠 Sa-Token 的终端列表：它的 SaTerminalInfo 只有 deviceType / deviceId /
--    createTime / tokenValue，没有设备名、IP、UA，而且会话一过期这批信息就随 Redis 键一起没了
--    ——「上次登录：iPhone 15，192.168.1.20」这种要留几个月的展示字段，必须落在库里。
--    device_id 用 NOT NULL DEFAULT '' 而不是 NULL：MySQL 唯一索引里 NULL 与 NULL 不算重复，
--    写成可空的话 uk_user_device 对老客户端（不带实例 ID）完全失效。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_user_device`
(
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花）',
    `user_id`          BIGINT       NOT NULL COMMENT '所属用户 ID（= im_user.id）',
    `device_type`      VARCHAR(16)  NOT NULL COMMENT '设备类型码：web 浏览器 / pc 桌面端 / android 安卓 / ios iOS / mini 小程序',
    `device_id`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '安装实例 ID（客户端首启生成的 UUID，卸载重装会变）；空串=老客户端只上送了类型码，这类行不参与顶号',
    `device_name`      VARCHAR(50)           DEFAULT NULL COMMENT '客户端自报的友好名（如「张三的 Pixel 8」），仅展示与踢下线提示，不参与任何判定',
    `ip`               VARCHAR(64)           DEFAULT NULL COMMENT '最近一次登录的客户端 IP（IPv6 最长 45 字符，留 64）；未接归属地库，故只到 IP',
    `user_agent`       VARCHAR(255)          DEFAULT NULL COMMENT '最近一次登录的 User-Agent（已洗成单行并截断），device_name 缺失时靠它兜底推设备名',
    `login_count`      INT          NOT NULL DEFAULT 1 COMMENT '该实例累计登录成功次数（同一实例重复登录只更新本行，不新增行）',
    `last_login_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近登录时间',
    `last_logout_time` DATETIME              DEFAULT NULL COMMENT '最近下线时间（主动注销 / 被同端顶号 / 被后台踢 / 撤销设备时写入）',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 在用 0 已撤销（撤销只注销既有会话，凭密码重新登录会自动回到 1）',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该实例首次登记时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 一个账号在同一台机器上只有一行：类型 + 实例唯一确定一台设备，重复登录走 UPDATE
    UNIQUE KEY `uk_user_device` (`user_id`, `device_type`, `device_id`),
    -- 「我的登录设备」列表按最近登录倒序取一页（上限 50 行，见 im.account.device-list-max）
    KEY `idx_user_last` (`user_id`, `last_login_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='登录设备登记表（一台登录实例一行：多端登录策略、被踢提示、设备管理页的数据来源）';

-- -------------------------------------------------------------------------------------
-- 2. im_login_log：登录与设备变动流水（账号安全留痕）
--
--    与设备表分表的意义：设备表答「现在有哪些设备在用」（状态表，一行一台），
--    流水表答「谁在什么时候从哪个 IP 进来过、被谁挤掉了」（事件表，只增）。
--    出盗号投诉时要的是后者，而状态表里的 last_login_time 只有一个，历史全被覆盖掉了。
--    继承 AuditEntity 口径：不启 deleted 列，超保留期由定时任务物理删除。
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_login_log`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花，兼时间排序键）',
    `user_id`     BIGINT       NOT NULL COMMENT '用户 ID（= im_user.id）',
    `event`       TINYINT      NOT NULL COMMENT '事件：1 登录成功 2 登录失败 3 主动注销 4 同端顶下线 5 被后台踢下线 6 设备被撤销 7 改绑凭证导致全端下线',
    `device_type` VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '设备类型码；登录失败（账号都不存在）时可能为空串',
    `device_id`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '安装实例 ID；老客户端为空串',
    `device_name` VARCHAR(50)           DEFAULT NULL COMMENT '设备名（上送的友好名，UA 兜底），仅展示',
    `ip`          VARCHAR(64)           DEFAULT NULL COMMENT '客户端 IP（未接归属地库，只到 IP）',
    `user_agent`  VARCHAR(255)          DEFAULT NULL COMMENT 'User-Agent（洗成单行并截断）',
    `detail`      VARCHAR(255)          DEFAULT NULL COMMENT '补充说明：失败原因 / 顶掉它的那台设备名 / 操作来源（如 bind-phone）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（流水不回改，列仅为与 AuditEntity 对齐）',
    PRIMARY KEY (`id`),
    -- 「我的登录记录」按用户过滤后天然按主键倒序取一页
    KEY `idx_user_time` (`user_id`, `id`),
    -- 保留期清理任务按天删除（im.account.login-log-keep-days）
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='登录与设备变动流水（账号安全留痕，只 INSERT 不更新；超保留期由定时任务物理删除）';
