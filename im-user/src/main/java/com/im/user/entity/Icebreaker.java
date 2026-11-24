package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 一条破冰文案，对应表 {@code im_icebreaker}（后台维护的公共字典，与 {@link InterestTag} 同一档东西）。
 *
 * <p>归属 im-user 而不是 im-friend：它的三档里有两档要读兴趣标签
 * （{@link #TAG_ID_ANY} 之外的行只在双方共有该标签时才给），而标签库
 * {@code im_tag} / {@code im_user_tag} 与 {@code DatingTagService} 都在 im-user。
 * 放到 im-friend 就得为一个字典再开一条 SPI，而 im-friend 拿到文案后也没有别的事可做。
 *
 * <p>{@link #useCount} 是运营口径的计数，只用于「哪些文案没人用」，
 * <b>不参与任何排序与打分</b>：把它排进推荐序会让先用起来的那几条永远排在前面，
 * 新写的文案永无出头之日。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_icebreaker")
public class Icebreaker extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：启用，会出现在建议里 */
    public static final int STATUS_ENABLED = 1;
    /**
     * 状态：停用。<b>只是不再下发，不删行</b>：后台下架一句文案时，
     * 已经复制到聊天框里的用户手上那份还在，删行会让「我刚才用的那条」查不到出处。
     */
    public static final int STATUS_DISABLED = 0;

    /** {@link #tagId} 的这个值表示通用文案，不绑定任何标签（与建表默认值一致） */
    public static final long TAG_ID_ANY = 0L;

    /** 一次建议里打招呼模板最多给几条：够挑，不至于让人在十几句里读不下去 */
    public static final int SUGGEST_GREETING_MAX = 8;
    /** 一次建议里话题卡最多给几条 */
    public static final int SUGGEST_TOPIC_MAX = 6;
    /** 一次建议里标签开场白最多给几条：受共同标签个数限制，通常远小于这个数 */
    public static final int SUGGEST_TAG_OPENER_MAX = 5;

    /** 占位符：对方昵称 */
    public static final String PH_NICKNAME = "{nickname}";
    /** 占位符：对方所在城市 */
    public static final String PH_CITY = "{city}";
    /** 占位符：这条文案绑定的那个共同标签名 */
    public static final String PH_TAG = "{tag}";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 文案类型 code，见 {@code IcebreakerType}：1 打招呼模板 2 话题卡 3 标签开场白 */
    private Integer type;

    /** 绑定的兴趣标签 ID；{@link #TAG_ID_ANY} 表示通用 */
    private Long tagId;

    /** 文案原文，可含 {@link #PH_NICKNAME} / {@link #PH_CITY} / {@link #PH_TAG} 三个占位符 */
    private String content;

    /** 同 type 内的排序，升序 */
    private Integer sort;

    private Integer status;

    /** 被点击使用次数，见类注释 */
    private Integer useCount;

    /** 是不是绑定了某个标签的行（type=3 基本都为真）。 */
    public boolean boundToTag() {
        return tagId != null && tagId != TAG_ID_ANY;
    }

    /**
     * 把占位符替换成这一对用户的真实值；<b>填不出来就返回 {@code null}</b>。
     *
     * <p>替换在服务端而不是前端：模板里出现什么占位符属于<b>内容</b>，
     * 让客户端跟着发版才能让运营改一句文案，那这个字典就白建了。
     *
     * <p>返回 null 的两种情形都不该退化成「把 {city} 原样发出去」：
     * <ul>
     *   <li>模板引用的那一项对方没填（没写城市却要发「看到你在{city}」）；</li>
     *   <li>模板里还留着替换后没消失的 {@code &#123;}——后台新加了一个本类不认识的占位符。
     *       这种情况下宁可少给一条，也不要给用户一句带花括号的开场白。</li>
     * </ul>
     *
     * @param nickname 对方昵称，可空
     * @param city     对方城市，可空
     * @param tagName  这条文案绑定的那个共同标签名，未绑定标签时传 null 即可
     */
    public String render(String nickname, String city, String tagName) {
        String text = content;
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.contains(PH_NICKNAME)) {
            if (isBlank(nickname)) {
                return null;
            }
            text = text.replace(PH_NICKNAME, nickname.trim());
        }
        if (text.contains(PH_CITY)) {
            if (isBlank(city)) {
                return null;
            }
            text = text.replace(PH_CITY, city.trim());
        }
        if (text.contains(PH_TAG)) {
            if (isBlank(tagName)) {
                return null;
            }
            text = text.replace(PH_TAG, tagName.trim());
        }
        // 还留着花括号说明有个占位符没人认得，见方法注释
        return text.indexOf('{') >= 0 ? null : text.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
