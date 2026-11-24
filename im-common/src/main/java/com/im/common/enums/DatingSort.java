package com.im.common.enums;

import lombok.Getter;

/**
 * 交友候选池的排序口径，写进 {@code im_match_preference.sort_by}（VARCHAR）。
 *
 * <p>用字符串而不是序号：这一列会被后台报表与 SQL 直接读，{@code 'active'} 比 {@code 4} 好懂得多，
 * 而后面的功能（热门、最新）要往这里加值——加在枚举中间比加在序号中间安全（序号那套一改就错位）。
 *
 * <p><b>取值与建表注释一一对应，不要改字符串字面量</b>：已经落库的行按这些值存。
 *
 * <p>五个取值都已在「筛选与偏好」与「推荐流与排序」两批里落地，但实现位置不同：
 * {@link #RECOMMEND} / {@link #ACTIVE} / {@link #NEW} 是一句 ORDER BY，{@link #HOT} 是「近几天活跃」
 * 的内存分区（它的另一半——收到多少喜欢——住在 im-friend，只能靠候选池查询的
 * {@code boostIds} 送进来），{@link #DISTANCE} 只在带半径的读法里有意义。
 */
@Getter
public enum DatingSort {

    /** 综合推荐：完资料度倒序、同分按活跃度倒序（出厂默认） */
    RECOMMEND("recommend", "综合推荐"),
    /** 活跃优先：最近活跃的人在前，适合「找人聊天」而不是「找人匹配」 */
    ACTIVE("active", "活跃优先"),
    /** 离我最近：只在带半径的读法（附近的人）里有意义，卡池里等同综合推荐 */
    DISTANCE("distance", "离我最近"),
    /** 热度：近几天活跃的人优先，再叠上「喜欢过我的人」与收到的喜欢数 */
    HOT("hot", "热门"),
    /** 最新：新资料与新加入的人优先，冷启动曝光池靠它 */
    NEW("new", "最新");

    private final String code;
    private final String desc;

    DatingSort(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 按 code 反查（忽略大小写与首尾空格），未匹配返回 {@code null}——本枚举不猜默认口径。 */
    public static DatingSort of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        for (DatingSort value : values()) {
            if (value.code.equalsIgnoreCase(trimmed)) {
                return value;
            }
        }
        return null;
    }

    /** 脏值与空值统一退回 {@link #RECOMMEND}：排序口径是一个可省的偏好，不是一个必须由用户给的信息。 */
    public static DatingSort orDefault(String code) {
        DatingSort value = of(code);
        return value == null ? RECOMMEND : value;
    }
}
