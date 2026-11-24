package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import com.im.common.enums.ProfileVisitType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 访客记录（{@code im_profile_visit}），一行代表「某一天 TA 来看过我」。
 *
 * <p><b>一人一天一行</b>（{@code visit_date} 在唯一键里）而不是每次访问一行：访客列表要回答的是
 * 「谁来过」，一天刷二十次就出二十条同名记录既没用又撑爆表；{@code times} 把热度保留下来。
 *
 * <p>与 {@link MatchExposure} 的分工：那一张记<b>系统把谁推给了谁</b>，这一张记<b>谁主动点进了谁</b>。
 * 两者不能互相替代——卡池里推过十次没点开的人不该出现在访客列表里，而只从搜索进来看过一眼的人
 * 从来没被推过。
 *
 * <p>本表<b>没有 {@code deleted} 列</b>，所以继承 {@link AuditEntity}：来访是一个发生过的事实。
 * 「不想被看见来过」由访问方的隐身位在写入前拦掉（见 {@code DatingProfileServiceImpl#view}），
 * 而不是事后删行——删过一次就等于允许伪造历史。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_profile_visit")
public class ProfileVisit extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 「谁看过我」回看多少个自然日。
     *
     * <p>取 30 天而不是全量：访客列表是一个「最近谁对我有兴趣」的入口，三年前的来访既无法解释
     * 也会让一个活跃账号的列表永远被老面孔占满头部。窗口只作用于读，历史行仍然留着——
     * 后续会员权益（「看完整访客列表」）要的就是这份历史。
     */
    public static final int LIST_WINDOW_DAYS = 30;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 看的人 */
    private Long visitorId;

    /** 被看的人，访客列表按它查 */
    private Long visitedId;

    /** 访问日期（服务端按当天写入，与唯一键一起把「一天多次」压成一行） */
    private LocalDate visitDate;

    /** 看的什么入口，取值见 {@link ProfileVisitType}；同一行的这一列取最近一次的类型 */
    private Integer viewType;

    /** 当天访问次数 */
    private Integer times;

    /** 当天首次访问时间 */
    private LocalDateTime firstTime;

    /** 当天最近访问时间，列表按它倒序 */
    private LocalDateTime lastTime;
}
