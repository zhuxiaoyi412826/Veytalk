import http from './request'

/**
 * 虚拟积分/币钱包与礼物 REST 接口。
 *
 * 不涉真实货币：充值走后端模拟支付桩（对齐 D1 降级模式），签到每日赠送固定积分。
 * 礼物目录与余额一次取回（fetchWallet），供直播间礼物面板直接渲染；送礼扣款与飘屏
 * 由后端在事务提交后经 /ws/live 广播，本模块只负责发起与乐观更新。
 *
 * 雪花 ID（roomId/giftId）经后端 SafeLongSerializer 已序列化为字符串，前端当不透明字符串用即可。
 */

/**
 * 取钱包：余额 + 上架礼物目录 + 今日是否已签到。
 * @returns {Promise<{ balance: number, gifts: Array<{ giftId: string, name: string, icon: string, price: number, kind: string }>, signedToday: boolean }>}
 */
export function fetchWallet() {
  return http.get('/wallet', { silent: true })
}

/**
 * 充值（模拟）：按请求额到账，返回带「模拟」标注的订单号与最新余额。
 * @param {number} amount 充值积分数量
 */
export function recharge(amount) {
  return http.post('/wallet/recharge', { amount })
}

/** 每日签到：赠送固定积分，重复签到后端回业务错误 */
export function checkin() {
  return http.post('/wallet/checkin')
}

/**
 * 送礼：后端校验房间直播中 → 原子扣款 → 落流水 → 事务提交后广播飘屏。
 * @param {string|number} roomId 房间 ID
 * @param {string|number} giftId 礼物 ID
 * @param {number} count 数量，后端夹在 [1, maxGiftCount]
 * @returns {Promise<{ balance: number, giftName: string, giftIcon: string, count: number, totalPrice: number }>}
 */
export function sendGift(roomId, giftId, count = 1) {
  return http.post(`/live/${roomId}/gift`, { giftId, count })
}

/**
 * 单聊转账（即时到账）：收款人由后端从会话反查，前端不传 toUserId。
 *
 * 返回的是「新余额 + 那条已经落库的转账消息」：转账消息属于内部类型，客户端不能自己 POST 发出去，
 * 这次调用就是它唯一的产生点，所以拿到结果要就地 appendMessage 上屏，而不是干等 WebSocket 回放。
 * @param {{ conversationId: string|number, amount: number, memo?: string }} payload
 * @returns {Promise<{ balance: number, message: object }>}
 */
export function transfer(payload) {
  return http.post('/wallet/transfer', payload)
}

/**
 * 发红包：单聊后端强制 1 份，群聊 count 会被夹到 packetMaxCount。
 * @param {{ conversationId: string|number, amount: number, count?: number, greeting?: string }} payload
 * @returns {Promise<{ balance: number, message: object }>}
 */
export function sendPacket(payload) {
  return http.post('/wallet/packet', payload)
}

/**
 * 红包详情与领取明细：点开气泡时现取，领取态在 Redis、钱在账本，所以这里只读。
 * @param {string|number} packetId
 */
export function fetchPacket(packetId) {
  return http.get(`/wallet/packet/${packetId}`, { silent: true })
}

/**
 * 抢红包。
 * @param {string|number} packetId
 * @returns {Promise<{ amount: number, balance: number, claimedCount: number, totalCount: number }>}
 */
export function claimPacket(packetId) {
  return http.post(`/wallet/packet/${packetId}/claim`)
}
