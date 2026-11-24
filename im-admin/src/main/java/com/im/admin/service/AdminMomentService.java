package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminCommentQueryDTO;
import com.im.common.domain.AdminMomentCommentDTO;
import com.im.common.domain.AdminMomentDTO;
import com.im.common.domain.AdminMomentQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminMomentSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台动态管理：全部状态翻页与详情、下架/恢复、管理员删除，以及跨动态的评论队列。
 *
 * <p>薄封装，不碰表；全部经 {@link AdminMomentSpi} 交给 im-friend 执行（星型架构）。
 * 动态与它下面的评论之所以共用一个 Service 而不拆两个：它们是<b>同一个权限点</b>
 * （133 {@code system:moment:manage}）下的一屏，拆两个 Bean 只会多出一句一模一样的
 * {@code requireSpi()}，却让人误以为评论有独立的授权边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMomentService {

    private final ObjectProvider<AdminMomentSpi> momentSpiProvider;

    public PageResult<AdminMomentDTO> page(AdminMomentQueryDTO query) {
        return requireSpi().pageMoments(query);
    }

    public AdminMomentDTO detail(Long momentId) {
        return requireSpi().detail(momentId);
    }

    public AdminMomentDTO setStatus(Long momentId, boolean online, Long operatorId, String reason) {
        AdminMomentDTO moment = requireSpi().setStatus(momentId, online, operatorId, reason);
        log.info("[后台动态管理] 动态 {} 已{}: operator={}", momentId, online ? "恢复上架" : "下架", operatorId);
        return moment;
    }

    public void delete(Long momentId, Long operatorId) {
        requireSpi().delete(momentId, operatorId);
        log.info("[后台动态管理] 动态 {} 已删除（含媒体/评论/点赞/白名单）: operator={}", momentId, operatorId);
    }

    /* ==================== 评论队列 ==================== */

    public PageResult<AdminMomentCommentDTO> pageComments(AdminCommentQueryDTO query) {
        return requireSpi().pageComments(query);
    }

    public AdminMomentCommentDTO setCommentStatus(Long commentId, boolean online, Long operatorId, String reason) {
        AdminMomentCommentDTO comment = requireSpi().setCommentStatus(commentId, online, operatorId, reason);
        log.info("[后台动态管理] 评论 {} 已{}: operator={}", commentId, online ? "恢复正常" : "下架", operatorId);
        return comment;
    }

    public void deleteComment(Long commentId, Long operatorId) {
        requireSpi().deleteComment(commentId, operatorId);
        log.info("[后台动态管理] 评论 {} 已删除（含子回复）: operator={}", commentId, operatorId);
    }

    private AdminMomentSpi requireSpi() {
        AdminMomentSpi spi = momentSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "动态模块未装配，后台无法管理动态");
        }
        return spi;
    }
}
