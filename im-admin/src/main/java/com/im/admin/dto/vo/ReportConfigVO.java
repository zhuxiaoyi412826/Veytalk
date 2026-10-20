package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 举报相关的服务端字典与开关，一个接口把前端需要知道的都说清。
 *
 * <p><b>为什么不把字典写死在前端</b>：理由码、对象类型码、处置动作码这三份表都落在
 * {@code im_report} 的列里，前端各存一份中文文案就有两处口径，改了枚举忘了改前端，
 * 队列上就会出现「未知理由」；更糟的是 {@code textRequired}——前端不知道某个理由必须填描述，
 * 提交才被打回，用户会觉得按钮在故意刁难。现在这三份都由枚举现出，加一个理由只改后端。
 *
 * <p>用户侧与后台侧<b>共用这一个接口</b>：处置动作码只对管理员有用，但它不是秘密
 * （工单详情里本来就会显示「已封禁账号」），为此拆两个端点只是多一处要同步的地方。
 *
 * <p>刻意<b>不返回</b> {@code dailyQuota} 与 {@code dedupHours}：把「一天能报几条、多久能重报」
 * 告诉客户端等于给脚本一张配额说明书；正常用户撞上限时自会收到 14004 那句提示。
 */
@Data
@Schema(description = "举报字典与开关")
public class ReportConfigVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "举报入口是否开启；关闭时前端隐藏入口，而不是让用户点了才报错")
    private Boolean enabled;

    @Schema(description = "补充描述最大字数，前端 textarea 的 maxlength 与此对齐")
    private Integer reasonTextMax;

    @Schema(description = "举报理由字典")
    private List<ReasonItem> reasons;

    @Schema(description = "可举报的对象类型字典")
    private List<Item> targetTypes;

    @Schema(description = "处置动作字典（后台渲染下拉与回显结论用）")
    private List<Item> dispositions;

    /** 码 + 中文名，够前端渲染 */
    @Data
    @Schema(description = "字典项")
    public static class Item implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "码串")
        private String code;

        @Schema(description = "中文名")
        private String name;

        public Item() {
        }

        public Item(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    /** 举报理由：除码与名字，还要带「是否必须填描述」 */
    @Data
    @Schema(description = "举报理由字典项")
    public static class ReasonItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "理由码")
        private String code;

        @Schema(description = "理由中文名")
        private String name;

        @Schema(description = "该理由是否必须填写补充描述")
        private Boolean textRequired;

        public ReasonItem() {
        }

        public ReasonItem(String code, String name, boolean textRequired) {
            this.code = code;
            this.name = name;
            this.textRequired = textRequired;
        }
    }
}
