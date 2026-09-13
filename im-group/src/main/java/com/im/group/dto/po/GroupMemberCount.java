package com.im.group.dto.po;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「群 -&gt; 在群成员数」的查询投影，供「我的群聊」列表一次性算完所有群的成员数。
 *
 * <p>不复用实体 {@code GroupMember}：这里只需要两列聚合结果，用实体承接会让 MyBatis
 * 走完整的自动映射，把整行字段都解析一遍，纯属浪费；逐群 {@code countActive} 则是 N 次往返。
 */
@Data
public class GroupMemberCount implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long groupId;

    /** 在群成员数，成员全部退群的群不会出现在结果集中 */
    private Integer memberCount;
}
