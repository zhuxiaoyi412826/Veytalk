package com.im.websocket.service;

import cn.hutool.core.convert.Convert;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.enums.DeviceType;
import com.im.common.exception.BusinessException;
import com.im.common.util.JwtUtil;
import com.im.websocket.dto.vo.WsTicketVO;
import com.im.websocket.manager.WsPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * WebSocket 连接票据的签发与校验。
 *
 * <h2>为什么要用票据，而不是直接把登录 token 挂到 URL 上</h2>
 *
 * <p>浏览器的 {@code WebSocket} 构造器不支持自定义请求头，所以握手阶段能带凭证的位置只有 URL 和 Cookie。
 * 用 URL 带 7 天有效的登录 token，等于把它写进浏览器历史、写进反向代理的 access log、
 * 写进公司出口的流量审计——这些地方都没有 token 轮换机制，泄露一次就是长期的账号失守。
 * Cookie 方案则会把 WebSocket 拖进 CSRF 的攻击面，而本项目 {@code is-read-cookie=false}，压根没有 Cookie 登录态。
 *
 * <p>票据方案把暴露窗口压到 60 秒，且票据只能换一条 WebSocket 连接、换不来任何 REST 接口。
 *
 * <h2>为什么票据不做一次性</h2>
 *
 * <p>做一次性就得在 Redis 里记「哪些票据已用」，握手路径从此硬依赖 Redis：
 * Redis 抖动一下，所有重连全部失败，而重连恰恰是最需要成功的时刻。
 * 现在的取舍是——60 秒 TTL 已经是足够的缓解，票据泄露后能做的事只有一条：
 * 在 60 秒内以该用户身份连一次 WebSocket。相比把这个风险换成「Redis 一挂全站连不上」，前者更可接受。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsTicketService {

    /**
     * 票据用途标识。
     *
     * <p>{@code JwtUtil} 是全系统共用的，文件访问票据也走它。校验时必须比对 purpose，
     * 否则一张 30 分钟有效的文件票据就能拿来建立 WebSocket 连接——
     * 它的签名合法、userId 有效，只是被签来做另一件事的。
     */
    public static final String PURPOSE = "ws-connect";

    private final JwtUtil jwtUtil;
    private final ImProperties imProperties;

    /**
     * 为已登录用户签发连接票据。
     *
     * @param deviceId 客户端声明的设备标识，会经 {@link DeviceType#codeOf} 归一化后写进票据。
     *                 归一化必须在签发时做完而不是留到握手时，
     *                 因为 Sa-Token 的多端管理用的也是归一化后的值，两边不一致会导致踢不中
     */
    public WsTicketVO issue(Long userId, String deviceId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String device = DeviceType.codeOf(deviceId);
        long ttl = imProperties.getJwt().getTicketTtlSeconds();
        String ticket = jwtUtil.createToken(userId, device, PURPOSE, Duration.ofSeconds(ttl));
        log.debug("签发 WebSocket 连接票据: userId={}, deviceId={}, ttl={}s", userId, device, ttl);
        return WsTicketVO.builder()
                .ticket(ticket)
                .endpoint(ImConstants.WS_ENDPOINT)
                .expiresIn(ttl)
                .deviceId(device)
                .build();
    }

    /**
     * 校验票据并还原连接身份。
     *
     * @throws BusinessException 票据缺失、签名不合法、已过期或用途不符
     */
    public WsPrincipal verify(String ticket) {
        Map<String, Object> claims = jwtUtil.parse(ticket);
        if (!PURPOSE.equals(Convert.toStr(claims.get(JwtUtil.CLAIM_PURPOSE)))) {
            // 签名是对的，但这是签给别的用途的票据。日志里记下类型，方便定位是不是前端拿错了 token
            log.warn("WebSocket 票据用途不符: purpose={}", claims.get(JwtUtil.CLAIM_PURPOSE));
            throw new BusinessException(ResultCode.WS_TICKET_INVALID);
        }
        Long userId = Convert.toLong(claims.get(JwtUtil.CLAIM_USER_ID));
        if (userId == null) {
            log.warn("WebSocket 票据缺少 userId 载荷");
            throw new BusinessException(ResultCode.WS_TICKET_INVALID);
        }
        return new WsPrincipal(userId, DeviceType.codeOf(Convert.toStr(claims.get(JwtUtil.CLAIM_DEVICE_ID))));
    }

    /**
     * 静默校验，失败返回 {@code null}。
     *
     * <p>握手拦截器用这个版本：它需要自己决定回什么 HTTP 状态码，
     * 而异常若逃逸到 Spring 的握手流程里，会被翻译成一句没有上下文的 500，
     * 前端只看到「连接失败」，分不清是票据过期还是服务端炸了。
     */
    public WsPrincipal verifyQuietly(String ticket) {
        try {
            return verify(ticket);
        } catch (BusinessException e) {
            log.warn("WebSocket 票据校验未通过: code={}, message={}", e.getCode(), e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // 非业务异常说明票据解析本身出了问题（例如密钥被换过），这类要留完整堆栈
            log.error("WebSocket 票据解析异常", e);
            return null;
        }
    }
}
