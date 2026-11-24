package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播页「图片」板块的图集作品，对应 {@code im_live_image}，<b>一行一个图集</b>（1~9 张）。
 *
 * <p>与 {@link LiveVideo} 一样是「控制面」数据：图片本体存在 {@code im_file}
 * （bizType = image_work / music_cover / novel_cover，对所有登录用户公开），本表只存标题、
 * 上传者、张数与受控访问地址。<b>绝不落票据直链</b>（会过期），地址一律服务端按
 * {@code im.file.access-url-prefix} 现拼。
 *
 * <p>{@link #imageIds} / {@link #imageUrls} 用逗号并列而不是子表，是刻意的取舍：两个字段里
 * 的元素分别是雪花 ID 与 {@code /api/file/download/{id}}，<b>都不含逗号</b>，所以并列串可以
 * 无歧义地反解；图集上限 9 张使字段长度可控（{@code VARCHAR(1024)} 装得下 9 个雪花 ID，
 * {@code TEXT} 装得下 9 条受控地址）。换来的是省一张子表与一次 join。<b>别误以为这能塞外部
 * URL</b>——{@link #imageUrls} 由服务端生成，任何前端传来的地址都不采信。
 *
 * <p>{@link #coverFileId} / {@link #coverUrl} 冗余首图：卡片与后台列表要的就是首图，
 * 不冗余就得每次去解析并列串。它俩恒等于 {@link #imageIds} / {@link #imageUrls} 的第一项，
 * 由服务层写入时保证，不接受独立设置（否则「封面和图不一致」会变成第二个 bug 报告）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_live_image")
public class LiveImage extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：正常（在信息流展示） */
    public static final int STATUS_NORMAL = 1;
    /** 状态：已下架（不展示） */
    public static final int STATUS_OFF = 2;

    /** 图集张数下限（至少要有一张） */
    public static final int MIN_IMAGES = 1;
    /** 图集张数上限：与前端多选、卡片九宫格同一口径，超过就是发错了板块 */
    public static final int MAX_IMAGES = 9;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上传者用户 ID */
    private Long uploaderId;

    private String title;

    /** 分类（图片板块二级分类标签，如 风景/人像…），缺省 综合 */
    private String category;

    /** 图集文件 ID 逗号并列（{@code im_file.id}，顺序即展示顺序） */
    private String imageIds;

    /** 图集受控访问地址逗号并列，与 {@link #imageIds} 同序，服务端现拼（列名 image_urls） */
    private String imageUrls;

    /** 首图文件 ID（冗余，等于 {@link #imageIds} 的第一项） */
    private Long coverFileId;

    /** 首图受控访问地址（冗余，等于 {@link #imageUrls} 的第一项） */
    private String coverUrl;

    /** 图集张数（1~9） */
    private Integer imageCount;

    /** 各图字节数合计 */
    private Long size;

    /** 首图扩展名，不含点 */
    private String ext;

    /** 首图 MIME 类型 */
    private String contentType;

    /** 打开次数（每次点开图集 +1，非去重） */
    private Integer playCount;

    private Integer status;

    /** 是否本人上传（决定前端能否编辑/删除） */
    public boolean ownedBy(Long userId) {
        return userId != null && userId.equals(uploaderId);
    }

    /**
     * 反解 {@link #imageIds} 为文件 ID 列表；空串给空列表。
     *
     * <p>不做数字校验：这些值只可能由服务层写入，读到脏数据说明库被人手改过，
     * 让它在下游抛出来比在这里静默丢掉一张图更容易被发现。
     */
    public List<Long> fileIdList() {
        List<Long> ids = new ArrayList<>();
        if (imageIds == null || imageIds.isBlank()) {
            return ids;
        }
        for (String part : imageIds.split(",")) {
            if (!part.isBlank()) {
                ids.add(Long.parseLong(part.trim()));
            }
        }
        return ids;
    }

    /** 反解 {@link #imageUrls} 为受控地址列表，口径同 {@link #fileIdList()} */
    public List<String> urlList() {
        List<String> urls = new ArrayList<>();
        if (imageUrls == null || imageUrls.isBlank()) {
            return urls;
        }
        for (String part : imageUrls.split(",")) {
            if (!part.isBlank()) {
                urls.add(part.trim());
            }
        }
        return urls;
    }
}
