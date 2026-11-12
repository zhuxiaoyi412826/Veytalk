package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminAgreementConsentDTO;
import com.im.common.domain.AdminAgreementConsentQueryDTO;
import com.im.common.domain.AdminAgreementDTO;
import com.im.common.domain.AdminAgreementQueryDTO;

import java.util.List;

/**
 * 后台协议与同意留痕契约，由 im-user 实现（{@code AgreementAdminSpiImpl}）。
 *
 * <p>星型架构老问题：im-admin 不能 import {@code AgreementMapper}，但「起草与发布新版本的
 * 隐私政策 / 用户协议 / 服务条款」和「翻某个用户的同意记录」这两件事只有协议表所在的 im-user
 * 能做，于是契约反转给它。
 *
 * <p>为什么不复用 {@link AdminArticleSpi}：文章的口径是「草稿→待审→发布→下架」，
 * 需要一套审核人和作者分离的状态机；协议的口径是「草稿→发布即生效，旧版自动归档且<b>永不删除</b>」，
 * 而且要连带写一张留痕表。两套流转硬拧进一个接口，最后会变成文章那边多出一个没人用的
 * {@code review()}、协议这边少掉一个必须的「发布时归档旧版」。
 *
 * <p>实现方注意：本接口被调到时 im-user 必然在场（它是主链路模块），但 im-admin 侧仍按惯例用
 * {@code ObjectProvider} 消费——模块缺席时报「模块未装配」，而不是 NoSuchBeanDefinition 的 5000。
 *
 * <p><b>发布是全站生效的动作</b>：新版本一旦落库，所有端的注册页与再同意弹窗立刻读到它。
 * 没有灰度，没有预发，也没有「撤回已发布版本」这条路（见 {@link #publish}）。
 */
public interface AdminAgreementSpi {

    /**
     * 翻页查协议版本，<b>含全状态</b>（草稿 / 已发布 / 已归档）；按 code 升序、version 倒序。
     * 返回的列表项不含正文（content 为 null）。
     */
    PageResult<AdminAgreementDTO> pageAgreements(AdminAgreementQueryDTO query);

    /**
     * 取单个版本全文（含 content），供后台预览与草稿编辑回填。记录不存在抛业务异常。
     */
    AdminAgreementDTO getDetail(Long id);

    /**
     * 起草新版本：在某个 code 下追加一个「当前最大 version + 1」的草稿行。
     *
     * <p>version 由服务端算，客户端传来的任何值一律忽略——版本号是留痕的一部分，
     * 让调用方决定它等于让留痕可以对不上账。
     *
     * <p>同一 code 允许同时存在多份草稿（法务与运营可以各写一版比稿），但只允许存在一份已发布。
     *
     * @param dto        至少含 code/title/content，summary/remark/required 可选
     * @param operatorId 操作管理员，进 create_by 与审计日志
     * @return 落库后的草稿（含正文）
     */
    AdminAgreementDTO createDraft(AdminAgreementDTO dto, Long operatorId);

    /**
     * 编辑草稿（改标题/摘要/正文/是否必签/变更说明）。
     *
     * <p><b>只允许改草稿</b>：已发布与已归档的版本是有人对着点过同意的历史文本，
     * 改它会让那条留痕变成伪证。要变更请另起新版本。记录不存在或状态不允许时抛业务异常。
     */
    AdminAgreementDTO updateDraft(AdminAgreementDTO dto, Long operatorId);

    /**
     * 发布草稿：草稿 → 已发布并盖生效时间，<b>同 code 原先那份已发布自动转归档</b>。
     *
     * <p>归档不是删除：旧行仍在，任何人都还能按 {@code code + version} 查到他当初同意的那份原文。
     *
     * <p>不提供「撤回已发布」：对外公示的合同文本没有收回这种事，只能被新版本顶替。
     * 幂等：目标行已是已发布时直接返回当前档案。
     */
    AdminAgreementDTO publish(Long id, Long operatorId, String remark);

    /**
     * 翻页查同意留痕，按同意时间倒序。实现方自己夹分页上限与关键词长度。
     */
    PageResult<AdminAgreementConsentDTO> pageConsents(AdminAgreementConsentQueryDTO query);

    /**
     * 取某个用户的完整同意链（全 code、全动作，按时间倒序），一屏给监管看完「他签过哪些版本」。
     *
     * <p>不分页：一个人对三份协议的版本数是个位到十几的量级，翻页只会把一份证明材料切成两半。
     * 用户不存在时返回空列表——注销账号的留痕按法定期限保留，但账号本身已经查不到了。
     */
    List<AdminAgreementConsentDTO> listUserConsents(Long userId);
}
