import http from './request'
import { webhookBase } from '@/utils/env'

/**
 * 群机器人管理接口（仅群主与管理员可用）。
 *
 * webhook 回调地址由后端按传入的 base 拼出（外部系统向该 URL POST {"content": "..."} 即可在本群发消息），
 * base 缺省时取当前环境外部可达的后端根地址（见 utils/env.js webhookBase）。
 */

/** 创建机器人：{ groupId, name, avatar?, description? }，返回含 webhookUrl 的机器人信息 */
export function createRobot(data, base = webhookBase()) {
  return http.post('/robot', data, { params: { base } })
}

/** 群机器人列表 */
export function fetchRobots(groupId, base = webhookBase()) {
  return http.get(`/robot/group/${groupId}`, { params: { base } })
}

/** 修改机器人：{ name?, avatar?, description?, enabled? }，字段传 null 不改 */
export function updateRobot(robotId, data, base = webhookBase()) {
  return http.put(`/robot/${robotId}`, data, { params: { base } })
}

/** 重置 webhook 密钥，旧回调地址立即失效 */
export function resetRobotToken(robotId, base = webhookBase()) {
  return http.post(`/robot/${robotId}/token/reset`, null, { params: { base } })
}

/** 删除机器人（逻辑删除，token 随即失效） */
export function deleteRobot(robotId) {
  return http.delete(`/robot/${robotId}`)
}
