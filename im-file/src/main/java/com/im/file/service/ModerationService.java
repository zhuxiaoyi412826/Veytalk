package com.im.file.service;

import com.im.common.domain.UploadCmd;
import com.im.file.dto.req.ModerationReportReq;
import com.im.file.dto.vo.ModerationPolicyVO;

/**
 * 图片内容审核（鉴黄）服务。
 *
 * <p>识别推理跑在发送端浏览器里，本服务负责三件事：把阈值策略下发下去、
 * 把命中上报落进审计通道与流水表、把拦下的那张图收进隔离区。
 * 它不改变任何上传与发送的结果——违规图从未作为消息发出去，副本是作为证据另传一次的。
 */
public interface ModerationService {

    /**
     * 当前审核策略，供发送端决定「要不要识别、按什么阈值判」。
     */
    ModerationPolicyVO policy();

    /**
     * 记录一次命中上报。
     *
     * <p>只留痕不判定：上报内容全部来自客户端，服务端不采信它的结论，
     * 审计关掉或总开关关掉时静默返回，调用方拿到的都是成功。
     *
     * @return 落库的流水 ID，供发送端把图片副本挂到这条记录上；
     *         未写库（开关关闭、审计关闭、表写失败）时返回 {@code null}，
     *         发送端据此跳过副本上传——没有记录可挂的图就不该往上传
     */
    Long report(Long userId, ModerationReportReq req);

    /**
     * 给一条命中记录挂上隔离区图片（发送端拦下后另传的那份副本）。
     *
     * <p>三道硬约束：只允许挂到自己那条记录上（{@code cmd.uploaderId} 必须等于记录的
     * {@code userId}）、一张记录只挂一次（重复投递幂等，不重传字节）、
     * 体积不过 {@code im.moderation.quarantine-max-size}。
     *
     * <p>字节的落库走 {@code FileService#store} 而不是自己写存储：扩展名白名单、
     * MD5 去重、元数据与对象键的生成必须与任何一次普通上传同一套规则，否则
     * 「接口拦得住、旁路拦不住」就又是一个越权口子。
     */
    void attachEvidence(Long logId, UploadCmd cmd);
}
