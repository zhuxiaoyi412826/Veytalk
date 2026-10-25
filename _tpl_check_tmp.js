const fs = require('fs')
const path = require('path')
const yaml = require(path.resolve('electron/node_modules/js-yaml'))

const TYPES = new Set(['markdown', 'textarea', 'input', 'dropdown', 'checkboxes'])
const RENDERS = new Set(['bash', 'diff', 'hcl', 'json', 'python', 'jvm', 'yaml', 'markdown'])
let fail = 0
const err = (f, m) => {
  fail += 1
  console.log(`  FAIL [${f}] ${m}`)
}

const dir = '.github/ISSUE_TEMPLATE'
for (const name of fs.readdirSync(dir)) {
  const file = path.join(dir, name)
  let doc
  try {
    doc = yaml.load(fs.readFileSync(file, 'utf8'))
  } catch (e) {
    err(name, 'YAML 解析失败: ' + e.message)
    continue
  }
  console.log(`\n== ${name} == YAML_OK`)

  if (name === 'config.yml') {
    if (typeof doc.blank_issues_enabled !== 'boolean') err(name, 'blank_issues_enabled 必须是布尔')
    if (!Array.isArray(doc.contact_links)) err(name, 'contact_links 必须是数组')
    else
      doc.contact_links.forEach((c, i) => {
        ;['name', 'url', 'about'].forEach((k) => {
          if (!c[k]) err(name, `contact_links[${i}] 缺 ${k}`)
        })
        if (c.url && !/^https?:\/\//.test(c.url)) err(name, `contact_links[${i}].url 非法`)
      })
    console.log(`   contact_links=${doc.contact_links ? doc.contact_links.length : 0}`)
    continue
  }

  ;['name', 'description', 'body'].forEach((k) => {
    if (!doc[k]) err(name, `顶层缺 ${k}`)
  })
  if (doc.name && doc.name.length > 100) err(name, 'name 过长')
  if (doc.labels && !Array.isArray(doc.labels)) err(name, 'labels 必须是数组')

  const ids = new Set()
  ;(doc.body || []).forEach((item, i) => {
    const at = `${item.type || '?'}#${i}`
    if (!TYPES.has(item.type)) {
      err(name, `${at}: type 非法（可用：${[...TYPES].join('/')}）`)
      return
    }
    if (item.type === 'markdown' && !item.value) err(name, `${at}: markdown 必须有 value`)
    if (item.type !== 'markdown') {
      if (!item.id) err(name, `${at}: 缺 id`)
      else if (ids.has(item.id)) err(name, `${at}: id 重复 ${item.id}`)
      else ids.add(item.id)
      if (!item.attributes || !item.attributes.label) err(name, `${at}: 缺 attributes.label`)
      if (item.attributes && item.attributes.label && item.attributes.label.length > 125) err(name, `${at}: label 过长`)
    }
    const a = item.attributes || {}
    if (item.type === 'dropdown') {
      if (!Array.isArray(a.options) || a.options.length === 0) err(name, `${at}: dropdown 需要非空 options`)
      else if (a.options.some((o) => typeof o !== 'string' || !o)) err(name, `${at}: options 必须全是非空字符串`)
    }
    if (item.type === 'checkboxes') {
      if (!Array.isArray(a.options) || a.options.length === 0) err(name, `${at}: checkboxes 需要非空 options`)
      else
        a.options.forEach((o, j) => {
          if (!o || typeof o.label !== 'string' || !o.label) err(name, `${at}: options[${j}] 缺 label`)
          if (o && typeof o.required !== 'boolean') err(name, `${at}: options[${j}].required 必须是布尔`)
        })
    }
    if (a.render && !RENDERS.has(a.render)) err(name, `${at}: render="${a.render}" 不在允许表内`)
    if (item.type === 'input' && a.placeholder && a.placeholder.length > 128) err(name, `${at}: input placeholder 上限 128，当前 ${a.placeholder.length}`)
    if (item.validations && typeof item.validations.required !== 'undefined' && typeof item.validations.required !== 'boolean')
      err(name, `${at}: validations.required 必须是布尔`)
  })
  const req = (doc.body || []).filter((b) => b.validations && b.validations.required).length
  console.log(`   body=${(doc.body || []).length} 必填=${req} labels=${JSON.stringify(doc.labels || [])} name=${doc.name}`)
}

console.log('\n' + (fail === 0 ? 'ALL_SCHEMA_OK' : 'SCHEMA_FAIL=' + fail))
