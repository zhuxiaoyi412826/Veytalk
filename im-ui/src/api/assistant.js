import http from './request'

/**
 * 文件传输助手会话定位。
 *
 * 网页扫码登录成功后调它，拿到当前用户与「文件传输助手」的单聊会话 ID，
 * 直接跳进会话即可收发文件；后端 getOrCreateSingle 幂等，未预建时会懒建。
 */
export function openAssistantSession() {
  return http.post('/conversation/assistant')
}
