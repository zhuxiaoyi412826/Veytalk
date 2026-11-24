import http from './request'

/**
 * 交友资料卡相关接口。
 *
 * 与 api/user.js 分开：/user/profile 改的是昵称头像这类全场景身份，/dating/profile 改的是
 * 只用于匹配的结构化条件与隐私开关，两者的校验规则、限流配额和权限都不同。
 */

/** 本人交友资料：含隐私开关、完资料度 profileScore 与待补提示 missingTips */
export function fetchDatingProfile() {
  return http.get('/dating/profile')
}

/**
 * 保存交友资料。
 *
 * 后端语义是「null 字段不修改」，所以不能把整个表单原样 PUT 回去；
 * 要清空哪个字段，就把字段名放进 data.clears。
 */
export function saveDatingProfile(data) {
  return http.put('/dating/profile', data)
}

/** 保存隐私与可见性开关：showAge / showDistance / showOnline / discoverable / momentVisibleDays */
export function saveDatingPrivacy(data) {
  return http.put('/dating/profile/privacy', data)
}

/**
 * 他人交友资料：按对方的隐私开关收口，永远没有经纬度，distanceText 是模糊文案。
 *
 * options 透传给 axios，资料卡页面用它传 { silent: true }：对方没填资料是正常结果不是错误，
 * 不该为此弹一条提示。
 */
export function fetchDatingCard(userId, options) {
  return http.get(`/dating/profile/${userId}`, options)
}

/** 学历/收入区间/婚恋状况/交友目的四组下拉项，外加 sorts 排序口径 */
export function fetchDatingDict() {
  return http.get('/dating/dict')
}

/* ==================== 照片墙 ==================== */

/**
 * 照片墙列表。
 *
 * 不传 userId 就是本人视角（返回待审与驳回的行，带 auditStatus / rejectReason）；
 * 传别人 id 时后端只给审核通过那几行的地址，那一行不存在就是直接被过滤了。
 * options 透传给 axios，资料卡页面用它传 { silent: true }：对方一张没传是正常结果。
 */
export function fetchPhotos(userId, options) {
  return http.get('/dating/photos', { params: userId ? { userId } : {}, ...options })
}

/**
 * 提交一张已上传的照片。
 *
 * data = { fileId, machineChecked, width, height }：地址与字节数都由服务端按 fileId 反查，
 * 这里传 URL 没有任何意义（也不采信）。
 */
export function addPhoto(data) {
  return http.post('/dating/photos', data)
}

/** 删除一张（只删照片行，不碰文件记录） */
export function deletePhoto(id) {
  return http.delete(`/dating/photos/${id}`)
}

/** 设为主封面：后端把它挪到第一位并保证全局只有一个封面 */
export function setMainPhoto(id) {
  return http.put(`/dating/photos/${id}/main`)
}

/** 整序：按期望展示顺序提交当前全部照片 id，第一个即封面 */
export function sortPhotoOrder(ids) {
  return http.put('/dating/photos/sort', { ids })
}

/* ==================== 兴趣标签 ==================== */

/**
 * 标签库（按分类分组）。
 *
 * 与 fetchDatingDict 分开：那一组是写死在 Java 枚举里的定长 code，这一组是后台可维护的表，
 * 变化频率不同、缓存策略也不同。
 */
export function fetchTagLibrary() {
  return http.get('/dating/tags/library')
}

/**
 * 某人已达成的标签，按 TA 自己拖的顺序。
 *
 * options 透传给 axios，资料卡页面用它传 { silent: true }：对方一个没选是正常结果。
 */
export function fetchTags(userId, options) {
  return http.get('/dating/tags', { params: userId ? { userId } : {}, ...options })
}

/**
 * 保存本人的标签：整组替换，数组顺序即展示顺序，传 [] 即清空。
 *
 * 后端返回保存后的结果，直接拿它重绘即可（服务端的去重与校验可能和提交的不完全一样）。
 */
export function saveTags(ids) {
  return http.put('/dating/tags', { ids })
}

/* ==================== 滑动匹配 ==================== */

/**
 * 取待滑动的卡池。
 *
 * 返回的是数组而不是分页结果：卡池是「刷到底就没了」的无限流，不是能翻到第 N 页的表格，
 * 分页那套 total 在这里既算不准也没人看。长度小于 size 即表示已经刷到底。
 *
 * sort 只对这一次请求生效（不传则用偏好里存的口径）：临时换个看法与改默认设置是两件事，
 * 所以这里不能顺便把 sort 写回偏好。
 */
export function fetchMatchDeck(size, sort) {
  const params = {}
  if (size) {
    params.size = size
  }
  if (sort) {
    params.sort = sort
  }
  return http.get('/dating/match/deck', { params })
}

/**
 * 滑动一个动作。
 *
 * data = { targetUserId, action, source }，action 1 喜欢 / 2 跳过，source 见 MatchSource（1 推荐流…）。
 * 一个方向只记一行，重复滑动是改动作不是插新行，所以连点「喜欢」「跳过」不会留下垃圾数据。
 * 成对时返回体直接带 conversationId，不必再查一次配对列表就能跳进聊天。
 */
export function actMatch(data) {
  return http.post('/dating/match/action', data)
}

/** 谁喜欢我：只含对方把我划成「喜欢」的记录，按最近一次时间倒序 */
export function fetchMatchLikes(query) {
  return http.get('/dating/match/likes', { params: query })
}

/** 我的配对：只含配对中的关系，conversationId 可直接跳进聊天 */
export function fetchMatchPairs(query) {
  return http.get('/dating/match/pairs', { params: query })
}

/**
 * 解除配对。
 *
 * 只把配对行置为已解除，会话与聊天记录都不动 —— 删会话会把双方都的历史一起抹掉，
 * 而「不想再匹配」从来不等于「要销毁我们聊过的话」。
 */
export function unmatchPair(pairId) {
  return http.delete(`/dating/match/pairs/${pairId}`)
}

/* ==================== 附近的人 ==================== */

/**
 * 附近的人，按距你由近到远。
 *
 * 与卡池共用 actMatch 写侧（source 传 2），这里只有一个读端点：
 * 后端不返回任何经纬度，`card.distanceText` 是「500 m 内 / 1 km 内 / 取整公里」那样的模糊文案。
 * 你自己还没定位（或定位超过 7 天失效）时后端回 2042，页面据此原地出「去定位」引导，
 * 所以调用处一般带 { silent: true }。
 */
export function fetchNearby(params = {}, options) {
  const query = {}
  if (params.radius) {
    query.radius = params.radius
  }
  if (params.size) {
    query.size = params.size
  }
  return http.get('/dating/nearby', { params: query, ...options })
}

/* ==================== 筛选与偏好 ==================== */

/**
 * 我的筛选偏好。
 *
 * 从没保存过也回一组出厂默认值并带 `saved: false`，所以调用处不需要处理 null。
 * 但这两件事要分开看：默认值只是**弹层里预先填好的表单**，后端在没保存过时根本不拿它筛人
 * （见服务层的 find 返回 null 就不摊条件）。所以 `saved === false` 时要在弹层旁边提示「尚未保存」，
 * 否则用户会以为自己什么都没设却刷不到 45 岁以上的人，而找不到原因。
 */
export function fetchPreference() {
  return http.get('/dating/preference')
}

/**
 * 保存筛选偏好。
 *
 * 语义是**整组替换**（与 saveDatingProfile 的「null 字段不修改」相反）：偏好是一组互相配合的条件，
 * 留空就是「这一条不限」，不是「这一条别动」——后者会让人无法把已设的筛选改回不限。
 * 因此提交时必须把弹层里的全部字段一起 PUT 回去。
 *
 * 越界与自相矛盾（年龄上限小于下限、城市只有一个字）会报错而不是被夹紧，
 * 所以调用处要把后端的 message 原样弹给用户，不要自己先兜一层。
 */
export function savePreference(data) {
  return http.put('/dating/preference', data)
}

/* ==================== 访客记录 ==================== */

/**
 * 谁看过我：近 30 天点开过我资料卡的人，一人一条，按最近一次来访时间倒序。
 *
 * 没有分页参数也不返 total：这是一个上限 50 位的短列表（一次拉完），不是能翻到第 N 页的表格。
 * 两条看不出来的口径在这里说明：
 * - 同一天反复点开只算「来过一天」，但 visitTimes 会累加，所以「来过 1 天 / 看了 8 次」是合法结果；
 * - 开了隐身的人来看我不会留下记录，所以列表短不等于没人看我。
 */
export function fetchProfileVisits(options) {
  return http.get('/dating/visits', options)
}

/* ==================== 破冰建议 ==================== */

/**
 * 和这个人聊什么：一次给三档。
 *
 * 返回 { targetUserId, nickname, commonTags, greetings, topics, tagOpeners }。
 * 两条看不出来但影响用法的口径：
 * - content 是**成品**：{nickname}/{city}/{tag} 已由服务端替成对方的真实值，
 *   前端不要再做一次模板替换（那等于把「模板里允许出现什么占位符」这件内容层面的事绑到发版上）；
 * - 对方没填的那一项（例如城市）会让引用它的整条不下发，所以「只有 5 条打招呼」是正常结果，
 *   不是接口漏了数据。不传 userId 或传自己时 commonTags 与 tagOpeners 为空数组。
 */
export function fetchIcebreakers(userId, options) {
  return http.get('/dating/icebreakers', { params: userId ? { userId } : {}, ...options })
}

/**
 * 记一次「用了这条开场白」，供运营淘汰没人用的文案。
 *
 * 只是计数，不影响已复制的内容；这条刚好被后台下架时后端回 2032，
 * 所以调用处一律带 { silent: true }：为一个计数弹一条错误不值得，重新拉一次列表就行。
 */
export function useIcebreaker(id, options) {
  return http.post(`/dating/icebreakers/${id}/use`, null, options)
}

/* ==================== 亲密度与火花 ==================== */

/**
 * 我与这个人的亲密度：分值、档位、火花天数、共同好友与共同群。
 *
 * 返回 { score, level, levelDesc, nextLevelScore, progress, streakDays, maxStreak,
 *        sparkAlive, lastChatDate, msgCount, interactCount, commonFriendCount, commonGroupCount }。
 * 三条看不出来但影响渲染的口径：
 * - **从不返 null**：没来往过就是一组 0，所以这一块可以无条件渲染，不需要判空；
 * - progress 是**当前档位内**的百分比（0~100）而不是占顶档的比例，直接绑 el-progress 即可；
 *   nextLevelScore 为 null 表示已到顶档，此时不要再画「还差 N 分」；
 * - sparkAlive 为 true 只代表今天或昨天聊过，昨天聊过、今天还没聊时它仍是 true，
 *   所以不要拿它去和「今天」做比较，天数一律用 streakDays 显示。
 */
export function fetchIntimacy(userId, options) {
  return http.get('/dating/intimacy', { params: { userId }, ...options })
}

/* ==================== 动态 Feed ==================== */

/**
 * 发布一条动态。
 *
 * data = { content, visibility, media, visibleUserIds }：
 * - 正文与附件至少要有一样，两者都空后端直接报错（不会替你发一条空动态）；
 * - media 是**已上传好的**文件，形如 [{ fileId, type, width, height, duration }]，
 *   type 1 图片 / 2 视频，最多 9 张图或 1 个视频且两者不混排；上传时 bizType 必须是
 *   moment_image / moment_video —— 后端按 fileId 反查这三样（归属人、业务类型、字节数），
 *   传错 bizType 会被判成「这个文件不属于这条动态」，地址也不要自己拼；
 * - visibility 1 公开（好友与配对对象可见，**不是全站公开**） 2 仅好友 3 部分人可见 4 仅自己；
 *   选 3 时 visibleUserIds 至少要有一人，其余档位传了也被忽略；
 * - visibleUserIds 一律传字符串：那是雪花 ID，经 Number() 会掉精度（后端 Long 接受字符串）。
 *
 * 返回发布后的整条动态，直接插到列表头部即可，不必重拉一页。
 */
export function publishMoment(data) {
  return http.post('/dating/moments', data)
}

/**
 * 我的动态流。
 *
 * 受众 = 我的好友 ∪ 我配对过的人 ∪ 我自己，再减掉我拉黑的人。
 * 游标翻页：把返回的**最后一条** momentId 当下一页的 cursorId，首页不传。
 * 返回的条数少于 size **不代表到底了**（可见范围是在内存里逐条过滤的，被滤掉的也算扫过），
 * 只有拿到空列表才是真的没有了 —— 用「不足一页就停」会漏掉后面还能看的动态。
 */
export function fetchMomentFeed(params = {}) {
  const query = {}
  if (params.cursorId) {
    query.cursorId = params.cursorId
  }
  if (params.size) {
    query.size = params.size
  }
  return http.get('/dating/moments/feed', { params: query })
}

/**
 * 某个人主页上的动态，游标翻页口径同 fetchMomentFeed。
 *
 * 看自己时连已下架（status=2）的一起给；看别人时按对方的「几天可见」设时间下界，
 * 并逐条过可见范围，所以**陌生人与被对方挡住的人这里恒为空列表**（不是报错，也不需要提示）。
 * options 透传给 axios，资料卡页面用它传 { silent: true }：一条没有是正常结果。
 */
export function fetchUserMoments(userId, params = {}, options) {
  const query = {}
  if (params.cursorId) {
    query.cursorId = params.cursorId
  }
  if (params.size) {
    query.size = params.size
  }
  return http.get(`/dating/moments/user/${userId}`, { params: query, ...options })
}

/**
 * 动态详情，带全部评论（两层：一级在外，回复挂在 replies 里）。
 *
 * 从聊天卡片点进来也走这一条：可见性按**点开的人**重算，不在受众里或已超出作者的
 * 「几天可见」都回 2050，所以调用处要把这一码单独认出来提示「这条动态不在了」，
 * 而不是当成网络错误让人一直重试。
 */
export function fetchMomentDetail(momentId, options) {
  return http.get(`/dating/moments/${momentId}`, options)
}

/**
 * 删除自己的动态：连带附件、评论、点赞与白名单一起清。
 *
 * im_file 一行都不动 —— 秒传时那份字节可能正被别处引用，所以别指望删动态能腾出空间。
 */
export function deleteMoment(momentId) {
  return http.delete(`/dating/moments/${momentId}`)
}

/**
 * 点赞 / 取消点赞，都返回点赞后的**总数**。
 *
 * 重复点按是幂等的（不会把计数点上去两次），本来就没赞过时取消也不报错，
 * 所以前端可以放心乐观更新，拿返回值覆写就是权威数。
 * 这两个动作要 friend:apply 权限，没这个权限的账号应该把按钮藏掉而不是让它点了吃 403。
 */
export function likeMoment(momentId) {
  return http.post(`/dating/moments/${momentId}/like`)
}

export function unlikeMoment(momentId) {
  return http.delete(`/dating/moments/${momentId}/like`)
}

/**
 * 评论或回复。
 *
 * data = { content, parentId, replyUserId }：回复一条**回复**时 parentId 仍要传那条
 * 一级评论的 ID（两层封顶），replyUserId 由服务端按 parentId 复核，传错不会串人。
 * 返回新评论，直接插进列表即可 —— 不要重拉详情，那会把用户正在输入的第二条评论冲掉。
 */
export function commentMoment(momentId, data) {
  return http.post(`/dating/moments/${momentId}/comments`, data)
}

/**
 * 删除评论：评论作者或动态作者都能删。
 *
 * 删一级评论会连它下面的回复一起删，并把整棵子树的行数从 commentCount 里减掉，
 * 所以删完要按返回后的计数重画，不要自己只减 1。
 */
export function deleteMomentComment(commentId) {
  return http.delete(`/dating/moments/comments/${commentId}`)
}

/**
 * 动态转聊天：往一个会话里发一张动态卡片（msgType=15）。
 *
 * data = { conversationId, toUserId, message, clientMsgId }：
 * - 目标定位 conversationId 优先于 toUserId，两个都不传后端报错；给自己发是允许的（当收藏用）；
 * - message 是可选附言，卡片上那三行（作者 / 摘要 / 附件数）由服务端反查后**覆写**，
 *   前端传什么都不会被采信 —— 别指望用附言冒充正文；
 * - clientMsgId 不传由服务端生成，只有「同一次点击可能重发」时才需要自己带一个固定值；
 * - ★ 转发**不改变可见范围**：卡片只带摘要不带图，收到的人点开时按他自己与作者的关系重算，
 *   不在受众里就是「这条动态不在了」。所以分享前不必也无法替对方确认能不能看。
 *
 * 返回 { conversationId, messageId }，可据此直接跳进那个会话。
 */
export function shareMoment(momentId, data) {
  return http.post(`/dating/moments/${momentId}/share`, data)
}
