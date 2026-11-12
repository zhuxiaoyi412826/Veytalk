package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminModerationLogDTO;
import com.im.common.domain.AdminModerationQueryDTO;
import com.im.common.domain.FileDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminModerationSpi;
import com.im.common.spi.FileStorageSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 后台审核记录查询（五期·路线 2）：薄封装，本类不读 {@code im_moderation_log}，
 * 一切经 {@link AdminModerationSpi} 交给 im-file——与 {@code AdminMessageService} 同一套星型约束。
 *
 * <p>权限复用 {@code system:log} 而不单设点（口径写在 {@code AdminModerationSpi} 注释）：
 * 日志管理页本来就能读 moderation.log 原文，这一屏只是同一份现场的结构化版本。
 *
 * <p>与日志文件页的分工：文件页答「那天到底发生了什么」（原文、上下文行），
 * 这一屏答「这个人试过几次、都撞在哪一类上」（按维度聚合翻页）。两边数据同源
 * （{@code ModerationServiceImpl} 双写），条目数对得上才能互查佐证。
 *
 * <p>{@link #evidenceFile} 是「看被拦下的那张图」的出口，与举报证据（{@code ReportService#mediaOf}）
 * 同一个权限思路：<b>不复用 im-file 的 canAccess</b>——那套口径只认上传者与同会话成员，
 * 管理员两者都不是，而判「这张拦得对不对」偏偏必须看到图本体。权限门就是
 * {@code AdminLogController} 类级的 {@code system:log}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminModerationService {

    private final ObjectProvider<AdminModerationSpi> moderationSpiProvider;
    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;

    public PageResult<AdminModerationLogDTO> page(AdminModerationQueryDTO query) {
        return requireSpi().pageLogs(query);
    }

    /**
     * 某条命中记录的隔离区图片元数据。
     *
     * <p>「有记录无图」与「没这条记录」在 SPI 侧是 null 与 NOT_FOUND 两个答案，这里把前者
     * 也抬成 NOT_FOUND：它是一个得给前台弹出来的错误（开关关掉 / 图超大 / 没跑迁移），
     * 而不是一个能渲染的空状态。
     */
    public FileDTO evidenceFile(Long logId) {
        FileDTO file = requireSpi().evidence(logId);
        if (file == null) {
            // 用 (code, message) 那个构造而不是 throwIf(NOT_FOUND, 文案)：NOT_FOUND 的自带文案没有
            // 占位符，额外传的理由会被 format 直接丢掉，而这句话前台是要弹给管理员看的
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(),
                    "该记录没有隔离区图片（隔离区开关关掉 / 图超过上限 / 副本上传失败 / 未跑迁移脚本）");
        }
        return file;
    }

    /** 打开隔离区图片的内容流，调用方负责关闭。 */
    public InputStream openEvidence(FileDTO file) {
        FileStorageSpi spi = fileStorageSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
        return spi.openStream(file);
    }

    private AdminModerationSpi requireSpi() {
        AdminModerationSpi spi = moderationSpiProvider.getIfAvailable();
        BusinessException.throwIf(spi == null, ResultCode.BUSINESS_ERROR, "审核记录服务不可用（im-file 模块未装载）");
        return spi;
    }
}
