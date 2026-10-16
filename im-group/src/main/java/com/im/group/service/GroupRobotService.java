package com.im.group.service;

import com.im.group.dto.req.CreateRobotRequest;
import com.im.group.dto.req.RobotWebhookRequest;
import com.im.group.dto.req.UpdateRobotRequest;
import com.im.group.dto.vo.GroupRobotVO;

import java.util.List;

/**
 * 群机器人与 Webhook 业务接口。
 *
 * <p>机器人是「外部系统 → 群消息」的单向入站通道：管理员在群设置里建机器人拿到一个回调 URL，
 * 外部系统向该 URL POST 文本，服务端以 {@code MsgType.ROBOT} 落一条内部消息并正常推送给全群。
 *
 * <p>除 {@link #sendByWebhook} 外全部方法第一个参数为操作者 ID，且都要求群主或管理员身份。
 */
public interface GroupRobotService {

    /**
     * 创建机器人：生成全局唯一 webhook token，返回含回调地址的视图。仅群主与管理员可操作。
     *
     * @param base 前端站点根地址，用于拼 {@code webhookUrl}；为空时回调地址退化为相对路径
     */
    GroupRobotVO create(Long operatorId, CreateRobotRequest request, String base);

    /**
     * 列出本群全部机器人（含 token 与回调地址），仅群主与管理员可见。
     */
    List<GroupRobotVO> list(Long operatorId, Long groupId, String base);

    /**
     * 修改机器人名称 / 头像 / 说明 / 启停状态，字段为 {@code null} 表示不改。仅群主与管理员可操作。
     */
    GroupRobotVO update(Long operatorId, Long robotId, UpdateRobotRequest request, String base);

    /**
     * 逻辑删除机器人（token 随即失效，历史机器人消息的身份快照不受影响）。仅群主与管理员可操作。
     */
    void delete(Long operatorId, Long robotId);

    /**
     * 重置 webhook token：旧地址立刻失效。仅群主与管理员可操作。
     */
    GroupRobotVO resetToken(Long operatorId, Long robotId, String base);

    /**
     * Webhook 入口（匿名，凭 token 认证）：向机器人所在群发一条机器人消息。
     *
     * <p>机器人不存在 / 已删除抛 6010，已停用抛 6011，所在群已解散抛 6009。
     */
    void sendByWebhook(String token, RobotWebhookRequest request);
}
