package com.im.common.enums;

import lombok.Getter;

/**
 * 文件存储实现类型，对应配置项 {@code im.file.storage}。
 */
@Getter
public enum StorageType {

    /** MinIO 对象存储 */
    MINIO("minio", "MinIO"),
    /** 本地磁盘 */
    LOCAL("local", "本地磁盘");

    private final String code;
    private final String desc;

    StorageType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static StorageType of(String code) {
        if (code != null) {
            for (StorageType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
        }
        return LOCAL;
    }
}
