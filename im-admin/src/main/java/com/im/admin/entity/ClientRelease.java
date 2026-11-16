package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 客户端安装包版本，对应 {@code im_client_release}：一行 = 某平台某版本的一个安装包。
 *
 * <p>字节不在本表：安装包经分片上传落在文件模块（{@code im_file} + MinIO/本地存储），
 * 本表只持 {@code fileId} 引用，并把展示要用的文件名/大小/MD5 冗余一份——
 * 列表页与官网下载页都不该为每一行再回查一次文件表（N+1），而这三列在上传完成那一刻
 * 就已定死、不会再变，冗余没有一致性代价。
 *
 * <p><b>启用互斥按平台分组</b>：同一 {@code platform} 下最多一行 {@code status=1}，
 * 由 {@code ClientReleaseService.enable} 在同一事务里「先压平同平台、再立起本行」保证。
 * 官网下载页每个平台只展示并递出启用的那一行——「后台启用哪个版本，访客下载到的就是哪个版本」，
 * 中间没有任何缓存层或手工同步步骤。
 *
 * <p>删除是物理删行且<b>只允许停用态</b>：启用中的行必须先停用再删，否则会出现
 * 「官网卡片还在、字节引用已消失」的半截状态。删行不删字节（对象留在存储里）：
 * 同 MD5 的包可能被另一行秒传复用，删字节会连坐；孤儿对象由存储侧生命周期策略收拾。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_client_release")
public class ClientRelease extends AuditEntity {

    /** 停用：官网列表不返回，公开下载端点也拒绝 */
    public static final int STATUS_DISABLED = 0;
    /** 启用：官网该平台的唯一下载入口；同平台至多一行持此态 */
    public static final int STATUS_ENABLED = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 平台码，取值见 {@link com.im.admin.enums.ReleasePlatform} */
    private String platform;

    /** 版本号（如 1.0.1）。只做格式与长度校验，不做语义比较：1.10 与 1.9 谁新由发版的人知道，代码不猜 */
    private String version;

    /** 更新说明，可空；官网卡片与后台列表原样展示 */
    private String releaseNote;

    /** 安装包字节所在的 {@code im_file} 主键 */
    private Long fileId;

    /** 安装包展示名（含扩展名），公开下载时 Content-Disposition 的文件名取它 */
    private String fileName;

    /** 安装包字节数，创建时从文件行抄来 */
    private Long fileSize;

    /** 整文件 MD5：官网可展示供用户校验下载完整性，也是秒传命中的依据 */
    private String md5;

    /** 状态，取值见本类两个常量 */
    private Integer status;

    /** 累计下载次数：公开下载端点每成功流式输出一次加 1 */
    private Long downloadCount;

    /** 创建人（后台账号 ID） */
    private Long createBy;

    /** 最后修改人（后台账号 ID）：启用/停用/换包都算修改 */
    private Long updateBy;
}
