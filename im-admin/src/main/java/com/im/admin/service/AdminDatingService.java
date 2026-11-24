package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminDatingQueryDTO;
import com.im.common.domain.AdminPhotoDTO;
import com.im.common.domain.AdminPhotoQueryDTO;
import com.im.common.domain.AdminProfileDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminDatingSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台交友资料与照片审核：全部状态翻页、强制隐身/恢复、推荐质量标记、照片通过与驳回。
 *
 * <p>与 {@code AdminVideoService} 同构的薄封装——本类不碰任何表，取数与改状态全部经
 * {@link AdminDatingSpi} 交给 im-user 执行（星型架构）。它唯一持有的逻辑是
 * 「模块缺席时说人话」：交友表还没建、模块没装的部署里这些端点回明确的「模块未装配」，
 * 而不是 NoSuchBeanDefinition 的 5000。
 *
 * <p>这里再落一条 {@code log.info} 不是重复：SPI 侧那条是「数据改成了什么」，这一条是
 * 「哪个管理员从后台点了这个按钮」。排查申诉时问的永远是后者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDatingService {

    private final ObjectProvider<AdminDatingSpi> datingSpiProvider;

    public PageResult<AdminProfileDTO> pageProfiles(AdminDatingQueryDTO query) {
        return requireSpi().pageProfiles(query);
    }

    public AdminProfileDTO setDiscoverable(Long userId, boolean discoverable, Long operatorId, String reason) {
        AdminProfileDTO profile = requireSpi().setDiscoverable(userId, discoverable, operatorId, reason);
        log.info("[后台交友管理] 用户 {} 已置为{}: operator={}", userId, discoverable ? "可被发现" : "强制隐身",
                operatorId);
        return profile;
    }

    public AdminProfileDTO setQualityFlag(Long userId, Integer qualityFlag, Long operatorId, String reason) {
        AdminProfileDTO profile = requireSpi().setQualityFlag(userId, qualityFlag, operatorId, reason);
        log.info("[后台交友管理] 用户 {} 的质量标记已置为{}: operator={}", userId, qualityFlag, operatorId);
        return profile;
    }

    /* ==================== 照片审核 ==================== */

    public PageResult<AdminPhotoDTO> pagePhotos(AdminPhotoQueryDTO query) {
        return requireSpi().pagePhotos(query);
    }

    public AdminPhotoDTO auditPhoto(Long id, boolean pass, String reason, Long operatorId) {
        AdminPhotoDTO photo = requireSpi().auditPhoto(id, pass, reason, operatorId);
        log.info("[后台照片审核] 照片 {} 已{}: operator={}, userId={}", id, pass ? "通过" : "驳回",
                operatorId, photo.getUserId());
        return photo;
    }

    public void deletePhoto(Long id, Long operatorId) {
        requireSpi().deletePhoto(id, operatorId);
        log.info("[后台照片审核] 照片 {} 已删除: operator={}", id, operatorId);
    }

    private AdminDatingSpi requireSpi() {
        AdminDatingSpi spi = datingSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "交友模块未装配，后台无法管理交友资料");
        }
        return spi;
    }
}

