package com.im.user.service;

import com.im.user.dto.req.AgreementAcceptItem;
import com.im.user.dto.vo.AgreementPendingVO;
import com.im.user.dto.vo.AgreementVO;

import java.util.List;

/**
 * 法定文本与同意留痕服务。
 *
 * <p>两类使用者共用这一个 Service：匿名访客要读文本（注册页、官网页脚、公示链接），
 * 已登录用户要对自己的表态负责（弹窗提交、后台举证）。
 */
public interface AgreementService {

    /**
     * 全部当前生效协议的简表（<b>不含正文</b>），按「用户协议、隐私政策、服务条款」固定顺序。
     *
     * <p>注册页与登录页一次拉齐：三份链接、三个版本号、哪几份必签，都在这一趟里拿到。
     */
    List<AgreementVO> listPublished();

    /**
     * 取某份协议的文本。
     *
     * @param code        协议标识
     * @param version     指定版本号；{@code null} 取当前生效版。历史版本同样永久可查，
     *                    这是「他同意过的到底是哪一份」这个问题唯一的公开答案入口
     * @param withContent 是否带正文，公示页 true、内嵌预览 false
     * @return 协议文本；查不到该 code / 该版本时抛 {@code AGREEMENT_NOT_FOUND}
     */
    AgreementVO view(String code, Integer version, boolean withContent);

    /**
     * 某账号当前还有哪些「必签或已改版」的协议没留下同意记录。
     */
    AgreementPendingVO pendingOf(Long userId);

    /**
     * 这个账号是否需要弹一次同意框。登录返回体里那一位布尔用的就是它。
     */
    boolean needConsent(Long userId);

    /**
     * 注册前置校验：必签项一份都不能少，且版本号必须仍是当前生效版。
     *
     * <p>与 {@link #recordConsents} 拆开是刻意的：建号之前先把话问清楚，
     * 免得「号已经建了、同意没交齐」这种半成品状态落进库里——那会让回滚逻辑
     * 变成一处既要删用户又要留证据的两难。
     */
    void validateForRegister(List<AgreementAcceptItem> items);

    /**
     * 落同意留痕。已对同一版本表过态的项跳过不重复写，其余逐项插入。
     *
     * @param source 见 {@code UserAgreement.SOURCE_*}，由调用方（也就是入口）决定，不由客户端传
     */
    void recordConsents(Long userId, List<AgreementAcceptItem> items, String source);

    /**
     * 登录后再同意弹窗的提交口。逐项校验版本仍是当前生效版，然后交给
     * {@link #recordConsents} 落库，source 固定为 {@code dialog}。
     */
    void consent(Long userId, List<AgreementAcceptItem> items);
}
