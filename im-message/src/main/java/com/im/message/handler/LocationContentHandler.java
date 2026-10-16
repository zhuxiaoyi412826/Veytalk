package com.im.message.handler;

import com.im.common.api.ResultCode;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 位置消息处理（{@link MsgType#LOCATION}）。
 *
 * <p>只存「坐标 + 文字地址」这类最小事实，不在服务端拼地图图片：地图瓦片由前端按坐标现取，
 * 服务端既不必依赖外部地图服务、也不受其可用性拖累。三道校验：
 * <ul>
 *   <li>经纬度必须存在且落在合法区间（纬 -90~90、经 -180~180），否则地图缩略图会算出越界瓦片；</li>
 *   <li>文字地址必填、去毒并限长，作为会话摘要与内容检索的正文（写进 {@code content}）；</li>
 *   <li>坐标不做归属校验（位置本身不涉及越权引用他人资源）。</li>
 * </ul>
 */
@Component
public class LocationContentHandler implements MessageContentHandler {

    /** 文字地址最大长度，避免有人塞一整篇小作文进位置摘要 */
    private static final int MAX_NAME_LENGTH = 200;

    @Override
    public Set<MsgType> supportedTypes() {
        return Set.of(MsgType.LOCATION);
    }

    @Override
    public void normalize(MessageSendCmd cmd) {
        MessageExtra extra = cmd.getExtra() == null ? new MessageExtra() : cmd.getExtra();
        Double lat = extra.getLatitude();
        Double lng = extra.getLongitude();
        BusinessException.throwIf(lat == null || lng == null, ResultCode.MESSAGE_CONTENT_ILLEGAL);
        BusinessException.throwIf(lat < -90 || lat > 90 || lng < -180 || lng > 180,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        String name = TextUtil.sanitize(cmd.getContent() == null ? extra.getLocationName() : cmd.getContent());
        name = name == null ? "" : name.trim();
        BusinessException.throwIf(name.isEmpty() || name.length() > MAX_NAME_LENGTH,
                ResultCode.MESSAGE_CONTENT_ILLEGAL);

        extra.setLocationName(name);
        extra.setLatitude(lat);
        extra.setLongitude(lng);
        cmd.setExtra(extra);
        cmd.setContent(name);
    }
}
