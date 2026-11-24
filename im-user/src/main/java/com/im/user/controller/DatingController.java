package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.req.DatingPrivacyRequest;
import com.im.user.dto.req.DatingProfileRequest;
import com.im.user.dto.req.PhotoAddRequest;
import com.im.user.dto.req.PhotoSortRequest;
import com.im.user.dto.req.TagSaveRequest;
import com.im.user.dto.vo.DatingDictVO;
import com.im.user.dto.vo.DatingProfileVO;
import com.im.user.dto.vo.IcebreakerSuggestVO;
import com.im.user.dto.vo.TagCategoryVO;
import com.im.user.dto.vo.TagVO;
import com.im.user.dto.vo.UserPhotoVO;
import com.im.user.service.DatingPhotoService;
import com.im.user.service.DatingProfileService;
import com.im.user.service.DatingTagService;
import com.im.user.service.IcebreakerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 交友资料卡接口：结构化条件、隐私开关、下拉字典、照片墙与兴趣标签，全部需要登录。
 *
 * <p>看别人资料（{@code /profile/{userId}}）不单独计配额：它已经是「滑动匹配 / 附近的人 / 访客」
 * 三个功能的公共读入口，限流规则应该由那些入口按自己的业务节奏定，而不是在这一层一刀切。
 *
 * <p>两个写接口共用 {@code user:profile:update} 权限而不新建权限码：改的是同一个人自己的档案，
 * 能在页面上编辑昵称头像的账号没有理由编辑不了生日。照片墙的五个端点同理开在这里——
 * 它们是同一个人在同一张表上的另一种内容，不是另一种权限。
 *
 * <p>标签的三个端点同理：读的是公共字典，写的是自己的那一组绑定行，仍然不构成新权限。
 *
 * <p>破冰的两个端点也开在这里：它读的还是同一份公共字典（{@code im_icebreaker}），
 * 而「记一次用了这条」改的是字典上的计数列、不是任何人的数据。
 * 后台维护这份字典才是另一回事，那一侧靠 {@code system:tag:manage}。
 *
 * <p>照片相关端点的限流比资料保存紧得多：每张图都要进一次审核，而保存资料与标签不会。
 */
@Tag(name = "20-交友", description = "交友资料卡：结构化资料、隐私开关、完资料度、照片墙与兴趣标签")
@Validated
@RestController
@RequestMapping("/api/dating")
@RequiredArgsConstructor
@SaCheckLogin
public class DatingController {

    private final DatingProfileService datingProfileService;
    private final DatingPhotoService datingPhotoService;
    private final DatingTagService datingTagService;
    private final IcebreakerService icebreakerService;

    @Operation(summary = "查询本人交友资料", description = "含隐私开关、完资料度与待补提示；缺行时自动补建")
    @GetMapping("/profile")
    public Result<DatingProfileVO> mine() {
        return Result.ok(datingProfileService.mine(SecurityUtil.getUserId()));
    }

    @Operation(summary = "保存本人交友资料",
            description = "只更新请求体中非 null 的字段；要清空哪个字段就把它写进 clears。定位由服务端重算 geohash，前端传 geohash 无用")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.profile.save")
    @PutMapping("/profile")
    public Result<DatingProfileVO> saveProfile(@RequestBody @Valid DatingProfileRequest request) {
        return Result.ok(datingProfileService.save(SecurityUtil.getUserId(), request), "资料已更新");
    }

    @Operation(summary = "保存交友隐私开关",
            description = "只更新请求体中非 null 的开关；隐身（discoverable=0）不影响已有的好友与配对关系，"
                    + "而隐身的另一边是：不再被陌生人刷到，且去看别人时不会留下访客记录")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.privacy.save")
    @PutMapping("/profile/privacy")
    public Result<DatingProfileVO> savePrivacy(@RequestBody @Valid DatingPrivacyRequest request) {
        return Result.ok(datingProfileService.savePrivacy(SecurityUtil.getUserId(), request), "设置已保存");
    }

    @Operation(summary = "查询他人交友资料",
            description = "按对方的隐私开关收口：隐藏年龄时 age 为 null，距离只给模糊文案，永不返回经纬度")
    @GetMapping("/profile/{userId}")
    public Result<DatingProfileVO> view(@Parameter(description = "目标用户 ID") @PathVariable Long userId) {
        return Result.ok(datingProfileService.view(userId, SecurityUtil.getUserId()));
    }

    @Operation(summary = "交友字典", description = "学历/收入区间/婚恋状况/交友目的四组下拉项，前端选择器的唯一出处")
    @GetMapping("/dict")
    public Result<DatingDictVO> dict() {
        return Result.ok(datingProfileService.dict());
    }

    /* ------------------------------ 照片墙 ------------------------------ */

    @Operation(summary = "照片墙列表",
            description = "不传 userId 或传自己时返回全部（含审核状态与驳回原因）；看别人时只返回审核通过的地址")
    @GetMapping("/photos")
    public Result<List<UserPhotoVO>> photos(
            @Parameter(description = "照片归属人；不传为本人") @RequestParam(required = false) Long userId) {
        Long currentUserId = SecurityUtil.getUserId();
        return Result.ok(datingPhotoService.list(userId == null ? currentUserId : userId, currentUserId));
    }

    @Operation(summary = "新增一张照片墙照片",
            description = "先用 bizType=dating_photo 上传拿到 fileId，再提交到这里；地址由服务端现拼，最多 9 张")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.photo.add")
    @PostMapping("/photos")
    public Result<List<UserPhotoVO>> addPhoto(@RequestBody @Valid PhotoAddRequest request) {
        return Result.ok(datingPhotoService.add(SecurityUtil.getUserId(), request), "照片已提交");
    }

    @Operation(summary = "删除一张照片墙照片",
            description = "只删照片行，不删文件记录：秒传时同一份字节可能还挂在别人的头像或聊天图上")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.photo.delete")
    @DeleteMapping("/photos/{id}")
    public Result<List<UserPhotoVO>> deletePhoto(@Parameter(description = "照片 ID") @PathVariable Long id) {
        return Result.ok(datingPhotoService.delete(SecurityUtil.getUserId(), id), "照片已删除");
    }

    @Operation(summary = "设为主封面", description = "把它挪到第一位，其余顺序相对不变；对外展示时会自动跳过未通过审核的那张")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.photo.main")
    @PutMapping("/photos/{id}/main")
    public Result<List<UserPhotoVO>> setMainPhoto(@Parameter(description = "照片 ID") @PathVariable Long id) {
        return Result.ok(datingPhotoService.setMain(SecurityUtil.getUserId(), id), "封面已更新");
    }

    @Operation(summary = "重排照片顺序",
            description = "按期望展示顺序提交当前全部照片 ID（第一个即封面）；整序而不是「挑一个插到第 N 位」是为了幂等")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.photo.sort")
    @PutMapping("/photos/sort")
    public Result<List<UserPhotoVO>> sortPhotos(@RequestBody @Valid PhotoSortRequest request) {
        return Result.ok(datingPhotoService.sort(SecurityUtil.getUserId(), request), "顺序已更新");
    }

    /* ------------------------------ 兴趣标签 ------------------------------ */

    @Operation(summary = "兴趣标签库",
            description = "标签选择器的唯一出处：只含启用中的标签，按分类分组；分类顺序即运营在库里排的次序")
    @GetMapping("/tags/library")
    public Result<List<TagCategoryVO>> tagLibrary() {
        return Result.ok(datingTagService.library());
    }

    @Operation(summary = "某人已达成的兴趣标签",
            description = "按其自选顺序返回（最重要的兴趣在前）；后台已停用的标签保留并标 disabled，已被删除的不返回")
    @GetMapping("/tags")
    public Result<List<TagVO>> tags(
            @Parameter(description = "标签归属人；不传为本人") @RequestParam(required = false) Long userId) {
        Long currentUserId = SecurityUtil.getUserId();
        return Result.ok(datingTagService.listByUser(userId == null ? currentUserId : userId));
    }

    @Operation(summary = "保存本人兴趣标签",
            description = "整组替换：数组即最终结果与展示顺序，传 [] 即清空；标签个数计入完资料度")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.tag.save")
    @PutMapping("/tags")
    public Result<List<TagVO>> saveTags(@RequestBody @Valid TagSaveRequest request) {
        return Result.ok(datingTagService.save(SecurityUtil.getUserId(), request), "兴趣标签已保存");
    }

    /* ------------------------------ 破冰建议 ------------------------------ */

    @Operation(summary = "破冰建议",
            description = "三档一起给：打招呼模板、话题卡、按双方共同兴趣标签挑出来的开场白。"
                    + "模板里的 {nickname}/{city}/{tag} 已在服务端替换成对方的真实值，填不出来的那一条不会下发；"
                    + "不传 userId 或传自己时只给通用两档")
    @GetMapping("/icebreakers")
    public Result<IcebreakerSuggestVO> icebreakers(
            @Parameter(description = "建议针对的人；不传为通用文案") @RequestParam(required = false) Long userId) {
        return Result.ok(icebreakerService.suggest(SecurityUtil.getUserId(), userId));
    }

    @Operation(summary = "记一次「用了这条开场白」",
            description = "只给字典加一次使用计数，不改任何文案；这条已被后台停用或删除时回 2032，前端重新拉一次列表即可")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.icebreaker.use")
    @PostMapping("/icebreakers/{id}/use")
    public Result<Void> useIcebreaker(@Parameter(description = "文案 ID") @PathVariable Long id) {
        icebreakerService.use(id);
        return Result.ok();
    }
}
