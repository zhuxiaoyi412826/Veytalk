package com.im.common.spi;

/**
 * 系统联系人装配契约，由 im-friend 模块实现。
 *
 * <p>把「每个用户自动挂上客服与文件传输助手两个保留账号」这件事收口成一个跨模块动作：
 * 注册发生在 im-user，而好友关系与会话分别归 im-friend、im-conversation，im-user 只能经本契约
 * 委托 im-friend 完成，不能直接跨模块写关系表。
 *
 * <p>实现必须幂等：已建立的关系与已存在的会话都不重复创建，注册钩子与存量回填可安全重跑。
 */
public interface SystemContactSpi {

    /**
     * 为指定用户装配系统保留联系人：与客服、文件传输助手各建一条双向好友关系与单聊会话，
     * 并在会话里落一条欢迎 / 使用说明的系统通知。
     *
     * <p>best-effort：内部对每条副作用单独吞异常，绝不因通知发送失败而让调用方（注册主流程）回滚。
     *
     * @param userId 新建用户 ID；传入保留账号自身 ID 时实现应直接忽略
     */
    void provisionContacts(Long userId);
}
