-- =====================================================================================
--  IM 即时通讯系统 —— 建库建表脚本
--  适用：MySQL 8.0+（InnoDB / utf8mb4）
--
--  执行方式（凭据从环境变量读取，脚本内不含任何明文密码）：
--    mysql -u "$env:MYSQL_USER" -p < sql/im_schema.sql
--    或在客户端中直接 source 本文件
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
DROP TABLE IF EXISTS `im_file`;
DROP TABLE IF EXISTS `im_group_member`;
DROP TABLE IF EXISTS `im_group`;
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
    `last_login_time` DATETIME              DEFAULT NULL COMMENT '最近登录时间',
    `last_login_ip`   VARCHAR(64)           DEFAULT NULL COMMENT '最近登录 IP',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_phone` (`phone`),
    KEY `idx_nickname` (`nickname`)
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
    `last_ack_seq`    BIGINT   NOT NULL DEFAULT 0 COMMENT '已确认读到的消息 seq，用于计算离线消息',
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
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息送达与已读回执表';

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
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 已解散',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_owner` (`owner_id`)
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

-- =====================================================================================
--  六、文件（im-file）
-- =====================================================================================

CREATE TABLE `im_file`
(
    `id`            BIGINT       NOT NULL COMMENT '文件 ID',
    `uploader_id`   BIGINT       NOT NULL COMMENT '上传者用户 ID',
    `biz_type`      VARCHAR(32)  NOT NULL COMMENT '业务类型：avatar / chat_image / chat_file / chat_voice',
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
