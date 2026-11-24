<script setup>
/**
 * 直播页「图片 / 音乐 / 小说」三个新板块共用的信息流组件。
 *
 * 一份组件吃三个板块：三张表的列口径在库里已对齐（计数列统一 play_count）、后端三个
 * 控制器形状完全一致、VO 也刻意只有一个 WorkVO，所以这里只需按 props.type 分支渲染卡片
 * 与发布表单，布局（左一级频道 + 顶搜索/二级分类 + 自适应网格）与视频板块同构。
 *
 * 刻意不改 Live.vue 里的视频板块、也不复用它的模板：那 4600 行已经上线跑通，
 * 把三种新形态塞进去等于把已验证的功能拖进回归风险；新板块独立成组件，Live.vue 只挂标签。
 *
 * 一处与视频不同的取舍：音乐**不做卡片内联 <audio>**。mediaUrl 是「首次返回空串 + 后台取
 * blob」的异步模型，15 张卡片各挂一个 audio 会一次性拉 15 个音频文件；改成点卡片后在网格
 * 上方起一个播放条，同时只有一个源在取内容，行为也与视频板块一致。
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Document, EditPen, Picture, Search } from '@element-plus/icons-vue'
import {
  createWork,
  deleteWork,
  fetchWorkCategories,
  fetchWorkDetail,
  fetchWorkPage,
  reportWorkPlay,
  updateWork
} from '@/api/work'
import { uploadFileSmart } from '@/api/file'
import { mediaUrl, readAudioDuration } from '@/utils/media'
import {
  IMPORT_CONFIRM_COUNT,
  IMPORT_MAX_BYTES,
  composeTitle,
  decodeText,
  splitIntoPieces
} from '@/utils/novelImport'
import { guardImage } from '@/utils/nsfw'
import UserAvatar from '@/components/UserAvatar.vue'
import MarkdownView from '@/components/MarkdownView.vue'

const props = defineProps({
  /** 板块标识，同时决定接口前缀与卡片形态 */
  type: { type: String, required: true }
})

const LABEL = { image: '图片', music: '音乐', novel: '小说' }
/** 图集张数区间，与后端 LiveImage.MIN_IMAGES / MAX_IMAGES 同口径（这里只是提前拦一次） */
const ALBUM_MIN = 1
const ALBUM_MAX = 9
/** 小说正文上限，与后端 LiveNovel.CONTENT_MAX 对齐；超了服务端也会拒，这里给个更友好的提示 */
const CONTENT_MAX = 200000

const type = computed(() => props.type)
const label = computed(() => LABEL[type.value] || '作品')
const isImage = computed(() => type.value === 'image')
const isMusic = computed(() => type.value === 'music')
const isNovel = computed(() => type.value === 'novel')

/* ---------- 一级频道 / 二级分类 / 搜索 ---------- */
const CHANNELS = [
  { key: 'featured', label: '精选' },
  { key: 'hot', label: '热门' },
  { key: 'mine', label: '我的' }
]
const channel = ref('featured')
/** 分类兜底值：接口异常时分类栏不至于空掉（拉到非空则整体替换），与视频板块同一策略 */
const FALLBACK_CATS = {
  image: ['综合', '风景', '人像', '美食', '动物', '建筑', '插画', '生活记录'],
  music: ['综合', '流行', '摇滚', '说唱', '电子', '古典', '民谣', '纯音乐'],
  novel: ['综合', '玄幻', '都市', '科幻', '悬疑推理', '历史', '言情', '武侠']
}
const categories = ref(FALLBACK_CATS[type.value] || ['综合'])
const filter = reactive({ category: '全部', keyword: '' })

const works = ref([])
const loading = ref(false)
// 与视频板块一致：一行约 5 个 × 三行 = 15，列数按屏宽 auto-fill 自适应
const query = reactive({ current: 1, size: 15, total: 0 })

async function loadCategories() {
  try {
    const list = await fetchWorkCategories(type.value)
    if (Array.isArray(list) && list.length) {
      categories.value = list
    }
  } catch {
    // silent 接口，拉不到就吃兜底，不打扰浏览
  }
}

async function loadWorks() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (channel.value === 'hot') params.sort = 'hot'
    if (channel.value === 'mine') params.mine = true
    if (filter.category && filter.category !== '全部') params.category = filter.category
    const kw = filter.keyword.trim()
    if (kw) params.keyword = kw
    const page = await fetchWorkPage(type.value, params)
    works.value = page?.records || []
    query.total = page?.total || 0
  } catch {
    // request.js 已弹过提示
  } finally {
    loading.value = false
  }
}

function switchChannel(key) {
  if (channel.value === key) return
  channel.value = key
  query.current = 1
  loadWorks()
}

function switchCategory(cat) {
  if (filter.category === cat) return
  filter.category = cat
  query.current = 1
  loadWorks()
}

function onSearch() {
  query.current = 1
  loadWorks()
}

/* ---------- 卡片展示 ---------- */
/** 三个板块的封面都是 coverUrl（图片板块恒等于首图），所以取法统一 */
function coverSrc(w) {
  return w.coverUrl ? mediaUrl(w.coverUrl) : ''
}

function fmtDuration(sec) {
  if (!sec || sec <= 0) return '0:00'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function fmtSize(bytes) {
  if (!bytes || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 封面右下角那一枚角标：图集张数 / 音频时长 / 小说字数，一眼看出这条是什么 */
function badgeText(w) {
  if (isImage.value) return `${w.imageCount || 1} 图`
  if (isMusic.value) return fmtDuration(w.duration)
  return `${w.wordCount || 0} 字`
}

const countWord = computed(() => (isImage.value ? '浏览' : isMusic.value ? '播放' : '阅读'))

async function onRemove(w) {
  try {
    await ElMessageBox.confirm(`确定删除${label.value}「${w.title}」吗？删除后不可恢复。`, `删除${label.value}`, {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteWork(type.value, w.id)
    works.value = works.value.filter((x) => x.id !== w.id)
    query.total = Math.max(0, query.total - 1)
    ElMessage.success('已删除')
  } catch {
    // request.js 已弹过提示
  }
}

/* ---------- 图片：图集弹层 ---------- */
const albumVisible = ref(false)
const albumWork = ref(null)
const albumIndex = ref(0)
const albumSrc = computed(() => {
  const urls = albumWork.value?.imageUrls || []
  const raw = urls[albumIndex.value]
  return raw ? mediaUrl(raw) : ''
})

function openAlbum(w) {
  albumWork.value = w
  albumIndex.value = 0
  albumVisible.value = true
  reportWorkPlay(type.value, w.id)
  w.playCount = (w.playCount || 0) + 1
}

function albumStep(delta) {
  const total = (albumWork.value?.imageUrls || []).length
  if (!total) return
  // 循环翻页：末张再点下一张回到第一张，比 disabled 到底更顺手
  albumIndex.value = (albumIndex.value + delta + total) % total
}

/* ---------- 音乐：网格上方播放条 ---------- */
const playingMusic = ref(null)
const audioEl = ref(null)
const audioSrc = computed(() => (playingMusic.value ? mediaUrl(playingMusic.value.fileUrl) : ''))
const playingMeta = computed(() => {
  const w = playingMusic.value
  if (!w) return ''
  return [w.uploaderName, fmtDuration(w.duration), `${w.playCount || 0} 次播放`, fmtSize(w.size)]
    .filter(Boolean)
    .join(' · ')
})

function openPlayer(w) {
  const changed = playingMusic.value?.id !== w.id
  playingMusic.value = w
  if (changed) {
    reportWorkPlay(type.value, w.id)
    w.playCount = (w.playCount || 0) + 1
  }
}

function closePlayer() {
  playingMusic.value = null
}

// mediaUrl 是异步的：src 还是空串时 autoplay 不会重试，所以 blob 到位后再驱动一次
watch(audioSrc, (src) => {
  if (src && audioEl.value) {
    audioEl.value.load?.()
    audioEl.value.play?.().catch(() => {})
  }
})

/* ---------- 小说：阅读弹层 ---------- */
const readVisible = ref(false)
const readLoading = ref(false)
const reading = ref(null)

/**
 * 点开才取正文：列表响应刻意不含 LONGTEXT，正文与阅读数都由这一次详情请求带回来，
 * 所以这里不能再补 reportWorkPlay，否则计数翻倍。
 */
async function openRead(w) {
  readVisible.value = true
  readLoading.value = true
  reading.value = null
  try {
    reading.value = await fetchWorkDetail(type.value, w.id)
    w.playCount = (w.playCount || 0) + 1
  } catch {
    readVisible.value = false
  } finally {
    readLoading.value = false
  }
}

/** 卡片点击分发：图片开图集、音乐起播放条、小说开阅读弹层 */
function onCardClick(w) {
  if (isImage.value) openAlbum(w)
  else if (isMusic.value) openPlayer(w)
  else openRead(w)
}

/* ---------- 发布 / 编辑 ---------- */
const editVisible = ref(false)
const saving = ref(false)
const saveHint = ref('')
const editingId = ref(null)
const form = reactive({ title: '', category: '综合', summary: '', content: '' })
/** 图集条目：{ file } 未上传（新选）| { fileId, rawUrl } 已上传（历史） */
const album = ref([])
/** 音频本体与封面，形状同上（音乐板块 fileId 只有一个，故不成数组） */
const audioItem = ref(null)
const coverItem = ref(null)
/** 隐藏 input 的句柄：点按钮代它开文件选择框，选完立刻清 value 以便重选同一文件 */
const albumInput = ref(null)
const audioInput = ref(null)
const coverInput = ref(null)
const bookInput = ref(null)
/**
 * TXT 整本导入结果：{ fileName, pieces: [{ title, content, words }], submitted }。
 *
 * <p>submitted 既是已发布篇数也是断点：中途失败后再点发布从这一篇续发，不重发前面的。
 * 解析出来的正文刻意<b>不进 form.content</b>：几百万字塞进 textarea 会把整个弹层连实时预览
 * 一起拖死（MarkdownView 是同步整篇解析），而且那个长度也没人会在框里手工改。
 */
const book = ref(null)
const bookParsing = ref(false)
/** 篇章清单只列前这么多条：三百章的书没必要渲三百个 div，剩下的用一行计数代替 */
const PIECES_PREVIEW = 120
const bookWords = computed(() => (book.value ? book.value.pieces.reduce((s, p) => s + p.words, 0) : 0))
const visiblePieces = computed(() => (book.value ? book.value.pieces.slice(0, PIECES_PREVIEW) : []))
/** 编辑时留下的原 size/ext/contentType：图集没有逐张字节数，未增删图片时沿用原值 */
const originMeta = reactive({ size: null, ext: '', contentType: '' })
const objectUrls = new Set()

function rememberUrl(url) {
  objectUrls.add(url)
  return url
}

function releaseUrls() {
  objectUrls.forEach((u) => URL.revokeObjectURL(u))
  objectUrls.clear()
}

function resetForm() {
  editingId.value = null
  form.title = ''
  form.category = '综合'
  form.summary = ''
  form.content = ''
  book.value = null
  album.value = []
  audioItem.value = null
  coverItem.value = null
  originMeta.size = null
  originMeta.ext = ''
  originMeta.contentType = ''
  saveHint.value = ''
  releaseUrls()
}

function openCreate() {
  resetForm()
  form.category = filter.category !== '全部' ? filter.category : '综合'
  editVisible.value = true
}

/** 编辑回填：小说要先取详情（列表没正文），另两个板块列表里的字段就够铺表单 */
async function openEdit(w) {
  resetForm()
  try {
    const detail = isNovel.value ? await fetchWorkDetail(type.value, w.id) : w
    editingId.value = detail.id
    form.title = detail.title || ''
    form.category = detail.category || '综合'
    form.summary = detail.summary || ''
    originMeta.size = detail.size ?? null
    originMeta.ext = detail.ext || ''
    originMeta.contentType = detail.contentType || ''
    if (isImage.value) {
      const ids = detail.imageIds || []
      const urls = detail.imageUrls || []
      album.value = ids.map((id, i) => ({ fileId: id, rawUrl: urls[i] || '' }))
    } else if (isMusic.value) {
      audioItem.value = {
        fileId: detail.fileId,
        rawUrl: detail.fileUrl || '',
        size: detail.size,
        ext: detail.ext || '',
        contentType: detail.contentType || '',
        duration: detail.duration || null
      }
    }
    if (detail.coverFileId) {
      coverItem.value = { fileId: detail.coverFileId, rawUrl: detail.coverUrl || '' }
    }
    editVisible.value = true
  } catch {
    // 拉不到详情（非本人 / 已删除），错误提示已由拦截器弹出
  }
}

/** 图集与封面的缩略图地址：新选的用本地 objectURL 预览，历史图走受控地址转 blob */
function itemSrc(item) {
  if (!item) return ''
  if (item.preview) return item.preview
  return item.rawUrl ? mediaUrl(item.rawUrl) : ''
}

function onAlbumChange(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  if (album.value.length + files.length > ALBUM_MAX) {
    ElMessage.warning(`一次最多 ${ALBUM_MAX} 张图，当前已选 ${album.value.length} 张`)
    return
  }
  files.forEach((file) => {
    album.value.push({ file, preview: rememberUrl(URL.createObjectURL(file)) })
  })
  if (!form.title) {
    form.title = files[0].name.replace(/\.[^.]+$/, '').slice(0, 60)
  }
}

function removeAlbumItem(index) {
  const item = album.value[index]
  if (item?.preview) {
    URL.revokeObjectURL(item.preview)
    objectUrls.delete(item.preview)
  }
  album.value.splice(index, 1)
}

/** 点缩略图把它挪到首位：首图就是卡片封面，作者总想挑一张最好看的 */
function makeFirst(index) {
  if (index <= 0) return
  const [item] = album.value.splice(index, 1)
  album.value.unshift(item)
}

function pickAudio(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  if (audioItem.value?.preview) {
    URL.revokeObjectURL(audioItem.value.preview)
    objectUrls.delete(audioItem.value.preview)
  }
  // 时长与字节数要等上传前才读得出来，这里先只记 File
  audioItem.value = { file, preview: '' }
  if (!form.title) {
    form.title = file.name.replace(/\.[^.]+$/, '').slice(0, 60)
  }
}

function removeAudio() {
  if (audioItem.value?.preview) {
    URL.revokeObjectURL(audioItem.value.preview)
    objectUrls.delete(audioItem.value.preview)
  }
  audioItem.value = null
}

function pickCover() {
  if (coverInput.value) {
    coverInput.value.click()
  }
}
function onCoverChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  coverItem.value = { file, preview: rememberUrl(URL.createObjectURL(file)) }
}

function removeCover() {
  if (coverItem.value?.preview) {
    URL.revokeObjectURL(coverItem.value.preview)
    objectUrls.delete(coverItem.value.preview)
  }
  // 置空即可：PUT 是整条替换，coverFileId 传 null 就是清封面
  coverItem.value = null
}

/**
 * 上传一张图片类文件前先过内容闸门（与视频封面同一条链路）：
 * 命中违规就整体中止发布，不把剩下的文件继续往上传——拦下来的图不该在 im_file 里留副本。
 */
async function uploadImage(file, bizType, scene) {
  const guard = await guardImage(file, { scene })
  if (!guard.pass) {
    throw new Error(guard.message)
  }
  return uploadFileSmart(file, bizType, null, () => {})
}

/** 图集按顺序逐张上传，顺序即展示顺序；并发会打乱顺序，这里要的是可预期的第 N 张 */
async function publishAlbum() {
  const ids = []
  const sizes = []
  let firstExt = ''
  let firstType = ''
  let added = 0
  for (const item of album.value) {
    if (item.fileId) {
      ids.push(item.fileId)
      continue
    }
    added++
    saveHint.value = `上传第 ${ids.length + 1} 张…`
    const vo = await uploadImage(item.file, 'image_work', 'image_work')
    ids.push(vo.fileId)
    sizes.push(vo.size || 0)
    if (!firstExt) {
      firstExt = vo.ext || ''
      firstType = vo.contentType || ''
    }
  }
  if (ids.length < ALBUM_MIN || ids.length > ALBUM_MAX) {
    throw new Error(`一次要发 ${ALBUM_MIN}~${ALBUM_MAX} 张图`)
  }
  // 没有新增图片时拿不到逐张字节数（库里只存合计），沿用原值比填 0 诚实
  const size = added > 0 ? sizes.reduce((a, b) => a + b, 0) : originMeta.size || 0
  return {
    imageIds: ids,
    title: form.title.trim(),
    category: form.category || '综合',
    size,
    ext: firstExt || originMeta.ext,
    contentType: firstType || originMeta.contentType
  }
}

async function publishMusic() {
  const body = { title: form.title.trim(), category: form.category || '综合' }
  if (audioItem.value?.file) {
    saveHint.value = '上传音频中…'
    const duration = await readAudioDuration(audioItem.value.file)
    const vo = await uploadFileSmart(audioItem.value.file, 'music', duration, () => {})
    Object.assign(body, {
      fileId: vo.fileId,
      duration,
      size: vo.size,
      ext: vo.ext,
      contentType: vo.contentType
    })
  } else if (audioItem.value?.fileId) {
    Object.assign(body, {
      fileId: audioItem.value.fileId,
      duration: audioItem.value.duration,
      size: audioItem.value.size,
      ext: audioItem.value.ext,
      contentType: audioItem.value.contentType
    })
  } else {
    throw new Error('请先选择音频文件')
  }
  return body
}

function publishNovel() {
  if (!form.content.trim()) {
    throw new Error('请填写正文')
  }
  if (form.content.length > CONTENT_MAX) {
    throw new Error(`正文超过 ${CONTENT_MAX} 字符，长篇请分篇发布`)
  }
  return {
    content: form.content,
    title: form.title.trim(),
    category: form.category || '综合',
    summary: form.summary.trim() || undefined
  }
}

/**
 * 选 TXT 并解析成待发布篇章。解码 + 逐行判章节是同步的大头，几 MB 文本要占住主线程
 * 上百毫秒，所以先让出一帧把「解析中」画出来，否则按钮看着像没反应。
 */
async function onBookChange(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) {
    return
  }
  if (file.size > IMPORT_MAX_BYTES) {
    ElMessage.warning(`文件超过 ${Math.round(IMPORT_MAX_BYTES / 1024 / 1024)}MB，请确认选的是纯文本`)
    return
  }
  bookParsing.value = true
  saveHint.value = '解析中…'
  try {
    const text = decodeText(await file.arrayBuffer())
    await new Promise((resolve) => setTimeout(resolve, 0))
    const pieces = splitIntoPieces(text)
    if (!pieces.length) {
      ElMessage.warning('没解析出可发布的正文（文件可能是空的）')
      return
    }
    book.value = { fileName: file.name, pieces, submitted: 0 }
    if (!form.title.trim()) {
      // 书名退而求其次用文件名：标题列宽 60，超长的文件名在这里就剪好，不留给后端去截
      form.title = file.name.replace(/\.[^.]+$/, '').slice(0, 60)
    }
    ElMessage.success(`识别出 ${pieces.length} 篇 / ${bookWords.value} 字，点「发布」逐篇提交`)
  } catch {
    ElMessage.error('解析失败，请确认文件是 TXT 纯文本')
  } finally {
    bookParsing.value = false
    saveHint.value = ''
  }
}

function clearBook() {
  book.value = null
}

function pickBook() {
  if (bookInput.value) {
    bookInput.value.click()
  }
}

/**
 * 整本发布：逐篇串行 createWork。
 *
 * <p>串行不是保守，是顺序本身：信息流按 createTime 排，并发发出去章节就会乱序，与图集
 * 「第 N 张」是同一条理由。任何一篇失败就停在那里不动，进度已记在 book.submitted，
 * 用户再点一次发布是从断点续发——一次网络抖动不该换成「从第一章重传三百遍」。
 */
async function publishBook() {
  const pieces = book.value.pieces
  const start = book.value.submitted || 0
  if (start >= pieces.length) {
    ElMessage.info('这次导入的篇章已经发完了，请清除导入或重新选文件')
    return
  }
  if (start === 0 && pieces.length > IMPORT_CONFIRM_COUNT) {
    try {
      await ElMessageBox.confirm(
        `将按章发布 ${pieces.length} 篇《${form.title.trim()}》，中途请勿关闭页面。继续？`,
        '整本发布',
        { confirmButtonText: '发布', cancelButtonText: '再想想', type: 'warning' }
      )
    } catch {
      return
    }
  }
  saving.value = true
  try {
    // 封面只挂第一篇：每章顶同一张图会把信息流刷成一面墙，而第一篇就代表这本书
    let coverFileId = null
    if (start === 0 && coverItem.value?.file) {
      saveHint.value = '上传封面中…'
      const vo = await uploadImage(coverItem.value.file, 'novel_cover', 'novel_cover')
      coverFileId = vo.fileId
    }
    for (let i = start; i < pieces.length; i++) {
      const piece = pieces[i]
      saveHint.value = `发布中 ${i + 1}/${pieces.length}：${piece.title}`
      await createWork(type.value, {
        title: composeTitle(form.title.trim(), piece.title),
        category: form.category || '综合',
        content: piece.content,
        // 摘要只给首篇手写的；其余篇章留空，由服务端从各自正文截取
        summary: i === 0 ? form.summary.trim() || undefined : undefined,
        coverFileId: i === 0 ? coverFileId : null
      })
      book.value.submitted = i + 1
    }
    ElMessage.success(`《${form.title.trim()}》已按章发布 ${pieces.length - start} 篇`)
    editVisible.value = false
    query.current = 1
    await loadWorks()
  } catch (err) {
    const done = book.value.submitted || 0
    ElMessage.error(
      `第 ${done + 1} 篇发布失败：${(err && err.message) || '请求中断'}；已发布 ${done} 篇，再点一次「发布」从断点续发`
    )
  } finally {
    saving.value = false
    saveHint.value = ''
  }
}

async function submitWork() {
  if (saving.value) return
  if (!form.title.trim()) {
    ElMessage.warning(isImage.value ? '请填写图集标题' : '请填写标题')
    return
  }
  // 小说整本走另一条路：它不是一条 body，而是一串 createWork
  if (isNovel.value && book.value) {
    await publishBook()
    return
  }
  saving.value = true
  try {
    let body
    if (isImage.value) {
      body = await publishAlbum()
    } else if (isMusic.value) {
      body = await publishMusic()
    } else {
      body = publishNovel()
    }
    // 封面排在本体之后：被内容闸门拦下时只是不发封面，已经传好的本体不至于白等一轮上传
    if (coverItem.value?.file) {
      saveHint.value = '上传封面中…'
      const scene = isMusic.value ? 'music_cover' : 'novel_cover'
      const vo = await uploadImage(coverItem.value.file, scene, scene)
      body.coverFileId = vo.fileId
    } else if (coverItem.value?.fileId) {
      body.coverFileId = coverItem.value.fileId
    } else {
      body.coverFileId = null
    }
    if (editingId.value) {
      await updateWork(type.value, editingId.value, body)
    } else {
      await createWork(type.value, body)
    }
    ElMessage.success(editingId.value ? '已保存' : '发布成功')
    editVisible.value = false
    query.current = 1
    await loadWorks()
  } catch (err) {
    ElMessage.error((editingId.value ? '保存失败：' : '发布失败：') + ((err && err.message) || '上传中断'))
  } finally {
    saving.value = false
    saveHint.value = ''
  }
}

/* ---------- 首次挂载：组件只在切到本 tab 时才创建，所以直接拉，不必等 watch ---------- */
loadCategories()
loadWorks()
onBeforeUnmount(releaseUrls)
</script>

<template>
  <div class="work-feed">
    <!-- 左侧一级频道：精选（最新）/ 热门（按计数）/ 我的（本人） -->
    <aside class="work-feed__nav">
      <div
        v-for="ch in CHANNELS"
        :key="ch.key"
        class="work-feed__nav-item"
        :class="{ 'is-active': channel === ch.key }"
        @click="switchChannel(ch.key)"
      >
        {{ ch.label }}
      </div>
    </aside>

    <div class="work-feed__main">
      <!-- 顶部：搜索 + 发布 -->
      <div class="work-feed__top">
        <el-input
          v-model="filter.keyword"
          class="work-feed__search"
          :placeholder="`搜索${label}标题`"
          clearable
          @keyup.enter="onSearch"
          @clear="onSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="openCreate">发布{{ label }}</el-button>
      </div>

      <!-- 二级分类：'全部' 不带 category 参数，其余精确命中 -->
      <div class="work-feed__cats">
        <span
          class="work-feed__cat"
          :class="{ 'is-active': filter.category === '全部' }"
          @click="switchCategory('全部')"
        >全部</span>
        <span
          v-for="cat in categories"
          :key="cat"
          class="work-feed__cat"
          :class="{ 'is-active': filter.category === cat }"
          @click="switchCategory(cat)"
        >{{ cat }}</span>
      </div>

      <!-- 音乐播放条：与视频板块同一位置，同时只有一个音频在取内容 -->
      <div v-if="isMusic && playingMusic" class="work-feed__player">
        <div class="work-feed__player-bar">
          <UserAvatar :src="playingMusic.uploaderAvatar" :name="playingMusic.uploaderName" :size="26" />
          <div class="work-feed__player-info">
            <div class="work-feed__player-title im-ellipsis">{{ playingMusic.title }}</div>
            <div class="work-feed__player-meta">{{ playingMeta }}</div>
          </div>
          <el-button text @click="closePlayer">关闭 ✕</el-button>
        </div>
        <div class="work-feed__player-body">
          <img v-if="coverSrc(playingMusic)" :src="coverSrc(playingMusic)" class="work-feed__player-cover" alt="" />
          <div v-else class="work-feed__player-cover work-feed__player-cover--empty">♪</div>
          <audio ref="audioEl" :src="audioSrc" controls autoplay class="work-feed__audio"></audio>
        </div>
      </div>

      <!-- 内容网格 -->
      <div v-loading="loading" class="work-feed__grid">
        <div v-for="w in works" :key="w.id" class="work-card" @click="onCardClick(w)">
          <div class="work-card__cover">
            <img v-if="coverSrc(w)" :src="coverSrc(w)" alt="" />
            <div v-else class="work-card__placeholder">
              <el-icon v-if="isImage" :size="34"><Picture /></el-icon>
              <el-icon v-else-if="isNovel" :size="34"><Document /></el-icon>
              <span v-else class="work-card__note">♪</span>
            </div>
            <span class="work-card__play">👁 {{ w.playCount || 0 }}</span>
            <span class="work-card__badge">{{ badgeText(w) }}</span>
            <span v-if="isMusic && playingMusic && playingMusic.id === w.id" class="work-card__playing">播放中</span>
            <span v-if="w.mine" class="work-card__del" @click.stop="onRemove(w)">删除</span>
          </div>
          <div class="work-card__info">
            <div class="work-card__title">{{ w.title }}</div>
            <div v-if="isNovel" class="work-card__summary">{{ w.summary || '暂无摘要' }}</div>
            <div class="work-card__uploader">
              <UserAvatar :src="w.uploaderAvatar" :name="w.uploaderName" :size="20" />
              <span class="im-ellipsis work-card__name">{{ w.uploaderName }}</span>
              <span v-if="w.category" class="work-card__cat">{{ w.category }}</span>
            </div>
            <div v-if="w.mine" class="work-card__ops" @click.stop>
              <el-button text size="small" type="primary" :icon="EditPen" @click="openEdit(w)">编辑</el-button>
              <el-button text size="small" type="danger" :icon="Delete" @click="onRemove(w)">删除</el-button>
            </div>
          </div>
        </div>
        <el-empty v-if="!loading && !works.length" :description="`该频道下还没有${label}`" class="work-feed__empty" />
      </div>

      <div v-if="query.total > query.size" class="work-feed__pager">
        <el-pagination
          layout="prev, pager, next"
          :total="query.total"
          :page-size="query.size"
          :current-page="query.current"
          @current-change="(p) => { query.current = p; loadWorks() }"
        />
      </div>
    </div>

    <!-- 图集弹层：左右翻页 + 底部缩略图条 -->
    <el-dialog v-model="albumVisible" :title="albumWork?.title" width="860px" top="5vh" class="work-album__dialog">
      <div v-if="albumWork" class="work-album">
        <div class="work-album__stage">
          <span class="work-album__nav work-album__nav--prev" @click="albumStep(-1)">‹</span>
          <img v-if="albumSrc" :src="albumSrc" class="work-album__image" alt="" />
          <div v-else class="work-album__image work-album__image--empty">加载中…</div>
          <span class="work-album__nav work-album__nav--next" @click="albumStep(1)">›</span>
          <span class="work-album__counter">{{ albumIndex + 1 }} / {{ (albumWork.imageUrls || []).length }}</span>
        </div>
        <div class="work-album__thumbs">
          <img
            v-for="(u, i) in albumWork.imageUrls || []"
            :key="u + i"
            :src="mediaUrl(u)"
            class="work-album__thumb"
            :class="{ 'is-active': i === albumIndex }"
            alt=""
            @click="albumIndex = i"
          />
        </div>
        <div class="work-album__meta">
          <UserAvatar :src="albumWork.uploaderAvatar" :name="albumWork.uploaderName" :size="24" />
          <span class="work-album__author">{{ albumWork.uploaderName }}</span>
          <span class="work-album__count">{{ countWord }} {{ albumWork.playCount || 0 }}</span>
          <span v-if="albumWork.category" class="work-album__cat">{{ albumWork.category }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- 小说阅读弹层：正文经 MarkdownView 渲染（与文章板块同一个渲染器） -->
    <el-dialog v-model="readVisible" title="正文" width="760px" top="6vh" class="work-read__dialog">
      <div v-loading="readLoading" class="work-read">
        <template v-if="reading">
          <h2 class="work-read__title">{{ reading.title }}</h2>
          <div class="work-read__meta">
            <UserAvatar :src="reading.uploaderAvatar" :name="reading.uploaderName" :size="26" />
            <span class="work-read__author">{{ reading.uploaderName }}</span>
            <span class="work-read__views">👁 {{ reading.playCount || 0 }}</span>
            <span class="work-read__words">{{ reading.wordCount || 0 }} 字</span>
            <span v-if="reading.category" class="work-read__cat">{{ reading.category }}</span>
          </div>
          <MarkdownView :content="reading.content" class="work-read__content" />
        </template>
      </div>
    </el-dialog>

    <!-- 发布 / 编辑弹窗：按板块分支 -->
    <el-dialog
      v-model="editVisible"
      :title="`${editingId ? '编辑' : '发布'}${label}`"
      :width="isNovel ? '920px' : '620px'"
      top="5vh"
      :close-on-click-modal="false"
      class="work-edit__dialog"
    >
      <div class="work-edit">
        <el-input v-model="form.title" maxlength="60" show-word-limit placeholder="标题（必填，最多 60 字）" />
        <el-select v-model="form.category" placeholder="分类" class="work-edit__cat">
          <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
        </el-select>

        <!-- 图片：多选 1~9，首图自动作封面 -->
        <template v-if="isImage">
          <div class="work-edit__hint">选 1~9 张图，第一张作封面；点缩略图上的「首」可调整顺序</div>
          <div class="work-edit__album">
            <div v-for="(item, i) in album" :key="(item.fileId || 'new') + '-' + i" class="work-edit__thumb">
              <img :src="itemSrc(item)" alt="" />
              <span class="work-edit__thumb-first" title="移到首图" @click="makeFirst(i)">首</span>
              <span class="work-edit__thumb-del" title="移除" @click="removeAlbumItem(i)">✕</span>
              <span v-if="i === 0" class="work-edit__thumb-tag">封面</span>
            </div>
            <div class="work-edit__thumb work-edit__thumb--add" @click="albumInput && albumInput.click()">
              <el-icon :size="22"><Plus /></el-icon>
            </div>
          </div>
          <input ref="albumInput" type="file" accept="image/*" multiple hidden @change="onAlbumChange" />
        </template>

        <!-- 音乐：单个音频 + 可选封面，时长客户端读 -->
        <template v-else-if="isMusic">
          <div class="work-edit__row">
            <el-button :icon="Picture" @click="audioInput.click()">
              {{ audioItem ? '更换音频' : '选择音频' }}
            </el-button>
            <span class="work-edit__name">
              {{ audioItem ? (audioItem.file ? audioItem.file.name : `已上传音频 #${audioItem.fileId}`) : '未选择（mp3 / wav / flac / aac / m4a / ogg…）' }}
            </span>
            <el-button v-if="audioItem && !editingId" text type="danger" @click="removeAudio">清空</el-button>
            <input ref="audioInput" type="file" accept="audio/*" hidden @change="pickAudio" />
          </div>
          <div class="work-edit__row">
            <el-button :icon="Picture" @click="pickCover">
              {{ coverItem ? '更换封面' : '上传封面（选填）' }}
            </el-button>
            <img v-if="coverItem" :src="itemSrc(coverItem)" class="work-edit__cover-img" alt="" />
            <el-button v-if="coverItem" text type="danger" @click="removeCover">移除封面</el-button>
            <input ref="coverInput" type="file" accept="image/*" hidden @change="onCoverChange" />
          </div>
        </template>

        <!-- 小说：摘要 + 封面 + 正文（手写走 Markdown 双栏，整本走 TXT 导入出篇章清单） -->
        <template v-else>
          <el-input
            v-model="form.summary"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            resize="none"
            placeholder="摘要（选填，空则由服务端从正文截取）"
            class="work-edit__summary"
          />
          <div class="work-edit__row">
            <el-button :icon="Picture" @click="pickCover">
              {{ coverItem ? '更换封面' : '上传封面（选填）' }}
            </el-button>
            <img v-if="coverItem" :src="itemSrc(coverItem)" class="work-edit__cover-img" alt="" />
            <el-button v-if="coverItem" text type="danger" @click="removeCover">移除封面</el-button>
            <input ref="coverInput" type="file" accept="image/*" hidden @change="onCoverChange" />
          </div>
          <!-- 导入整本与手写正文是二选一：进了整本就收起正文框，几百万字塞进 textarea
               会把弹层连实时预览一起卡死（MarkdownView 是同步整篇解析） -->
          <div class="work-edit__row">
            <el-button :icon="Document" :loading="bookParsing" :disabled="!!editingId" @click="pickBook">
              {{ book ? '重新导入 TXT' : '导入 TXT 整本' }}
            </el-button>
            <span class="work-edit__name">
              {{
                book
                  ? `${book.fileName} · ${book.pieces.length} 篇 / ${bookWords} 字`
                  : editingId
                    ? '编辑单篇不提供导入整本'
                    : '按「第X章」自动拆成多篇逐篇发布（纯文本，≤ 8MB）'
              }}
            </span>
            <el-button v-if="book" text type="danger" :disabled="saving" @click="clearBook">清除导入</el-button>
            <input ref="bookInput" type="file" accept=".txt,text/plain" hidden @change="onBookChange" />
          </div>
          <div v-if="!book" class="work-edit__editor">
            <div class="work-edit__pane">
              <div class="work-edit__pane-bar"><span>Markdown 正文</span></div>
              <el-input
                v-model="form.content"
                type="textarea"
                :autosize="{ minRows: 14, maxRows: 22 }"
                placeholder="支持 Markdown：# 标题、**加粗**、- 列表、`代码`…"
              />
            </div>
            <div class="work-edit__pane">
              <div class="work-edit__pane-bar"><span>实时预览</span></div>
              <div class="work-edit__preview">
                <MarkdownView :content="form.content" />
              </div>
            </div>
          </div>

          <div v-if="book" class="work-edit__pieces">
            <div class="work-edit__pieces-bar">
              <span>将按章发布 {{ book.pieces.length }} 篇，顺序即章节顺序；每篇首行自动带「## 篇名」</span>
              <span v-if="book.submitted" class="work-edit__pieces-done">
                已发布 {{ book.submitted }} 篇，再点发布从第 {{ book.submitted + 1 }} 篇继续
              </span>
            </div>
            <div class="work-edit__pieces-list">
              <div v-for="(piece, i) in visiblePieces" :key="i" class="work-edit__piece">
                <span class="work-edit__piece-no">{{ i + 1 }}</span>
                <span class="work-edit__piece-title">{{ piece.title }}</span>
                <span class="work-edit__piece-words">{{ piece.words }} 字</span>
              </div>
              <div v-if="book.pieces.length > PIECES_PREVIEW" class="work-edit__piece-more">
                …其余 {{ book.pieces.length - PIECES_PREVIEW }} 篇同上
              </div>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <span v-if="saveHint" class="work-edit__hint">{{ saveHint }}</span>
        <el-button :disabled="saving" @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitWork">
          {{ editingId ? '保存' : book ? `发布 ${book.pieces.length - (book.submitted || 0)} 篇` : '发布' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 布局与视频板块同构，只是类名换成 work-feed：两边可以各自演化而不互相牵连 */
.work-feed {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.work-feed__nav {
  flex: none;
  width: 96px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: sticky;
  top: 12px;
}

.work-feed__nav-item {
  padding: 9px 14px;
  border-radius: 8px;
  font-size: 15px;
  color: #18191c;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.work-feed__nav-item:hover {
  background: rgba(0, 0, 0, 0.05);
}

.work-feed__nav-item.is-active {
  background: #fff2f5;
  color: #fe2c55;
  font-weight: 600;
}

.work-feed__main {
  flex: 1;
  min-width: 0;
}

.work-feed__top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.work-feed__search {
  max-width: 360px;
}

.work-feed__cats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.work-feed__cat {
  padding: 5px 14px;
  border-radius: 16px;
  background: #f2f3f5;
  font-size: 13px;
  color: #4e5969;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.work-feed__cat:hover {
  background: #e5e6eb;
}

.work-feed__cat.is-active {
  background: #fe2c55;
  color: #fff;
}

.work-feed__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px 12px;
  min-height: 120px;
}

.work-feed__empty {
  grid-column: 1 / -1;
}

.work-feed__pager {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}

/* 音乐播放条 */
.work-feed__player {
  margin-bottom: 16px;
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.work-feed__player-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--im-border, #e4e7ed);
}

.work-feed__player-info {
  flex: 1;
  min-width: 0;
}

.work-feed__player-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.work-feed__player-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.work-feed__player-body {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px;
}

.work-feed__player-cover {
  width: 84px;
  height: 84px;
  border-radius: 6px;
  object-fit: cover;
  background: #1f2329;
  color: #c9cdd1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
}

.work-feed__audio {
  flex: 1;
  min-width: 0;
  height: 40px;
}

/* 卡片 */
.work-card {
  display: flex;
  flex-direction: column;
  cursor: pointer;
}

.work-card__cover {
  position: relative;
  width: 100%;
  /* padding-top 撑 16:9：与视频卡片同款兼容写法，旧内核 aspect-ratio 不可靠 */
  padding-top: 56.25%;
  border-radius: 6px;
  overflow: hidden;
  background: #1f2329;
  transition: box-shadow 0.2s;
}

.work-card:hover .work-card__cover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.work-card__cover img {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.work-card__placeholder {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #4b5563;
}

.work-card__note {
  font-size: 30px;
  line-height: 1;
}

.work-card__badge {
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 2px 6px;
  border-radius: 0 0 6px 0;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  line-height: 16px;
}

.work-card__play {
  position: absolute;
  left: 6px;
  top: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 12px;
}

.work-card__playing {
  position: absolute;
  left: 6px;
  bottom: 6px;
  padding: 1px 6px;
  border-radius: 4px;
  background: #fb7299;
  color: #fff;
  font-size: 12px;
}

.work-card__del {
  position: absolute;
  right: 6px;
  top: 6px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 12px;
  opacity: 0;
  transition: opacity 0.2s;
}

.work-card:hover .work-card__del {
  opacity: 1;
}

.work-card__info {
  padding: 8px 2px 0;
}

.work-card__title {
  font-size: 14px;
  line-height: 20px;
  color: #18191c;
  margin-bottom: 4px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  word-break: break-all;
}

.work-card:hover .work-card__title {
  color: var(--el-color-primary, #409eff);
}

.work-card__summary {
  font-size: 12px;
  line-height: 18px;
  color: #909399;
  margin-bottom: 6px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.work-card__uploader {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #909399;
}

.work-card__name {
  flex: 1;
  min-width: 0;
}

.work-card__cat {
  flex: none;
  padding: 0 6px;
  border-radius: 8px;
  background: #f2f3f5;
  color: #4e5969;
}

.work-card__ops {
  display: flex;
  gap: 4px;
  margin-top: 2px;
}

/* 图集弹层 */
.work-album__stage {
  position: relative;
  background: #000;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

.work-album__image {
  max-width: 100%;
  max-height: 60vh;
  display: block;
}

.work-album__image--empty {
  color: #c9cdd1;
  font-size: 13px;
  padding: 60px 0;
}

.work-album__nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 36px;
  height: 60px;
  line-height: 60px;
  text-align: center;
  font-size: 28px;
  color: #fff;
  background: rgba(0, 0, 0, 0.35);
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
}

.work-album__nav:hover {
  background: rgba(0, 0, 0, 0.6);
}

.work-album__nav--prev {
  left: 8px;
}

.work-album__nav--next {
  right: 8px;
}

.work-album__counter {
  position: absolute;
  right: 10px;
  bottom: 10px;
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
}

.work-album__thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.work-album__thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: 4px;
  cursor: pointer;
  border: 2px solid transparent;
  background: #1f2329;
}

.work-album__thumb.is-active {
  border-color: #fe2c55;
}

.work-album__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}

.work-album__author {
  color: #303133;
}

/* 阅读弹层 */
.work-read {
  min-height: 160px;
}

.work-read__title {
  font-size: 20px;
  margin: 0 0 10px;
  color: #18191c;
}

.work-read__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #909399;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--im-border, #e4e7ed);
  margin-bottom: 14px;
}

.work-read__author {
  color: #303133;
  font-size: 13px;
}

.work-read__content {
  max-height: 62vh;
  overflow-y: auto;
}

/* 发布弹窗 */
.work-edit {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.work-edit__cat {
  width: 180px;
}

.work-edit__hint {
  font-size: 12px;
  color: #909399;
}

.work-edit__row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.work-edit__name {
  color: #606266;
  font-size: 13px;
  word-break: break-all;
}

.work-edit__cover-img {
  width: 96px;
  height: 60px;
  object-fit: cover;
  border-radius: 4px;
  background: #1f2329;
}

.work-edit__album {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.work-edit__thumb {
  position: relative;
  width: 88px;
  height: 88px;
  border-radius: 6px;
  overflow: hidden;
  background: #f2f3f5;
}

.work-edit__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.work-edit__thumb--add {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed #c9cdd1;
  color: #86909c;
  cursor: pointer;
}

.work-edit__thumb--add:hover {
  border-color: var(--el-color-primary, #409eff);
  color: var(--el-color-primary, #409eff);
}

.work-edit__thumb-first,
.work-edit__thumb-del {
  position: absolute;
  top: 2px;
  width: 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}

.work-edit__thumb-first {
  left: 2px;
}

.work-edit__thumb-del {
  right: 2px;
}

.work-edit__thumb:hover .work-edit__thumb-first,
.work-edit__thumb:hover .work-edit__thumb-del {
  opacity: 1;
}

.work-edit__thumb-tag {
  position: absolute;
  left: 0;
  bottom: 0;
  width: 100%;
  text-align: center;
  background: rgba(254, 44, 85, 0.8);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
}

.work-edit__editor {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.work-edit__pane {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.work-edit__pane-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: #4e5969;
}

.work-edit__preview {
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 4px;
  padding: 10px 12px;
  min-height: 320px;
  max-height: 480px;
  overflow-y: auto;
}

/* 篇章清单只用来核对「切得对不对」，不是正文预览，所以限高自己滚 */
.work-edit__pieces {
  border: 1px solid var(--im-border, #e4e7ed);
  border-radius: 4px;
  overflow: hidden;
}

.work-edit__pieces-bar {
  background: #f7f8fa;
  color: #4e5969;
  display: flex;
  flex-wrap: wrap;
  font-size: 13px;
  gap: 8px;
  justify-content: space-between;
  padding: 8px 12px;
}

/* 断点提示用告警色：它意味着上一轮没跑完，库里已经有前半本了 */
.work-edit__pieces-done {
  color: var(--el-color-warning, #e6a23c);
}

.work-edit__pieces-list {
  max-height: 260px;
  overflow-y: auto;
}

.work-edit__piece {
  align-items: center;
  border-top: 1px solid #f2f3f5;
  display: flex;
  font-size: 13px;
  gap: 10px;
  padding: 6px 12px;
}

.work-edit__piece-no {
  color: #86909c;
  flex: none;
  font-variant-numeric: tabular-nums;
  text-align: right;
  width: 34px;
}

.work-edit__piece-title {
  color: #1d2129;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.work-edit__piece-words {
  color: #86909c;
  flex: none;
}

.work-edit__piece-more {
  border-top: 1px solid #f2f3f5;
  color: #86909c;
  font-size: 12px;
  padding: 6px 12px;
}
</style>
