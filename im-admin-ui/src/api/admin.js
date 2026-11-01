import http from './request'

/**
 * 管理后台的接口封装：看板、用户管理、举报工单、角色分配，以及三期的
 * 视频管理 / 直播管理 / 监控告警 / 日志管理。
 *
 * 形状对齐 im-admin 的各 Controller，一处不改：
 *  - /api/admin/dashboard      GET，refresh 为 query 参数
 *  - /api/admin/user/page      GET，入参 keyword / status / current / size
 *  - /api/admin/user/{id}      GET
 *  - /api/admin/user/{id}/disable  POST，reason 是 <b>@RequestParam 而不是 JSON body</b>
 *  - /api/admin/user/{id}/enable   POST
 *  - /api/admin/report/page    GET，入参 status / targetType / reasonCode / keyword / current / size
 *  - /api/admin/report/{id}    GET
 *  - /api/admin/report/{id}/accept   POST，无 body
 *  - /api/admin/report/{id}/reject   POST，body { remark }
 *  - /api/admin/report/{id}/handle   POST，body { action, remark }
 *  - /api/report/config        GET，字典（后台侧同样能读，不为后台另开一份）
 *
 * 三期新增（同样一处不改）：
 *  - /api/admin/video/page     GET，入参 keyword / status / uploaderId / current / size
 *  - /api/admin/video/{id}/offline  POST，reason 是 @RequestParam（与 disable 同一口径）
 *  - /api/admin/video/{id}/online   POST，无 body
 *  - /api/admin/video/{id}          DELETE，无 body
 *  - /api/admin/live/page    GET，入参 keyword / status / anchorId / current / size
 *  - /api/admin/live/{roomId}       GET
 *  - /api/admin/live/{roomId}/stop  POST，无 body（原因服务端固定为 ban）
 *  - /api/admin/monitor/overview    GET；/api/admin/monitor/slow  GET
 *  - /api/admin/log/files           GET；/api/admin/log/content  GET，key / tail / keyword
 *
 * 四期新增：
 *  - /api/admin/config/sensitive-filter  GET / PUT，enabled 是 @RequestParam（与 disable 的
 *    reason 同一口径），不是 JSON body。这两个端点原先在用户侧 /api/message/sensitive-filter
 *    上只要求登录，任何账号都能关掉全服遮罩，现已搬进后台并校 system:config。
 *
 * 四期第四批新增（系统配置页扩充，全部走 system:config，只有公告那一组走新的 system:announcement）：
 *  - /api/admin/config/item       GET，一次回全部目录项（服务端枚举，不分页也不传筛选条件）
 *  - /api/admin/config/item       PUT，key / value 都是 @RequestParam，value 一律字符串
 *  - /api/admin/config/item       DELETE，同上；「恢复默认」是物理删库里的这一行
 *  - /api/admin/sensitive-word/page  GET，入参 keyword / current / size（PageQuery 本体，无子类字段）
 *  - /api/admin/sensitive-word    POST，body { word, remark }；word 可以是分隔符隔开的一批词
 *  - /api/admin/sensitive-word/{id}   DELETE
 *  - /api/admin/announcement/page GET，入参 status / keyword / current / size
 *  - /api/admin/announcement/{id}     GET；POST 新建；PUT/{id} 编辑（仅草稿）
 *  - /api/admin/announcement/{id}/publish | /revoke  POST，无 body
 *  - /api/admin/announcement/{id}     DELETE，仅草稿
 *  这一批与前面几批最大的不同是【新增了一个权限点 118 system:announcement】：跑完 SQL
 *  必须清一次 Redis 的 im:auth:* 缓存，否则已登录的 admin 仍看不到这一屏（详见那份 migration）。
 *
 * 四期第二批新增（远程控制审计，<b>四个端点全是 GET</b>，一个写动作都没有）：
 *  - /api/admin/remote/page      GET，入参 keyword / status / userId / current / size
 *  - /api/admin/remote/{sessionId}      GET，会话不存在回 9005
 *  - /api/admin/remote/audit/page       GET，入参 sessionId / actor / action / dangerOnly / keyword
 *    / current / size；sessionId 不传就是跨会话的全局检索（单页上限因此只有 50）
 *  - /api/admin/remote/overview         GET
 *  两个分页接口的入参是一个 DTO 对象（不是 @RequestParam 一个个接），所以 params 里
 *  多一个未知字段不会报错、只会静默忽略；这里只传后端真认的那几个。
 *
 * 四期第三批（封禁时长）：/admin/user/{id}/disable 多一个可选 minutes，
 *  /admin/report/{id}/handle 的 body 多一个可选 disableMinutes；两者不传都是永久封禁，
 *  所以存量调用不传也不会错——新参数全部向后兼容。
 *
 * 四期第五批新增（钱包与礼物 / AI 面试记录 / 群组管理 / 消息管理，四个屏各一个权限点）：
 *  - /api/admin/wallet/overview | /page | /{userId} | /tx/page   GET；/{userId}/adjust  POST，
 *    delta 与 reason 都是 @RequestParam
 *  - /api/admin/wallet/gifts    GET（全量不分页）；/gift/{giftId}/status  POST，onShelf 是 query
 *  - /api/admin/wallet/gift-record/page  GET（数据在 im-live，由后端跨模块装配）
 *  - /api/admin/interview/page | /{sessionId} | /overview   GET，<b>三个端点全是读</b>
 *  - /api/admin/group/page | /{groupId} | /member/page      GET；/{groupId}/dismiss  POST 无 body；
 *    /{groupId}/mute-all  POST，muteAll 是 query
 *  - /api/admin/message/page | /{messageId} | /overview     GET；/{messageId}/recall  POST 无 body
 *  本批<b>零 DDL</b>（四屏读的全是既有的表），但新增四个权限点 119~122：跑完 SQL 必须清一次
 *  im:auth:* 缓存，理由与上一批相同。消息那一屏服务端有一道与权限无关的硬闸：
 *  带 keyword 必须同时带 conversationId / fromUserId / 时间范围之一，否则回 1001（见 MessageView）。
 *
 * disable 的 reason 用 params 而不是 data 传：后端没写 @RequestBody，
 * 发一个 JSON body 过去它会当成「reason 为空」而打回「请填写禁用事由」，
 * 而前端看到的是一句看不出问题的 400 提示。
 * 本批的 adjust / giftStatus / muteAll 三个写动作同样是 @RequestParam，同一个坑同一个避法。
 */

/** 看板数据；refresh=true 会绕过服务端 60s 缓存真跑一遍聚合，只能人手点，不做定时轮询 */
export function fetchDashboard(refresh = false) {
  return http.get('/admin/dashboard', { params: { refresh } })
}

export function fetchUserPage(params) {
  return http.get('/admin/user/page', { params })
}

export function fetchUserDetail(userId) {
  return http.get(`/admin/user/${userId}`)
}

/**
 * 禁用账号，必须写事由（服务端校验），后端会顺手踢掉该用户的全部长连接。
 *
 * minutes 不传（或传 null）就是永久封禁；传了则到期由 im-user 的定时任务自动解封。
 * 已禁用的账号再调一次是「改期」而不是报错，所以恢复后再禁与直接改时长两条路都能走。
 */
export function disableUser(userId, reason, minutes) {
  return http.post(`/admin/user/${userId}/disable`, null, { params: { reason, minutes } })
}

export function enableUser(userId) {
  return http.post(`/admin/user/${userId}/enable`)
}

export function fetchReportPage(params) {
  return http.get('/admin/report/page', { params })
}

export function fetchReportDetail(id) {
  return http.get(`/admin/report/${id}`)
}

/** 待处理 → 已受理，纯人工推进，不写处理时间 */
export function acceptReport(id) {
  return http.post(`/admin/report/${id}/accept`)
}

/** 判定举报不成立：只改工单状态，不动被举报对象 */
export function rejectReport(id, remark) {
  return http.post(`/admin/report/${id}/reject`, { remark })
}

/**
 * 执行处置动作并置为已处置；动作由对象归属模块执行，失败时工单停在原状态。
 *
 * minutes 只被 {@code ban_user} 读（其它动作传了也不报错也不生效），留空为永久封禁。
 * 它走 JSON body 而不是 query：本接口的入参是一个 @RequestBody DTO，
 * 拿 params 送后端收不到（与 {@link disableUser} 恰好相反，那边后端用的是 @RequestParam）。
 */
export function handleReport(id, action, remark, minutes) {
  return http.post(`/admin/report/${id}/handle`, { action, remark, disableMinutes: minutes })
}

/** 举报字典：状态/理由/对象类型/处置动作的中文名都从这里出，前端不复制一份码表 */
export function fetchReportConfig() {
  return http.get('/report/config')
}

/* ==================== 角色分配（二期） ==================== */

/** 可分配的角色列表（启用且未删除），给「配角色」下拉与角色目录页用 */
export function fetchRoles() {
  return http.get('/admin/role/list')
}

/** 某用户当前持有的角色对象列表，弹框据此勾选复选框 */
export function fetchUserRoles(userId) {
  return http.get(`/admin/role/user/${userId}`)
}

/**
 * 整体重设用户角色：roleIds 就是该用户最终应持有的角色，空数组=清空全部后台角色。
 * 后端会拒三种调用：改自己、摘掉最后一个可用管理员、包含未知/停用角色。
 */
export function assignUserRoles(userId, roleIds) {
  return http.put(`/admin/role/user/${userId}`, { roleIds })
}

/* ==================== 视频管理（三期） ==================== */

/** 视频列表：含已下架（status=2），删除后的行整体隐身；固定按发布时间倒序 */
export function fetchVideoPage(params) {
  return http.get('/admin/video/page', { params })
}

/** 下架视频：reason 走 query 参数进服务端审计日志，与 disableUser 同一口径 */
export function offlineVideo(id, reason) {
  return http.post(`/admin/video/${id}/offline`, null, { params: { reason } })
}

/** 恢复上架，幂等 */
export function onlineVideo(id) {
  return http.post(`/admin/video/${id}/online`)
}

/** 逻辑删除，不可自助恢复——前端必须二次确认 */
export function deleteVideo(id) {
  return http.delete(`/admin/video/${id}`)
}

/* ==================== 直播管理（三期） ==================== */

/** 场次列表：含全部历史与已封禁场次；直播中恒排最前，响应绝不含 streamKey */
export function fetchLivePage(params) {
  return http.get('/admin/live/page', { params })
}

export function fetchLiveDetail(roomId) {
  return http.get(`/admin/live/${roomId}`)
}

/**
 * 强制关播（ban）：终态，没有解封接口。对已结束的场次幂等跳过，
 * 响应带回处置后的最新档案，前端就地刷那一行。
 */
export function stopLive(roomId) {
  return http.post(`/admin/live/${roomId}/stop`)
}

/* ==================== 监控告警（三期） ==================== */

/** 监控总览：JVM/HTTP/连接池/健康/慢接口一屏取数，取不到的指标为 null（显示「—」） */
export function fetchMonitorOverview() {
  return http.get('/admin/monitor/overview')
}

/** 只回慢接口分组，比整屏总览便宜，供想高频刷新的场景 */
export function fetchMonitorSlow() {
  return http.get('/admin/monitor/slow')
}

/* ==================== 日志管理（三期） ==================== */

/** 七个逻辑档的存在性、大小与最后修改时间；前端只认 key，永远不传路径 */
export function fetchLogFiles() {
  return http.get('/admin/log/files')
}

/** 尾读日志：tail 行服务端夹上限，关键词只在尾读窗口内过滤（不是全文件搜索） */
export function fetchLogContent(key, tail, keyword) {
  return http.get('/admin/log/content', { params: { key, tail, keyword: keyword || undefined } })
}

/* ==================== 系统配置（四期） ==================== */

/**
 * 敏感词过滤运行态：{ enabled, ready, wordCount }。
 *
 * ready 与 wordCount 是开关之外必须一起看到的两个字段：词库装载失败时
 * enabled 仍可能是 true，但一个词都不会遮，只看开关会误判成「过滤没生效」。
 */
export function fetchSensitiveFilter() {
  return http.get('/admin/config/sensitive-filter')
}

/**
 * 切换敏感词过滤开关（全服生效、不需重启）。
 *
 * enabled 走 params：后端是 @RequestParam，发 JSON body 会被当成没传值。
 * 返回服务端切换后的运行态，前端用它回填而不是相信自己点下去的那个值。
 *
 * 与四期第一批相比多了一句：<b>这一笔现在落库了</b>。重启后盖回来的是库里那一行，
 * 不再是配置文件的值；要退回配置文件语义得用「恢复默认」把行删掉（见 resetConfigItem）。
 */
export function setSensitiveFilter(enabled) {
  return http.put('/admin/config/sensitive-filter', null, { params: { enabled } })
}

/* ==================== 运行期配置目录（四期第四批） ==================== */

/**
 * 可改的配置项全量：一次回 27 项（当前值 / 启动默认值 / 是否已覆盖 / 分组与说明）。
 *
 * 不分页也不传筛选：这个清单的长度由代码里的枚举决定，不是由数据决定，
 * 而页面需要一次拿到全量才能按 group 分区、按 overridden 统计「改过几项」。
 */
export function fetchConfigItems() {
  return http.get('/admin/config/item')
}

/**
 * 修改一个配置项：key 与 value 都是 @RequestParam，value 一律送字符串
 * （"true" / "120"）——服务端按白名单里声明的类型解释并校范围，前端不先转成 boolean/number
 * 再拼回去，少一次翻译就少一种能出错的形式。
 */
export function updateConfigItem(key, value) {
  return http.put('/admin/config/item', null, { params: { key, value } })
}

/**
 * 恢复默认：物理删掉 im_config 里那一行并把运行态写回启动那一刻的快照。
 * 未覆盖的项调它也是幂等的（没有行可删），但界面只在 overridden 为真时才给这个按钮。
 */
export function resetConfigItem(key) {
  return http.delete('/admin/config/item', { params: { key } })
}

/* ==================== 在线敏感词库（四期第四批） ==================== */

/**
 * 词库分页：<b>只列后台加的词</b>，classpath 那 19643 条不在表里（它们没有 ID，也没法在这里删）。
 *
 * keyword 由服务端先按同一口径折叠再做模糊匹配，所以输全角也能命中半角的入库形式。
 */
export function fetchSensitiveWordPage(params) {
  return http.get('/admin/sensitive-word/page', { params })
}

/**
 * 新增词条。word 这一个字段同时装得下「一个词」和「顿号/逗号/换行分隔的一批词」，
 * 拆开成两个接口只会让前端先判一次「该走哪一个」，而后端本来就在拆。
 *
 * 响应带回 total/added/duplicated/invalid 与重建后的生效总数，四个数都得显示：
 * 只报一句「成功」，贴进 300 个词的人无从知道实际长了几个。
 */
export function addSensitiveWords(word, remark) {
  return http.post('/admin/sensitive-word', { word, remark })
}

/** 按 ID 删而不是按词删：删掉的是后台那一行，文件词库里的词不受影响（也不在这里显示） */
export function deleteSensitiveWord(id) {
  return http.delete(`/admin/sensitive-word/${id}`)
}

/* ==================== 全服公告（四期第四批，新权限点 system:announcement） ==================== */

export function fetchAnnouncementPage(params) {
  return http.get('/admin/announcement/page', { params })
}

export function fetchAnnouncementDetail(id) {
  return http.get(`/admin/announcement/${id}`)
}

/**
 * 新建公告。form.publishNow 为真时同一个调用里落库为已发布并向全部在线连接广播，
 * 不是「先存草稿再调发布」两次请求——中间那次失败会在库里留下一条谁也不知道的草稿。
 */
export function createAnnouncement(form) {
  return http.post('/admin/announcement', form)
}

/** 编辑草稿（已发布的不可编辑，后端回 14012） */
export function updateAnnouncement(id, form) {
  return http.put(`/admin/announcement/${id}`, form)
}

/** 草稿 → 已发布：先落库再广播，广播失败只记日志、状态仍是已发布，离线用户靠补拉拿到 */
export function publishAnnouncement(id) {
  return http.post(`/admin/announcement/${id}/publish`)
}

/** 已发布 → 已撤回：只影响之后的补拉，<b>已经弹出去的那条通知收不回来</b> */
export function revokeAnnouncement(id) {
  return http.post(`/admin/announcement/${id}/revoke`)
}

/** 删除，仅草稿：已发布与已撤回的行留下，它们是「什么时候说过什么话」的台账 */
export function deleteAnnouncement(id) {
  return http.delete(`/admin/announcement/${id}`)
}

/* ==================== 远程控制审计（四期第二批） ==================== */

/**
 * 会话台账：含已拒接与已结束的历史，进行中的恒排最前。
 *
 * keyword 只匹配设备标识与设备名，不认昵称也不认用户 ID——用户维度用 userId 精确筛，
 * 把昵称塞进 keyword 需要一次跨模块反查，重名用户上还会给出别人家的会话。
 */
export function fetchRemoteSessionPage(params) {
  return http.get('/admin/remote/page', { params })
}

/** 单会话档案；进行中的 bytes 是内存中继累计值（该列只在收尾时落库） */
export function fetchRemoteSessionDetail(sessionId) {
  return http.get(`/admin/remote/${sessionId}`)
}

/**
 * 审计流水。sessionId 不传即跨会话全局检索，那是本页唯一的重查询（实现侧单页夹 50）。
 *
 * dangerOnly 的高危词表在服务端，响应里每条带一个 dangerous 布尔，前端不自己判。
 */
export function fetchRemoteAuditPage(params) {
  return http.get('/admin/remote/audit/page', { params })
}

/** 顶部四个数字，无服务端缓存；本页不对它做轮询，只给人手点刷新 */
export function fetchRemoteOverview() {
  return http.get('/admin/remote/overview')
}

/* ==================== 钱包与礼物（四期第五批，权限点 system:wallet） ==================== */

/**
 * 概览数字。那几个 SUM 是对 im_user_wallet 的全表扫描（这张表除主键无索引），
 * 后端不缓存也不轮询，所以只有人手点一次「刷新数字」才会打过去。
 */
export function fetchWalletOverview() {
  return http.get('/admin/wallet/overview')
}

/** 钱包台账：排序固定「余额高的在前」，前端没有排序参数可传 */
export function fetchWalletPage(params) {
  return http.get('/admin/wallet/page', { params })
}

/**
 * 单人钱包档案（含最近 10 条流水）。
 *
 * <b>data 为 null 是合法结果</b>：那个号从未开过钱包行，读它不该顺手往库里插一行。
 * 调用方必须按 null 走「未开户」空态，不能 `res.balance || 0` 把「没账户」显示成「0 余额」。
 */
export function fetchWalletDetail(userId) {
  return http.get(`/admin/wallet/${userId}`)
}

/** 账本流水：userId 留空即全站检索，实现侧把全站口径的单页夹到 50（那张表只有 idx_user_time） */
export function fetchWalletTxPage(params) {
  return http.get('/admin/wallet/tx/page', { params })
}

/**
 * 调整余额：delta 正数入账、负数扣减、0 被后端拒。
 *
 * reason 必填（服务端校验），并连同操作人一起写进流水备注。它不受
 * im.wallet.recharge-max 约束——那个上限管的是「用户能自己往口袋里塞多少」。
 * 返回调整后的最新档案，前端就地刷那一行，不需要再拉一次列表。
 */
export function adjustWalletBalance(userId, delta, reason) {
  return http.post(`/admin/wallet/${userId}/adjust`, null, { params: { delta, reason } })
}

/** 礼物目录全量（含已下架——后台要能看到它并把它重新上架），按 sort 升序 */
export function fetchGiftCatalog() {
  return http.get('/admin/wallet/gifts')
}

/** 上/下架，只改状态：这里没有改价、改名、删行的入口，后端也不给 */
export function setGiftStatus(giftId, onShelf) {
  return http.post(`/admin/wallet/gift/${giftId}/status`, null, { params: { onShelf } })
}

/** 送礼流水（只读账本）：每行带双方昵称与房间标题，房间已清理则标题为 null */
export function fetchGiftRecordPage(params) {
  return http.get('/admin/wallet/gift-record/page', { params })
}

/* ==================== AI 面试记录（四期第五批，权限点 system:interview） ==================== */

/** 场次台账：进行中的恒排最前；列表行不带问答与事件正文，细节在单场档案里 */
export function fetchInterviewPage(params) {
  return http.get('/admin/interview/page', { params })
}

/**
 * 单场档案：后台里<b>唯一</b>交出候选人作答原文的路径，读一次在后端留一条含场次 ID 的日志。
 * 所以视图只在抽屉真正打开时才调它，列表加载不顺手带。
 */
export function fetchInterviewDetail(sessionId) {
  return http.get(`/admin/interview/${sessionId}`)
}

/** 六个计数 + 违规率 + 违规榜前 10 人 */
export function fetchInterviewOverview() {
  return http.get('/admin/interview/overview')
}

/* ==================== 群组管理（四期第五批，权限点 system:group:manage） ==================== */

/** 群台账：<b>默认含已解散的群</b>（这是台账不是收件箱，「这群什么时候没的」也得查得到） */
export function fetchGroupPage(params) {
  return http.get('/admin/group/page', { params })
}

/** 单群档案：群信息 + 最近成员快照 + 最近入群申请 + 机器人；绝不含 inviteCode 与 webhookToken */
export function fetchGroupDetail(groupId) {
  return http.get(`/admin/group/${groupId}`)
}

/** 群成员真分页（档案里的 members 只是快照）：只给在群成员，入群时间正序 */
export function fetchGroupMemberPage(params) {
  return http.get('/admin/group/member/page', { params })
}

/**
 * 强制解散：<b>不可逆</b>（成员关系要重新生成，那是造数据而不是撤销）。
 * 对已解散的群重复调用幂等返回当前档案，所以连点两次不会出现「第二次失败所以不知道到底散没散」。
 */
export function dismissGroup(groupId) {
  return http.post(`/admin/group/${groupId}/dismiss`)
}

/** 全员禁言开关，可逆；挡的是除群主与管理员以外的所有人（豁免规则沿用群内禁言） */
export function setGroupMuteAll(groupId, muteAll) {
  return http.post(`/admin/group/${groupId}/mute-all`, null, { params: { muteAll } })
}

/* ==================== 消息管理（四期第五批，权限点 system:message:manage） ==================== */

/**
 * 消息检索。服务端三道与权限无关的硬闸：带 keyword 必须同时带范围（否则 1001）、
 * 单页 50、列表正文截到 2000 字（截断标志是 contentTruncated，全文在 {@link fetchMessageDetail}）。
 */
export function fetchMessagePage(params) {
  return http.get('/admin/message/page', { params })
}

/** 单条全文档案：唯一交出完整正文的端点，读一次在后端留一条含 messageId 的日志 */
export function fetchMessageDetail(messageId) {
  return http.get(`/admin/message/${messageId}`)
}

/** 今日 / 近 7 日量、今日撤回与其中后台强撤（对后台自身的问责指标）、保留天数与库里最早一条 */
export function fetchMessageOverview() {
  return http.get('/admin/message/overview')
}

/**
 * 强制撤回：无视时限与发送者身份，但转账 / 红包与系统通知仍被后端拒绝
 * （那两条拦的不是权限，而是「撤回会把已经生效的资金凭证抹掉」这个事实）。
 */
export function recallMessage(messageId) {
  return http.post(`/admin/message/${messageId}/recall`)
}

/* ==================== 文章管理 ==================== */

/** 文章列表：含全状态（草稿/待审/已发布/驳回/已下架）；关键词按标题模糊，纯数字额外认作者 ID；列表项不含正文 */
export function fetchArticlePage(params) {
  return http.get('/admin/article/page', { params })
}

/** 文章详情：含正文，供后台预览与编辑回填 */
export function fetchArticleDetail(id) {
  return http.get(`/admin/article/${id}`)
}

/** 代写文章：publishNow=true 直接发布（免审），false 落待审；dto 含 title/summary/content/coverUrl/authorId */
export function createArticle(dto, publishNow) {
  return http.post('/admin/article', dto, { params: { publishNow } })
}

/** 编辑文章：改标题/摘要/正文/封面，不动状态 */
export function updateArticle(id, dto) {
  return http.put(`/admin/article/${id}`, dto)
}

/** 审核通过：待审(1)→已发布(2)，reason 走 query 进审计日志 */
export function approveArticle(id, reason) {
  return http.post(`/admin/article/${id}/approve`, null, { params: { reason } })
}

/** 审核驳回：待审(1)→驳回(3) 并写 rejectReason */
export function rejectArticle(id, reason) {
  return http.post(`/admin/article/${id}/reject`, null, { params: { reason } })
}

/** 下架：已发布(2)→已下架(4)，广场不再展示但记录保留 */
export function offlineArticle(id, reason) {
  return http.post(`/admin/article/${id}/offline`, null, { params: { reason } })
}

/** 恢复上架：已下架(4)→已发布(2)，幂等 */
export function onlineArticle(id) {
  return http.post(`/admin/article/${id}/online`)
}

/** 逻辑删除，不可自助恢复——前端必须二次确认 */
export function deleteArticle(id) {
  return http.delete(`/admin/article/${id}`)
}
