package com.im.common.spi;

/**
 * 用户数据清除契约，由各个持有用户数据的模块（好友、群、会话、消息）分别实现。
 *
 * <p>为「账号注销 / 数据删除」（GDPR / 个保法）服务：im-user 在用户主动销号时聚合调用全部实现，
 * 由每个模块自行删除归属于该用户的数据行。接口定义在 {@code im-common}，实现分散在各业务模块，
 * im-user 只依赖本接口、经 {@code ObjectProvider} 收集所有 bean——与项目既有 SPI 解耦范式一致，
 * 单个模块缺席时销号流程照常进行。
 *
 * <p>实现应尽量幂等：同一 {@code userId} 重复调用不报错。删除失败请直接抛出，交由外层事务统一回滚，
 * 不要吞异常——半个用户的数据被清、另一半留存，比整体失败更难处置。
 */
public interface UserDataEraseSpi {

    /**
     * 清除该用户在本模块持有的全部数据。
     *
     * @param userId 被注销用户的 ID
     */
    void eraseUserData(Long userId);
}
