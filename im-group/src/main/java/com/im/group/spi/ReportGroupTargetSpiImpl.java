package com.im.group.spi;

import com.im.common.domain.ReportTargetDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ReportTargetType;
import com.im.common.spi.ReportTargetSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.group.entity.Group;
import com.im.group.mapper.GroupMapper;
import com.im.group.mapper.GroupMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「举报一个群」的证据解析。
 *
 * <p><b>本类刻意不覆写 {@code dispose}</b>：接口默认实现会直接返回失败。
 * 理由不是「还没写」，而是后台对群能做的动作在一期就只有「不处理」和「警告群主」，
 * 那两个都由 im-admin 自己完成（改工单状态、经 {@code PushSpi} 推一条通知），
 * 根本不需要改动群本身。真要「封群」得先有群状态里的封禁位与后续禁止发言链路，那是另一件事。
 *
 * <p>把 dispose 留白还有个实际好处：管理员在下拉里看不到一个会失败的按钮
 * （{@code ReportDisposition} 里 {@code CLOSE_ROOM} 只作用于 room、{@code BAN_USER} 只作用于 user，
 * 群本来就拿不到需要动群本体的动作）。
 *
 * <h2>已解散的群仍然给得出证据</h2>
 *
 * <p>解散是 {@code status = 0}，群名、公告、成员历史都还在（见 {@link Group} 类注释），
 * 所以「群里有人长期发违规内容、群主解散跑路」这种工单依然能复核到举报那一刻的画面。
 * 这里返回证据而不是 null，是因为对象确实存在；能不能处置是工单状态那一步的事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportGroupTargetSpiImpl implements ReportTargetSpi {

    /** 公告摘要本地限长，后台落库前还会再夹一道兜底 */
    private static final int EXCERPT_MAX = 200;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserQuerySpi userQuerySpi;

    @Override
    public String targetType() {
        return ReportTargetType.GROUP.getCode();
    }

    @Override
    public ReportTargetDTO resolve(Long targetId) {
        if (targetId == null) {
            return null;
        }
        Group group = groupMapper.selectById(targetId);
        if (group == null) {
            return null;
        }
        UserBriefDTO owner = group.getOwnerId() == null ? null : userQuerySpi.getById(group.getOwnerId());
        String ownerName = owner == null ? "已注销用户"
                : (TextUtil.isNotBlank(owner.getNickname()) ? owner.getNickname() : owner.getUsername());

        Map<String, Object> meta = new LinkedHashMap<>(6);
        meta.put("群主", ownerName);
        meta.put("成员数", String.valueOf(groupMemberMapper.countActive(group.getId())));
        meta.put("群状态", group.isDismissed() ? "已解散" : "正常");
        meta.put("全员禁言", group.isMuteAllOn() ? "开启" : "关闭");
        meta.put("入群审批", group.isJoinApprovalOn() ? "需要" : "不需要");
        meta.put("建群时间", format(group.getCreateTime()));
        return ReportTargetDTO.builder()
                .targetType(ReportTargetType.GROUP.getCode())
                .targetId(group.getId())
                .reportedUserId(group.getOwnerId())
                .title(TextUtil.isBlank(group.getName()) ? "群聊 #" + group.getId() : group.getName())
                .excerpt(TextUtil.summary(group.getNotice(), EXCERPT_MAX))
                .meta(meta)
                .dispositions(supportedDispositions())
                .build();
    }

    private String format(LocalDateTime time) {
        return time == null ? "—" : TIME_FORMAT.format(time);
    }
}
