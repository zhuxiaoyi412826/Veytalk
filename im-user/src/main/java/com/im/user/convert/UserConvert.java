package com.im.user.convert;

import com.im.common.domain.UserBriefDTO;
import com.im.user.dto.vo.UserCardVO;
import com.im.user.dto.vo.UserVO;
import com.im.user.entity.User;

/**
 * 用户对象转换器。
 *
 * <p>刻意做成无状态静态工具：在线状态、好友关系等外部数据由 Service 层补齐，
 * 避免转换器反向依赖 SPI 造成装配上的环。
 */
public final class UserConvert {

    private UserConvert() {
    }

    /**
     * 实体 -> 本人资料 VO。
     */
    public static UserVO toVO(User user) {
        if (user == null) {
            return null;
        }
        return UserVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .signature(user.getSignature())
                .phone(user.getPhone())
                .email(user.getEmail())
                .status(user.getStatus())
                .lastLoginTime(user.getLastLoginTime())
                .createTime(user.getCreateTime())
                .build();
    }

    /**
     * 实体 -> 跨模块精简 DTO，不含手机号与邮箱。
     */
    public static UserBriefDTO toBrief(User user) {
        if (user == null) {
            return null;
        }
        return UserBriefDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .signature(user.getSignature())
                .build();
    }

    /**
     * 实体 -> 他人资料卡片，好友关系字段留空由调用方填充。
     */
    public static UserCardVO toCard(User user) {
        if (user == null) {
            return null;
        }
        return UserCardVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .signature(user.getSignature())
                .createTime(user.getCreateTime())
                .friend(Boolean.FALSE)
                .blocked(Boolean.FALSE)
                .blockedByOther(Boolean.FALSE)
                .build();
    }
}
