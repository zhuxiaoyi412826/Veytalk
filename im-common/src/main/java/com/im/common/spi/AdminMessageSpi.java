package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminMessageDTO;
import com.im.common.domain.AdminMessageOverviewDTO;
import com.im.common.domain.AdminMessageQueryDTO;

/**
 * 后台消息管理契约，由 im-message 实现（{@code MessageAdminSpiImpl}）。
 *
 * <h2>这是整个后台最敏感的一屏</h2>
 *
 * <p>{@code pageMessages} 带 {@code keyword} 时等于<b>读任意两个人聊天正文</b>。
 * 所以它挂在 {@code ImConstants.PERM_SYSTEM_MESSAGE_MANAGE} 上，与 {@code system:log} 同级对待：
 * 已经给了某人 {@code system:group:manage} 或 {@code system:report:handle}，也仍然要
 * 单独授这一点才看得到这一屏。这一条不是形式——工单处置那一屏本来就能看见被举报消息的
 * 上下文，如果这里不设独立点，「按关键词全库搜聊天记录」就会顺着同一个入口一起被给出去。
 *
 * <h2>与用户侧的 {@code message:recall:any} 不是一回事</h2>
 *
 * <p>后者是聊天端的功能权限，能在撤回时限内撤别人的消息（多见于群管理员）；
 * 本契约的 {@code forceRecall} 是后台的<b>无视时限</b>强制撤回，两套点各管各的门。
 * 实现上两者共用 {@code MessageService} 的同一具身体（{@code recallInternal} 的
 * {@code force} 分支），所以「钱已到账的转账与红包不能撤」「系统通知不能撤」
 * 这些硬拒绝在后台同样成立——后台能越过的是<b>时限与权限</b>这两道闸，
 * 不是「撤回会造成资金凭证丢失」这条客观事实。
 *
 * <h2>默认视图是便宜的，筛选才是贵的</h2>
 *
 * <p>{@code im_message} 上能用的索引只有 {@code idx_conv_seq}（会话维度）、
 * {@code idx_send_time} 与主键。不带任何条件的「最新一页」走主键倒扫，取满就停；
 * 一旦加上 {@code keyword}，那就是对 {@code TEXT} 列做 {@code LIKE '%kw%'}，
 * <b>全表扫描 + filesort</b>，且这个代价随消息总量线性增长。两个缓解措施：
 * <ol>
 *   <li>{@code MessageExpireCleanTask} 按 {@code im.message.retention-days} 物理清理过期消息，
 *       表本身不会无限长大（该值默认为 0，即<b>永不清理</b>，那时这一屏的关键词检索就
 *       是真·全表扫，概览里的 {@code expireDays} 为 0 正是这个信号）；</li>
 *   <li>实现方把关键词截到 30 字符、单页截到 50 行，并在有时间窗条件时优先让它走
 *       {@code idx_send_time}——先按时间把范围砍下来再模糊匹配，比反过来便宜得多，
 *       所以前端把「先选时间再搜词」做成提示而不是可选习惯。</li>
</ol>
 *
 * <h2>正文给的是截断后的，附件给的是元数据</h2>
 *
 * <p>图片 / 文件 / 语音这几类，{@code content} 存的是文件 ID，不是可读文本，
 * 所以那一屏的「内容」列实际来自 {@code extra} 摘要（尺寸、时长、文件名）。
 * 摘要<b>不含 {@code fileUrl}</b>：那是受控访问地址，后台看原始附件应当走
 * 文件那一屏的鉴权路径，而不是在这里顺手拿到一个能直接打开的 URL。
 * 文本类正文超过阈值会被截断并置 {@code contentTruncated}，全文要用 {@code detail} 单独取——
 * 让「读全文」成为一个独立的、会打日志的动作，是这一屏为数不多的自我约束。
 */
public interface AdminMessageSpi {

    /**
     * 消息检索翻页。
     *
     * <p>排序固定按主键倒序（雪花 ID 随时间递增，等价于「最近的在上面」），不接受外部排序参数：
     * 按 {@code send_time} 排在带 {@code keyword} 时要对整个结果集 filesort，
     * 而这一屏根本不存在「按时间正序看历史」的真实场景——那是对某个会话的翻阅，
     * 属于会话详情，用户侧 {@code /api/message/history} 已经服务得很好。
     *
     * <p>每行的会话名与类型批量补齐（一页一次），发送者昵称同理；
     * {@code readCount} <b>只在单聊有值</b>，群聊为 null——群已读数要靠
     * {@code im_conversation_member.last_read_seq} 逐成员推算，代价与「一眼看清」不成比例。
     */
    PageResult<AdminMessageDTO> pageMessages(AdminMessageQueryDTO query);

    /**
     * 单条消息全文档案（不截断正文，带引用原消息摘要）。
     *
     * <p>这是唯一会把完整 {@code content} 交出去的方法，调用即记一条含 messageId 的后端日志。
     *
     * @return 不存在返回 {@code null}
     */
    AdminMessageDTO detail(Long messageId);

    /**
     * 顶部概览：今日 / 本周消息量、今日撤回数（区分用户自撤与后台强撤）、
     * 保留天数、最早留存时间、按类型分布。
     *
     * <p>{@code todayAdminRecalled} 单独列出来，是因为它是<b>对后台自身的问责指标</b>：
     * 这一屏的每一次强制撤回都要能数出来。它靠 force 分支在撤回成功时往
     * {@code im_message.extra}（JSON 列，<b>零 DDL</b>）里补一个 {@code recalledByAdmin = true}，
     * 计数即 {@code JSON_EXTRACT}；这个字段上线以前的历史行为 {@code null}，读作「用户自撤」，
     * 不做回溯猜测。
     *
     * <p>两个撤回数字的口径都是<b>今天发出且已被撤回</b>（{@code send_time} 落在今天），
     * 不是「今天撤回了多少」：后者要按 {@code recall_time} 过滤，而那一列没有索引，
     * 等于每次刷新都全表扫。想看「昨天发的那条今天被撤了」，那是逐条核查的场景，
     * 属于 {@code detail}，不是一个该摆在顶部的日计数。
     *
     * <p>{@code typeSlices} 是一次 {@code GROUP BY msg_type}，扫的也是今天这一天
     * （靠 {@code idx_send_time}），不是全表。
     */
    AdminMessageOverviewDTO overview();

    /**
     * 强制撤回：无视时限与发送者身份，但客观禁忌照旧（资金类、系统通知不可撤，已撤的幂等返回）。
     *
     * <p>撤回后双方会话里都会看到那条「消息已被撤回」的系统提示，措辞与群管理员撤
     * 稍有不同（署名「管理员」），这是刻意的：让在场的人知道是谁撤的，
     * 比让后台的处置动作不留痕迹更重要。
     *
     * @return 撤回后的消息档案（{@code recalled = 1}、{@code recallTime} 有值）
     */
    AdminMessageDTO forceRecall(Long messageId, Long adminId);
}
