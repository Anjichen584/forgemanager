#!/usr/bin/env node
/**
 * netprobe.js —— 找出「连不上」到底卡在哪一层（v2.25.7）
 *
 * 被问过的问题：「并发拉到多少会被 429 / 是不是 IP 节流？」
 * 之前只有猜测。这个脚本把四种可能的限制**逐个单独测一遍**，每种给一个明确的结论：
 *
 *   A 段：同 IP 快速反复重连       → 测 bukkit.yml 的 `connection-throttle`
 *   B 段：一次性 burst 连 N 个     → 测 `max-players` 与 `max-joins-per-tick`
 *   C 段：逐个连到 N 个（正常节奏）→ 测真实可承载并发 + 起服耗时 + TPS
 *   D 段：压满之后留在线上 20 秒    → 测「连上了但被中途踢」这类隐性上限
 *
 * 关键设计：**每段只改一个变量**。混着测永远得不出结论 —— 上一轮就是
 * 「8 并发失败」被当成 429，实际是 max-players=5 满了，白排查半天。
 *
 *   node netprobe.js                  # 默认测到 12
 *   node netprobe.js --max 20         # 测到 20
 *   node netprobe.js --only A         # 只跑某一段
 *
 * 输出：netprobe-report.json + 终端一张结论表。
 */
const mineflayer = require('mineflayer')
const fs = require('fs')
const path = require('path')

const HOST = process.env.FM_HOST || '127.0.0.1'
const PORT = parseInt(process.env.FM_PORT || '25577', 10)
const VERSION = process.env.FM_VERSION || '1.21.11'
const MAX = parseInt(process.env.PROBE_MAX || '12', 10)

const argv = process.argv.slice(2)
const ONLY = (() => {
  const i = argv.indexOf('--only')
  return i >= 0 ? new Set(argv[i + 1].toUpperCase().split(',')) : null
})()
const maxArg = argv.indexOf('--max')
const TARGET = maxArg >= 0 ? parseInt(argv[maxArg + 1], 10) : MAX

const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)

// ---------------------------------------------------------------- 连接原语
/**
 * 连一个 bot 并等它 spawn。
 * 返回 { ok, ms, reason, bot }，**从不抛异常** —— 探针要收集全部结果，
 * 一个失败不能中断整轮（这跟压测器要 fail-fast 是相反的要求）。
 */
function connectOnce (name, timeoutMs = 25000) {
  return new Promise(resolve => {
    const t0 = Date.now()
    let settled = false
    const done = (ok, reason, bot) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve({ ok, ms: Date.now() - t0, reason: reason || null, bot: ok ? bot : null })
    }
    let bot
    try {
      bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: VERSION })
    } catch (e) {
      return done(false, 'createBot 抛异常：' + e.message)
    }
    const timer = setTimeout(() => {
      try { bot.quit() } catch (_) {}
      done(false, '超时 ' + timeoutMs + 'ms 未 spawn')
    }, timeoutMs)

    bot.once('spawn', () => done(true, null, bot))
    bot.once('kicked', r => {
      const raw = JSON.stringify(r)
      done(false, classify(raw))
      try { bot.quit() } catch (_) {}
    })
    bot.once('error', e => {
      done(false, classify(e.message))
      try { bot.quit() } catch (_) {}
    })
    bot.once('end', () => done(false, '连接结束（未 spawn）'))
  })
}

/** 把 kick 原因归类成「哪一层限制」，这是整个脚本最有价值的部分 */
function classify (raw) {
  const s = String(raw || '')
  const low = s.toLowerCase()
  if (low.includes('server_full')) return 'FULL：服务器满（max-players）'
  if (low.includes('connection_throttle') || low.includes('connection throttled') || low.includes('throttl')) {
    return 'THROTTLE：连接节流（同 IP 重连太快）'
  }
  if (/429|too many requests/.test(low)) return 'RATE429：HTTP 429 限流（认证层）'
  if (low.includes('whitelist')) return 'WHITELIST：不在白名单'
  if (low.includes('banned')) return 'BANNED：被封禁'
  if (low.includes('timed out') || low.includes('timeout')) return 'TIMEOUT：网络/服务端无响应'
  if (low.includes('econnrefused') || low.includes('refused')) return 'REFUSED：端口没人听（服务没起）'
  if (low.includes('reset')) return 'RESET：连接被重置'
  return 'OTHER：' + s.slice(0, 160)
}

const quitAll = async bots => {
  for (const b of bots) { try { b.quit() } catch (_) {} }
  await wait(1200)
}

// ---------------------------------------------------------------- 各段测试
const results = {}

/** A：同 IP 快速反复重连 —— 专门打 bukkit.yml 的 connection-throttle */
async function probeReconnectThrottle () {
  log('\n=== A 段：同 IP 快速重连（测 connection-throttle）===')
  const out = []
  for (let i = 0; i < 8; i++) {
    const r = await connectOnce('ProbeRe' + i, 20000)
    out.push({ attempt: i + 1, ok: r.ok, ms: r.ms, reason: r.reason })
    log(`  第 ${i + 1} 次：${r.ok ? 'OK' : r.reason}（${r.ms}ms）`)
    if (r.bot) { try { r.bot.quit() } catch (_) {} }
    await wait(150) // 故意快于 connection-throttle 的 4000ms
  }
  const throttled = out.filter(x => !x.ok && x.reason && x.reason.startsWith('THROTTLE')).length
  results.reconnectThrottle = {
    attempts: out.length,
    succeeded: out.filter(x => x.ok).length,
    throttled,
    detail: out
  }
  log(`  → ${out.filter(x => x.ok).length}/${out.length} 成功，节流 ${throttled} 次`)
  return throttled > 0
}

/** B：一次性 burst —— 所有 bot 同一瞬间发起连接 */
async function probeBurst (n) {
  log(`\n=== B 段：一次性 burst 连 ${n} 个（测 max-players / max-joins-per-tick）===`)
  const t0 = Date.now()
  const tasks = []
  for (let i = 0; i < n; i++) tasks.push(connectOnce('Burst' + String.fromCharCode(65 + i), 30000))
  const rs = await Promise.all(tasks)
  const ok = rs.filter(r => r.ok)
  const fail = rs.filter(r => !r.ok)
  log(`  → 成功 ${ok.length}/${n}，总耗时 ${Date.now() - t0}ms`)
  const reasons = {}
  for (const f of fail) reasons[f.reason] = (reasons[f.reason] || 0) + 1
  for (const [k, v] of Object.entries(reasons)) log(`     ${v} × ${k}`)
  results['burst' + n] = { n, succeeded: ok.length, failed: fail.length, reasons, totalMs: Date.now() - t0 }
  await quitAll(ok.map(x => x.bot))
  await wait(2000)
  return { ok: ok.length, reasons }
}

/** C：正常节奏逐个连 —— 这是压测器真实的连接方式 */
async function probeSequential (n) {
  log(`\n=== C 段：逐个连到 ${n} 个（正常节奏，间隔 600ms）===`)
  const t0 = Date.now()
  const bots = []
  const fails = []
  for (let i = 0; i < n; i++) {
    const name = 'Seq' + String.fromCharCode(65 + i)
    const r = await connectOnce(name, 30000)
    if (r.ok) {
      bots.push(r.bot)
      log(`  ${name} 上线（${r.ms}ms）  累计 ${bots.length}`)
    } else {
      fails.push({ name, reason: r.reason })
      log(`  ${name} 失败：${r.reason}`)
    }
    await wait(600)
  }
  const totalMs = Date.now() - t0
  results['sequential' + n] = {
    n, succeeded: bots.length, failed: fails.length, fails, totalMs,
    avgJourneyMs: Math.round(totalMs / Math.max(1, n))
  }
  return { bots, fails, totalMs }
}

/** D：压满后停留，看会不会被中途踢（隐性上限：TPS 崩、内存不足） */
async function probeDwell (bots, seconds = 20) {
  log(`\n=== D 段：${bots.length} 个在线停留 ${seconds} 秒（测隐性上限）===`)
  const kicked = []
  for (const b of bots) {
    b.once('kicked', r => kicked.push(classify(JSON.stringify(r))))
    b.once('end', () => kicked.push('END：连接被动结束'))
  }
  const t0 = Date.now()
  await wait(seconds * 1000)
  const alive = bots.filter(b => b.entity && b.player && !b.ended).length
  results.dwell = {
    seconds, online: bots.length, alive, kicked: kicked.length,
    reasons: [...new Set(kicked)], elapsedMs: Date.now() - t0
  }
  log(`  → 仍在线 ${alive}/${bots.length}，被踢 ${kicked.length} 次`)
  for (const r of [...new Set(kicked)]) log(`     ${r}`)
  return alive === bots.length
}

// ---------------------------------------------------------------- 主流程
async function main () {
  log('================ 连接上限探针 ================')
  log(`目标 ${HOST}:${PORT}  版本 ${VERSION}  探到 ${TARGET} 并发`)
  log('目的：把「连不上」拆成「哪一层限制」，而不是笼统归因成 429\n')

  const run = k => !ONLY || ONLY.has(k)

  if (run('A')) {
    const throttled = await probeReconnectThrottle()
    log(throttled
      ? '  ⚠ 确认存在同 IP 重连节流 → 需要限流 + 代理轮换'
      : '  ✓ 没有同 IP 重连节流（connection-throttle 在这台服上不起作用）')
  }

  if (run('B')) {
    // 先 burst 一半，再 burst 全部，看出「第几个开始失败」
    await probeBurst(Math.min(6, TARGET))
    await probeBurst(TARGET)
  }

  if (run('C')) {
    const { bots, fails, totalMs } = await probeSequential(TARGET)
    if (!fails.length) {
      log(`  ✓ 全部 ${bots.length} 个稳定上线（总耗时 ${totalMs}ms）`)
      if (run('D')) await probeDwell(bots, 20)
    } else {
      log(`  ✗ 在 ${bots.length} 个成功之后开始失败`)
    }
    await quitAll(bots)
  }

  // ---------------------------------------------------------------- 结论
  log('\n================ 结论 ================')
  const findings = []
  if (results.reconnectThrottle) {
    findings.push(results.reconnectThrottle.throttled > 0
      ? `A 同 IP 快速重连：**会**被节流（${results.reconnectThrottle.throttled}/${results.reconnectThrottle.attempts} 次）`
      : 'A 同 IP 快速重连：**不会被**节流 → 不是 IP 层限制')
  }
  for (const key of Object.keys(results)) {
    if (!key.startsWith('burst')) continue
    const r = results[key]
    const fullHits = Object.entries(r.reasons).filter(([k]) => k.startsWith('FULL')).reduce((a, [, v]) => a + v, 0)
    if (fullHits > 0) findings.push(`${key.toUpperCase()} burst ${r.n}：${fullHits} 次撞 max-players 上限（服务器满，不是 429）`)
    else if (r.failed === 0) findings.push(`${key.toUpperCase()} burst ${r.n}：全部成功`)
    else findings.push(`${key.toUpperCase()} burst ${r.n}：失败 ${r.failed}（${Object.keys(r.reasons).join('; ')}）`)
  }
  if (results.dwell) {
    findings.push(`D 停留 ${results.dwell.seconds}s：存活 ${results.dwell.alive}/${results.dwell.online}`
        + (results.dwell.kicked ? `，被踢原因 ${results.dwell.reasons.join('; ')}` : '，无被踢'))
  }
  for (const f of findings) log('  · ' + f)

  results.summary = findings
  results.at = new Date().toISOString()
  results.target = { host: HOST, port: PORT, version: VERSION, target: TARGET }
  fs.writeFileSync(path.join(__dirname, 'netprobe-report.json'), JSON.stringify(results, null, 2))
  log('\n报告：netprobe-report.json')
}

main().catch(e => { console.log('[fatal] ' + e.stack); process.exit(1) })