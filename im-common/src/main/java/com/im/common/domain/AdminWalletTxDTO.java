package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一条钱包账本流水（{@code im_wallet_transaction}），后台视角。
 *
 * <p>{@code amount} 是<b>有符号</b>的：出账为负。前端渲染红绿全靠它，
 * 所以这里不再另给一个「方向」字段——两个字段表达同一件事迟早会互相矛盾
 * （{@code amount=+100, direction=out} 这种行一旦出现在响应里，
 * 排查的人会先怀疑数据库而不是怀疑代码）。
 *
 * <p>{@code refId} 的语义<b>随 type 变</b>，这是写侧（{@code WalletServiceImpl#writeTransaction}）
 * 定的口径，读侧只能照抄：送礼存记录/房间 ID，{@code transfer} 存对方用户 ID，
 * {@code packet}/{@code refund} 存红包 ID，{@code live_pay} 存房间 ID，其余为 null。
 * 所以后台把它当「关联 ID」原样显示而不猜成某个具体实体，跳转链接只在
 * {@code type=adjust} 这一路（本批新增的后台调整）才给得准。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletTxDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 流水 ID（雪花） */
    private Long txId;

    /** 钱包归属用户 ID */
    private Long userId;

    /** 归属用户昵称（列表页批量补，查不到给「已注销用户」） */
    private String nickname;

    /** 类型：recharge / gift / checkin / adjust / transfer / packet / refund / live_pay */
    private String type;

    /** 变动额（有符号，出账为负） */
    private Long amount;

    /** 交易后余额快照（落库时回读，不是后台现算） */
    private Long balanceAfter;

    /** 关联业务 ID，语义随 type 变，见类注释 */
    private Long refId;

    /** 备注（充值到账/送礼/后台调整事由） */
    private String remark;

    /** 流水时间 */
    private LocalDateTime createTime;
}
