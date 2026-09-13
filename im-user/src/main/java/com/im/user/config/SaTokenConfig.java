package com.im.user.config;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import com.im.common.constant.ImConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 登录体系配置。
 *
 * <p>采用 <b>JWT 简单模式</b>（{@link StpLogicJwtForSimple}）：只把 token 的生成方式换成 JWT，
 * 其余行为（会话、多端管理、注销、踢人）完全沿用 Sa-Token 默认的有状态实现，状态都落在 Redis。
 *
 * <p><b>为何不用看起来更“高级”的 {@code StpLogicJwtForMixin}：</b>
 * Mixin 模式把身份判定改成直接解析 JWT payload，因此服务端“让某个 token 失效”的能力被官方主动禁用了。
 * 具体表现是 {@code StpLogicJwtForMixin} 把下面四个方法重写为直接
 * {@code throw new ApiDisabledException()}（默认文案就是“this api is disabled”）：
 * <pre>
 *   _logout(Object loginId, SaLogoutParameter)          ← StpUtil.logout(userId) / kickout(userId) / kickout(userId, device) 都汇聚到这里
 *   _logoutByTokenValue(String token, SaLogoutParameter) ← StpUtil.logoutByTokenValue(token) / kickoutByTokenValue(token)
 *   replaced(Object loginId, String device)              ← is-concurrent=false 时登录流程会自动调用
 *   searchTokenValue(String, int, int, boolean)
 * </pre>
 * 本项目的 {@code AuthServiceImpl} 恰好全部依赖这几个能力：登录时 {@code kickSameDevice} 要
 * {@code kickout(userId, device)} 顶掉同设备旧连接，改密码后 {@code logoutEverywhere} 要
 * {@code logout(userId)} 清全部登录态，续签要 {@code logoutByTokenValue} 注销旧 token。
 * 用 Mixin 的实际后果是：首次登录能成功，但只要 Redis 里还留着同设备的旧 token，
 * 后续每一次登录都会报“this api is disabled”——因为 {@code kickout} 最终会走进被禁用的 {@code _logout}。
 *
 * <p>Simple 模式同样交付“token 是 JWT、网关可离线验签”这个核心诉求，
 * 并且额外支持 {@code StpUtil.getExtra} 读取 JWT 载荷里的扩展参数（Mixin 反而不支持 {@code is-share}）。
 *
 * <p>该模式 {@code isSupportShareToken()} 返回 false，需在配置文件中保持
 * {@code sa-token.is-share=false}、{@code sa-token.jwt-secret-key} 已设置。
 *
 * <p>Sa-Token 的 {@code SaBeanInject} 会自动接收容器中的 {@link StpLogic} Bean 并替换默认实现，
 * 因此这里只需声明 Bean，无需手工调用 {@code StpUtil.setStpLogic}。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class SaTokenConfig {

    @Bean
    public StpLogic stpLogic() {
        log.info("Sa-Token 启用 JWT 简单模式（StpLogicJwtForSimple），loginType={}", ImConstants.LOGIN_TYPE);
        return new StpLogicJwtForSimple(ImConstants.LOGIN_TYPE);
    }
}
