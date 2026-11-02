package com.im.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 图片审核命中流水，对应 {@code im_moderation_log}（五期·路线 2）。
 *
 * <p>与审计文件 {@code MODERATION/moderation.log} 是<b>双写</b>关系：文件那条链路照旧
 * （不依赖数据库可用性，数据库挂了追责现场也留得住），表是查询侧——文件只留 30 天
 * 且不能按维度检索，「谁在什么时候试过什么图」要按用户/场景/类别翻页只能靠表。
 *
 * <p>继承 {@link AuditEntity}：流水表只增不改不删，没有逻辑删除列——注销一条命中记录
 * 等于销毁追责现场，这个口子从表结构上就不留。
 *
 * <p>唯一的例外是 {@link #evidenceFileId}：它是对判定结果的一次性「补写」（NULL → 有值），
 * 只因为图片副本是在上报之后才传上来的。类别、概率、阈值、评分这些判定字段永远不回改，
 * 所以挂证据不会把这张表变成可涂改的表。
 *
 * <p>所有字段在写入前都过了 {@code ModerationServiceImpl} 的清洗（折行、去引号、限长、
 * 概率夹到 0~1），库里不会有 {@code prob=1e309} 或带换行的文件名这种脏行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_moderation_log")
public class ModerationLog extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上报用户 ID（发图人 / 设头像的人） */
    private Long userId;

    /** 业务场景，取值见 {@link com.im.file.enums.FileBizType}（图片类） */
    private String scene;

    /** 命中类别名（模型输出原名）：Porn / Hentai / Violent 等 */
    private String hitClass;

    /** 命中类别概率（0~1，服务端夹后落库） */
    private Double hitProbability;

    /** 上报时前端实际使用的阈值，事后核对阈值改动的影响 */
    private Double threshold;

    /** 原始文件名（客户端报的，已洗成单行）；图片本体见 {@link #evidenceFileId} */
    private String fileName;

    /** 文件字节数 */
    private Long fileSize;

    /** 会话 ID，聊天场景非空 */
    private Long conversationId;

    /** 模型完整评分的 JSON 串 {@code [{className,probability}]}，上限 16 条，仅留痕 */
    private String scores;

    /**
     * 隔离区图片的 {@code im_file.id}（{@code biz_type=moderation_quarantine}）。
     *
     * <p>为 null 是常态而不是异常：隔离区开关关掉、图超过
     * {@code im.moderation.quarantine-max-size}、或副本上传失败（发送端对它是尽力而为）。
     */
    private Long evidenceFileId;
}
