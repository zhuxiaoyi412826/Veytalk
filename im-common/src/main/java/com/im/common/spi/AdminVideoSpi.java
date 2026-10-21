package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminVideoDTO;
import com.im.common.domain.AdminVideoQueryDTO;

/**
 * 后台短视频管理契约，由 im-live 实现（{@code VideoAdminSpiImpl}）。
 *
 * <p>星型架构老问题：im-admin 不能 import {@code LiveVideoMapper}，但「翻全部视频（含已下架）、
 * 下架 / 恢复、以管理员身份删除」这三件事只有直播模块能做，于是契约反转给它。
 *
 * <p>与用户端 {@code LiveVideoService} 的边界：用户端只看得到 status=1 的流、删除仅限本人；
 * 后台看得到全状态，删除走管理员身份跳过归属校验——所以不复用那个 Service 的方法语义，
 * 实现类直接对 mapper 说话，避免把「仅本人可删」的校验绕进了共用路径。
 *
 * <p>实现方注意：本接口被调到时 im-live 必然在场（同进程装配），但 im-admin 侧用
 * {@code ObjectProvider} 消费——没装直播模块时后台显示「模块未部署」，而不是 500。
 */
public interface AdminVideoSpi {

    /**
     * 翻页查短视频，<b>含已下架</b>（status=2）；排序写死发布时间倒序。
     * 分页上限与关键词长度由实现方自己夹。
     */
    PageResult<AdminVideoDTO> pageVideos(AdminVideoQueryDTO query);

    /**
     * 下架 / 恢复一条视频（改 {@code im_live_video.status}，零 DDL——这一列本来就有）。
     *
     * <p>幂等：目标状态与当前一致时直接返回当前档案，不重复写库。
     * 记录不存在抛业务异常（后台据此提示「已被删除」）。
     *
     * @param id         视频记录 ID
     * @param online     {@code true} 恢复上架（status=1），{@code false} 下架（status=2）
     * @param operatorId 操作管理员，只进服务端审计日志
     * @param reason     事由，进日志；允许为空
     * @return 变更后的视频档案
     */
    AdminVideoDTO setStatus(Long id, boolean online, Long operatorId, String reason);

    /**
     * 以管理员身份删除（逻辑删除，与上传者本人删除同一落点：{@code deleted=1}）。
     *
     * <p>幂等：记录不存在时静默返回。文件本体（im_file / MinIO）一期不级联清理，
     * 与用户端删除保持同一口径——先留 30 天反悔余地，回收由后续清理任务负责。
     *
     * @param id         视频记录 ID
     * @param operatorId 操作管理员，进审计日志
     */
    void delete(Long id, Long operatorId);
}
