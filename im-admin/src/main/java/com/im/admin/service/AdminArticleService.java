package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminArticleDTO;
import com.im.common.domain.AdminArticleQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminArticleSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台文章管理：全状态翻页、详情、代写、编辑、审核通过/驳回、上下架、删除。
 *
 * <p>与 {@code AdminVideoService} 同构的薄封装——本类不碰任何表，取数与改状态全部经
 * {@code AdminArticleSpi} 交给 im-live 执行（星型架构）。它唯一持有的逻辑是「模块缺席时说人话」：
 * 没装该模块的部署里这些端点返回明确的「模块未装配」，而不是 NoSuchBeanDefinition 的 5000。
 *
 * <p>响应直接透出 {@code AdminArticleDTO} 而不再抄一层 VO：这个 DTO 从出生就是按后台口径设计的
 * （含全状态、含作者内部 ID、不含任何凭证字段），再抄一层只是把「两个类字段不一致」变成新的坑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminArticleService {

    private final ObjectProvider<AdminArticleSpi> articleSpiProvider;

    public PageResult<AdminArticleDTO> page(AdminArticleQueryDTO query) {
        return requireSpi().pageArticles(query);
    }

    public AdminArticleDTO detail(Long id) {
        return requireSpi().getDetail(id);
    }

    public AdminArticleDTO create(AdminArticleDTO dto, Long operatorId, boolean publishNow) {
        AdminArticleDTO created = requireSpi().create(dto, operatorId, publishNow);
        log.info("[后台文章管理] 文章 {} 已代写: operator={}, publishNow={}",
                created.getId(), operatorId, publishNow);
        return created;
    }

    public AdminArticleDTO update(AdminArticleDTO dto, Long operatorId) {
        AdminArticleDTO updated = requireSpi().update(dto, operatorId);
        log.info("[后台文章管理] 文章 {} 已编辑: operator={}", dto.getId(), operatorId);
        return updated;
    }

    public AdminArticleDTO review(Long id, boolean approved, String reason, Long operatorId) {
        AdminArticleDTO result = requireSpi().review(id, approved, reason, operatorId);
        log.info("[后台文章管理] 文章 {} 已审核{}: operator={}", id, approved ? "通过" : "驳回", operatorId);
        return result;
    }

    public AdminArticleDTO setOffline(Long id, boolean offline, Long operatorId, String reason) {
        AdminArticleDTO result = requireSpi().setOffline(id, offline, operatorId, reason);
        log.info("[后台文章管理] 文章 {} 已{}: operator={}", id, offline ? "下架" : "恢复上架", operatorId);
        return result;
    }

    public void delete(Long id, Long operatorId) {
        requireSpi().delete(id, operatorId);
        log.info("[后台文章管理] 文章 {} 已删除: operator={}", id, operatorId);
    }

    private AdminArticleSpi requireSpi() {
        AdminArticleSpi spi = articleSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "文章模块未装配，后台无法管理文章");
        }
        return spi;
    }
}

