package com.im.file.service;

import com.im.file.dto.req.ModerationReportReq;
import com.im.file.dto.vo.ModerationPolicyVO;

/**
 * 图片内容审核（鉴黄）服务。
 *
 * <p>识别推理跑在发送端浏览器里，本服务只负责两件事：把阈值策略下发下去、
 * 把命中上报落进审计通道。它不改变任何上传与发送的结果——被拦下的图片根本不会到这里。
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
     */
    void report(Long userId, ModerationReportReq req);
}
