package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.api.ResultCode;
import com.im.common.domain.FileDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FileStorageSpi;
import com.im.user.dto.req.PhotoAddRequest;
import com.im.user.dto.req.PhotoSortRequest;
import com.im.user.dto.vo.UserPhotoVO;
import com.im.user.entity.UserPhoto;
import com.im.user.mapper.UserPhotoMapper;
import com.im.user.service.DatingPhotoService;
import com.im.user.service.DatingProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 照片墙实现。
 *
 * <p>与 {@code DatingProfileServiceImpl} 之间是单向依赖：本类注入资料服务用于重算完资料度，
 * 而资料服务只注入 {@link UserPhotoMapper} 取张数。反过来注成双向就是构造器循环依赖，
 * 应用会在启动那一步直接失败。
 *
 * <p>张数、封面、顺序这些「跨行的约束」全部由服务层在每次写入后归一（{@link #applyOrder}）：
 * MySQL 没有部分唯一索引，表达不了「同一用户最多一行为封面」，把它写进 DDL 只会得到一个从不生效的注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatingPhotoServiceImpl implements DatingPhotoService {

    /**
     * 文件业务类型串，与 {@code com.im.file.enums.FileBizType.DATING_PHOTO} 取值一致。
     *
     * <p>这里重复一个字符串而不是直接引用那个枚举：im-user 依赖 im-file 会形成反向依赖，
     * 文件模块将来要用交友数据时就循环了。与 {@code CallContentHandler} 的处理一致。
     */
    private static final String BIZ_DATING_PHOTO = "dating_photo";

    /** 顺序与封面两个不变量的一次重排最多涉及这么多行，超过就是数据脏了 */
    private static final int MAX_ROWS = UserPhoto.MAX_COUNT;

    private final UserPhotoMapper userPhotoMapper;
    private final DatingProfileService datingProfileService;
    /** 文件元数据与受控地址都只能经文件模块取；im-file 缺席时报「存储服务不可用」而不是谎称文件不存在 */
    private final ObjectProvider<FileStorageSpi> fileStorageSpiProvider;

    @Override
    public List<UserPhotoVO> list(Long targetUserId, Long currentUserId) {
        boolean self = targetUserId != null && targetUserId.equals(currentUserId);
        List<UserPhoto> rows = listEntities(targetUserId);
        List<UserPhotoVO> result = new ArrayList<>(rows.size());
        for (UserPhoto row : rows) {
            // 外人视角跳过未通过的行：不是把状态置 0，而是这一行根本不出现在响应里
            if (!self && !isPassed(row)) {
                continue;
            }
            result.add(toVO(row, self));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserPhotoVO> add(Long userId, PhotoAddRequest request) {
        FileStorageSpi storage = requireStorage();
        FileDTO file = storage.getById(request.getFileId());
        // 三重闸门：文件存在 + 是本人上传的 + 业务类型正确。漏掉后两条就能拿别人的图片或聊天图
        // 冒充成自己的照片墙内容，而审核与配额记在谁头上就全乱了
        BusinessException.throwUnless(file != null
                && userId.equals(file.getUploaderId())
                && BIZ_DATING_PHOTO.equals(file.getBizType()), ResultCode.DATING_PHOTO_FILE_INVALID);

        List<UserPhoto> exists = listEntities(userId);
        BusinessException.throwIf(exists.size() >= MAX_ROWS,
                ResultCode.DATING_PHOTO_LIMIT, MAX_ROWS);
        BusinessException.throwIf(exists.stream().anyMatch(row -> Objects.equals(row.getFileId(), file.getFileId())),
                ResultCode.DATING_PHOTO_DUPLICATE);

        UserPhoto photo = new UserPhoto();
        photo.setUserId(userId);
        photo.setFileId(file.getFileId());
        // 地址由服务端现拼：前端传什么 URL 都不算数
        photo.setUrl(storage.accessUrl(file.getFileId()));
        photo.setSort(exists.size());
        // 第一张自动成为封面，否则新用户交完全套照片后资料卡还是空的
        photo.setIsMain(exists.isEmpty() ? UserPhoto.MAIN : UserPhoto.NOT_MAIN);
        // 机器审核真跑过且没命中才当「已通过」；模型降级时这一张留在待审池里等人工
        photo.setAuditStatus(Boolean.TRUE.equals(request.getMachineChecked())
                ? UserPhoto.AUDIT_PASSED : UserPhoto.AUDIT_PENDING);
        photo.setWidth(request.getWidth());
        photo.setHeight(request.getHeight());
        // 字节数取文件元数据，不信客户端上报：它只影响展示，没必要为此再开一条可伪造的路
        photo.setSize(file.getSize());
        userPhotoMapper.insert(photo);

        datingProfileService.recalcScore(userId);
        return list(userId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserPhotoVO> delete(Long userId, Long photoId) {
        requireOwned(userId, photoId);
        // 只逻辑删照片行：不销毁 im_file 记录，秒传时那条记录的字节可能正被别人共用
        userPhotoMapper.deleteById(photoId);
        applyOrder(listEntities(userId));
        datingProfileService.recalcScore(userId);
        return list(userId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserPhotoVO> setMain(Long userId, Long photoId) {
        requireOwned(userId, photoId);
        List<UserPhoto> rows = listEntities(userId);
        List<UserPhoto> ordered = new ArrayList<>(rows.size());
        for (UserPhoto row : rows) {
            if (!Objects.equals(row.getId(), photoId)) {
                ordered.add(row);
            }
        }
        ordered.add(0, userPhotoMapper.selectById(photoId));
        applyOrder(ordered);
        return list(userId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<UserPhotoVO> sort(Long userId, PhotoSortRequest request) {
        List<UserPhoto> rows = listEntities(userId);
        Map<Long, UserPhoto> index = new HashMap<>();
        for (UserPhoto row : rows) {
            index.put(row.getId(), row);
        }
        List<Long> ids = request.getIds();
        // 必须不多不少正好是当前的这组：少传等于偷偷删，多传或掺进别人的 ID 就是越权改他人的排序；
        // 数组里有重复 ID 时 size 也对不上，不需要再单验一次
        BusinessException.throwIf(ids.size() != index.size() || !index.keySet().containsAll(ids),
                ResultCode.DATING_PHOTO_SORT_MISMATCH);
        List<UserPhoto> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ordered.add(index.get(id));
        }
        applyOrder(ordered);
        return list(userId, userId);
    }

    @Override
    public UserPhotoVO cover(Long userId) {
        List<UserPhoto> rows = listEntities(userId);
        UserPhoto fallback = null;
        for (UserPhoto row : rows) {
            if (!isPassed(row)) {
                continue;
            }
            if (Objects.equals(row.getIsMain(), UserPhoto.MAIN)) {
                return toVO(row, false);
            }
            if (fallback == null) {
                fallback = row;
            }
        }
        return fallback == null ? null : toVO(fallback, false);
    }

    @Override
    public Map<Long, UserPhotoVO> covers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        // 行已按 (user_id, sort, id) 排好，所以「每人第一行」就是该人的封面候选；
        // 再额外护一次 is_main，是为了应付 sort 与 is_main 不一致的脏数据（与服务层整序不同源的历史行）
        Map<Long, UserPhoto> picked = new LinkedHashMap<>();
        for (UserPhoto row : userPhotoMapper.selectApprovedByUserIds(userIds)) {
            UserPhoto current = picked.get(row.getUserId());
            if (current == null
                    || (Objects.equals(row.getIsMain(), UserPhoto.MAIN)
                    && !Objects.equals(current.getIsMain(), UserPhoto.MAIN))) {
                picked.put(row.getUserId(), row);
            }
        }
        Map<Long, UserPhotoVO> result = new LinkedHashMap<>();
        for (Map.Entry<Long, UserPhoto> entry : picked.entrySet()) {
            result.put(entry.getKey(), toVO(entry.getValue(), false));
        }
        return result;
    }

    @Override
    public Map<Long, Integer> countApprovedByUserIds(Collection<Long> userIds) {
        return userPhotoMapper.countApprovedByUserIds(userIds);
    }

    @Override
    public List<UserPhoto> listEntities(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // sort 相同的老数据按 id 兜底定序，否则同一秒提交的多张每次刷新顺序都可能不同
        return userPhotoMapper.selectList(Wrappers.<UserPhoto>lambdaQuery()
                .eq(UserPhoto::getUserId, userId)
                .orderByAsc(UserPhoto::getSort)
                .orderByAsc(UserPhoto::getId));
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 按下标重写 {@code sort} 并把第一行置为封面，把两个不变量一次性收敛。
     *
     * <p>已经是目标态的行跳过：整序接口会被前端拖拽连续触发，九行全写一遍是白给的写放大。
     */
    private void applyOrder(List<UserPhoto> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            UserPhoto row = ordered.get(i);
            int main = i == 0 ? UserPhoto.MAIN : UserPhoto.NOT_MAIN;
            if (Objects.equals(row.getSort(), i) && Objects.equals(row.getIsMain(), main)) {
                continue;
            }
            userPhotoMapper.update(null, Wrappers.<UserPhoto>lambdaUpdate()
                    .eq(UserPhoto::getId, row.getId())
                    .set(UserPhoto::getSort, i)
                    .set(UserPhoto::getIsMain, main));
        }
    }

    private UserPhoto requireOwned(Long userId, Long photoId) {
        UserPhoto photo = photoId == null ? null : userPhotoMapper.selectById(photoId);
        // 「不是你的」与「不存在」回同一个码：否则照片 ID 的存在性可以被外部逐个试探出来
        BusinessException.throwIf(photo == null || !Objects.equals(photo.getUserId(), userId),
                ResultCode.DATING_PHOTO_NOT_FOUND);
        return photo;
    }

    private UserPhotoVO toVO(UserPhoto photo, boolean self) {
        UserPhotoVO.UserPhotoVOBuilder builder = UserPhotoVO.builder()
                .id(photo.getId())
                .url(photo.getUrl())
                .sort(photo.getSort())
                .width(photo.getWidth())
                .height(photo.getHeight());
        if (!self) {
            return builder.build();
        }
        return builder
                .fileId(photo.getFileId())
                .isMain(photo.getIsMain())
                .auditStatus(photo.getAuditStatus())
                .auditStatusDesc(auditDesc(photo.getAuditStatus()))
                .rejectReason(photo.getRejectReason())
                .size(photo.getSize())
                .createTime(photo.getCreateTime())
                .build();
    }

    private String auditDesc(Integer status) {
        int value = status == null ? UserPhoto.AUDIT_PENDING : status;
        return switch (value) {
            case UserPhoto.AUDIT_PASSED -> "已通过";
            case UserPhoto.AUDIT_REJECTED -> "未通过";
            default -> "审核中";
        };
    }

    private boolean isPassed(UserPhoto photo) {
        return Objects.equals(photo.getAuditStatus(), UserPhoto.AUDIT_PASSED);
    }

    private FileStorageSpi requireStorage() {
        FileStorageSpi storage = fileStorageSpiProvider.getIfAvailable();
        BusinessException.throwIf(storage == null, ResultCode.FILE_STORAGE_UNAVAILABLE);
        return storage;
    }
}
