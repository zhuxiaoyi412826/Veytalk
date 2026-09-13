package com.im.user.spi;

import com.im.common.spi.UserProfileSpi;
import com.im.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link UserProfileSpi} 在本模块的实现，目前唯一的调用方是 im-file 的头像上传接口。
 *
 * <p>这一层只做薄委派：头像地址的长度裁剪与非法字符清理已经在
 * {@link UserService#updateAvatar} 之前由上传方完成，这里再校验一次只会让「哪一层负责什么」变得模糊。
 */
@Service
@RequiredArgsConstructor
public class UserProfileSpiImpl implements UserProfileSpi {

    private final UserService userService;

    @Override
    public void updateAvatar(Long userId, String avatarUrl) {
        userService.updateAvatar(userId, avatarUrl);
    }
}
