-- =====================================================================================
--  V1.0.9：登录设备表 im_user_device + 登录流水表 im_login_log（多端登录与设备管理的地基）
--
--  ★增加：2 张新表
--         im_user_device（一台登录实例一行，`uk_user_device` 唯一键）
--         im_login_log  （登录与设备变动流水，只 INSERT，按保留天数物理清理）
--  ★修改：无（不动任何既有表的列与索引）
--  ★清除：无（不删数据、不动种子）
--
--  口径：
--   · 这两张表只为解决一件历史遗留事：请求字段 deviceId 长期以来装的是<b>设备类型码</b>
--     （web / pc / android / ios / mini），后端因此分不清「同一个 android 是几台手机」。
--     所以现在的 deviceId 升级为「设备位 = 类型:实例」（见 com.im.common.util.DeviceIds），
--     库里把它拆成 device_type + device_id 两列入库，方便「只看手机端」这类筛选。
--   · device_id 用 NOT NULL DEFAULT '' 而不是 NULL：MySQL 的唯一索引里 NULL 与 NULL 不算重复，
--     写成可空的话 `uk_user_device` 对老客户端（不带实例 ID）完全失效，同一台浏览器能堆进
--     任意多行；空串则是「一个类型一行」的正确语义。
--   · 老客户端（已发布的 APK / 小程序）只上送裸类型码，落库就是 device_id=''。
--     这类行<b>一律不参与顶号</b>：拿类型码顶号等于把「同浏览器的三个标签页」互相挤下线，
--     正是当年 kickSameDevice 被删掉的原因。顶号只对带实例 ID 的客户端生效。
--   · 与 im_remote_device 无关：那张表是远程控制（屏幕投屏）的被控端注册表，
--     识别码模式下甚至不归属任何账号（user_id=0）；本表管的是「这个账号在哪些设备上登录过」。
--   · 只记 IP 不记城市：全仓没有 ip2region / GeoLite 这类离线库（实测零命中），
--     内网部署也没有外网GeoIP接口可用。宁可显示「192.168.1.20」，也不能凭空编一个城市名。
--   · status=0（已撤销）只下线既有会话，不禁止下次登录：能凭密码重新登进来就说明身份成立，
--     那时自动回到 status=1。真要拒绝登录是后台封号的职责（im_user.status），两套语义不混用。
--   · 不涉及 RBAC：设备管理是前台「设置」里用户管自己的设备，不是后台新菜单，
--     因此本版<b>不加权限点、不需要清任何 Redis 键</b>。
--   · 内容结构与 sql/im_schema.sql 第二十节逐字一致；全新库跑那两份全量脚本即可，
--     不需要本脚本。本脚本给存量库用。
--   · 回滚：U1.0.9__login_device.sql（先落两张前像备份表，再 DROP）。
--   · 幂等：全篇 CREATE TABLE IF NOT EXISTS，没有一条 ALTER / UPDATE 落在既有表上，
--     因此不需要 V1.0.6 那套 information_schema 会话变量守卫；重复应用、跑一半失败后
--     重放都安全。
--   · ★ 生产应用顺序：台账只应用「最小的待应用版本」。1.0.4 / 1.0.6 / 1.0.7 / 1.0.8 若都还没
--     在页面点过「应用」，顺序必须是 1.0.4 → 1.0.6 → 1.0.7 → 1.0.8 → 1.0.9（前四个脚本幂等，
--     对已手工 source 过的库只登记台账）。
--   · ★ 本版是「先建表、后发版」：表建好而后端 jar 还没更新时，两张表一直是空的，
--     老后端不认识它们，不会报错；反过来（先发 jar 后建表）登录会在 doLogin 回调里
--     刷「设备登记失败」的 warn（登录本身不受影响，代码包了 try-catch），但设备列表全空。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 1. im_user_device：登录设备登记（一台登录实例一行，多端策略与「下线其他设备」的数据来源）
--
--    为什么不能只靠 Sa-Token 的终端列表：它的 SaTerminalInfo 只有 deviceType / deviceId /
--    createTime / tokenValue，没有设备名、IP、UA，而且会话一过期这批信息就随 Redis 键一起没了
--    ——「上次登录：iPhone 15，广东」这种要留几个月的展示字段，必须落在库里。
--    登录流水也不塞进本表：一台设备的历史是「多行、只增、要按时间清」，与「一行、要更新」
--    混在一张表里，两边的查询和清理都会别扭。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_user_device`
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
--    与「登录设备」分表的意义：设备表答的是「现在有哪些设备在用」，是一张状态表；
--    流水表答的是「谁在什么时候从哪个 IP 进来过、被谁挤掉了」，是一张事件表。
--    出盗号投诉时要的是后者，而状态表里的 last_login_time 只有一个，历史全被覆盖掉了。
--    排序按主键倒序（雪花 ID 即时间序），与 im_moderation_log 同一口径；
--    idx_create_time 是给保留期清理任务用的（DELETE WHERE create_time < ?），不参与查询排序。
-- -------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `im_login_log`
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

-- -------------------------------------------------------------------------------------
-- 3. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面应用时语句结果会被丢弃，
--    所以这几条刻意留成注释，不进 splitStatements 的语句列表）
--
--    3.1 两张表到位：期望 2
--      SELECT COUNT(*) FROM information_schema.TABLES
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('im_user_device', 'im_login_log');
--
--    3.2 唯一键在位（顶号判定靠它保证一台机器一行，缺了就会重复插行）
--      SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
--      FROM information_schema.STATISTICS
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user_device'
--      ORDER BY INDEX_NAME, SEQ_IN_INDEX;   -- 应看到 PRIMARY / uk_user_device / idx_user_last
--
--    3.3 刚发完版应有数据（用新客户端登一次，否则本查询为空是正常的）
--      SELECT user_id, device_type, device_id, device_name, ip, login_count, last_login_time
--      FROM im_user_device ORDER BY last_login_time DESC LIMIT 20;
-- =====================================================================================
