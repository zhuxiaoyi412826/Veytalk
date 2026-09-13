package com.im.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.file.entity.FileEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件元数据 Mapper。
 */
@Mapper
public interface FileMapper extends BaseMapper<FileEntity> {

    /**
     * 按 MD5 找一条可以复用字节的记录，秒传的依据。
     *
     * <p>必须同时限定 {@code storage_type}：从本地磁盘切到 MinIO 之后，旧记录的 {@code object_key}
     * 在新存储里根本不存在，若只按 MD5 匹配就会写出一条指向空气的新记录，下载时才炸。
     *
     * <p>按 id 升序取最早的一条：MD5 相同时复用哪一份字节没有区别，
     * 但固定取最早的那条能让同一批文件反复上传时始终收敛到同一个对象键，不会在存储里散落多份副本。
     *
     * @return 没有可复用记录时返回 {@code null}
     */
    default FileEntity selectReusableByMd5(String md5, String storageType) {
        return selectOne(Wrappers.<FileEntity>lambdaQuery()
                .eq(FileEntity::getMd5, md5)
                .eq(FileEntity::getStorageType, storageType)
                .orderByAsc(FileEntity::getId)
                .last("LIMIT 1"));
    }
}
