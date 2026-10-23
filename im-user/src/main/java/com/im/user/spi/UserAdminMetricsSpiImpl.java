package com.im.user.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.domain.AdminMetricDTO;
import com.im.common.spi.AdminMetricsSpi;
import com.im.user.entity.User;
import com.im.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户模块贡献给后台看板的数字。
 *
 * <p>只报自己表里的数（{@code im_user}），不去猜「在线多少人」——那是连接注册表的事实，
 * 由 im-websocket 自己报。这样每个数字都能追到一个明确的出处，看板上不会出现
 * 「用户数」和「在线数」别着劲矛盾却没人知道自己该改哪一版的情况。
 *
 * <p><b>四条指标全都走全表扫</b>（{@code im_user} 只有 username/phone 唯一键与 nickname 索引，
 * 没有 create_time / status 索引）。这是刻意的：一期用户量小，加索引换不到的收益
 * 抵不上「为一个看板数字给主表添一列维护负担」。代价写在这里，
 * 等用户表上了百万级，正解是改读按日汇总表而不是硬扫——那时这条注释就是待办。
 * 缓存由后台那一层兜（默认 60 秒），本类不再自己缓存一层。
 *
 * <p>类名带模块前缀是必需的，原因见 {@code AdminMetricsSpi} 接口注释。
 */
@Service
@RequiredArgsConstructor
public class UserAdminMetricsSpiImpl implements AdminMetricsSpi {

    /** 趋势窗口（天）：与后台的举报趋势同口径，两块数字并排看才对得上 */
    private static final int TREND_DAYS = 7;

    private final UserMapper userMapper;

    @Override
    public String source() {
        return "user";
    }

    @Override
    public String sectionTitle() {
        return "用户";
    }

    @Override
    public List<AdminMetricDTO> metrics() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime weekStart = todayStart.minusDays(TREND_DAYS - 1L);
        List<AdminMetricDTO> metrics = new ArrayList<>(4);
        metrics.add(metric("totalUsers", "用户总数",
                userMapper.selectCount(Wrappers.<User>lambdaQuery()), "含已禁用账号，不含逻辑删除"));
        metrics.add(metric("todayNewUsers", "今日新增",
                userMapper.selectCount(Wrappers.<User>lambdaQuery().ge(User::getCreateTime, todayStart)),
                "按注册时间统计，口径为今日零点起"));
        metrics.add(metric("weekNewUsers", "近 7 天新增",
                userMapper.selectCount(Wrappers.<User>lambdaQuery().ge(User::getCreateTime, weekStart)),
                "含今日，起点为 7 天前的零点"));
        metrics.add(metric("disabledUsers", "已禁用账号",
                userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getStatus, User.STATUS_DISABLED)),
                "由后台停用或举报处置封禁的账号总数"));
        return metrics;
    }

    /**
     * 打包一格数字。{@code selectCount} 理论上不返回 null，但一旦查询异常回空，
     * 看板上应显示「—」而不是 0：「今天真的没人注册」和「我没查到」是两句话。
     */
    private AdminMetricDTO metric(String key, String label, Long value, String hint) {
        return AdminMetricDTO.builder()
                .key(key)
                .label(label)
                .value(value)
                .hint(hint)
                .build();
    }
}
