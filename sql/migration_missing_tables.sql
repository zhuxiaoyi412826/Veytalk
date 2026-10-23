-- =====================================================================================
--  存量库升级补丁：补齐 im_schema.sql 里有、而本地库里缺失的表与列
--
--  为什么是一份汇总补丁而不是逐功能脚本：表情回应 / 投票 / 群精华 / 入群审批 / 群机器人
--  这几项当初只更新了全量的 sql/im_schema.sql，没留增量脚本（收藏与举报留了，见
--  migration_message_favorite.sql / migration_report.sql），而全量那份开头是一串
--  DROP TABLE IF EXISTS —— 存量库跑它等于清库重建。所以这里做一次对账，把欠的一次补齐。
--
--  适用场景：数据库是在早期版本上建的（跑过 im_schema.sql），之后陆续加的功能带来的
--  新表/新列没有跟着 ALTER，于是这些功能一用就报 1007「系统繁忙，请稍后重试」。
--
--  实测本机 im_db 的缺口（用 information_schema 与 im_schema.sql 逐列逐索引比对得出）：
--    缺表 7 张：im_message_reaction / im_message_vote / im_message_essence /
--               im_message_favorite / im_group_join_request / im_group_robot / im_report
--    缺列 2 个 + 缺索引 1 个：im_group.invite_code、im_group.join_approval、uk_invite_code
--
--  受影响的功能（不止表情回应）：
--    表情回应、投票、群精华、消息收藏 —— 读写都直接报错；
--    ★ 历史消息分页 /api/message/history 也是坏的：它在 toViewList 末尾要聚合表情回应
--      （attachReactions），表不存在 → 整页查询抛异常。界面上还能看到消息，是因为前端
--      渲染的是本地缓存（IndexedDB / SQLite），把服务端的失败遮住了。
--    群列表 / 群详情 —— MyBatis-Plus 按实体列生成 SELECT 清单，带上 invite_code 即
--      Unknown column 'invite_code'，所有群查询一起失败。
--
--  怎么跑（写操作请由你本人执行）：
--    1) 先备份：mysqldump -u<user> -p --databases im_db > im_db_backup.sql
--    2) 执行本文件（PowerShell 用 -e "source"，别用管道，否则中文注释会被刷成 ?）：
--       mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_missing_tables.sql"
--       （或粘进客户端整段运行；本文件只做 CREATE/ALTER，不含 DROP，跑完不会掉数据）
--    ★ 跑完本补丁后，就不要再跑 migration_message_favorite.sql 与 migration_report.sql 了：
--      那两个是「DROP 再 CREATE」的写法（当时表还不存在所以安全），现在表里可能有数据了，
--      再跑一次等于清空收藏与举报。
--    3) 回滚说明：新增的都是空表与两个可空/有默认值的列，不改任何已有行；
--       真要回退就 DROP 掉对应的表、ALTER TABLE im_group DROP COLUMN 那两列即可。
--    4) 建表语句都带 IF NOT EXISTS，重复执行只会提示 already exists，不会覆盖数据；
--       但 im_group 那两条 ALTER（共三个对象：两列一索引）重复跑会报 Duplicate column name / Duplicate key name，
--       属正常 —— 说明已经加过了，忽略即可（或先跑下面「前置自查」确认）。
--
--  前置自查（只读，看看到底缺不缺，决定要不要跑 ALTER 段）：
--    SELECT TABLE_NAME FROM information_schema.TABLES
--     WHERE TABLE_SCHEMA='im_db'
--       AND TABLE_NAME IN ('im_message_reaction','im_message_vote','im_message_essence',
--                          'im_message_favorite','im_group_join_request','im_group_robot','im_report');
--    SELECT COLUMN_NAME FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA='im_db' AND TABLE_NAME='im_group'
--       AND COLUMN_NAME IN ('invite_code','join_approval');
--    两条查回来的行数少于 7 / 少于 2，就是缺，需要跑本文件。
--
--  跑完的验收：
--    SELECT COUNT(*) FROM im_db.im_message_reaction;          -- 能查通（0 行）即表已就位
--    GET /api/message/history 不再回 {code:1007}（可直接跑 tmp/repro.ps1，它把登录→会话→历史→回应四步都打一遍）。
--    ★ 不需要重启后端：表名是每条 SQL 下发时才在 MySQL 侧解析的，连接池与 MyBatis 都不缓存表结构，
--      不会出现「上一条还报 Table doesn't exist、下一条查通了但后端不认识」的情况。若界面还是老报错，
--      是前端本地缓存（IndexedDB / SQLite）没失效，刷新页签即可。
--    鼠标移到任意一条消息上 → 气泡下方浮出表情 → 点一个，气泡下方应出现「👍 1」的小胶囊，
--    对方和自己的工作台端同步出现；再点一次取消。
--
--  交付前已核过的两件事：
--    ① 上面的「前置自查」由助手代跑（只读），实测 im_db 里这 7 张表一张都没有、im_group 的两列也缺，
--       与 error.log 里 36 次 Table doesn't exist + 15 次 Unknown column 'invite_code' 完全对得上；
--    ② 本文件的 7 段 CREATE 与 sql/im_schema.sql 逐列逐索引、连表选项与注释都比对一致，
--       差别只有刻意加的 IF NOT EXISTS；文中出现的 DROP 全在注释里，可执行语句仅 CREATE / ALTER。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
--  一、im_group 补两列一索引（入群审批 + 邀请链接/二维码）
--      UNIQUE 允许多个 NULL，所以存量群在没生成邀请码前都留 NULL，不冲突。
-- -------------------------------------------------------------------------------------
ALTER TABLE `im_group`
    ADD COLUMN `invite_code` VARCHAR(32) DEFAULT NULL COMMENT '群邀请码（邀请链接 / 二维码使用，懒生成）' AFTER `mute_all`,
    ADD COLUMN `join_approval` TINYINT NOT NULL DEFAULT 0 COMMENT '入群是否需要审批：1 需要（邀请链接与二维码入口提交后进入待审批），0 不需要（扫码链接直接入群）' AFTER `invite_code`;

ALTER TABLE `im_group`
    ADD UNIQUE KEY `uk_invite_code` (`invite_code`);

-- -------------------------------------------------------------------------------------
--  二、消息维度的四张标记附属表
--      为什么各自建表而不是塞进 im_message.extra 的 JSON：都要按 (message_id, user_id)
--      去重计数并按会话/消息反查聚合，JSON 列承担不了高效查询。
--      唯一键的维度差别是刻意的：精华一群一行（uk_message），收藏一人一行
--      （uk_user_message）—— 同一条消息可被多人各自收藏，A 取消不能伤到 B。
-- -------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `im_message_reaction`
(
    `id`          BIGINT      NOT NULL COMMENT '主键（雪花）',
    `message_id`  BIGINT      NOT NULL COMMENT '被回应的消息 ID',
    `user_id`     BIGINT      NOT NULL COMMENT '回应者 ID',
    `emoji`       VARCHAR(32) NOT NULL COMMENT '表情字符（emoji，如点赞/红心/笑脸）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回应时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_user_emoji` (`message_id`, `user_id`, `emoji`),
    KEY `idx_message` (`message_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息表情回应表（一人对一条消息的同一表情只计一次，再点为取消）';

CREATE TABLE IF NOT EXISTS `im_message_vote`
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

CREATE TABLE IF NOT EXISTS `im_message_essence`
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

CREATE TABLE IF NOT EXISTS `im_message_favorite`
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

-- -------------------------------------------------------------------------------------
--  三、入群审批与群机器人
-- -------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `im_group_join_request`
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

CREATE TABLE IF NOT EXISTS `im_group_robot`
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

-- -------------------------------------------------------------------------------------
--  四、举报工单（管理后台的待处理队列）
--      必须建表而不是进 Redis：举报是用来追责的持久记录，且「按状态取待处理」与
--      「按举报人查自己的记录」两种访问都要索引。
-- -------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `im_report`
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

-- -------------------------------------------------------------------------------------
--  后置自查（只读）：应回 7 行
-- -------------------------------------------------------------------------------------
SELECT TABLE_NAME AS 已就位的表
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'im_db'
  AND TABLE_NAME IN ('im_message_reaction', 'im_message_vote', 'im_message_essence',
                     'im_message_favorite', 'im_group_join_request', 'im_group_robot', 'im_report')
ORDER BY TABLE_NAME;
