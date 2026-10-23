package com.im.common.spi;

import com.im.common.domain.AdminMetricDTO;

import java.util.List;

/**
 * 看板指标贡献契约，由各业务模块实现（一期：im-user / im-message / im-live）。
 *
 * <p>存在的理由是星型架构的老问题：后台要把「用户数、今日消息数、在播场数」摆在同一屏，
 * 但 im-admin 谁都不能依赖，只能反过来请各模块把数字递过来。
 *
 * <p>与 {@code MessageUserDataEraseSpi} 那类「一对多」契约同构：im-admin 注入
 * {@code List<AdminMetricsSpi>}，逐个调用后按 {@link #sectionTitle()} 分区显示。
 * <b>某个模块缺席，看板上就少一个分区</b>，这本身就是准确的表达（没装直播模块就没有在播场数），
 * 不需要为它编一个 0。
 *
 * <p>两个必须守住的约定：
 * <ul>
 *   <li>{@link #metrics()} 是<b>只读且可能很贵</b>的（几条 COUNT），实现方不要在里面做循环查询；</li>
 *   <li>后台会对整屏结果做短缓存（默认 60 秒），所以实现方<b>不必</b>自己再缓存一层，
 *       否则两层 TTL 叠出来的滞后会让管理员以为数据坏了。</li>
 * </ul>
 *
 * <p><b>实现类的命名规矩</b>：本接口有四个实现，类名必须各自唯一
 * （现为 {@code UserAdminMetricsSpiImpl} / {@code MessageAdminMetricsSpiImpl} /
 * {@code LiveAdminMetricsSpiImpl} / {@code WebSocketAdminMetricsSpiImpl}）。
 * 若四个都叫 {@code AdminMetricsSpiImpl}，编译不报错，但 Spring 用短类名首字母小写作默认 bean 名，
 * 扫包时会先撞上 {@code ConflictingBeanDefinitionException} 而<b>整个应用起不来</b>。
 * 新增实现请带模块前缀，别去给 {@code @Service} 手动指定 bean 名绕过去（那只是把坑留给下一个同名类）。
 */
public interface AdminMetricsSpi {

    /**
     * 分区标识，英文小写（如 {@code user}），只用于日志与排查。
     */
    String source();

    /**
     * 分区标题，中文（如「用户」「消息」「直播」），直接显示在看板上。
     */
    default String sectionTitle() {
        return source();
    }

    /**
     * 本模块贡献的指标，顺序即看板上的显示顺序。
     *
     * @return 永不为 {@code null}；取不到数的指标应以 {@code value = null} 的形式带上来，
     *         而不是整块返回空列表（那样管理员分不清「没数据」和「模块没实现」）
     */
    List<AdminMetricDTO> metrics();
}
