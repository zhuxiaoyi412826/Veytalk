package com.im.user.service;

import com.im.user.dto.vo.LoginDeviceVO;
import com.im.user.dto.vo.LoginRecordVO;

import java.util.List;

/**
 * 登录设备与账号安全流水。
 *
 * <p>它管三件事：① 每次登录把这台设备登记进 {@code im_user_device} 并写一条 {@code im_login_log}；
 * ② 按「同一类设备最多同时在线 N 台」把超额的<b>其它</b>设备顶下线（先推 WebSocket 报文再注销会话）；
 * ③ 前台「设置 · 登录设备」要用的列表、撤销、下线其他。
 *
 * <p>三条铁律贯穿本类所有方法，改代码前先读完：
 * <ol>
 *   <li><b>老客户端不参与顶号</b>：只上送裸类型码（{@code android}）的设备分不清自己代表几台机器，
 *       拿它顶号会把同一浏览器的多个标签页互相挤下线——这正是当年 {@code kickSameDevice} 被删的原因；</li>
 *   <li><b>先送达再注销</b>：{@code PushSpi.kickOut} 必须在 {@code StpUtil.kickout} 之前，
 *       会话先没了 socket 还开着，客户端就只能看到「连接断了」；</li>
 *   <li><b>淘汰完要重新标记自己在线</b>：注销旧会话会触发 Sa-Token 监听器清在线键，
 *       清不到设备位时它保守地清掉<b>全部</b>终端，不补这一刀就是「明明在线却不绿」。</li>
 * </ol>
 */
public interface DeviceService {

    /**
     * 一次登录成功后的登记与超额处理，由 Sa-Token 监听器调用（四种登录方式共用同一份口径）。
     *
     * <p>实现不得抛出异常打断登录：调用方包了 try-catch，但语义上这里失败只该少一条记录。
     *
     * @param userId     登录用户
     * @param tokenValue 本次登录签发的 token，用于把「自己」从待淘汰名单里排除
     * @param deviceSlot 设备位（{@code 类型:实例}），老客户端为裸类型码
     * @param ip         客户端 IP
     * @param userAgent  User-Agent（已洗成单行并截断）
     * @param deviceName 客户端自报的设备友好名，可为空
     */
    void onLogin(Long userId, String tokenValue, String deviceSlot, String ip, String userAgent, String deviceName);

    /**
     * 记一条与设备相关的流水（主动注销、登录失败、改绑凭证全端下线）。
     *
     * <p>{@code deviceSlot} 为空时只记事件不记设备（例如密码错误但还没走到建会话那一步）。
     * 本方法吞掉所有异常：留痕失败绝不能把业务动作带失败。
     */
    void recordEvent(Long userId, String deviceSlot, int event, String detail);

    /**
     * 某个账号登记过的设备，按最近登录时间倒序。
     *
     * <p>★ 只允许本人调用（{@code /api/device/list} 传的是当前登录用户），
     * 因此本方法不提供给后台或任何跨用户场景复用。
     */
    List<LoginDeviceVO> listByUser(Long userId);

    /**
     * 某个账号的登录与设备变动记录，按发生时间倒序。
     */
    List<LoginRecordVO> listRecords(Long userId, int limit);

    /**
     * 撤销一台设备：注销它在 Sa-Token 里的全部会话、断掉它的长连接，并把登记行标为已撤销。
     *
     * <p>按 {@code (id, userId)} 双条件定位——只按主键删等于给越权开门。
     * 撤销<b>不</b>禁止下次登录：能凭密码登进来就说明身份成立，那时该行会自动回到在用。
     *
     * @return 是否确实改到了行（行不存在或不属于本人时为 false，由调用方决定报什么错）
     */
    boolean revoke(Long userId, Long id);

    /**
     * 下线当前账号<b>除本机以外</b>的全部设备（逐位推 kickout 报文 + 注销会话 + 回写下线时间）。
     *
     * <p>「设置 · 下线其他设备」与「改密 / 换绑后保留本机」两个场景共用它，区别只在文案与事件码。
     *
     * @param reason 被下线的那一端看到的提示语
     * @param event  留痕事件码，见 {@code LoginLog.EVENT_*}
     * @return 被下线的设备数（按设备位计，不是按连接数）
     */
    int logoutOthers(Long userId, String reason, int event);

    /**
     * 按保留期清理登录流水，供定时任务调用。
     *
     * @return 本次删除的行数；等于单轮上限时说明还有存货，下个周期接着删
     */
    int purgeExpiredLogs();
}
