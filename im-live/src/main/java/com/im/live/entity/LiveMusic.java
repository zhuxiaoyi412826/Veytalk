package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 直播页「音乐」板块的音频作品，对应 {@code im_live_music}，<b>一行一首</b>。
 *
 * <p>列口径与 {@link LiveVideo} 对齐（只差没有 width/height：音频没有画面尺寸），
 * 这样前端 {@code WorkFeed.vue} 一份组件、后台 {@code WorkManage.vue} 一份表格就能吃三个板块，
 * 不必为「播放次数到底叫 playCount 还是 viewCount」做三层字段名映射。
 *
 * <p>音频与封面本体在 {@code im_file}（bizType = music / music_cover，公开），本表只存元数据。
 * {@link #fileUrl} / {@link #coverUrl} 是服务端按 {@code im.file.access-url-prefix} 现拼的长期受控
 * 地址，前端经 {@code mediaUrl} 用带登录头的请求取成 blob 再交给 {@code <audio>}；
 * <b>绝不落票据直链</b>，也不采信前端传来的 URL。
 *
 * <p>{@link #duration} 与 {@link #size} 由客户端读元数据后上报、服务端只存不校验（与视频同口径）：
 * 校验了也拦不住谎报，而谎报只影响列表上的一行文案。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_live_music")
public class LiveMusic extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：正常（在信息流展示） */
    public static final int STATUS_NORMAL = 1;
    /** 状态：已下架（不展示） */
    public static final int STATUS_OFF = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上传者用户 ID */
    private Long uploaderId;

    private String title;

    /** 分类（音乐板块二级分类标签，如 流行/翻唱…），缺省 综合 */
    private String category;

    /** 音频文件 ID（im_file.id，bizType=music） */
    private Long fileId;

    /** 音频受控访问地址 */
    private String fileUrl;

    /** 封面图文件 ID，可空 */
    private Long coverFileId;

    /** 封面图受控访问地址，可空（前端退化为占位图） */
    private String coverUrl;

    /** 时长（秒），上传前由客户端读取元数据后上报，缺省 0 */
    private Integer duration;

    /** 文件字节数 */
    private Long size;

    private String ext;

    private String contentType;

    /** 播放次数（每次点开 +1，非去重） */
    private Integer playCount;

    private Integer status;

    /** 是否本人上传（决定前端能否编辑/删除） */
    public boolean ownedBy(Long userId) {
        return userId != null && userId.equals(uploaderId);
    }
}
