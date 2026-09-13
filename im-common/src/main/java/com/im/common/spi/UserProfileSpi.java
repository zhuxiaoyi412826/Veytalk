package com.im.common.spi;

/**
 * 用户资料写入契约，由 im-user 模块实现。
 *
 * <p>与 {@link UserQuerySpi} 刻意分开：查询是只读的、任何模块都能随便调，
 * 写入会改变别人看到的样子，必须单独收口，方便日后统一加审计与频控。
 */
public interface UserProfileSpi {

    /**
     * 更新用户头像地址。
     *
     * <p>供 im-file 的头像上传接口在落盘成功后回调，避免前端「先传头像再改资料」两次请求
     * 中间出现「头像已经传上去了但资料里还是旧地址」的空窗。
     *
     * @param userId    用户 ID
     * @param avatarUrl 头像访问地址
     */
    void updateAvatar(Long userId, String avatarUrl);
}
