package com.im.websocket.manager;

/**
 * WebSocket 连接身份：谁、从哪一端连上来的。
 *
 * <p>刻意做成不可变记录：它在握手阶段由签名票据解析出来，此后整条连接的生命周期内都不该再变。
 * 如果允许中途改身份，就等于允许一条已建立的连接被移交给另一个用户——
 * 而 WebSocket 握手之后不会再有任何一次请求经过 Sa-Token 拦截器，身份一旦松动就没有第二道关卡能拦住它。
 *
 * <p>两个字段都只认票据里的值，不认 URL 查询参数：URL 是客户端可以随意拼的，
 * 拿它当身份来源等于把「我是谁」的决定权交给了连接方。
 *
 * @param userId   用户 ID
 * @param deviceId 设备标识，已经过 {@code DeviceType.codeOf} 归一化
 */
public record WsPrincipal(Long userId, String deviceId) {
}
