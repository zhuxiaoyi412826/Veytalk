-- =====================================================================================
--  U1.0.4：回滚 V1.0.4 —— 删除视频板块二级分类字典表
--  ★清除：im_live_video_category 整表（含后台后来新增/停用的分类行，数据不可恢复）。
--  不动 im_live_video.category 文本列：历史视频的所属分类值保持原样，
--  回滚后前端分类栏退回 im-ui 内置的默认 17 项兜底。
--  回滚前建议先备份：mysqldump -u<user> -p --default-character-set=utf8mb4 im_db im_live_video_category > backup_1.0.4_category.sql
-- =====================================================================================

DROP TABLE IF EXISTS `im_live_video_category`;
