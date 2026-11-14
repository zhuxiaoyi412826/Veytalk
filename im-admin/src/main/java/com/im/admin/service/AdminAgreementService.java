package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminAgreementConsentDTO;
import com.im.common.domain.AdminAgreementConsentQueryDTO;
import com.im.common.domain.AdminAgreementDTO;
import com.im.common.domain.AdminAgreementQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminAgreementSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 后台协议与同意留痕：版本翻页、详情、起草、编辑草稿、发布，以及同意记录的两种查法。
 *
 * <p>与 {@code AdminArticleService} 同构的薄封装——本类不碰任何表，取数与改状态全部经
 * {@code AdminAgreementSpi} 交给 im-user 执行（星型架构）。它唯一持有的逻辑是「模块缺席时说人话」，
 * 以及把每一次<b>对外生效</b>的动作单独记一行日志：文章改错了顶多没人看，
 * 协议改错了是一份对用户生效过的文本被人当证据拿出来。
 *
 * <p>响应直接透出 {@code AdminAgreementDTO} 而不再抄一层 VO：这个 DTO 从出生就是按后台口径设计的
 * （含全状态、含发布人、不含任何凭证字段），再抄一层只是把「两个类字段不一致」变成新的坑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAgreementService {

    private final ObjectProvider<AdminAgreementSpi> agreementSpiProvider;

    public PageResult<AdminAgreementDTO> page(AdminAgreementQueryDTO query) {
        return requireSpi().pageAgreements(query);
    }

    public AdminAgreementDTO detail(Long id) {
        return requireSpi().getDetail(id);
    }

    public AdminAgreementDTO createDraft(AdminAgreementDTO dto, Long operatorId) {
        AdminAgreementDTO created = requireSpi().createDraft(dto, operatorId);
        log.info("[后台协议管理] 草稿已创建: id={}, code={}, version={}, operator={}",
                created.getId(), created.getCode(), created.getVersion(), operatorId);
        return created;
    }

    public AdminAgreementDTO updateDraft(AdminAgreementDTO dto, Long operatorId) {
        AdminAgreementDTO updated = requireSpi().updateDraft(dto, operatorId);
        log.info("[后台协议管理] 草稿已编辑: id={}, operator={}", dto.getId(), operatorId);
        return updated;
    }

    /**
     * 发布新版本。<b>这一句日志是本模块最重要的一行</b>：它记的是「从这一刻起，
     * 所有新用户点对的文本换了一份」，出争议时这是唯一能对齐时间线的服务端证据。
     */
    public AdminAgreementDTO publish(Long id, Long operatorId, String remark) {
        AdminAgreementDTO published = requireSpi().publish(id, operatorId, remark);
        log.warn("[后台协议管理] 协议已发布生效: code={}, version={}, operator={}, remark={}",
                published.getCode(), published.getVersion(), operatorId, remark);
        return published;
    }

    public PageResult<AdminAgreementConsentDTO> pageConsents(AdminAgreementConsentQueryDTO query) {
        return requireSpi().pageConsents(query);
    }

    public List<AdminAgreementConsentDTO> userConsents(Long userId) {
        return requireSpi().listUserConsents(userId);
    }

    private AdminAgreementSpi requireSpi() {
        AdminAgreementSpi spi = agreementSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "用户模块未装配，后台无法管理协议");
        }
        return spi;
    }
}
