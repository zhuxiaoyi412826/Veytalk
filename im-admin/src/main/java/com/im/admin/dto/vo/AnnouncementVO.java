package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 全服公告的一行（后台列表、后台详情与用户端补拉共用同一个形状）。
 *
 * <p>用户端也用它而不是另开一个 {@code AnnouncementBriefVO}：公告本来就只有一标题一正文，
 * 两个类之间会差在「谁带了 status」这类细节上，而那种差异一旦出现在接口文档里，
 * 就说明同一份内容有两套解释。多回几个字段对客户端无害，少回几个才要解释。
 *
 * <p>{@code statusName} 由服务端拼好下发：列表里显示「已发布」还是「已撤回」是中文文案，
 * 让前端按数字自己翻译，等于把状态语义复制一份到 JS 里（改一个状态的含义要发两次版）。
 */
@Data
@Schema(description = "全服公告")
public class AnnouncementVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "公告 ID，用户端按它做本地已读去重")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文（已按敏感词遮罩）")
    private String content;

    @Schema(description = "通知样式：info / success / warning / error")
    private String tone;

    @Schema(description = "状态码：0 草稿 1 已发布 2 已撤回")
    private Integer status;

    @Schema(description = "状态中文名")
    private String statusName;

    @Schema(description = "发布时刻，草稿为空")
    private LocalDateTime publishTime;

    @Schema(description = "失效时刻，为空表示不失效")
    private LocalDateTime expireTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "最后修改人（后台账号 ID），发布与撤回都算修改")
    private Long updateBy;
}
