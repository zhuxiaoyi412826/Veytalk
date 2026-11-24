package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminWorkCategoryDTO;
import com.im.common.domain.AdminWorkCreateDTO;
import com.im.common.domain.AdminWorkDTO;
import com.im.common.domain.AdminWorkQueryDTO;
import com.im.common.domain.AdminWorkUpdateDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminWorkCategorySpi;
import com.im.common.spi.AdminWorkSpi;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 后台「图片 / 音乐 / 小说」作品管理：薄封装，全部动作经 {@link AdminWorkSpi} 交给 im-live 执行。
 *
 * <p>与 {@code AdminVideoService} 同构——本类不碰任何表（星型架构：im-admin 不 import 业务 Mapper）。
 * 它唯一持有的逻辑是「模块缺席时说人话」：没装直播模块的部署里这些端点返回明确的「模块未装配」，
 * 而不是 NoSuchBeanDefinition 变出来的 5000。
 *
 * <p><b>一套服务吃三个板块</b>：每个方法都收 {@code type}，权限判定不在这里（在
 * {@link WorkAuthGuard}，由控制器每个方法首行调用）。本类只做转发与 SPI 缺席兜底，
 * 不校验板块合法性——两处校验早晚会长出两套口径，留一处才有一处真相。
 *
 * <p>响应直接透出 {@link AdminWorkDTO} 不再抄一层 VO 的理由同 {@code AdminVideoService}：
 * 这个 DTO 生来就是后台口径（含已下架、含内部 ID、不含任何凭证），再抄一层只是把
 * 「两个类字段不一致」变成新的坑。
 */
@Service
@RequiredArgsConstructor
public class AdminWorkService {

    private final ObjectProvider<AdminWorkSpi> workSpiProvider;
    private final ObjectProvider<AdminWorkCategorySpi> categorySpiProvider;

    /* ==================== 作品 ==================== */

    public PageResult<AdminWorkDTO> page(AdminWorkQueryDTO query) {
        return requireWorkSpi().page(query);
    }

    public AdminWorkDTO detail(String type, Long id) {
        return requireWorkSpi().detail(type, id);
    }

    public AdminWorkDTO create(String type, AdminWorkCreateDTO dto, Long operatorId) {
        return requireWorkSpi().create(type, dto, operatorId);
    }

    public AdminWorkDTO update(String type, Long id, AdminWorkUpdateDTO dto, Long operatorId) {
        return requireWorkSpi().update(type, dto, id, operatorId);
    }

    public AdminWorkDTO setStatus(String type, Long id, boolean online, Long operatorId, String reason) {
        return requireWorkSpi().setStatus(type, id, online, operatorId, reason);
    }

    public void delete(String type, Long id, Long operatorId) {
        requireWorkSpi().delete(type, id, operatorId);
    }

    /* ==================== 分类字典 ==================== */

    public List<AdminWorkCategoryDTO> listCategories(String type) {
        return requireCategorySpi().listCategories(type);
    }

    public AdminWorkCategoryDTO createCategory(String type, String name, Integer sort, Long operatorId) {
        return requireCategorySpi().createCategory(type, name, sort, operatorId);
    }

    public AdminWorkCategoryDTO updateCategory(String type, Long id, String name, Integer sort,
                                               Integer status, Long operatorId) {
        return requireCategorySpi().updateCategory(type, id, name, sort, status, operatorId);
    }

    public void deleteCategory(String type, Long id, Long operatorId) {
        requireCategorySpi().deleteCategory(type, id, operatorId);
    }

    private AdminWorkSpi requireWorkSpi() {
        AdminWorkSpi spi = workSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "直播模块未装配，后台无法管理作品");
        }
        return spi;
    }

    private AdminWorkCategorySpi requireCategorySpi() {
        AdminWorkCategorySpi spi = categorySpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "直播模块未装配，后台无法管理板块分类");
        }
        return spi;
    }
}
