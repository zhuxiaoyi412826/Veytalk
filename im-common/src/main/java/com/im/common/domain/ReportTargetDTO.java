package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 被举报对象的<b>证据快照</b>，由各模块的 {@code ReportTargetSpi} 产出，序列化后落进 {@code im_report.evidence}。
 *
 * <p>为什么是「快照」而不是「提交工单、处理时再查」：举报天然滞后于内容，
 * 消息会被撤回、直播会关播、群会解散、删号的人会把资料抹掉。
 * 等管理员处理时再去查对象，十有八九查到一个「不存在」，工单就成了废纸。
 * 所以<b>提交那一刻就把能看到的都存下来</b>，之后对象怎么变都不影响处置与追溯。
 *
 * <p>代价必须写明白：<b>这份数据是有隐私与体积代价的</b>：{@link #excerpt} 由归属模块自己截断（服务端限长），
 * 存的是「管理员判案需要的最小内容」而不是全文。这也是为什么它不进消息表或房间表，
 * 只在举报这一条链路上产生。
 *
 * <p>{@link #meta} 的<b>键直接用中文标签</b>而不是英文键名 + 前端字典：这张 Map 唯一的消费者
 * 是后台工单详情那一段「键值对」展示，没有任何代码按键取值。用英文键就等于把一份字段名翻译表
 * 复制到后台前端，之后归属模块加一个快照字段要改两处、还容易漏。代价是这些键<b>不能当契约引用</b>：
 * 谁将来要按键取值，请改成结构化字段而不是去 parse 这份 Map。
 *
 * <p>{@link #dispositions} 是提交那一刻该对象<b>可执行的处置动作码</b>（来自归属模块，
 * 模块没装配就是空）：把它一起存下来，后台列表就能直接渲染出有效的下拉，
 * 不必每行再回查一次 SPI；代价是模块后来才装上时，旧工单的下拉仍然是空的——
 * 那种情况按「重新举报一次」处理，不改动已落库的证据。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "被举报对象的证据快照")
public class ReportTargetDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "对象类型：user / message / room / group")
    private String targetType;

    @Schema(description = "对象 ID")
    private Long targetId;

    @Schema(description = "责任人用户 ID（消息的发送者、房间的主播、群的群主、被举报人本人）；取不到为空")
    private Long reportedUserId;

    @Schema(description = "一句话标识：昵称 / 「消息·来自某某」/ 直播标题 / 群名称")
    private String title;

    @Schema(description = "内容摘要，服务端限长；无正文的对象（如用户）为空")
    private String excerpt;

    @Schema(description = "其余快照字段，键是可直接显示的中文标签、值是已格式化的字符串（时间、所属会话、开播信息等）")
    private Map<String, Object> meta;

    @Schema(description = "提交该对象可执行的处置动作码，空表示归属模块未装配")
    private List<String> dispositions;
}
