-- =====================================================================================
--  增量迁移：图片审核命中落库（五期·路线 2）
--
--  用途：给「已经跑过前四期、只想升级审核追责查询」的环境使用。全新环境直接跑
--        sql/im_schema.sql + sql/im_data.sql 即可（本表已并入那份建表脚本），无需本脚本。
--
--  背景：MODERATION/moderation.log 只留 30 天（logback maxHistory）、不能按维度检索，
--        追责数据应当同时落一张流水表，后台「审核记录」页按 谁 / 场景 / 类别 / 时间 翻页。
--        双写：审计文件照旧写（不依赖数据库可用性），表是查询侧。
--
--  ★★ 零权限变更：后台查询端点复用既有的 system:log 权限点（115），不新增 RBAC 行。
--     理由：这一屏与「日志管理」同级别敏感（日志页本来就能看 moderation.log 原文），
--     能看日志文件的人就该能查日志的库内版本，拆两个点只会制造「半个现场看不到」。
--
--  ⚠ 数据库写操作一律由你本人执行（团队约定）。本脚本只建一张新表，不动任何既有表：
--      回滚就一句：DROP TABLE IF EXISTS `im_moderation_log`;
--
--  不跑会怎样：后端启动与既有功能全部正常（MyBatis 不会预检表存在），但一旦有人触发
--  审核命中，落库那一路会报 table doesn't exist——实现里是 try/catch 静默降级
--  （审计文件照常写，只留一条 WARN），不炸接口；但后台「审核记录」页永远查不到数据。
--
--  执行方式（PowerShell 用 -e "source"，别用管道，否则中文注释被刷成 ?）：
--      mysql -u "$env:MYSQL_USER" -p --default-character-set=utf8mb4 im_db -e "source sql/migration_moderation_log.sql"
--
--  幂等：CREATE TABLE IF NOT EXISTS，重复执行无副作用。
--  ⚠ 本表后续又长过一列：存量库跑完本脚本还得接着跑 sql/migration_moderation_evidence.sql
--     （给 im_moderation_log 加 evidence_file_id，隔离区图片的指向）。两份按顺序跑才是当前结构；
--     本脚本里的 CREATE TABLE 是当时那一刻的历史快照，不回填列（IF NOT EXISTS 对已建好的库不生效）。
-- =====================================================================================

USE `im_db`;

CREATE TABLE IF NOT EXISTS `im_moderation_log`
(
    `id`              BIGINT       NOT NULL COMMENT '主键（雪花，兼时间排序键）',
    `user_id`         BIGINT       NOT NULL COMMENT '上报用户 ID（发图人 / 设头像的人）',
    `scene`           VARCHAR(32)  NOT NULL COMMENT '业务场景：avatar / chat_image / video_cover 等图片类 FileBizType',
    `hit_class`       VARCHAR(32)  NOT NULL COMMENT '命中类别名（模型输出原名）：Porn / Hentai / Violent 等',
    `hit_probability` DECIMAL(5, 4) NOT NULL DEFAULT 0 COMMENT '命中类别概率（0~1，服务端夹后落库）',
    `threshold`       DECIMAL(5, 4)         DEFAULT NULL COMMENT '上报时前端实际使用的阈值，事后核对阈值改动的影响',
    `file_name`       VARCHAR(200)          DEFAULT NULL COMMENT '原始文件名（图片未上传，只有客户端报的；已洗成单行）',
    `file_size`       BIGINT                DEFAULT NULL COMMENT '文件字节数',
    `conversation_id` BIGINT                DEFAULT NULL COMMENT '会话 ID，聊天场景非空',
    `scores`          JSON                  DEFAULT NULL COMMENT '模型完整评分 [{className,probability}]，上限 16 条，仅留痕',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '命中时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（流水无更新业务，按底座口径保留）',
    PRIMARY KEY (`id`),
    -- 「这个人试过几次」：按用户过滤后天然按主键倒序取一页
    KEY `idx_user` (`user_id`, `id`),
    -- 按场景看分布（比如头像位有没有人试毒）
    KEY `idx_scene` (`scene`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='图片审核命中流水（追责数据落库，只增不改不删；与审计文件双写）';

-- 核对（只读）：应该看到 1 行、表名 im_moderation_log：
--   SHOW TABLES LIKE 'im_moderation_log';
-- 保留策略：本表不参与任何清理任务，行只增。命中上报本身是低频事件
-- （限流每分钟 30 次/用户），一年也长不到需要归档的量级；真到那天再谈按年归档。
