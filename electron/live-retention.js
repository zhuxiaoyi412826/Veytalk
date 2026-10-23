'use strict'
/**
 * 直播回放源文件的保留期清理（媒体面）。
 *
 * 为什么需要它：回放是「免费」的——分片从推流那一刻起就落在媒体服务器上，Java 后端只管
 * Redis 里那个入口标记（TTL = im.live.replay.retention-days）。入口到期只是「看不到入口」，
 * 盘上的字节还在占着，一场 2 小时四档直播按 2K/1K/720P/480P 合计约 8~10GB，不清理会把盘吃光。
 * 所以画面寿命必须有人来结束，这个脚本就是那个「人」：它在**媒体服务器所在的那台机器**上跑，
 * 直接按文件系统删除过期房间目录，不经过 Java（控制面与媒体面分离的约束不破）。
 *
 * ★ 判「过期」的口径是**整个房间目录里最新那一个文件的 mtime**，不是单个文件的 mtime。
 *   这条不能图省事写成 `find -mtime +1 -delete`：正在直播的房间是一小时前的分片还在、
 *   一秒后的分片也要来，按文件删会把这场直播的老分片抽走，回放清单立刻残缺；
 *   按房间整体判就只会删「已经不会再有人往里写」的场次。
 *
 * 与后端同一个数字：入口 TTL 与这里的 --days 都读 `IM_LIVE_REPLAY_RETENTION_DAYS`，
 * 默认 1 天。两边不一致时以「文件不早于入口消失」为安全方向（本脚本每轮最多滞后一个扫描
 * 周期删除，入口是先过期的一方，不会出现「入口还在但拉不到画面」）。
 *
 * 用法：
 *   node electron/live-retention.js --root=/data/live --days=1            # 扫一次就退出（cron/任务计划友好）
 *   node electron/live-retention.js --root=./live-data/hls --interval=1800  # 常驻，每 30 分钟扫一次
 *   node electron/live-retention.js --root=/data/live --dry-run          # 只报告要删什么，不动手
 *
 * 生产挂法（Linux）：`crontab -e` 加一行
 *   10 4 * * * /usr/bin/node /opt/im/electron/live-retention.js --root=/data/live --days=1 >> /var/log/live-retention.log 2>&1
 * 生产挂法（Windows）：任务计划程序 → 每天触发，操作填上面那条 node 命令。
 * 自测无需手工挂载：`live-media-server.js` 启动后自己会周期调用本文件的 sweepExpired()。
 */
const fs = require('fs')
const path = require('path')

/** 默认保留天数，与 im.live.replay.retention-days 保持一致（改这里就要改那边） */
const DEFAULT_DAYS = Number(process.env.IM_LIVE_REPLAY_RETENTION_DAYS || 1) || 1

const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 目录里是否还有「比 cutoff 更新」的文件。
 *
 * 找到就立刻返回（早退）：正在直播的房间第一个被采样的文件多半就是最近写入的那批，
 * 不需要把几万个分片全 stat 一遍；只有真要删的场次才会走完全程。
 */
function hasFreshFile(dir, cutoff) {
  const stack = [dir]
  let bytes = 0
  let files = 0
  while (stack.length) {
    const cur = stack.pop()
    let entries
    try {
      entries = fs.readdirSync(cur, { withFileTypes: true })
    } catch {
      continue // 读不了的子目录（权限/正被删除）不阻塞整体判断，交给下一轮
    }
    for (const e of entries) {
      // withFileTypes 不跟随符号链接：symlink 一律跳过，绝不顺着它删到目录外面去
      if (e.isSymbolicLink()) continue
      const p = path.join(cur, e.name)
      if (e.isDirectory()) {
        stack.push(p)
        continue
      }
      if (!e.isFile()) continue
      let st
      try {
        st = fs.statSync(p)
      } catch {
        continue
      }
      if (st.mtimeMs >= cutoff) return { fresh: true, bytes: 0, files: 0 }
      bytes += st.size
      files++
    }
  }
  return { fresh: false, bytes, files }
}

/** 明显是文件系统根的路径一律拒绝：误传 --root=/ 或 --root=C:\ 会把整盘当成房间目录删 */
function isFileSystemRoot(abs) {
  if (path.parse(abs).root === abs) return true
  // 驱动器盘符根（C:\ ）与 POSIX 根都算，再加一层「parent 就是自己」的兜底
  const parent = path.dirname(abs)
  return parent === abs
}

/**
 * 扫一轮：删掉 root 下第一层里所有「最后活动时间早于 cutoff」的房间目录。
 *
 * @param {object} options
 * @param {string} options.root        房间目录的父目录（自测 live-data/hls、生产 /data/live）
 * @param {number} [options.days]      保留天数，<=0 或缺省用 DEFAULT_DAYS
 * @param {boolean} [options.dryRun]   只统计不删除
 * @param {number} [options.now]       可注入的「当前时刻」，便于测试
 * @param {(msg: string) => void} [options.log] 可注入日志，便于被媒体服务器复用/测试静默
 * @returns {{skipped:boolean, scanned:number, deleted:number, failed:number, freedBytes:number, deletedNames:string[], cutoff:number}}
 */
function sweepExpired(options = {}) {
  const log = options.log || ((m) => console.log(m))
  const root = options.root ? path.resolve(options.root) : ''
  const days = Number(options.days) > 0 ? Number(options.days) : DEFAULT_DAYS
  const now = options.now || Date.now()
  const cutoff = now - days * DAY_MS
  const summary = { skipped: false, scanned: 0, deleted: 0, failed: 0, freedBytes: 0, deletedNames: [], cutoff }

  if (!root || !fs.existsSync(root)) {
    summary.skipped = true
    log(`[retention] 媒体目录不存在，跳过：${root || '(未指定 --root)'}`)
    return summary
  }
  if (isFileSystemRoot(root)) {
    summary.skipped = true
    log(`[retention] 拒绝执行：--root 不能是文件系统根（${root}），应指向房间目录的父目录，如 /data/live`)
    return summary
  }

  let entries
  try {
    entries = fs.readdirSync(root, { withFileTypes: true })
  } catch (err) {
    summary.skipped = true
    log(`[retention] 读不出媒体目录，跳过：${err.message}`)
    return summary
  }

  for (const e of entries) {
    const dir = path.join(root, e.name)
    // 只认房间目录：散落的文件、符号链接、软链目录一概不碰
    if (!e.isDirectory() || e.isSymbolicLink()) continue
    summary.scanned++

    let dirStat
    try {
      dirStat = fs.statSync(dir)
    } catch {
      continue
    }
    // 目录自身 mtime 新（Linux 下新建分片会带动它）就先放行，省一次全量 walk
    if (dirStat.mtimeMs >= cutoff) continue
    const walk = hasFreshFile(dir, cutoff)
    if (walk.fresh) continue // 还在播/还在写，或者刚关播不久

    if (options.dryRun) {
      summary.deletedNames.push(e.name)
      summary.freedBytes += walk.bytes
      log(`[retention] (dry-run) 将删除 ${dir}：${walk.files} 个文件，约 ${(walk.bytes / 1024 / 1024).toFixed(1)}MB`)
      continue
    }
    try {
      fs.rmSync(dir, { recursive: true, force: true })
      summary.deleted++
      summary.deletedNames.push(e.name)
      summary.freedBytes += walk.bytes
      log(
        `[retention] 已删除过期回放 ${e.name}：${walk.files} 个文件，` +
          `释放约 ${(walk.bytes / 1024 / 1024).toFixed(1)}MB（保留 ${days} 天）`
      )
    } catch (err) {
      // Windows 下播放器正在读某个分片会 EBUSY/EPERM：本轮放弃，下一轮再删，不该让整个清理任务挂掉
      summary.failed++
      log(`[retention] 删除失败（留到下一轮）：${dir} — ${err.message}`)
    }
  }

  return summary
}

/** 解析命令行；返回 null 表示该打印用法就退出 */
function parseArgs(argv) {
  const opts = { root: '', days: DEFAULT_DAYS, interval: 0, dryRun: false }
  for (const a of argv) {
    const m = /^--([^=]+)=(.*)$/.exec(a)
    if (m) {
      const key = m[1]
      const val = m[2]
      if (key === 'root') opts.root = val
      else if (key === 'days') opts.days = Number(val) > 0 ? Number(val) : DEFAULT_DAYS
      else if (key === 'interval') opts.interval = Number(val) > 0 ? Number(val) : 0
      continue
    }
    if (a === '--dry-run') opts.dryRun = true
    else if (a === '--help' || a === '-h') return null
  }
  if (!opts.root) {
    // 不猜 root：默认指到自测目录，生产必须显式传，宁可少删也不删错地方
    opts.root = path.join(__dirname, 'live-data', 'hls')
    console.log(`[retention] 未指定 --root，按自测目录处理：${opts.root}`)
  }
  return opts
}

module.exports = { sweepExpired, hasFreshFile, parseArgs, DEFAULT_DAYS, DAY_MS }

// 直接运行（被 require 时不进这里）
if (require.main === module) {
  const opts = parseArgs(process.argv.slice(2))
  if (!opts) {
    console.log('用法: node electron/live-retention.js --root=/data/live [--days=1] [--interval=秒] [--dry-run]')
    process.exit(0)
  }
  const run = () => {
    const s = sweepExpired({ root: opts.root, days: opts.days, dryRun: opts.dryRun })
    console.log(
      `[retention] 本轮扫描 ${s.scanned} 个房间目录，删除 ${s.deleted} 个，` +
        `失败 ${s.failed} 个，释放约 ${(s.freedBytes / 1024 / 1024).toFixed(1)}MB（保留 ${opts.days} 天）`
    )
  }
  run()
  if (opts.interval > 0) {
    console.log(`[retention] 常驻模式：每 ${opts.interval} 秒扫一次（Ctrl+C 退出）`)
    setInterval(run, opts.interval * 1000)
  }
}
