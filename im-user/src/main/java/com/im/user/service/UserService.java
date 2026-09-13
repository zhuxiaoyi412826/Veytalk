package com.im.user.service;

import com.im.common.api.PageResult;
import com.im.user.dto.req.ChangePasswordRequest;
import com.im.user.dto.req.UpdateProfileRequest;
import com.im.user.dto.req.UserSearchQuery;
import com.im.user.dto.vo.UserCardVO;
import com.im.user.dto.vo.UserVO;
import com.im.user.entity.User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户领域服务：资料读写、注册落库、检索与在线状态聚合。
 */
public interface UserService {

    /**
     * 按 ID 取用户，不存在时抛业务异常。
     */
    User requireById(Long userId);

    User findByUsername(String username);

    User findByPhone(String phone);

    /**
     * 按账号或手机号查找，登录与添加好友共用。
     */
    User findByAccount(String account);

    boolean existsUsername(String username);

    boolean existsPhone(String phone);

    /**
     * 创建用户并绑定默认的 {@code user} 角色，密码在服务内部加密。
     */
    User createUser(String username, String rawPassword, String nickname, String phone, String email);

    /**
     * 本人完整资料，含角色与权限码。
     */
    UserVO getProfile(Long userId);

    UserVO updateProfile(Long userId, UpdateProfileRequest request);

    /**
     * 修改密码，成功后调用方需要决定是否踢掉已有登录态。
     */
    void changePassword(Long userId, ChangePasswordRequest request);

    /**
     * 更新头像地址，由 im-file 模块上传成功后回调。
     */
    void updateAvatar(Long userId, String avatarUrl);

    /**
     * 记录最近一次登录时间与 IP。
     */
    void touchLogin(Long userId, String ip);

    /**
     * 搜索用户，结果自动排除当前登录用户自己。
     */
    PageResult<UserCardVO> search(UserSearchQuery query, Long currentUserId);

    /**
     * 他人资料卡片，附带与当前用户的好友/拉黑关系。
     */
    UserCardVO getCard(Long targetUserId, Long currentUserId);

    /**
     * 批量查询在线状态。
     */
    Map<Long, Boolean> batchOnline(Collection<Long> userIds);

    List<String> listRoles(Long userId);

    List<String> listPermissions(Long userId);
}
