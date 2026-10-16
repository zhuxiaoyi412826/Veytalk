package com.im.group.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.util.TextUtil;
import com.im.group.dto.req.CreateRobotRequest;
import com.im.group.dto.req.RobotWebhookRequest;
import com.im.group.dto.req.UpdateRobotRequest;
import com.im.group.dto.vo.GroupRobotVO;
import com.im.group.entity.Group;
import com.im.group.entity.GroupRobot;
import com.im.group.mapper.GroupMapper;
import com.im.group.mapper.GroupRobotMapper;
import com.im.group.service.GroupPermissionChecker;
import com.im.group.service.GroupRobotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;

/**
 * 群机器人与 Webhook 业务实现。
 *
 * <p>跨模块依赖（会话、消息）一律走 {@code ObjectProvider}：im-group 单独启动时机器人仍能建，
 * 只是回调发消息时降级为不可用；与 {@code GroupServiceImpl} 的依赖策略保持一致。
 *
 * <p>Webhook 认证采用「能力 URL」模型：token 高熵随机、全局唯一、出现在 URL 里即等同访问凭证。
 * 因此它的安全边界是「token 不可猜 + 机器人启用状态 + 按 IP 限流」三重，任何一环缺失都不该放行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupRobotServiceImpl implements GroupRobotService {

    /** 每群机器人数量上限，防止把群设置面板刷成一长串、也限制滥用面 */
    private static final int MAX_ROBOTS_PER_GROUP = 10;

    /** webhook token 随机字节数，256 位熵足以抗暴力猜测 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GroupRobotMapper groupRobotMapper;
    private final GroupMapper groupMapper;
    private final GroupPermissionChecker permissionChecker;
    private final ObjectProvider<ConversationSpi> conversationSpiProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;

    /* ==================== 管理端 CRUD ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupRobotVO create(Long operatorId, CreateRobotRequest request, String base) {
        BusinessException.throwIf(request == null, ResultCode.BAD_REQUEST, "参数不能为空");
        // requireOwnerOrAdmin 会顺带校验群存在且未解散
        permissionChecker.requireOwnerOrAdmin(request.getGroupId(), operatorId);
        BusinessException.throwIf(groupRobotMapper.countByGroup(request.getGroupId()) >= MAX_ROBOTS_PER_GROUP,
                ResultCode.BAD_REQUEST, "每个群最多 " + MAX_ROBOTS_PER_GROUP + " 个机器人");

        GroupRobot robot = new GroupRobot();
        robot.setGroupId(request.getGroupId());
        robot.setName(TextUtil.sanitize(request.getName(), 64));
        robot.setAvatar(TextUtil.isBlank(request.getAvatar()) ? null : request.getAvatar());
        robot.setDescription(TextUtil.isBlank(request.getDescription())
                ? null : TextUtil.sanitize(request.getDescription(), 255));
        robot.setCreatorId(operatorId);
        robot.setStatus(GroupRobot.STATUS_ENABLED);
        insertWithUniqueToken(robot);

        log.info("[群机器人] 创建: groupId={}, robotId={}, name={}, creator={}",
                robot.getGroupId(), robot.getId(), robot.getName(), operatorId);
        return toVO(robot, base);
    }

    @Override
    public List<GroupRobotVO> list(Long operatorId, Long groupId, String base) {
        permissionChecker.requireOwnerOrAdmin(groupId, operatorId);
        List<GroupRobotVO> result = new ArrayList<>();
        for (GroupRobot robot : groupRobotMapper.listByGroup(groupId)) {
            result.add(toVO(robot, base));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupRobotVO update(Long operatorId, Long robotId, UpdateRobotRequest request, String base) {
        GroupRobot robot = requireRobotWithPermission(operatorId, robotId);
        BusinessException.throwIf(request == null, ResultCode.BAD_REQUEST, "修改内容不能为空");

        var wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.<GroupRobot>lambdaUpdate()
                .eq(GroupRobot::getId, robotId);
        boolean changed = false;
        if (request.getName() != null) {
            String name = TextUtil.sanitize(request.getName(), 64);
            BusinessException.throwIf(TextUtil.isBlank(name), ResultCode.BAD_REQUEST, "机器人名称不能为空");
            wrapper.set(GroupRobot::getName, name);
            robot.setName(name);
            changed = true;
        }
        if (request.getAvatar() != null) {
            if (TextUtil.isBlank(request.getAvatar())) {
                wrapper.setSql("avatar = NULL");
                robot.setAvatar(null);
            } else {
                wrapper.set(GroupRobot::getAvatar, request.getAvatar());
                robot.setAvatar(request.getAvatar());
            }
            changed = true;
        }
        if (request.getDescription() != null) {
            if (TextUtil.isBlank(request.getDescription())) {
                wrapper.setSql("description = NULL");
                robot.setDescription(null);
            } else {
                wrapper.set(GroupRobot::getDescription, TextUtil.sanitize(request.getDescription(), 255));
                robot.setDescription(TextUtil.sanitize(request.getDescription(), 255));
            }
            changed = true;
        }
        if (request.getEnabled() != null) {
            int status = request.getEnabled() ? GroupRobot.STATUS_ENABLED : GroupRobot.STATUS_DISABLED;
            wrapper.set(GroupRobot::getStatus, status);
            robot.setStatus(status);
            changed = true;
        }
        if (changed) {
            groupRobotMapper.update(null, wrapper);
        }
        return toVO(robot, base);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long operatorId, Long robotId) {
        requireRobotWithPermission(operatorId, robotId);
        // 逻辑删除：token 随即不再命中，历史机器人消息的 extra 快照保持可读
        groupRobotMapper.deleteById(robotId);
        log.info("[群机器人] 删除: robotId={}, operator={}", robotId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupRobotVO resetToken(Long operatorId, Long robotId, String base) {
        requireRobotWithPermission(operatorId, robotId);
        // 先置空再写入：直接 update 相同 token 会撞唯一键，这里换新随机值等价于「旧地址失效 + 新地址生成」
        GroupRobot robot = groupRobotMapper.selectById(robotId);
        robot.setWebhookToken(generateToken());
        try {
            groupRobotMapper.updateById(robot);
        } catch (DuplicateKeyException e) {
            robot.setWebhookToken(generateToken());
            groupRobotMapper.updateById(robot);
        }
        log.info("[群机器人] 重置 token: robotId={}, operator={}", robotId, operatorId);
        return toVO(robot, base);
    }

    /* ==================== Webhook 回调 ==================== */

    @Override
    public void sendByWebhook(String token, RobotWebhookRequest request) {
        BusinessException.throwIf(request == null || TextUtil.isBlank(request.getContent()),
                ResultCode.MESSAGE_CONTENT_ILLEGAL);
        GroupRobot robot = groupRobotMapper.selectByToken(token);
        BusinessException.throwIf(robot == null, ResultCode.GROUP_ROBOT_NOT_FOUND);
        BusinessException.throwUnless(robot.isEnabled(), ResultCode.GROUP_ROBOT_DISABLED);

        Group group = groupMapper.selectById(robot.getGroupId());
        BusinessException.throwIf(group == null || group.isDismissed(), ResultCode.GROUP_DISMISSED);

        ConversationSpi conversationSpi = conversationSpiProvider.getIfAvailable();
        BusinessException.throwIf(conversationSpi == null, ResultCode.SYSTEM_ERROR, "会话模块未装配");
        Long conversationId = conversationSpi.findGroup(group.getId());
        BusinessException.throwIf(conversationId == null, ResultCode.CONVERSATION_NOT_FOUND);

        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        BusinessException.throwIf(messageSpi == null, ResultCode.SYSTEM_ERROR, "消息模块未装配");

        MessageExtra extra = MessageExtra.builder()
                .robotName(robot.getName())
                .robotAvatar(robot.getAvatar())
                .build();
        // internal=true 跳过成员/禁言/好友校验；机器人不是真实用户，绝不能走普通发送校验链
        messageSpi.send(MessageSendCmd.builder()
                .conversationId(conversationId)
                .fromUserId(robot.getId())
                .msgType(MsgType.ROBOT.getCode())
                .content(request.getContent())
                .extra(extra)
                .internal(Boolean.TRUE)
                .push(Boolean.TRUE)
                .build());
        log.info("[群机器人] Webhook 发送: robotId={}, groupId={}", robot.getId(), group.getId());
    }

    /* ==================== 内部工具 ==================== */

    /**
     * 取机器人并校验其所在群的管理权限：机器人本身不直接携带操作者可判定的权限，
     * 必须先定位到 groupId 再交给 {@link GroupPermissionChecker} 统一判定。
     */
    private GroupRobot requireRobotWithPermission(Long operatorId, Long robotId) {
        BusinessException.throwIf(robotId == null, ResultCode.BAD_REQUEST, "机器人 ID 不能为空");
        GroupRobot robot = groupRobotMapper.selectById(robotId);
        BusinessException.throwIf(robot == null, ResultCode.GROUP_ROBOT_NOT_FOUND);
        permissionChecker.requireOwnerOrAdmin(robot.getGroupId(), operatorId);
        return robot;
    }

    /**
     * 生成 token 并插入，撞唯一键（概率极低）时重生成重试。
     */
    private void insertWithUniqueToken(GroupRobot robot) {
        for (int attempt = 0; attempt < 3; attempt++) {
            robot.setWebhookToken(generateToken());
            try {
                groupRobotMapper.insert(robot);
                return;
            } catch (DuplicateKeyException e) {
                log.warn("[群机器人] token 碰撞，重试第 {} 次", attempt + 1);
            }
        }
        throw new BusinessException(ResultCode.SYSTEM_ERROR, "生成 webhook 失败，请重试");
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private GroupRobotVO toVO(GroupRobot robot, String base) {
        String root = normalizeBase(base);
        String url = root + "/api/robot/webhook/" + robot.getWebhookToken();
        return GroupRobotVO.builder()
                .robotId(robot.getId())
                .groupId(robot.getGroupId())
                .name(robot.getName())
                .avatar(robot.getAvatar())
                .description(robot.getDescription())
                .webhookToken(robot.getWebhookToken())
                .webhookUrl(url)
                .status(robot.getStatus())
                .enabled(robot.isEnabled())
                .creatorId(robot.getCreatorId())
                .createTime(robot.getCreateTime())
                .build();
    }

    /**
     * 规整前端传来的站点根地址，只保留 {@code scheme://host[:port]}，防被塞入路径或注入。
     *
     * <p>非法或非 http(s) 时返回空串，回调地址退化为相对路径——反正外部系统多半会自己补全域名，
     * 但不能因为一个脏 base 就拼出 {@code https://evil/xxx} 这种钓鱼地址。
     */
    private String normalizeBase(String base) {
        if (TextUtil.isBlank(base)) {
            return "";
        }
        try {
            java.net.URI uri = new java.net.URI(base.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null) {
                return "";
            }
            String lower = scheme.toLowerCase();
            if (!"http".equals(lower) && !"https".equals(lower)) {
                return "";
            }
            int port = uri.getPort();
            return lower + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return "";
        }
    }
}

