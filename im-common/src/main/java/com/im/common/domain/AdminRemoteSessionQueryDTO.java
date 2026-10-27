package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 后台远程会话台账的查询条件，由 im-admin 组装、im-remote 执行。
 *
 * <p>{@code keyword} 只匹配设备标识与设备名，<b>不认用户 ID 也不认昵称</b>：
 * 用户维度有独立的 {@code userId} 精确条件，把昵称塞进 keyword 需要一次跨模块反查
 * （昵称 → 用户 ID 列表 → IN），而 im_remote_session 里存的是 ID，这一跳既慢又容易
 * 在重名用户上给出别人家的会话。与后台用户页「keyword 不认用户 ID」是同一条口径。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminRemoteSessionQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键词：按 deviceId / 设备名模糊匹配，为空不过滤 */
    private String keyword;

    /** 状态过滤：null 全部，其余取 inviting / active / rejected / ended */
    private String status;

    /** 参与方过滤：控制方或被控方任一命中即算，null 不限 */
    private Long userId;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方仍要自己夹上限（调用方的参数不可信） */
    private long size;
}
