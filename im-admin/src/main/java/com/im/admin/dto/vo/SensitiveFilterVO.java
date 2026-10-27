package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 敏感词过滤的运行态（后台配置页用）。
 *
 * <p>三个字段是三种状态，缺一不可：{@code enabled=true} 而 {@code ready=false} 时
 * 界面上看着「已开启」实际一个词都不遮（{@code mask()} 要求两者同时为真），
 * 光看开关会误判成「过滤失效了但没日志」。
 *
 * <p>{@code ready} 现在的含义比四期第一批时宽了一点：词库不再只有 classpath 那一份，
 * {@code wordCount} 是「文件词库 ∪ 后台词库」合并去重后的生效条数，
 * 所以「后台加了 3 个词而这里的数字没动」仍然意味着过滤器没吃到词。
 *
 * <p>{@code overridden} 与 {@code startupDefault} 是这一批落库之后新增的两列：
 * 只有一个 {@code enabled} 的话，管理员分不清「现在开着是因为我打开了」还是
 * 「配置文件里就写着开」，而这两种情况下「关掉会不会重启后回来」的答案完全不同。
 */
@Data
@Schema(description = "敏感词过滤运行态")
public class SensitiveFilterVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "过滤开关：true 为开启（命中遮成 *）")
    private Boolean enabled;

    @Schema(description = "词库是否装载成功：false 时即便 enabled 也不做任何遮蔽")
    private Boolean ready;

    @Schema(description = "实际入树的生效词条数（文件词库与后台词库合并去重后的总数）")
    private Integer wordCount;

    @Schema(description = "当前值是配置文件里写死的还是后台改过并落库的：false 表示重启后回到启动默认值")
    private Boolean overridden;

    @Schema(description = "启动默认值（配置文件与代码默认值在本次进程启动那一刻的快照）")
    private Boolean startupDefault;
}
