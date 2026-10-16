-- =====================================================================================
--  增量迁移：直播连麦 + 礼物/虚拟积分钱包（D 组第二项）
--
--  用途：给「已经跑过完整库、只想补新功能表」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本脚本内容已并入那两份）。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。执行前建议先备份：
--      mysqldump -u "$env:MYSQL_USER" -p im_db > im_db_bak.sql
--    回滚：这 4 张表是纯新增，删表即回滚，不影响既有数据：
--      DROP TABLE IF EXISTS `im_gift_record`; DROP TABLE IF EXISTS `im_wallet_transaction`;
--      DROP TABLE IF EXISTS `im_gift`;        DROP TABLE IF EXISTS `im_user_wallet`;
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_linkmic_gift.sql"
-- =====================================================================================

USE `im_db`;

-- 幂等：重复执行本脚本先删旧的再建（这 4 张表都是本迁移专属，不存在运行期历史需要保留时才用 DROP；
-- 若线上已有送礼流水需要保留，请把下面 4 行 DROP 注释掉再跑 CREATE）。
DROP TABLE IF EXISTS `im_gift_record`;
DROP TABLE IF EXISTS `im_wallet_transaction`;
DROP TABLE IF EXISTS `im_gift`;
DROP TABLE IF EXISTS `im_user_wallet`;

-- -------------------------------------------------------------------------------------
-- 1. 用户钱包：一行一个用户，主键即 user_id（与 im_user.id 同值，非雪花）
-- -------------------------------------------------------------------------------------
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

-- -------------------------------------------------------------------------------------
-- 2. 礼物目录：运营可增删改，前端礼簿按 status=1 且 sort 升序渲染
-- -------------------------------------------------------------------------------------
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

-- -------------------------------------------------------------------------------------
-- 3. 钱包账本：充值/送礼/签到/后台调整逐条流水（只增，供对账与明细页）
-- -------------------------------------------------------------------------------------
CREATE TABLE `im_wallet_transaction`
(
    `id`            BIGINT      NOT NULL COMMENT '流水 ID（雪花）',
    `user_id`       BIGINT      NOT NULL COMMENT '钱包归属用户 ID',
    `type`          VARCHAR(16) NOT NULL COMMENT '类型：recharge 充值 / gift 送礼 / checkin 签到 / adjust 后台调整',
    `amount`        BIGINT      NOT NULL COMMENT '变动额（有符号，支出为负）',
    `balance_after` BIGINT      NOT NULL COMMENT '交易后余额快照',
    `ref_id`        BIGINT               DEFAULT NULL COMMENT '关联业务 ID（如送礼记录 ID / 房间 ID）',
    `remark`        VARCHAR(128)         DEFAULT NULL COMMENT '备注',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='钱包账本流水（只增不改）';

-- -------------------------------------------------------------------------------------
-- 4. 送礼流水：一行一笔送礼，冗余礼物名与成交单价，礼物后续下架/改价也不影响历史展示
-- -------------------------------------------------------------------------------------
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

-- -------------------------------------------------------------------------------------
-- 5. 礼物目录种子数据（ID 段 5000~5999，落在 im_data.sql 约定的种子区间内）
-- -------------------------------------------------------------------------------------
INSERT INTO `im_gift` (`id`, `name`, `icon`, `price`, `kind`, `sort`, `status`)
VALUES (5001, '小心心', '❤️', 1, 'normal', 1, 1),
       (5002, '棒棒糖', '🍭', 10, 'normal', 2, 1),
       (5003, '大啤酒', '🍺', 52, 'normal', 3, 1),
       (5004, '加油鸭', '🦆', 66, 'normal', 4, 1),
       (5005, '玫瑰花', '🌹', 99, 'normal', 5, 1),
       (5006, '烟花', '🎆', 520, 'effect', 6, 1),
       (5007, '火箭', '🚀', 1314, 'effect', 7, 1),
       (5008, '星球', '🪐', 5200, 'effect', 8, 1);

-- -------------------------------------------------------------------------------------
-- 6. 演示用户钱包（alice/bob 各 1000 积分便于体验送礼；admin 10000）。
--    仅种子 ID 段，运行期真实用户首次访问钱包时由服务端自动建 0 余额行。
-- -------------------------------------------------------------------------------------
INSERT INTO `im_user_wallet` (`user_id`, `balance`, `total_recharged`)
VALUES (1, 10000, 10000),
       (1001, 1000, 1000),
       (1002, 1000, 1000);
