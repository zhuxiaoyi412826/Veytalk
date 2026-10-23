package com.im.user.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ConvType;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.user.dto.req.TransferRequest;
import com.im.user.dto.vo.WalletActionVO;
import com.im.user.service.TransferService;
import com.im.user.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 转账实现：一条链路上三个动作必须同生共死——扣付款方、收入款方、发那条转账消息。
 *
 * <p>整个方法只有一个 {@code @Transactional}：{@code WalletService} 的 debit/credit 与
 * {@code MessageSpi#send} 都是 {@code REQUIRED} 传播，会直接加入本事务，于是
 * 「扣了钱没入账」「入了账却没消息」这两种对不上账的中间态在数据库层面不存在。
 *
 * <p>校验必须在扣钱<b>之前</b>全部跑完：转账不像发消息可以失败了再让用户改，
 * 钱一旦动过再回滚，用户看到的就是「余额莫名其妙少了一笔」，即使回滚成功也会引发客诉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private final WalletService walletService;
    private final ConversationSpi conversationSpi;
    private final UserQuerySpi userQuerySpi;
    private final MessageSpi messageSpi;
    private final ImProperties imProperties;
    /** 好友模块缺席时（单模块启动的开发形态）不拦转账，与消息模块对 friendSpi 的降级口径一致 */
    private final ObjectProvider<FriendRelationSpi> friendSpiProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletActionVO transfer(Long userId, TransferRequest request) {
        ImProperties.Wallet wallet = requireTransferEnabled();
        long amount = request.getAmount();
        BusinessException.throwIf(amount < wallet.getTransferMin() || amount > wallet.getTransferMax(),
                ResultCode.TRANSFER_AMOUNT_INVALID);

        Long conversationId = request.getConversationId();
        Integer conversationType = conversationSpi.getType(conversationId);
        BusinessException.throwIf(conversationType == null, ResultCode.CONVERSATION_NOT_FOUND);
        // 群聊没有「转给会话」这回事：目标不明确，且一人转全群等于变相发红包，那条路已经有入口
        BusinessException.throwIf(ConvType.GROUP.getCode() != conversationType, ResultCode.TRANSFER_SINGLE_ONLY);
        BusinessException.throwUnless(conversationSpi.isMember(conversationId, userId),
                ResultCode.CONVERSATION_NO_PERMISSION);

        Long peerId = conversationSpi.getTargetId(conversationId, userId);
        BusinessException.throwIf(peerId == null || peerId.equals(userId), ResultCode.TRANSFER_PEER_NOT_FOUND);
        // 好友判定兼住了拉黑：isFriend 的口径是双向都未拉黑，所以被对方拉黑时也转不出去
        FriendRelationSpi friendSpi = friendSpiProvider.getIfAvailable();
        BusinessException.throwIf(friendSpi != null && !friendSpi.isFriend(userId, peerId),
                ResultCode.TRANSFER_PEER_NOT_FRIEND);
        UserBriefDTO peer = userQuerySpi.getById(peerId);
        BusinessException.throwIf(peer == null, ResultCode.TRANSFER_PEER_NOT_FOUND);
        UserBriefDTO self = userQuerySpi.getById(userId);

        // ref_id 存「对方用户 ID」：付款方那行的对方是收款人，收款方那行的对方是付款人，
        // 这样任一方的流水明细都能直接 JOIN 出对手方，不必再解析备注
        walletService.debit(userId, amount, ImConstants.WALLET_TX_TRANSFER, peerId,
                "转出给-" + brief(peer.getNickname()));
        walletService.credit(peerId, amount, ImConstants.WALLET_TX_TRANSFER, userId,
                "收到-" + brief(self == null ? "对方" : self.getNickname()) + " 的转账");

        MessageExtra extra = MessageExtra.builder()
                .transferAmount(amount)
                .transferToUserId(peerId)
                .transferToNickname(peer.getNickname())
                .transferMemo(request.getMemo())
                .build();
        // internal=true：本类型不允许客户端直接 POST，附言的清洗与敏感词遮蔽由消息模块的内容处理器统一做
        MessageDTO message = messageSpi.send(MessageSendCmd.builder()
                .conversationId(conversationId)
                .fromUserId(userId)
                .msgType(MsgType.TRANSFER.getCode())
                .extra(extra)
                .internal(Boolean.TRUE)
                .build());
        log.info("[转账] fromUserId={}, toUserId={}, amount={}", userId, peerId, amount);
        return WalletActionVO.builder()
                .balance(walletService.getBalance(userId))
                .message(message)
                .build();
    }

    /** 钱包总开关与「用户间转积分」开关串在一起：任一处关掉，转账与红包都拒绝 */
    private ImProperties.Wallet requireTransferEnabled() {
        ImProperties.Wallet wallet = imProperties.getWallet();
        BusinessException.throwUnless(wallet.isEnabled() && wallet.isTransferEnabled(), ResultCode.WALLET_DISABLED);
        return wallet;
    }

    /** 备注里只放截短后的昵称，长度兜在 {@code remark VARCHAR(128)} 之内 */
    private static String brief(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "好友";
        }
        String trimmed = nickname.trim();
        return trimmed.length() <= 24 ? trimmed : trimmed.substring(0, 24);
    }
}
