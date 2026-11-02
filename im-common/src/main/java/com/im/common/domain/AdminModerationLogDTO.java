package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台审核记录的一行（五期·路线 2），由 im-file 从 {@code im_moderation_log} 组装。
 *
 * <p>与审计文件行同源但<b>不是给文件看的</b>：文件名、类别这些字段在写入时已经过
 * 「折行、去引号、限长」清洗（见 {@code ModerationServiceImpl}），库里存的干净值
 * 原样交给后台渲染即可，前端不需要再做任何转义。
 *
 * <p>{@code scores} 给的是<b>解析后的评分列表</b>而不是库里的 JSON 原文：
 * 让前端拿到就能遍历，不用每处渲染各自 parse 一遍。
 *
 * <p>{@code nickname} 是查询时经 {@code UserQuerySpi} 回填的；用户已注销时
 * 为「已注销用户」占位（注销会匿名化，这正是要能追溯的场景）。
 *
 * <p>{@code evidenceFileId} 只给 ID 不给地址：图片字节经后台自己的
 * {@code GET /api/admin/log/moderation/{id}/image} 取（门是 {@code system:log}），
 * 而 im-file 那套受控地址走的是「上传者或同会话成员」口径，管理员两者都不是。
 * 为 null 就是真的无图可看（隔离区关掉 / 图超大 / 上传失败），前端据此置灰按钮。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminModerationLogDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 流水 ID（雪花） */
    private Long id;

    /** 上报用户 ID */
    private Long userId;

    /** 用户昵称（查询时回填，注销用户显示占位） */
    private String nickname;

    /** 业务场景：avatar / chat_image / video_cover */
    private String scene;

    /** 命中类别名 */
    private String hitClass;

    /** 命中类别概率（0~1） */
    private Double hitProbability;

    /** 上报时前端实际使用的阈值，可空 */
    private Double threshold;

    /** 原始文件名（客户端报的，已清洗），可空 */
    private String fileName;

    /** 文件字节数，可空 */
    private Long fileSize;

    /** 会话 ID，聊天场景非空 */
    private Long conversationId;

    /** 模型完整评分（按概率从高到低），可空 */
    private List<ModerationScoreDTO> scores;

    /** 隔离区图片的 im_file ID，可空（未开启隔离区或副本没传上来） */
    private Long evidenceFileId;

    /** 命中时间 */
    private LocalDateTime createTime;
}
