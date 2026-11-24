-- =====================================================================================
--  V1.0.7：交友域一次建齐（资料卡扩展 / 照片墙 / 兴趣标签 / 滑动匹配 / 附近的人 /
--           筛选偏好 / 曝光池 / 访客记录 / 亲密度火花 / 动态与评论 / 破冰字典 / 三个权限点）
--
--  ★增加：16 张新表
--         im-user 侧 4 张：im_user_profile / im_user_photo / im_tag / im_user_tag
--         im-friend 侧 12 张：im_match_like / im_match_pair / im_match_preference /
--           im_match_exposure / im_profile_visit / im_intimacy / im_moment /
--           im_moment_media / im_moment_visible / im_moment_like / im_moment_comment /
--           im_icebreaker
--         种子：im_tag 48 行（id 1~48）、im_icebreaker 55 行（id 1~55）、
--               权限点 132 system:dating:manage / 133 system:moment:manage /
--               134 system:tag:manage，admin（role 1）绑定 6032~6034；
--               另为存量 im_user 回填 im_user_profile 基础行（一人一行，字段全默认）
--  ★修改：无（不动任何既有表的列与索引）
--  ★清除：无
--
--  口径：
--   · 本版<b>全部是新增表 + INSERT IGNORE</b>，没有一条 ALTER / DROP / UPDATE 落在既有表上，
--     因此不需要 information_schema 会话变量守卫（V1.0.6 那套守卫只为「跨连接 ALTER 静默跳过」
--     而存在）。CREATE TABLE IF NOT EXISTS 与 INSERT IGNORE 本身幂等，重复应用、跑一半失败
--     后重放都安全。
--   · 内容结构与 sql/im_schema.sql「十一、交友域」逐字一致，种子与 sql/im_data.sql 一致；
--     全新库跑那两份即可，不需要本脚本。本脚本给存量库用。
--   · 回滚：U1.0.7__dating_core.sql（第一步落 16 张前像备份表，再 DROP，最后摘权限点）。
--   · ★ 跑完必须清一次权限缓存：DEL im:auth:perm:* 与 im:auth:role:*（按 pattern 逐键删，
--     禁 FLUSHDB）。不清的话三个新后台页面对 admin 返回 1003（TTL 30 分钟且登录不刷新）。
--   · ★ 生产应用顺序：台账只应用「最小的待应用版本」，1.0.4 当年是手工 source 的、台账里仍是
--     待应用——必须先在页面点 1.0.4「应用」（幂等空转，只登记台账），再依次 1.0.6、1.0.7。
--   · 照片墙与动态的媒体本体仍走 im-file（bizType 新增 dating_photo / moment_image /
--     moment_video），本脚本不建文件表；im_file 里的既有行不受影响。
--   · 距离模糊化是<b>展示口径</b>而不是存储口径：lng/lat 全精度存（DECIMAL(10,6) 约 0.1 m），
--     服务端算出距离后再按「500 m 以内只显示「500 m 内」」收口，绝不把精确坐标下发给陌生人。
--   · 年龄不落冗余列，只存 birthday，服务端按当天现算——存年龄会在生日那天集体过期。
--   · 配对不写 im_friend（用户选定「直接建私聊会话，不自动加好友」），会话 ID 由
--     ConversationSpi 建好后记在 im_match_pair.conversation_id。
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
CREATE TABLE IF NOT EXISTS `im_user_profile`
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
CREATE TABLE IF NOT EXISTS `im_user_photo`
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
CREATE TABLE IF NOT EXISTS `im_tag`
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

CREATE TABLE IF NOT EXISTS `im_user_tag`
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
CREATE TABLE IF NOT EXISTS `im_match_like`
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
CREATE TABLE IF NOT EXISTS `im_match_pair`
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
CREATE TABLE IF NOT EXISTS `im_match_preference`
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
CREATE TABLE IF NOT EXISTS `im_match_exposure`
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
CREATE TABLE IF NOT EXISTS `im_profile_visit`
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
CREATE TABLE IF NOT EXISTS `im_intimacy`
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
CREATE TABLE IF NOT EXISTS `im_moment`
(
    `id`            BIGINT        NOT NULL COMMENT '动态 ID（雪花）',
    `user_id`       BIGINT        NOT NULL COMMENT '发布用户 ID',
    `content`       VARCHAR(1024)          DEFAULT NULL COMMENT '动态正文（纯文本，上限 500 字，服务端按字符数校验；可为空即纯图动态）',
    `visibility`    TINYINT       NOT NULL DEFAULT 1 COMMENT '可见范围：1 公开（好友与匹配对象可见） 2 仅好友 3 部分人可见（读 im_moment_visible） 4 仅自己',
    `media_count`   INT           NOT NULL DEFAULT 0 COMMENT '媒体条数（冗余，卡片决定画一格还是九宫格）',
    `like_count`    INT           NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，写时增减，与 im_moment_like 行数最终一致）',
    `comment_count` INT           NOT NULL DEFAULT 0 COMMENT '评论数（冗余计数，含一级与子回复）',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 已下架（后台或本人下架，行保留）',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间（Feed 排序按它，三天可见也按它算）',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除 1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_status_time` (`user_id`, `status`, `create_time`),
    KEY `idx_status_time` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交友动态（短时朋友圈内容，一行一条，正文 + 九宫格 + 可见范围）';

CREATE TABLE IF NOT EXISTS `im_moment_media`
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

CREATE TABLE IF NOT EXISTS `im_moment_visible`
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

CREATE TABLE IF NOT EXISTS `im_moment_like`
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

CREATE TABLE IF NOT EXISTS `im_moment_comment`
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
CREATE TABLE IF NOT EXISTS `im_icebreaker`
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

-- -------------------------------------------------------------------------------------
-- 12. 存量用户回填 im_user_profile 基础行
--
--     推荐流、附近的人、筛选都要 join 这张表取条件与坐标，缺行的人会被 INNER JOIN 吃掉、
--     永远刷不到——那等于「老用户在新功能上线后集体隐身」。所以这里给每个未注销账号补一行
--     全默认值，之后由服务层在真正保存资料时 UPDATE。
--     服务层同时保留「读不到就懒建」的兜底：这一行没落成（比如中途失败）也不会 NPE。
--     INSERT IGNORE + 主键 = user_id，重复执行天然空转。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_user_profile` (`user_id`)
SELECT `id` FROM `im_user` WHERE `deleted` = 0;

-- -------------------------------------------------------------------------------------
-- 13. 兴趣标签种子（48 个，分 8 类；与 im_data.sql 同一份，逐字一致）
--     id 段 1~48 固定，后台后来自己加的标签从 49 起（雪花或自增由服务层决定，不占这段）。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_tag` (`id`, `name`, `category`, `sort`, `status`) VALUES
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

-- -------------------------------------------------------------------------------------
-- 14. 破冰文案种子（id 1~55：打招呼 1~20、话题卡 21~40、标签定制开场白 41~55）
--     占位符 {nickname} / {city} / {tag} 由服务端替换后下发，前端不做模板逻辑——
--     模板里出现什么占位符属于内容，不该让客户端跟着发版。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_icebreaker` (`id`, `type`, `tag_id`, `content`, `sort`, `status`) VALUES
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

-- -------------------------------------------------------------------------------------
-- 15. 权限点与 admin 绑定：一屏一个点，伞权限 system:manage（107）不通配这些新点，
--     所以必须显式绑给 role 1，否则后台三个新页面只有重新分配过角色的人进得去。
--     ★ 这一步落库后不清 im:auth:perm:* / im:auth:role:* 就等于没做（见文件头）。
-- -------------------------------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (132, 'system:dating:manage', '后台交友管理', 'system', '交友资料/照片墙/配对/滑动记录/访客的检索与处置（含照片驳回与资料降权）', 1),
       (133, 'system:moment:manage', '后台动态管理', 'system', '动态与评论的检索、下架、删除与举报处置（不可逆删除含媒体引用）', 1),
       (134, 'system:tag:manage', '后台标签与破冰管理', 'system', '兴趣标签库与打招呼模板/话题卡的增删改与启停', 1);

INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
VALUES (6032, 1, 132),
       (6033, 1, 133),
       (6034, 1, 134);

-- -------------------------------------------------------------------------------------
-- 16. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面应用时语句结果会被丢弃，
--     所以这几条刻意留成注释，不进 splitStatements 的语句列表）
--
--  16.1 16 张表到位：期望 16 行，一张不少
--    SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (
--      'im_user_profile','im_user_photo','im_tag','im_user_tag','im_match_like','im_match_pair',
--      'im_match_preference','im_match_exposure','im_profile_visit','im_intimacy','im_moment',
--      'im_moment_media','im_moment_visible','im_moment_like','im_moment_comment','im_icebreaker')
--    ORDER BY TABLE_NAME;
--
--  16.2 存量用户都有资料行：两个数应当相等（老账号没回填就会在推荐流里隐身）
--    SELECT (SELECT COUNT(*) FROM `im_user` WHERE `deleted` = 0) AS users,
--           (SELECT COUNT(*) FROM `im_user_profile` WHERE `deleted` = 0) AS profiles;
--
--  16.3 种子行数：期望 tag 48、icebreaker 55（打招呼 20 / 话题卡 20 / 标签开场白 15）
--    SELECT COUNT(*) AS tags FROM `im_tag`;
--    SELECT `type`, COUNT(*) AS rows_per_type FROM `im_icebreaker` GROUP BY `type`;
--
--  16.4 RBAC 到位：两个数都应为 3。不是 3 就先确认第 15 段落库了，再清缓存——
--    缓存没清时页面表现与没授权一模一样（1003），极易误判成脚本没跑
--    SELECT COUNT(*) AS perm_rows FROM `im_permission` WHERE `id` IN (132, 133, 134) AND `deleted` = 0;
--    SELECT COUNT(*) AS bound FROM `im_role_permission` WHERE `id` IN (6032, 6033, 6034) AND `role_id` = 1;
-- =====================================================================================
