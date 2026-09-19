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

    /**
     * 清除某个 MD5 在当前存储下的全部秒传依据（把 md5 置空）。
     *
     * <p>当发现 MD5 命中的源对象已从存储中消失（如 MinIO 控制台手工删除）时调用：这些行的
     * {@code object_key} 已指向空气，留着 md5 只会让后续每一次相同文件的上传都秒传命中这条坏记录。
     * 置空后它们不再参与 {@link #selectReusableByMd5}，下一次上传会重新写入字节并建立一条指向真实对象的新记录。
     *
     * <p>刻意用 {@code setSql("md5 = NULL")} 而非 {@code .set(FileEntity::getMd5, null)}：
     * 全局 {@code update-strategy: not_null} 会让后者的 null 字段被静默忽略（见 application.yml 的说明），
     * 只有拼 SQL 片段才能真正把列写成 NULL。
     *
     * @return 受影响的行数
     */
    default int clearReusableMd5(String md5, String storageType) {
        return update(null, Wrappers.<FileEntity>lambdaUpdate()
                .setSql("md5 = NULL")
                .eq(FileEntity::getMd5, md5)
                .eq(FileEntity::getStorageType, storageType));
    }
}
