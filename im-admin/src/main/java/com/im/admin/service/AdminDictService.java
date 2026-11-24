package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminIcebreakerDTO;
import com.im.common.domain.AdminTagDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminDictSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 后台字典：兴趣标签库与破冰文案库的增删改与启停。
 *
 * <p>薄封装，不碰表；取数与写入全部经 {@link AdminDictSpi} 交给 im-user 执行（星型架构）。
 * 之所以单独一个类而不并进 {@code AdminDatingService}：这两屏是两个权限点（132 / 134），
 * 一次 {@code requireSpi()} 缺失提示的文案也不同——把两套混进一个 Bean，就会出现
 * 「只有标签权限的管理员拿到了资料 SPI」这种在 Java 层而非 HTTP 层才发生的越权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDictService {

    private final ObjectProvider<AdminDictSpi> dictSpiProvider;

    /* ==================== 兴趣标签 ==================== */

    public List<AdminTagDTO> listTags(String category) {
        return requireSpi().listTags(category);
    }

    public AdminTagDTO createTag(String name, String category, String icon, Integer sort, Long operatorId) {
        AdminTagDTO tag = requireSpi().createTag(name, category, icon, sort, operatorId);
        log.info("[后台标签管理] 新增标签 {}（name={}）: operator={}", tag.getId(), tag.getName(), operatorId);
        return tag;
    }

    public AdminTagDTO updateTag(Long id, String name, String category, String icon, Integer sort, Integer status,
                                 Long operatorId) {
        AdminTagDTO tag = requireSpi().updateTag(id, name, category, icon, sort, status, operatorId);
        log.info("[后台标签管理] 修改标签 {}（name={}, sort={}, status={}）: operator={}",
                id, tag.getName(), tag.getSort(), tag.getStatus(), operatorId);
        return tag;
    }

    public void deleteTag(Long id, Long operatorId) {
        requireSpi().deleteTag(id, operatorId);
        log.info("[后台标签管理] 删除标签 {}: operator={}", id, operatorId);
    }

    /* ==================== 破冰文案 ==================== */

    public PageResult<AdminIcebreakerDTO> pageIcebreakers(Integer type, Long tagId, String keyword,
                                                          long current, long size) {
        return requireSpi().pageIcebreakers(type, tagId, keyword, current, size);
    }

    public AdminIcebreakerDTO createIcebreaker(Integer type, Long tagId, String content, Integer sort,
                                              Long operatorId) {
        AdminIcebreakerDTO item = requireSpi().createIcebreaker(type, tagId, content, sort, operatorId);
        log.info("[后台破冰管理] 新增文案 {}（type={}）: operator={}", item.getId(), item.getType(), operatorId);
        return item;
    }

    public AdminIcebreakerDTO updateIcebreaker(Long id, Integer type, Long tagId, String content, Integer sort,
                                               Integer status, Long operatorId) {
        AdminIcebreakerDTO item = requireSpi().updateIcebreaker(id, type, tagId, content, sort, status, operatorId);
        log.info("[后台破冰管理] 修改文案 {}（type={}, status={}）: operator={}",
                id, item.getType(), item.getStatus(), operatorId);
        return item;
    }

    public void deleteIcebreaker(Long id, Long operatorId) {
        requireSpi().deleteIcebreaker(id, operatorId);
        log.info("[后台破冰管理] 删除文案 {}: operator={}", id, operatorId);
    }

    private AdminDictSpi requireSpi() {
        AdminDictSpi spi = dictSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "交友模块未装配，后台无法维护标签与破冰字典");
        }
        return spi;
    }
}
