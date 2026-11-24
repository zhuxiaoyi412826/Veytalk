package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminIcebreakerDTO;
import com.im.common.domain.AdminTagDTO;

import java.util.List;

/**
 * 后台字典契约：兴趣标签库（{@code im_tag}）与破冰文案库（{@code im_icebreaker}），由 im-user 实现
 * （{@code DictAdminSpiImpl}）。
 *
 * <p>两张表为什么共用一个接口与一屏：它们在前台本来就是一件事——破冰文案里有一档要绑标签
 * （{@code im_icebreaker.tag_id}），运营改开场白时必须能同时看到标签名。拆成两个接口就是两个菜单页
 * 来回跳，而权限点也已经按「标签与破冰管理」一起种在 V1.0.7（134）。
 *
 * <p>只由后台可写、用户端只读，是这两个库的固有形态：用户只能从启用中的标签里<b>选</b>，
 * 不能自造标签——一旦允许自造，「健身 / 健身ing / 喜欢健身」就会分裂成三个互不相干的同好池，
 * 而同好反查正是标签库存在的全部理由。
 */
public interface AdminDictSpi {

    /* ==================== 兴趣标签 ==================== */

    /**
     * 标签列表，<b>含停用</b>，按 {@code (category, sort, id)} 升序。
     *
     * <p>不分页：这是一个几百行量级的字典（种子 48 行），分页只为把「重名」这种冲突藏到第二页去。
     *
     * @param category 分类名精确过滤，空则返回全部
     */
    List<AdminTagDTO> listTags(String category);

    /**
     * 新增标签。
     *
     * @param name     必填，未删除行内不允许同名；超长由实现方夹到列宽
     * @param category 为空时落兜底分类「其他」，与建表默认值一致
     * @param sort     为空则排到最后
     */
    AdminTagDTO createTag(String name, String category, String icon, Integer sort, Long operatorId);

    /**
     * 修改标签：改名 / 换分类 / 调序 / 启停。传 {@code null} 表示该项不改。
     *
     * <p>改名会<b>即刻反映到所有已绑定它的资料卡</b>（关联表存的是 tag_id，名字现取），
     * 这正是用字典表而不是逗号串的意义。
     */
    AdminTagDTO updateTag(Long id, String name, String category, String icon, Integer sort, Integer status,
                          Long operatorId);

    /**
     * 删除标签（逻辑删）：已绑定的 {@code im_user_tag} 行保留，用户资料卡上那一栏由前端按
     * 「标签已失效」处理而不是整栏消失。幂等。
     *
     * <p>不提供物理删：那会让历史 {@code im_user_tag} 与 {@code im_icebreaker.tag_id} 指向空气，
     * 而这两处都有读方要按 ID 取名。
     */
    void deleteTag(Long id, Long operatorId);

    /* ==================== 破冰文案 ==================== */

    /**
     * 翻页查破冰文案，<b>含停用</b>；按 {@code (type, sort, id)} 升序（与前台下发顺序同一口径，
     * 运营在后台看到的次序就是用户看到的次序，否则「把这条挪到第一位」在两边含义不同）。
     *
     * @param type    文案类型：null 全部，1 打招呼模板 2 话题卡 3 标签开场白
     * @param tagId   只看绑定某个标签的：null 全部
     * @param keyword 按文案内容模糊匹配，长度由实现方夹
     */
    PageResult<AdminIcebreakerDTO> pageIcebreakers(Integer type, Long tagId, String keyword, long current, long size);

    /**
     * 新增一条文案。
     *
     * <p>{@code tagId} 传 0 表示通用；类型 3（标签开场白）必须绑一个存在的标签，否则这句话
     * 里没有可替换的 {@code {tag}}，落到用户手上就是一句带花括号的开场白——实现方要拦。
     */
    AdminIcebreakerDTO createIcebreaker(Integer type, Long tagId, String content, Integer sort, Long operatorId);

    /**
     * 修改一条文案：改类型 / 改绑定 / 改正文 / 调序 / 启停。传 {@code null} 表示该项不改。
     *
     * <p>正文里的占位符<b>不做合法性校验以外的拦截</b>：运营可以写一个新占位符，
     * 服务端 {@code Icebreaker#render} 认不出来时宁可<b>不下发这一条</b>，也不会把花括号原样发给用户。
     */
    AdminIcebreakerDTO updateIcebreaker(Long id, Integer type, Long tagId, String content, Integer sort,
                                        Integer status, Long operatorId);

    /** 删除一条文案（逻辑删）。已复制走的用户手上那份仍在，删行只影响后续下发。幂等。 */
    void deleteIcebreaker(Long id, Long operatorId);
}
