package com.im.admin.service;

import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminRemoteAuditDTO;
import com.im.common.domain.AdminRemoteAuditQueryDTO;
import com.im.common.domain.AdminRemoteOverviewDTO;
import com.im.common.domain.AdminRemoteSessionDTO;
import com.im.common.domain.AdminRemoteSessionQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminRemoteSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 后台远程控制审计：会话台账、单会话档案、审计流水、概览数字。
 *
 * <p>同 {@code AdminLiveService} 的薄封装模式：本类不读 {@code im_remote_session}，
 * 一切经 {@code AdminRemoteSpi} 交给 im-remote。这一屏<b>一个写动作都没有</b>，
 * 所以这里也没有任何「执行完再回读档案」的方法——理由写在
 * {@link AdminRemoteSpi} 的类注释里（简版：被控端随时能自己断；真要阻断走工单处置；
 * 且禁用账号并不断得掉已建立的远程通道，别把封号当成掐断）。
 *
 * <p>排序、单页上限、高危动作词表都在 SPI 实现侧夹死，这里不接受任何排序参数，
 * 也不替前端补码表——后台界面显示的「高危」必须和数据库里判定「高危」是同一份清单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRemoteService {

    private final ObjectProvider<AdminRemoteSpi> remoteSpiProvider;

    public PageResult<AdminRemoteSessionDTO> page(AdminRemoteSessionQueryDTO query) {
        return requireSpi().pageSessions(query);
    }

    public AdminRemoteSessionDTO detail(Long sessionId) {
        AdminRemoteSessionDTO session = requireSpi().detail(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.REMOTE_SESSION_NOT_FOUND);
        }
        return session;
    }

    public PageResult<AdminRemoteAuditDTO> pageAudits(AdminRemoteAuditQueryDTO query) {
        return requireSpi().pageAudits(query);
    }

    public AdminRemoteOverviewDTO overview() {
        return requireSpi().overview();
    }

    private AdminRemoteSpi requireSpi() {
        AdminRemoteSpi spi = remoteSpiProvider.getIfAvailable();
        if (spi == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "远程控制模块未装配，后台无法审计远程会话");
        }
        return spi;
    }
}
