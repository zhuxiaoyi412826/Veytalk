package com.im.user.spi;

import com.im.common.domain.ReportDisposeCmd;
import com.im.common.domain.ReportDisposeResult;
import com.im.common.domain.ReportTargetDTO;
import com.im.common.enums.ReportDisposition;
import com.im.common.enums.ReportTargetType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ReportTargetSpi;
import com.im.common.util.TextUtil;
import com.im.user.entity.User;
import com.im.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「举报一个人」的证据解析与封禁执行。
 *
 * <h2>用户这一类对象没有正文</h2>
 *
 * <p>{@code excerpt} 留空是有意的：举报一个人的资料（头像违规、昵称涉黄）时，
 * 管理员要看的就是那份资料本身，而不是又长又没用的摘要。所以这里把判断材料
 * 摊进 {@code meta}（账号、脱敏手机号、注册时间、最近登录），
 * 昵称直接当 {@code title}——工单列表那一行就靠它认人。
 *
 * <p>这些字段是<b>提交那一刻</b>的快照：被封的人之后把昵称改了，工单上留的仍是举报时的名字，
 * 否则「他为什么被封」这件事会随资料一起被改掉。这也是证据要单独存一份、
 * 而不是处理时再查一遍数据库的原因。
 *
 * <h2>封禁走的是后台用户管理同一条路</h2>
 *
 * <p>动作实现直接委派给本模块的 {@link AdminUserSpiImpl#setDisabled}，不在这里再写一遍
 * {@code UPDATE im_user SET status}：两处写同一列，迟早出现一处踢了连接、另一处忘了踢的分歧。
 * 委派过来的代价是自我豁免与「最后一个管理员」那两道护栏也一起生效——正好是这里想要的行为。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportUserTargetSpiImpl implements ReportTargetSpi {

    /** 时间统一格式化成字符串再进证据：{@code meta} 是给人看的 Map，值原样显示 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserMapper userMapper;
    private final AdminUserSpiImpl adminUserSpi;

    @Override
    public String targetType() {
        return ReportTargetType.USER.getCode();
    }

    @Override
    public ReportTargetDTO resolve(Long targetId) {
        if (targetId == null) {
            return null;
        }
        User user = userMapper.selectById(targetId);
        if (user == null) {
            // 已注销或从未存在：返回 null 让提交侧报 14001，不落一张处理不了的工单
            return null;
        }
        boolean disabled = user.getStatus() != null && user.getStatus() == User.STATUS_DISABLED;
        Map<String, Object> meta = new LinkedHashMap<>(6);
        meta.put("账号", user.getUsername());
        meta.put("手机号", TextUtil.isBlank(user.getPhone()) ? "未绑定" : TextUtil.maskPhone(user.getPhone()));
        meta.put("账号状态", disabled ? "已禁用" : "正常");
        meta.put("注册时间", format(user.getCreateTime()));
        meta.put("最近登录", format(user.getLastLoginTime()));
        return ReportTargetDTO.builder()
                .targetType(ReportTargetType.USER.getCode())
                .targetId(user.getId())
                .reportedUserId(user.getId())
                .title(user.getNickname())
                .meta(meta)
                .dispositions(supportedDispositions())
                .build();
    }

    @Override
    public ReportDisposeResult dispose(ReportDisposeCmd cmd) {
        ReportDisposition action = ReportDisposition.of(cmd.getAction());
        if (action != ReportDisposition.BAN_USER && action != ReportDisposition.UNBAN_USER) {
            return ReportDisposeResult.fail("用户模块不能执行动作「" + cmd.getAction() + "」");
        }
        boolean disabled = action == ReportDisposition.BAN_USER;
        // 时长只在封禁时读：unban_user 传个分钟数进来只能被忽略，与其收一个不读的参数不如不接
        Long minutes = disabled ? cmd.getDisableMinutes() : null;
        try {
            if (adminUserSpi.setDisabled(cmd.getTargetId(), disabled, cmd.getOperatorId(), cmd.getRemark(), minutes) == null) {
                return ReportDisposeResult.fail("该账号不存在或已注销");
            }
        } catch (BusinessException e) {
            // 护栏（禁自己、禁最后一个管理员、时长非法）与「用户不存在」都从这里出来，
            // 原样回显比抛异常有用：管理员能看懂下一步该换个人操作还是该去修数据
            return ReportDisposeResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[举报处置] 变更账号状态异常: userId={}, err={}", cmd.getTargetId(), e.getMessage());
            return ReportDisposeResult.fail("账号状态变更失败：" + e.getMessage());
        }
        return ReportDisposeResult.ok(disabled
                ? (minutes == null ? "已永久封禁账号并踢下线" : "已封禁账号 " + durationText(minutes) + "，到期自动解封")
                : "已解除封禁（对方需重新登录）");
    }

    /**
     * 把分钟数说成一句人话，只取一个最大单位（「2 天 6 小时」这种拼接在回执里只会变长不多信息）。
     *
     * <p>这里报的是「管理员请求的时长」而不是算出来的绝对到期时间：后者由 im-user 按
     * 它自己的时钟算，两边各说一件事比一处说两件事然后对不上好。
     */
    private String durationText(long minutes) {
        if (minutes % (60 * 24) == 0) {
            return minutes / (60 * 24) + " 天";
        }
        if (minutes % 60 == 0) {
            return minutes / 60 + " 小时";
        }
        return minutes + " 分钟";
    }

    private String format(LocalDateTime time) {
        return time == null ? "—" : TIME_FORMAT.format(time);
    }
}
