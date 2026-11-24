package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 后台代建作品请求（图片 / 音乐 / 小说共用），由 im-admin 组装、im-live 落库。
 *
 * <p>为什么不复用 {@link AdminWorkDTO} 收这个表单：那个类的三个 URL 字段是服务端算出来的
 * 受控访问地址，让后台表单直接提交 URL 等于把「地址由前端说了算」写进契约。本类只收
 * {@code im_file.id}——管理员先在后台上传文件拿到 ID，服务端再按配置前缀拼地址。
 *
 * <p>与用户端 {@code LiveVideoCreateRequest} 同一口径：时长/大小/扩展名/MIME 由客户端读元数据
 * 后随请求上报，服务端只存不校验（校验了也拦不住谎报，而谎报只影响列表展示）。
 * 但**图片张数、小说正文字数、摘要**是服务端算的——这三个值前端算错会直接影响信息流与详情渲染。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWorkCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 板块：image / music / novel（必填，决定落到哪张表） */
    private String type;

    /** 标题，可空——空则服务端落「未命名图片/音乐/小说」，超 60 字截断（与用户端同一口径） */
    private String title;

    /** 分类文本，可空——空则落「综合」；不做「必须在字典里」的强校验（字典项停用后历史行还得能保存） */
    private String category;

    /** 图集文件 ID（bizType=image_work），顺序即展示顺序，1~9 张；仅图片板块 */
    private List<Long> imageIds;

    /** 音频文件 ID（bizType=music）；仅音乐板块 */
    private Long fileId;

    /** 封面文件 ID（图片板块留空即取首图；music_cover / novel_cover）；可空 */
    private Long coverFileId;

    /** Markdown 正文；仅小说板块，上限由实现方夹（超限拒绝并提示分篇） */
    private String content;

    /** 列表摘要，可空——空则服务端从正文截取前 80 字；仅小说板块 */
    private String summary;

    /** 时长（秒），客户端上报；仅音乐板块 */
    private Integer duration;

    /** 字节数（图集为各图合计），客户端上报；小说板块由服务端按正文 UTF-8 字节数覆盖 */
    private Long size;

    /** 扩展名，不含点，客户端上报 */
    private String ext;

    /** MIME 类型，客户端上报 */
    private String contentType;
}
