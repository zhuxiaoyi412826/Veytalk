-- =====================================================================================
--  文章功能增量脚本（只补文章相关，不 DROP / 不清任何既有表，现网数据不受影响）
--  适用：已经跑过旧版 im_schema.sql、库里已有业务数据的场景。
--  全新库无需本文件——im_schema.sql + im_data.sql 已包含这三处改动。
--
--  内容：① 新建 im_article 表          ② 权限点 123 system:article:manage
--        ③ 给 admin 角色授予 123
--
--  执行（PowerShell 下用 -e "source"，不要用 < 或 Get-Content 管道，中文会被刷成 ?）：
--    mysql -u root -p --default-character-set=utf8mb4 -e "source sql/im_article_patch.sql"
--  全部语句均幂等（IF NOT EXISTS / INSERT IGNORE），重复执行安全。
-- =====================================================================================

USE `im_db`;

-- ① 直播页「文章」板块：Markdown 文章 + 审核状态机 ------------------------------------
-- 正文图片与封面本体存 im_file（bizType=article_img/article_cover），本表只存原文与元数据。
CREATE TABLE IF NOT EXISTS `im_article`
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
    KEY `idx_status_create` (`status`, `create_time`),
    KEY `idx_author_create` (`author_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='直播页文章板块（Markdown + 审核状态机）';

-- ② 后台文章管理权限点 ----------------------------------------------------------------
INSERT IGNORE INTO `im_permission` (`id`, `perm_code`, `perm_name`, `module`, `description`, `status`)
VALUES (123, 'system:article:manage', '后台文章管理', 'system', '文章的检索/新建/编辑/审核通过驳回/上下架/删除', 1);

-- ③ 授予 admin 角色（role_id=1）；如需审核员也管文章，把下面这行同样格式补 (role_id=3) 即可
INSERT IGNORE INTO `im_role_permission` (`id`, `role_id`, `permission_id`)
VALUES (6023, 1, 123);
