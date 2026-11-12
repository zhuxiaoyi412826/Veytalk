package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminModerationLogDTO;
import com.im.common.domain.AdminModerationQueryDTO;
import com.im.common.domain.FileDTO;

/**
 * 后台审核记录查询契约，由 im-file 实现（{@code ModerationAdminSpiImpl}，五期·路线 2）。
 *
 * <p>存在理由：{@code MODERATION/moderation.log} 只留 30 天（logback maxHistory）且不能按
 * 维度检索，追责数据必须同时落 {@code im_moderation_log} 表才能按「谁 / 场景 / 类别 / 时间」
 * 翻页。写侧在 im-file 的 {@code ModerationServiceImpl#report}（双写：文件照旧，表是查询侧），
 * 读侧表归 im-file，im-admin 经本契约取数——与 {@code AdminMessageSpi} 同一套星型约束。
 *
 * <p>权限口径：这一屏复用 {@code ImConstants.PERM_SYSTEM_LOG} 而不单设点。
 * 日志管理页本来就能读 moderation.log 原文，能看文件的人就该能查文件的库内版本，
 * 拆两个点只会制造「半个现场看不到」。
 */
public interface AdminModerationSpi {

    /**
     * 审核命中流水翻页。
     *
     * <p>排序固定按主键倒序（雪花 ID 即时间序），不接受外部排序参数；
     * 每行的昵称批量回填（一页一次 {@code listByIds}），注销用户显示占位。
     */
    PageResult<AdminModerationLogDTO> pageLogs(AdminModerationQueryDTO query);

    /**
     * 某条命中记录的隔离区图片（发送端拦下后另传上来的那份副本）。
     *
     * <p>只回元数据，字节由调用方拿 {@code FileStorageSpi#openStream} 自己开：
     * 与后台看举报证据同一套做法，不为了一个图片再套一层 SPI 方法。
     *
     * @return 记录存在但没挂过副本时返回 {@code null}（隔离区关掉 / 图超大 / 上传失败）
     * @throws com.im.common.exception.BusinessException 记录不存在时抛 {@code NOT_FOUND}
     *         ——拿主键反查「有没有这条记录」是枚举命中量的口子，所以不存在要说清楚，
     *         不能与「有记录但无图」混成同一个 null
     */
    FileDTO evidence(Long logId);
}
