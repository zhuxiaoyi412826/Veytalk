package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminWorkCreateDTO;
import com.im.common.domain.AdminWorkDTO;
import com.im.common.domain.AdminWorkQueryDTO;
import com.im.common.domain.AdminWorkUpdateDTO;

/**
 * 后台「图片 / 音乐 / 小说」作品管理契约，由 im-live 实现（{@code WorkAdminSpiImpl}）。
 *
 * <p>星型架构老问题（与 {@link AdminVideoSpi}、{@link AdminArticleSpi} 同源）：im-admin 不能
 * import {@code LiveImageMapper} / {@code LiveMusicMapper} / {@code LiveNovelMapper}，但
 * 「翻页、看详情、代建、编辑、上下架、以管理员身份删除」这六件事只有持有那三张表的 im-live 能做。
 *
 * <p><b>一套接口吃三个板块，方法入参都带 {@code type}</b>：三张表的列口径刻意对齐过，
 * 六件事的动作也完全同构，写成三个 SPI 就是三份同构代码，将来改一处校验要改三遍。
 * 分发收在实现类一处（按 {@code com.im.common.enums.WorkBoardType} 转对应 Service），
 * {@code video} 与未知值一律拒绝——视频板块有自己的 {@link AdminVideoSpi}，不从这里走。
 *
 * <p>权限仍然是「一屏一个点」：type→权限点的映射在 im-admin 侧的守卫里做（见
 * {@code ImConstants.PERM_SYSTEM_IMAGE_MANAGE} 三个常量），共用一个控制器不等于共用一个权限。
 *
 * <p>实现方注意：本接口被调到时 im-live 必然在场（同进程装配），但 im-admin 侧用
 * {@code ObjectProvider} 消费——没装该模块时后台显示「模块未部署」，而不是 500。
 */
public interface AdminWorkSpi {

    /**
     * 翻页查某板块的作品，<b>含已下架</b>（status=2）；排序写死创建时间倒序，分页上限由实现方自己夹。
     *
     * <p>列表项<b>不含小说正文</b>：{@code im_live_novel.content} 是 LONGTEXT，一屏 20 行全带上
     * 正文能把响应撑爆——实现方必须在 select 里显式排除这一列，而不是「查出来再置 null」。
     */
    PageResult<AdminWorkDTO> page(AdminWorkQueryDTO query);

    /**
     * 取单条详情（小说含 {@code content}，供后台预览与编辑回填）。记录不存在抛业务异常。
     *
     * @param type 板块：image / music / novel
     * @param id   作品 ID（对应板块表的主键）
     */
    AdminWorkDTO detail(String type, Long id);

    /**
     * 管理员代建一条作品：发布即可见（status=1），不走文章的审核状态机。
     *
     * <p>归属：未显式指定作者时记在操作者名下（{@code uploader_id = operatorId}），
     * 后台列表里作者显示管理员昵称——「谁点的发布」比「假装是个用户」更好对账。
     *
     * @param type       板块，与 {@code dto.getType()} 不一致时以本参数为准（URL 是权威来源）
     * @param dto        媒体字段按板块必填其一：图集 imageIds / 音频 fileId / 小说 content
     * @param operatorId 操作管理员，进审计日志并作为归属
     * @return 落库后的作品档案（受控地址由服务端拼好）
     */
    AdminWorkDTO create(String type, AdminWorkCreateDTO dto, Long operatorId);

    /**
     * 管理员编辑任意作品：只改传了值的字段，不改归属、不改状态（状态走 {@link #setStatus}）。
     * 记录不存在抛业务异常。
     */
    AdminWorkDTO update(String type, AdminWorkUpdateDTO dto, Long id, Long operatorId);

    /**
     * 下架 / 恢复（改板块表的 {@code status}，与视频同一口径：1 正常 2 已下架）。
     *
     * <p>幂等：目标状态与当前一致时直接返回当前档案，不重复写库。记录不存在抛业务异常。
     *
     * @param online     {@code true} 恢复上架（status=1），{@code false} 下架（status=2）
     * @param reason     事由，只进服务端审计日志，可为空
     */
    AdminWorkDTO setStatus(String type, Long id, boolean online, Long operatorId, String reason);

    /**
     * 以管理员身份删除（逻辑删除 {@code deleted=1}，与本人删除同一落点）。
     *
     * <p>幂等：记录不存在时静默返回。{@code im_file} 里的媒体本体一期不级联清理，
     * 与用户端删除保持同一口径——先留反悔余地，回收交给后续清理任务。
     */
    void delete(String type, Long id, Long operatorId);
}
