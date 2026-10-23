package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminVideoDTO;
import com.im.common.domain.AdminVideoQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminVideoSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台视频管理：全部状态翻页、下架/恢复、管理员删除。
 *
 * <p>与 {@code AdminUserService} 同构的薄封装——本类不碰任何表，取数与改状态全部
 * 经 {@code AdminVideoSpi} 交给 im-live 执行（星型架构）。它唯一持有的逻辑是
 * 「模块缺席时说人话」：没装直播模块的部署里这些端点返回明确的「模块未装配」，
 * 而不是 NoSuchBeanDefinition 的 5000。
 *
 * <p>响应直接透出 {@code AdminVideoDTO} 而不再抄一层 AdminVideoVO：这个 DTO 从出生
 * 就是按后台口径设计的（含已下架、含上传者内部 ID、不含任何凭证字段），
 * 再抄一层只是把「两个类字段不一致」变成新的坑。用户管理那边有 VO 是因为
 * 它在 SPI 之外还要拼「被举报次数」这一列，这里没有这种拼接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminVideoService {

    private final ObjectProvider<AdminVideoSpi> videoSpiProvider;

    public PageResult<AdminVideoDTO> page(AdminVideoQueryDTO query) {
        return requireSpi().pageVideos(query);
    }

    public AdminVideoDTO setStatus(Long id, boolean online, Long operatorId, String reason) {
        AdminVideoDTO video = requireSpi().setStatus(id, online, operatorId, reason);
        log.info("[后台视频管理] 视频 {} 已{}: operator={}", id, online ? "恢复上架" : "下架", operatorId);
        return video;
    }

    public void delete(Long id, Long operatorId) {
        requireSpi().delete(id, operatorId);
        log.info("[后台视频管理] 视频 {} 已删除: operator={}", id, operatorId);
    }

    private AdminVideoSpi requireSpi() {
        AdminVideoSpi spi = videoSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "直播模块未装配，后台无法管理视频");
        }
        return spi;
    }
}

