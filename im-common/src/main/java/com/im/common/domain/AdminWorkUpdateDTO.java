package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 后台编辑作品请求（图片 / 音乐 / 小说共用）：只改传了值的字段，{@code null} 一律表示「不改」。
 *
 * <p>与 {@link AdminWorkCreateDTO} 分开的理由是媒体语义不同：新建必须带上本板块的媒体
 * （图集 ID / 音频 ID / 正文），编辑时大多只改标题与分类，此时一个媒体字段都不该动——
 * 把两个动作塞进同一个类，实现方就得靠「哪个字段为 null」同时判断「改不改」和「是不是新建」，
 * 两件事耦在一个判据里迟早出错。
 *
 * <p>类里<b>没有 type 字段</b>：板块由 {@code AdminWorkSpi} 的方法入参给出（后台控制器从 URL 的
 * {@code {type}} 段拿），再在 DTO 里放一份只会让「URL 说 image、body 说 novel」变成需要裁决的分歧。
 *
 * <p>状态不在这里改：上下架走 {@code setStatus}（要记事由、要幂等、要单独审计），
 * 借编辑改状态等于把「改个错别字」和「把作品从广场撤下来」写成同一个动作。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWorkUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 标题：非 null 才改，空串按「未命名」占位，超 60 字截断 */
    private String title;

    /** 分类文本：非 null 才改 */
    private String category;

    /** 图集文件 ID（1~9 张）：非 null 才整组替换（图片板块） */
    private List<Long> imageIds;

    /** 音频文件 ID：非 null 才换（音乐板块） */
    private Long fileId;

    /** 封面文件 ID：非 null 才换；传 0 表示「去掉封面」（Long 的 null 已被「不改」占用） */
    private Long coverFileId;

    /** Markdown 正文：非 null 才改，同时服务端重算 wordCount / size / summary 兜底（小说板块） */
    private String content;

    /** 列表摘要：非 null 才改（小说板块） */
    private String summary;

    /** 时长（秒）：非 null 才改（音乐板块） */
    private Integer duration;

    /** 字节数：非 null 才改；随媒体替换一起上报，否则留着旧值是错账 */
    private Long size;

    /** 扩展名：非 null 才改 */
    private String ext;

    /** MIME 类型：非 null 才改 */
    private String contentType;
}
