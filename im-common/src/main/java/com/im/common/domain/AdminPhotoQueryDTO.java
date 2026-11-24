package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台照片审核队列的查询条件，由 im-admin 组装、im-user 执行。
 *
 * <p>固定按上传时间<b>升序</b>（不是倒序）：审核是清队列，先传来的先处理，
 * 否则新来的图永远插到队首，第一批传图的人等到功能下线都没被看过。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminPhotoQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审核状态：null 全部，0 待审 1 通过 2 驳回。默认让前端停在 0（待审） */
    private Integer auditStatus;

    /** 只看某个人的照片：从资料那一屏点「看 TA 的图」跳过来时用 */
    private Long userId;

    /** 关键词：纯数字时按用户 ID 精确匹配；其余留空即可（图片本身没法按文本搜） */
    private String keyword;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限 */
    private long size;
}
