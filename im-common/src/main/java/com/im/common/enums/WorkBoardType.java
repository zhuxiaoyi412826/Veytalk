package com.im.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import com.im.common.constant.ImConstants;
import lombok.Getter;

/**
 * 直播页的四个内容板块，写进 {@code im_live_video_category.type}。
 *
 * <p>用字符串而不是 TINYINT：这一列要与 {@code im_file.biz_type}（image_work / music /
 * novel_cover…）、后台 URL 里的 {@code /api/admin/work/{type}}、前端 tab 名三处对齐，
 * 数字编码在日志里每读一行都得先查字典，而排查「某个板块打不开」时看的就是这些日志与 URL。
 *
 * <p>这个枚举是三处口径的唯一出处，改动必须三处一起看：
 * <ul>
 *   <li>后台通用接口把路径里的 {@code type} 映射成权限点（{@link #permCode()}），
 *       未知 code 直接拒——不写三个同构控制器，但「一屏一个权限点」的约定不能因此松掉；</li>
 *   <li>分类字典的 {@code type} 校验（只接受这四个值，脏值会让某个板块的分类栏永远为空）；</li>
 *   <li>im-live 的 SPI 实现按它分发到三张作品表（{@link #isAdminWork()}）。</li>
 * </ul>
 *
 * <p>{@link #VIDEO} 也在枚举里，但不走通用后台接口：视频板块先于这套机制上线，
 * 已有 {@code /api/admin/video/**} 一套端点、DTO 与前端页面，迁过来只换来回归风险、
 * 拿不到任何好处。它留在这里的理由是「分类字典那一列确实有 video 这一种值」，
 * 少了它字典校验就得在别处再写一份白名单。
 */
@Getter
public enum WorkBoardType {

    /** 视频板块：走自己的 {@code /api/admin/video/**}，通用接口不接 */
    VIDEO("video", "视频", ImConstants.PERM_SYSTEM_VIDEO_MANAGE, false),
    /** 图片板块：一行一个图集（1~9 张，首图作封面），表 {@code im_live_image} */
    IMAGE("image", "图片", ImConstants.PERM_SYSTEM_IMAGE_MANAGE, true),
    /** 音乐板块：一行一首音频，表 {@code im_live_music} */
    MUSIC("music", "音乐", ImConstants.PERM_SYSTEM_MUSIC_MANAGE, true),
    /** 小说板块：一行一篇 Markdown 长篇，表 {@code im_live_novel} */
    NOVEL("novel", "小说", ImConstants.PERM_SYSTEM_NOVEL_MANAGE, true);

    @JsonValue
    private final String code;
    private final String desc;
    /** 这一屏的后台权限点（精确串，伞权限 system:manage 不通配） */
    private final String permCode;
    /** 是否由通用的 {@code /api/admin/work/{type}/**} 与 {@code AdminWorkSpi} 承接 */
    private final boolean adminWork;

    WorkBoardType(String code, String desc, String permCode, boolean adminWork) {
        this.code = code;
        this.desc = desc;
        this.permCode = permCode;
        this.adminWork = adminWork;
    }

    /**
     * 按板块串反查枚举，忽略大小写与首尾空白（前端可能传 {@code IMAGE}）。
     *
     * @return 未匹配时返回 {@code null}，由调用方决定拒绝方式——本枚举不猜「默认视频」
     */
    public static WorkBoardType of(String code) {
        if (code != null) {
            for (WorkBoardType value : values()) {
                if (value.code.equalsIgnoreCase(code.trim())) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 反查「通用后台接口能接」的板块：{@code video} 与未知值都返回 {@code null}，
     * 调用方据此拒请求，不必自己再写一遍白名单。
     */
    public static WorkBoardType ofAdminWork(String code) {
        WorkBoardType type = of(code);
        return type != null && type.adminWork ? type : null;
    }
}
