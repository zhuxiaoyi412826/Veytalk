-- =====================================================================================
--  U1.0.9：回滚「登录设备表 + 登录流水表」（配套 V1.0.9__login_device.sql）
--
--  ★清除：表 `im_user_device`、表 `im_login_log`（连同已登记的设备与流水）
--  ★增加：前像备份表 `im_user_device_bak_20261127` / `im_login_log_bak_20261127`
--         （★ 这两张表只在回滚时存在，故意不并回 sql/im_schema.sql，先例见 im_user_ban_bak_20261026）
--  ★修改：无
--
--  回滚之前要看清楚的三件事：
--   1. **MySQL 的 DDL 隐式提交、不可回卷**。DROP TABLE 一执行，设备登记与登录留痕就没了，
--      唯一找回途径是第 1 / 3 段的备份表，所以备份必须排在 DROP 之前，且守卫在会话变量
--      丢失时直接炸，绝不静默跳到 DROP。
--   2. **只删这两张新表，不动 im_user / im_remote_device**。账号本身与远程控制设备注册表
--      都和本次变更无关；im_remote_device 名字像但不是同一件事（见 V1.0.9 文件头）。
--   3. **回滚后代码必须同步退回**：UserDevice / LoginLog 两个实体、DeviceService 那一路
--      （设备列表、撤销、下线其他、同端顶号、登录留痕）全部引用这两张表，
--      表拆了而 jar 没退，登录会在 doLogin 回调里刷 warn（不影响登录），
--      而「设置 · 登录设备」那一屏直接 1146 Table doesn't exist。
--      同时要把 `im.account.exclusive-login` 关掉，否则顶号代码仍会尝试读设备表。
--
--  幂等：四段都可重复执行，半途失败后可直接重试回滚（台账在 U 失败时保持 APPLIED）。
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- 0. 三个存在性判定一次取完（后面的备份与 DROP 都复用这三个会话变量）
-- -------------------------------------------------------------------------------------
SET @dev_on = (SELECT COUNT(*) FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_user_device');
SET @log_on = (SELECT COUNT(*) FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_login_log');

-- -------------------------------------------------------------------------------------
-- 1. 设备表前像备份（整表拷：它本身就几列，且回滚要保的是「谁在哪些设备上登过」这份事实）
-- -------------------------------------------------------------------------------------
SET @bak_dev = CASE
    WHEN @dev_on IS NULL OR @log_on IS NULL
        THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u109_guard_conn_lost`'
    WHEN @dev_on = 0 THEN 'SELECT ''im_user_device 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_user_device_bak_20261127` AS SELECT * FROM `im_user_device`' END;
PREPARE stmt FROM @bak_dev;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 2. 拆设备表
-- -------------------------------------------------------------------------------------
SET @drop_dev = CASE
    WHEN @dev_on IS NULL OR @log_on IS NULL
        THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_u109_guard_conn_lost`'
    WHEN @dev_on = 0 THEN 'SELECT ''im_user_device 已不在，跳过'' AS msg'
    ELSE 'DROP TABLE `im_user_device`' END;
PREPARE stmt FROM @drop_dev;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 3. 流水表前像备份 + 拆表（流水是排查盗号投诉的唯一依据，即便回滚也先留一份）
-- -------------------------------------------------------------------------------------
SET @bak_log = CASE
    WHEN @dev_on IS NULL OR @log_on IS NULL
        THEN 'SELECT ''会话变量丢失，未备份不得往下 DROP'' FROM `im_u109_guard_conn_lost`'
    WHEN @log_on = 0 THEN 'SELECT ''im_login_log 已不在，前像备份跳过'' AS msg'
    ELSE 'CREATE TABLE IF NOT EXISTS `im_login_log_bak_20261127` AS SELECT * FROM `im_login_log`' END;
PREPARE stmt FROM @bak_log;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_log = CASE
    WHEN @dev_on IS NULL OR @log_on IS NULL
        THEN 'SELECT ''会话变量丢失，拒绝静默跳过'' FROM `im_u109_guard_conn_lost`'
    WHEN @log_on = 0 THEN 'SELECT ''im_login_log 已不在，跳过'' AS msg'
    ELSE 'DROP TABLE `im_login_log`' END;
PREPARE stmt FROM @drop_log;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------------------------------
-- 4. 跑完怎么核对（纯只读，手工 source 时直接复制执行；走页面回滚时语句结果会被丢弃）
--
--    4.1 两张表都不在：期望 0
--      SELECT COUNT(*) AS still_there FROM information_schema.TABLES
--      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('im_user_device', 'im_login_log');
--
--    4.2 备份表在位（行数应与回滚前一致，要恢复时从这两张表 INSERT ... SELECT 回去）
--      SELECT (SELECT COUNT(*) FROM `im_user_device_bak_20261127`) AS devices,
--             (SELECT COUNT(*) FROM `im_login_log_bak_20261127`) AS logs;
--
--    4.3 两张表都是「只被新代码读写」，回滚后无残留：没有外键指向它们，
--      也不需要回头清理 im_config / im_permission（V1.0.9 没往这两张表塞任何行）
-- =====================================================================================
