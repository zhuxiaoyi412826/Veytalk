package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminInterviewOverviewDTO;
import com.im.common.domain.AdminInterviewQueryDTO;
import com.im.common.domain.AdminInterviewSessionDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminInterviewSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台 AI 面试记录：场次翻页、单场档案（含作答原文与违规事件）、顶部概览。
 *
 * <p>薄封装，本类不读 {@code im_interview_*} 任何一张表，一切经 {@link AdminInterviewSpi}
 * 交给 im-ai。<b>这一屏一个写动作都没有</b>，理由不是「还没做」，而是三条都不能做：
 * 违规计数是判罚依据（改了就不能自证）、事件流水表注释写死只增不改不删、
 * 真要中止一场作弊的面试，正当路径是禁用该候选人的账号（{@code /api/admin/user}），
 * 那是一次有权限、有留痕、有理由的处置，而不是管理员隔空点「结束」。
 *
 * <p>计数一律读会话表上落好的那份，后台不重算——配置开关会变，重算出来的数与当初
 * 据以强停的数不一致，就是自己推翻自己的判罚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminInterviewService {

    private final ObjectProvider<AdminInterviewSpi> interviewSpiProvider;

    public PageResult<AdminInterviewSessionDTO> page(AdminInterviewQueryDTO query) {
        return requireSpi().pageSessions(query);
    }

    /**
     * 单场面试档案：计数 + 违规按类型拆分 + 逐轮问答 + 事件流水。
     *
     * <p>这是这一屏唯一会把候选人作答原文交出去的路径，读一次在 im-ai 侧留一条含场次 ID
     * 的后端日志；查不到按业务错误回给前端，而不是 {@code Result.ok(null)} 让抽屉里一片空白。
     */
    public AdminInterviewSessionDTO detail(Long sessionId) {
        BusinessException.throwIf(sessionId == null, ResultCode.BAD_REQUEST, "面试场次 ID 必填");
        AdminInterviewSessionDTO session = requireSpi().detail(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.AI_INTERVIEW_NOT_FOUND);
        }
        return session;
    }

    public AdminInterviewOverviewDTO overview() {
        return requireSpi().overview();
    }

    private AdminInterviewSpi requireSpi() {
        AdminInterviewSpi spi = interviewSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "AI 模块未装配，后台无法查看面试记录");
        }
        return spi;
    }
}
