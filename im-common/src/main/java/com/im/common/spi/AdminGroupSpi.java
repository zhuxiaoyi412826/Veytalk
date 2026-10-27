package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminGroupDTO;
import com.im.common.domain.AdminGroupMemberQueryDTO;
import com.im.common.domain.AdminGroupQueryDTO;

/**
 * 后台群组管理契约，由 im-group 实现（{@code GroupAdminSpiImpl}）。
 *
 * <h2>两个写动作，都绕开群内权限体系，但绕开的方式要说清</h2>
 *
 * <p>{@code forceDismiss} 与 {@code forceMuteAll} 走的是 {@code GroupService} 上
 * 新开的 {@code force} 分支：<b>只跳过「操作人必须是群主/管理员」这一条校验</b>，
 * 其余一切（置状态、成员批量退群、会话通知、WebSocket 广播、缓存失效）与群主自己点
 * 完全同一条代码。做出这个选择的理由是：管理员解散一个群和群主解散一个群，
 * 对群里那 200 个人而言看到的东西必须一模一样——一条「群已被解散」的系统通知加一个
 * 进不去的会话，而不是「后台悄悄把群拔了，成员还在往里发消息」。
 *
 * <p>反过来说，<b>后台不能做的事也写在这里</b>：不能改群名、不能换群主、不能踢某个成员、
 * 不能设管理员。那些是群内治理，是群主的权利；管理员替群主「主持人事」会让
 * 一个百人群的日常秩序变成平台可随手干预的东西，而 {@code ImConstants.PERM_SYSTEM_GROUP_MANAGE}
 * 的授权理由是「阻断一个正在滋事的群」，不是「接管它」。
 *
 * <h2>强制解散是不可逆的</h2>
 *
 * <p>群 {@code status} 置 0、成员行全部置为已退群，之后没有任何恢复入口
 * （恢复要重新生成成员关系与入群时间，那是造数据而不是撤销）。历史消息仍留在会话表里，
 * 但活人已进不去，只能靠导出。所以 {@code detail} 与列表都<b>必须</b>把已解散的群也显示出来
 * （{@code query.status} 可筛），否则「这个群什么时候没的」就查不到了。
 *
 * <h2>这一屏没有 invite_code，也没有 webhook_token</h2>
 *
 * <p>前者是入群凭证，后者是外部系统代发群消息的凭证，拿到任何一个都能绕过正常关系链做事。
 * 逐条理由写在 {@code AdminGroupDTO} 的类注释里。
 *
 * <h2>台账翻页的代价</h2>
 *
 * <p>{@code im_group} 只有 {@code idx_owner} 一条二级索引，{@code keyword} 是
 * {@code LIKE '%kw%'}（群名要能模糊匹配，前后都加通配符是唯一的写法），所以按名字搜群
 * 是<b>全表扫描 + filesort</b>。单页上限因此夹得比别的屏更严，且实现方把关键词长度截到
 * 30 字符——不是因为长词更危险，而是因为运营真会粘一整段群公告进来。
 */
public interface AdminGroupSpi {

    /**
     * 群台账翻页，默认全部（含已解散），按群 ID（雪花）倒序——新建的群在前，且走主键倒扫。
     *
     * <p>每行的成员数走 {@code GroupMemberMapper.selectMemberCounts} 一次批量
     * {@code GROUP BY group_id}，不是每行数一次——二十行就是二十次查询。
     * 待审批申请数只在档案里有值，列表不带（那需要对每个群各发一次计数，
     * 而 {@code im_group_join_request} 上按群的索引只服务单群分页）。
     */
    PageResult<AdminGroupDTO> pageGroups(AdminGroupQueryDTO query);

    /**
     * 单群档案：群信息 + 最近成员快照 + 最近入群申请（含已处理）+ 机器人（含已停用）。
     *
     * <p>三段快照的条数都由实现方夹死，成员页签另有 {@link #pageMembers} 走真分页。
     *
     * @return 不存在返回 {@code null}
     */
    AdminGroupDTO detail(Long groupId);

    /**
     * 群成员真分页，默认只给在群的（{@code status = 1}）。
     *
     * <p>排序按入群时间正序，群主与管理员靠 {@code role} 提前：这一屏要看的是
     * 「这个群的组织者是谁、他被禁言了没有」，按时间倒序会把最早的一批骨干压到最后一页。
     * <b>只给在群成员</b>（{@code status = 1}）：退群历史虽然可查，但把它做成默认视图会把
     * 「现在这个群有谁」这个 90% 场景掺进一堆退群记录，本批不开这个开关（理由见
     * {@code AdminGroupMemberQueryDTO} 的类注释）。
     *
     * <p>{@code keyword} 同时匹配群内昵称与用户表里的昵称/账号：后者在 {@code im_user} 上，
     * 本模块读不到，所以走 {@code UserQuerySpi#searchUserIds} 先把名字变成 ID 再拼 {@code IN}。
     */
    PageResult<AdminGroupDTO.MemberItem> pageMembers(AdminGroupMemberQueryDTO query);

    /**
     * 强制解散，等价于群主亲自解散，只是不查操作人的群内角色。
     *
     * <p>对已解散的群重复调用是幂等的：实现方按「影响 0 行」当作已成功返回当前档案，
     * 而不是报错——管理员连点两次、或者两个人同时处置同一个群，都不该出现
     * 「第二次失败所以不知道到底散没散」的状态。
     *
     * @param adminId 操作人用户 ID，只进日志与群内通知的措辞，不写进群行
     * @return 解散后的档案（{@code status = 0}）
     */
    AdminGroupDTO forceDismiss(Long groupId, Long adminId);

    /**
     * 强制开/关全员禁言，可逆，因此比解散轻。
     *
     * <p>它挡的是<b>除了群主与管理员以外的所有人</b>（沿用群内禁言的既有豁免规则，
     * 后台不重新发明一遍），所以禁一个滋事的群不等于禁掉那个群里最嗓子的两个人。
     *
     * @return 改动后的档案
     */
    AdminGroupDTO forceMuteAll(Long groupId, boolean muteAll, Long adminId);
}
