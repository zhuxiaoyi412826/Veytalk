package com.im.admin.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一个运行期配置项在后台页面上的完整呈现（四期第四批）。
 *
 * <p>前端不需要自己维护一份「哪个 key 叫什么、能填多少」的字典：那就是第二份真相，
 * 而且是一份改了 Java 枚举就不会跟着变的真相。目录元数据全部由服务端从
 * {@code AdminConfigKey} 摊平下来，前端只负责按 {@code type} 决定渲染开关还是数字框、
 * 按 {@code group} 分组展示。
 *
 * <p>{@code value} 与 {@code startupDefault} 都是字符串而不是各自的类型：这一列的取值口径
 * 由服务端的白名单独一份地裁决（{@code "true"} / {@code "120"}），前端原样显示、原样回传，
 * 不在中间做一次「转成 boolean 再拼回去」的翻译——翻译一次就多一种能出错的形式。
 */
@Data
@Schema(description = "运行期配置项（后台系统配置页）")
public class ConfigItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置键，写入时用它寻址；不在白名单里的键服务端直接拒绝")
    private String key;

    @Schema(description = "分组码，与配置文件里 im.* 的属性段同名，如 rateLimit")
    private String group;

    @Schema(description = "分组中文名，页面分区标题用")
    private String groupLabel;

    @Schema(description = "值类型：BOOLEAN 渲染开关，NUMBER 渲染数字框")
    private String type;

    @Schema(description = "项目名称")
    private String title;

    @Schema(description = "这一项到底管什么的说明，含改了会怎样的后果")
    private String description;

    @Schema(description = "NUMBER 的取值下界，BOOLEAN 为空")
    private Long min;

    @Schema(description = "NUMBER 的取值上界，BOOLEAN 为空")
    private Long max;

    @Schema(description = "BOOLEAN 的可选值，固定为 [true, false]")
    private List<String> options;

    @Schema(description = "当前运行态的值")
    private String value;

    @Schema(description = "启动默认值：配置文件与代码默认值在进程启动那一刻的快照，「恢复默认」回到这里")
    private String startupDefault;

    @Schema(description = "是否被后台改过（库里有没有这一行）。false 时它跟随配置文件")
    private Boolean overridden;

    @Schema(description = "最后修改人（后台账号 ID），未覆盖时为空")
    private Long updateBy;

    @Schema(description = "最后修改时间，未覆盖时为空")
    private LocalDateTime updateTime;
}
