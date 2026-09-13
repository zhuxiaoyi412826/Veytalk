package com.im.message.handler;

import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;

import java.util.Set;

/**
 * 消息内容处理策略。
 *
 * <p>不同消息类型的校验与规范化逻辑差异很大：文本要做 XSS 过滤与长度限制，
 * 附件类要校验文件归属并回填元数据，系统通知只能由服务端生成。
 * 把这些分支从发送主流程里拆出来，{@code MessageServiceImpl#send} 就不必随消息类型增加而膨胀。
 *
 * <p>实现类声明为 Spring Bean 即可自动生效，由 {@code MessageServiceImpl} 按 {@link #supportedTypes()}
 * 建立类型到处理器的映射；新增消息类型只需加一个实现类，不用改主流程。
 */
public interface MessageContentHandler {

    /**
     * 本处理器负责的消息类型，一个处理器可以覆盖多个类型（如图片 / 文件 / 语音共用附件逻辑）。
     */
    Set<MsgType> supportedTypes();

    /**
     * 校验并就地规范化指令的 {@code content} 与 {@code extra}。
     *
     * <p>校验不通过时抛 {@code BusinessException}，由全局异常处理器转成统一响应。
     *
     * @param cmd 发送指令，{@code fromUserId} 已填充，可用于归属校验
     */
    void normalize(MessageSendCmd cmd);
}
