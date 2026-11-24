package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 一屏破冰建议：给「和这个人聊什么」的三档答案。
 *
 * <p>三档分开返回而不是混成一个数组：它们在界面上是三个不同的位置——
 * 打招呼模板是发出去的第一句、话题卡是聊不下去时抛的问题、标签开场白带着「你们共同喜欢 X」的说明，
 * 混成一列就得靠 type 在前端重新分组，而分组的口径（哪一档几条）本来在服务端就已经定好了。
 *
 * <p>三个数组都可能为空，但<b>不会同时为空</b>：只要库里还有启用中的通用文案就有得给。
 * 真的全空说明后台把字典清空了，前端整块不渲染即可，不需要一个额外的「有没有建议」标志位。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "破冰建议")
public class IcebreakerSuggestVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "建议针对的人；没传目标时为 null（此时只给通用文案）")
    private Long targetUserId;

    @Schema(description = "对方昵称，前端写「和 TA 聊什么」这类标题用；查不到人为 null")
    private String nickname;

    @Schema(description = "双方的共同兴趣标签名，标签开场白就是从这几个标签挑出来的；没有共同标签时为空数组")
    private List<String> commonTags;

    @Schema(description = "打招呼模板，最多 8 条，按运营排的次序")
    private List<IcebreakerItemVO> greetings;

    @Schema(description = "话题卡，最多 6 条")
    private List<IcebreakerItemVO> topics;

    @Schema(description = "按共同标签挑出来的开场白，最多 5 条；没有共同标签时为空数组")
    private List<IcebreakerItemVO> tagOpeners;
}
