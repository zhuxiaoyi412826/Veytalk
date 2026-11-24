/**
 * 小说板块「导入 TXT 整本」的纯文本处理：字节解码 → 章节识别 → 超上限二次切分。
 *
 * <p>做成没有 DOM 依赖的纯函数（连 File 对象都不碰，只吃 ArrayBuffer），理由是这三段里
 * 最容易出错的是编码判定和章节误切，把它们和 UI 分开才能单独验证；WorkFeed.vue 只负责
 * 选文件、给进度、逐篇提交。
 *
 * <p><b>为什么要切分</b>：后端 {@code LiveNovel.CONTENT_MAX} 是 20 万字符，超限直接拒
 * （不截断——悄悄吃掉作者的稿子比报错严重）。一本网文整本远超这个量，所以导入的语义是
 * 「一章一篇」：切出来的每一片仍然是一条独立的小说记录，走的是本来就该分篇的既有口径，
 * 后端与阅读器渲染都不用为长篇另开一条路。
 */

/**
 * 单篇正文字符上限。刻意留余量不贴着后端的 20 万：每片首行还要拼 `## 章节名` 的 Markdown
 * 前缀，而且后端算的是 trim 之后的长度，贴线算容易出现「前端以为刚好、后端刚好超」的边界。
 */
export const CHAPTER_MAX_CHARS = 195000

/**
 * 导入文件字节上限（8MB）。中文按 UTF-8 3 字节/字算是两百多万字，够一整本长篇；
 * 超过这个量的文件多半是选错了（或者是带图分的 epub 转出来的怪东西），拦下来比慢慢解析好。
 */
export const IMPORT_MAX_BYTES = 8 * 1024 * 1024

/** 识别到的章节数超过这个值，提交前要求用户再确认一次（几百次串行请求不是一瞬间的事） */
export const IMPORT_CONFIRM_COUNT = 100

/** 一篇都没识别到时的篇名（整本没有章节标记，就按字数切段） */
export const PART_TITLE = '正文'

/** 章节标记行之前那段散落内容（书名、作者、免责声明）的归属篇名 */
export const PREFACE_TITLE = '前言'

/**
 * 章节标题行的起手指纹。覆盖网文与出版书的常见写法：第N章 / 第N回 / 第N节 / 第N卷 / 第N集、
 * 序章、序言、楔子、后记、尾声、番外、附录、终章、结局。数字段同时收阿拉伯数字与中文数字。
 */
const CHAPTER_HEAD = /^(?:第\s*[0-9〇零一二三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟两]+\s*[章节回卷集]|序\s*[章言]|楔\s*子|后\s*记|尾\s*声|终\s*章|结\s*局|附\s*录|外\s*传|番\s*外)/

/** 篇名里不该出现的句读：真的标题不带这些，而正文里的「他终于想起了第三章的故事。」必须被排除掉 */
const TITLE_FORBIDDEN_PUNCT = /[。，！？；,.!?;]/

/** 篇名与副标题之间的合法分隔符：「第三章 初见」「第三章·初见」「第三章：初见」都算切好了 */
const TITLE_SEPARATOR = /^[\s\u3000:：、·\-—「《【(（]/

/** 标题行最大长度：超过这个长度基本可以断定是正文叙述而不是篇名 */
const TITLE_MAX_LEN = 40

/** 紧跟篇名的不是分隔符而是正文时，只有整行短到这个长度才当标题（「第三章初见」算，「第三章的内容我后来重写过很多次」不算） */
const TITLE_TIGHT_MAX_LEN = 14

/**
 * 把文件字节解成字符串。
 *
 * <p>顺序是 BOM → 严格 UTF-8 → GBK：中文 TXT 一大半是 GBK/GB2312（Windows 记事本与
 * 各大书站的默认编码），直接按 UTF-8 读会解出一屏乱码，而浏览器不会替我们报错。
 * 所以先拿 {@code fatal: true} 试 UTF-8——它是唯一能区分两种编码的硬判据：GBK 字节序列
 * 极少能通过 UTF-8 的合法性校验，试通了就真的是 UTF-8，试翻了就交给 GBK。
 *
 * @param {ArrayBuffer} buffer 文件字节
 * @returns {string} 归一化换行后的文本
 */
export function decodeText(buffer) {
  const bytes = new Uint8Array(buffer)
  let text = ''

  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    text = new TextDecoder('utf-8').decode(bytes.subarray(3))
  } else if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    text = new TextDecoder('utf-16le').decode(bytes.subarray(2))
  } else if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    text = new TextDecoder('utf-16be').decode(bytes.subarray(2))
  } else {
    text = utf8OrGbk(bytes)
  }

  // 清掉 NUL 与零宽字符，并把 CRLF / 单 CR 统一成 LF，后面的按行处理只需要认 \n
  return text.replace(/\u0000/g, '').replace(/\uFEFF/g, '').replace(/\r\n?/g, '\n')
}

function utf8OrGbk(bytes) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    // UTF-8 校验没过 = 这批字节不是 UTF-8，最可能的是 GBK。老 Safari 不认识 gbk 标签，
    // 构造会抛 RangeError，那就退到 windows-1252：至少不炸，中文会花，但提示里能看出来
    try {
      const gbk = new TextDecoder('gbk').decode(bytes)
      // GBK 解出来一片替代符或大量控制字符时说明它其实也不是 GBK（比如是 Big5/UTF-7），
      // 与其交出去渲染成乱码，不如退回 UTF-8 宽松解码让内容至少是可复制的
      const bad = (gbk.match(/\uFFFD/g) || []).length
      if (bad > gbk.length * 0.01) {
        return new TextDecoder('utf-8').decode(bytes)
      }
      return gbk
    } catch {
      return new TextDecoder('utf-8').decode(bytes)
    }
  }
}

/**
 * 判定一行是不是章节标题行。
 *
 * <p>四道门槛同时成立才算：行首命中篇名指纹、行长 ≤ 40、行内不带句读、篇名之后的衔接合理。
 * 只认指纹会把正文里那句「他终于想起了第三章的故事」当成新章起点，整本书被切得七零八落——
 * 这种错发出去是收不回来的，所以判定宁可偏保守：漏切一个篇名只是少开一篇（内容仍然完整落在
 * 上一篇里），误切一处则是把一段正文劈成两篇、还凭空多出一个假标题。
 */
function isChapterLine(line) {
  const value = line.trim()
  if (!value || value.length > TITLE_MAX_LEN || TITLE_FORBIDDEN_PUNCT.test(value)) {
    return false
  }
  const matched = CHAPTER_HEAD.exec(value)
  if (!matched) {
    return false
  }
  const rest = value.slice(matched[0].length)
  if (!rest || TITLE_SEPARATOR.test(rest)) {
    return true
  }
  // 篇名后面直接贴汉字：短到像「第三章初见」才当标题，长句子一律当正文
  return value.length <= TITLE_TIGHT_MAX_LEN
}

/**
 * 按空白字符剔除后的字数，与后端 {@code WorkRules.wordCount} 同口径。
 *
 * <p>中文没有空格可分，按词切会永远算出 1；这里只用于导入预览显示，真落库的数以服务端为准。
 */
export function countWords(text) {
  let count = 0
  for (let i = 0; i < text.length; i++) {
    if (!/\s/.test(text.charAt(i))) {
      count++
    }
  }
  return count
}

/**
 * 把整本文本切成待发布的篇章。
 *
 * <p>返回的每一片都是一条小说记录的内容体，首行已经带上 {@code ## 篇名}，这样阅读器里
 * 翻到任意一篇都能看到自己是谁的哪一章，二次切分出来的续篇也不会变成没有标题的一坨字。
 *
 * @param {string} text 解码后的整本正文
 * @returns {{ title: string, content: string, words: number }[]} 每篇一片，顺序即书籍顺序
 */
export function splitIntoPieces(text) {
  const lines = text.split('\n')
  const chapters = []
  const prefaceLines = []
  let current = null
  let found = false

  for (const line of lines) {
    if (isChapterLine(line)) {
      // 同名篇名连排（有些书「第一章」重复出现）不去重：那是作者的原样，改动反而对不上稿
      found = true
      current = { title: line.trim(), lines: [] }
      chapters.push(current)
    } else if (current) {
      current.lines.push(line)
    } else {
      // 第一个章节标记之前的内容（书名页、作者信息、免责声明）：空行原样留着，
      // 它们是段落结构的一部分，剔掉会把书前的排版抹平
      prefaceLines.push(line)
    }
  }

  const sources = []
  if (!found) {
    // 一个章节都没认出来：整本当一篇交给二次切分，按字数切成「正文（1）（2）…」。
    // 这里直接拿 text 原样而不是拼回的行数组：上一版把空行当垃圾剔了，结果段间
    // 空行全部消失、整本书挤成一坨，拼回来与原文也对不上
    sources.push({ title: PART_TITLE, body: text.trim() })
  } else {
    const preface = prefaceLines.join('\n').trim()
    if (preface) {
      sources.push({ title: PREFACE_TITLE, body: preface })
    }
    for (const c of chapters) {
      sources.push({ title: c.title, body: c.lines.join('\n').trim() })
    }
  }

  const pieces = []
  for (const item of sources) {
    if (!item.body) {
      continue
    }
    for (const part of chunkBody(item.title, item.body)) {
      pieces.push({
        title: part.title,
        content: `## ${part.title}\n\n${part.body}`,
        words: countWords(part.body)
      })
    }
  }
  return pieces
}

/**
 * 单章超上限时按段落边界二次切分。
 *
 * <p>下刀顺序：先试空行（段落之间），再退到单行，最后才是硬切——只有出现一行就超过
 * 19.5 万字的怪物文件才会走到硬切那一支。宁可切出「（续1）」也不要悄悄截掉后半段。
 */
function chunkBody(title, body) {
  if (body.length <= CHAPTER_MAX_CHARS) {
    return [{ title, body }]
  }

  const out = []
  let rest = body
  let index = 1
  while (rest.length > CHAPTER_MAX_CHARS) {
    const cut = findCut(rest)
    out.push({ title: `${title}（${index}）`, body: rest.slice(0, cut).trim() })
    rest = rest.slice(cut).trim()
    index++
  }
  if (rest) {
    out.push({ title: index === 1 ? title : `${title}（${index}）`, body: rest })
  }
  return out
}

function findCut(text) {
  const window = text.slice(0, CHAPTER_MAX_CHARS)

  let blank = window.lastIndexOf('\n\n')
  if (blank >= CHAPTER_MAX_CHARS * 0.6) {
    return blank
  }
  const line = window.lastIndexOf('\n')
  if (line > 0) {
    return line
  }
  // 整个窗口没有一个换行：超长单行，只能按字符硬切，切完这一刀继续
  return CHAPTER_MAX_CHARS
}

/**
 * 拼一行不超过列宽的篇标题。
 *
 * <p>后端 {@code WorkRules.title} 对超宽标题是「截断」而不是报错，而书名 + 篇名很容易就顶到
 * VARCHAR(60)。信息流卡片上要认出这是哪本书的哪一章，被牺牲的该是书名而不是章名，
 * 所以这里主动把书名削短，绝不整行丢字符。
 *
 * @param {string} book 书名（用户填的标题或文件名）
 * @param {string} piece 篇名（含「（续N）」后缀）
 * @param {number} max 列宽，默认 {@code WorkRules.TITLE_MAX} 的 60
 */
export function composeTitle(book, piece, max = 60) {
  const name = (book || '').trim()
  const tail = (piece || '').trim()
  if (!name) {
    return tail.slice(0, max)
  }
  if (!tail) {
    return name.slice(0, max)
  }
  const joined = `${name} ${tail}`
  if (joined.length <= max) {
    return joined
  }
  // 给篇名留足位置，书名只留剩下的（至少 4 个字，再短就不如不要书名）
  const keep = Math.max(4, max - tail.length - 1)
  return `${name.slice(0, keep)} ${tail}`.slice(0, max)
}
