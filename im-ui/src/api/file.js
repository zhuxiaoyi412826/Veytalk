import http from './request'

/**
 * 文件相关接口。
 *
 * 后端存进数据库和消息 extra 里的地址是「受控地址」/api/file/download/{fileId}，
 * 长期有效但不带任何凭证；浏览器直接拿它当 img src 会因为没有 satoken 头而 401。
 * 因此展示层统一走 utils/media.js，用带登录头的 XHR 把内容取成 blob 再交给 img。
 */

/**
 * 上传文件。
 *
 * @param file     File 对象
 * @param bizType  avatar / chat_image / chat_file / chat_voice，缺省后端按 chat_file 处理
 * @param duration 语音时长（秒），仅语音需要
 * @param onProgress 上传进度回调，参数是 0-100 的整数
 */
export function uploadFile(file, bizType, duration, onProgress) {
  const form = new FormData()
  // 后端用 @RequestPart("file") 接收，字段名必须是 file
  form.append('file', file)
  return http.post('/file/upload', form, {
    params: { bizType, duration },
    // 刻意不设 Content-Type：multipart 的 boundary 由浏览器生成，
    // 手写一个不带 boundary 的 'multipart/form-data' 会让服务端无法切分请求体。
    // axios 在浏览器环境下遇到 FormData 会主动清掉该头，不写反而最稳。
    timeout: 120000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    }
  })
}

/** 上传头像，后端会顺手写进当前用户资料，不必再调一次修改资料接口 */
export function uploadAvatar(file) {
  const form = new FormData()
  form.append('file', file)
  return http.post('/file/avatar', form, { timeout: 120000 })
}

/** 文件元数据，需要对该文件有访问权 */
export function fetchFileMeta(fileId) {
  return http.get(`/file/${fileId}`)
}

/** 换取带短时票据的直链，票据有效期由后端 im.jwt.file-ticket-ttl-seconds 决定（默认 1800 秒） */
export function fetchSignedUrl(fileId) {
  return http.get(`/file/${fileId}/url`)
}
