#!/usr/bin/env node
/**
 * connpool.js —— 连接池 + 限流 + 代理自动轮换（v2.25.7）
 *
 * 为什么需要它：压测器原来直接 `mineflayer.createBot()`，撞上任何连接层限制就一次性失败。
 * 而「连不上」其实有五六种完全不同的原因，处理方式相反：
 *
 *   ┌ 硬性拒绝 ── max-players 满 / 白名单 / 封禁 ──► **立刻报错，绝不重试**
 *   │              重试只会白等 4 轮，还把真原因埋掉。
 *   │
 *   ├ 同 IP 节流 ── connection-throttle ─────────► 换代理（换出口 IP）或等窗口
 *   │
 *   ├ 认证限流 ── HTTP 429（仅在线模式）─────────► 指数退避 + 抖动 + 换代理
 *   │
 *   └ 网络抖动 ── reset / timeout ───────────────► 指数退避重试同一出口
 *
 * 实测结论（netprobe.js，本机离线服）：同 IP 150ms 连 8 次 0 节流；
 * burst 24 全部成功；真正的墙是 `max-players`。所以在离线服上这个池子是**兜底**，
 * 不会触发轮换；一旦上在线模式或公网服，它才开始干活。
 *
 * ── 用法 ──
 *   const pool = new ConnPool({ proxies: [...] })
 *   const bot  = await pool.acquire(name)      // 拿一个已 spawn 的 bot（自动重试/换代理）
 *   pool.release(bot)                           // 归还并断开
 *
 * ── 代理配置 ──
 *   优先级：显式传入的 proxies > 文件 tools/guitest/proxies.txt > 环境变量 PROXIES
 *   每行一个，格式 `socks5://user:pass@host:port` 或 `socks5h://host:port`
 *   mineflayer 传 `socks5: host` + `socks5Username/Password`；见 buildOptions()
 *
 * ── 限流模型 ──
 *   令牌桶（token bucket）：capacity = 你好一口气想连几个，refill = 每秒恢复几个。
 *   比固定 sleep 好：连得慢的时候不会额外惩罚，连得快的时候被平滑到速率上限。
 */
'use strict'
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')

// ---------------------------------------------------------------- 分类
const Kind = {
  HARD: 'hard',        // 硬性拒绝：不重试
  THROTTLE: 'throttle',// 同 IP 节流：换代理
  RATE: 'rate',        // 429：退避 + 换代理
  NET: 'net',          // 网络抖动：退避重试
  UNKNOWN: 'unknown'
}

function classify (raw) {
  const s = String(raw || '')
  const low = s.toLowerCase()
  if (low.includes('server_full') || low.includes('whitelist') || low.includes('banned')
      || low.includes('not allowed') || low.includes('not_whitelisted')) {
    return Kind.HARD
  }
  if (low.includes('connection_throttle') || low.includes('connection throttled')
      || low.includes('throttl')) {
    return Kind.THROTTLE
  }
  if (/429|too many requests|rate.?limit/.test(low)) return Kind.RATE
  // 注意 etimedout 是没空格的（Node 的错误码），只写 'timed out' 会漏
  if (low.includes('timed out') || low.includes('etimedout') || low.includes('timeout')
      || low.includes('reset') || low.includes('econnrefused')
      || low.includes('econnreset') || low.includes('disconnect')) {
    return Kind.NET
  }
  return Kind.UNKNOWN
}

/** 人话解释，出错时直接打给看日志的人 */
function explain (kind, raw) {
  switch (kind) {
    case Kind.HARD:
      return `服务端硬性拒绝（${raw}）。这通常是 max-players 满了／不在白名单／被封禁，`
           + `不是限流 —— 重试无用，请改 server.properties 或查白名单。`
    case Kind.THROTTLE:
      return `同 IP 连接节流（${raw}）。处理：换出口 IP（代理轮换）或等节流窗口过去。`
    case Kind.RATE:
      return `认证层限流 HTTP 429（${raw}）。这只出现在在线模式，处理：指数退避 + 抖动 + 换代理。`
    case Kind.NET:
      return `网络层异常（${raw}）。处理：指数退避后重试同一出口。`
    default:
      return `未识别的失败（${raw}）`
  }
}

// ---------------------------------------------------------------- 令牌桶
class TokenBucket {
  constructor (capacity, refillPerSec) {
    this.capacity = capacity
    this.refillPerSec = refillPerSec
    this.tokens = capacity
    this.last = Date.now()
  }

  _refill () {
    const now = Date.now()
    this.tokens = Math.min(this.capacity, this.tokens + ((now - this.last) / 1000) * this.refillPerSec)
    this.last = now
  }

  /** 取一个令牌；不够就等到够为止。返回等待毫秒数（便于统计） */
  async take () {
    this._refill()
    if (this.tokens >= 1) {
      this.tokens -= 1
      return 0
    }
    const need = (1 - this.tokens) / this.refillPerSec * 1000
    const waitMs = Math.ceil(need)
    await sleep(waitMs)
    this._refill()
    this.tokens = Math.max(0, this.tokens - 1)
    return waitMs
  }
}

const sleep = ms => new Promise(r => setTimeout(r, ms))
const jitter = ms => Math.floor(Math.random() * ms)

// ---------------------------------------------------------------- 代理
function loadProxies (explicit) {
  if (Array.isArray(explicit) && explicit.length) return explicit
  const file = path.join(__dirname, 'proxies.txt')
  try {
    const lines = fs.readFileSync(file, 'utf8')
      .split(/\r?\n/)
      .map(s => s.trim())
      .filter(s => s && !s.startsWith('#'))
    if (lines.length) return lines
  } catch (_) { /* 没这个文件是正常情况 */ }
  const env = process.env.PROXIES
  if (env && env.trim()) return env.split(',').map(s => s.trim()).filter(Boolean)
  return []
}

/**
 * 把 `socks5h://user:pass@host:port` 拆成 mineflayer 认的形状。
 * 注意 mineflayer 用的是 `socks5` 选项（不是 `proxy`），且只支持 SOCKS。
 */
function parseProxy (url) {
  try {
    const u = new URL(url.includes('://') ? url : 'socks5h://' + url)
    const out = { socks5: u.hostname }
    if (u.port) out.socks5Port = parseInt(u.port, 10)
    if (u.username) out.socks5Username = decodeURIComponent(u.username)
    if (u.password) out.socks5Password = decodeURIComponent(u.password)
    return out
  } catch (e) {
    return null
  }
}

// ---------------------------------------------------------------- 主类
class ConnPool {
  /**
   * @param {object} o
   * @param {string} o.host
   * @param {number} o.port
   * @param {string} o.version
   * @param {string[]} [o.proxies]       代理列表；空则不轮换
   * @param {number} [o.joinsPerSec]     全局连接速率（默认 4/秒 ≈ 250ms 一个）
   * @param {number} [o.burst]           令牌桶容量（默认 6）
   * @param {number} [o.maxAttempts]     每个 bot 最多尝试几次（默认 5）
   * @param {number} [o.onlineMode]      是否在线模式；true 时把 429 当重点处理
   */
  constructor (o) {
    this.host = o.host || '127.0.0.1'
    this.port = o.port || 25577
    this.version = o.version || '1.21.10'  // v2.26.5：随服务器迁移
    this.onlineMode = !!o.onlineMode
    this.proxies = loadProxies(o.proxies).map(parseProxy).filter(Boolean)
    this.proxyIndex = 0
    this.bucket = new TokenBucket(o.burst || 6, o.joinsPerSec || 4)
    this.maxAttempts = o.maxAttempts || 5
    this.stats = {
      attempts: 0, succeeded: 0, hardFailures: 0,
      throttles: 0, rateLimits: 0, netErrors: 0, unknowns: 0,
      proxySwitches: 0, waitedMs: 0, reasons: {}
    }
  }

  /** 当前出口代理（没有配置就返回 null，直连） */
  currentProxy () {
    if (!this.proxies.length) return null
    return this.proxies[this.proxyIndex % this.proxies.length]
  }

  /** 换下一个代理；返回是否真的换了（没配代理时返回 false，调用方据此决定要不要等） */
  rotateProxy (why) {
    if (!this.proxies.length) return false
    this.proxyIndex++
    this.stats.proxySwitches++
    const p = this.currentProxy()
    console.log(`  [pool] 换出口 → ${p.socks5}:${p.socks5Port || 1080}（原因：${why}）`)
    return true
  }

  buildOptions (name, proxy) {
    const opt = { host: this.host, port: this.port, username: name, version: this.version }
    // 在线模式必须真实认证；离线模式绝不能带 auth:'microsoft'（会去连 Mojang）
    opt.auth = this.onlineMode ? 'microsoft' : 'offline'
    if (proxy) {
      opt.socks5 = proxy.socks5
      if (proxy.socks5Port) opt.socks5Port = proxy.socks5Port
      if (proxy.socks5Username) opt.socks5Username = proxy.socks5Username
      if (proxy.socks5Password) opt.socks5Password = proxy.socks5Password
    }
    return opt
  }

  connectOnce (name, proxy, timeoutMs) {
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
        bot = mineflayer.createBot(this.buildOptions(name, proxy))
      } catch (e) {
        return done(false, 'createBot 抛异常：' + e.message)
      }
      const timer = setTimeout(() => {
        try { bot.quit() } catch (_) {}
        done(false, 'spawn 超时 ' + timeoutMs + 'ms')
      }, timeoutMs)
      bot.once('spawn', () => done(true, null, bot))
      bot.once('kicked', r => {
        const raw = JSON.stringify(r)
        try { bot.quit() } catch (_) {}
        done(false, raw)
      })
      bot.once('error', e => {
        try { bot.quit() } catch (_) {}
        done(false, e.message)
      })
      bot.once('end', () => done(false, 'end（未 spawn）'))
    })
  }

  /**
   * 等旧连接真的断干净（v2.25.7）。
   *
   * 踩过的坑：32 并发时 Conc25 连续 5 次上线失败，整轮 abort。但单独测「32 个一起连」
   * 却 32/32 全成功 —— 说明不是连接数上限，而是**同名会话残留**：
   * 上一次 attempt 刚 quit()，服务端还没把会话清掉，同名的下一次连接就被当成重复登录拒掉。
   * 修法：重试同名之前先等旧连接的 'end'，最多 3 秒；再叠退避。
   */
  async waitClosed (bot, ms = 3000) {
    if (!bot) return
    try { bot.quit() } catch (_) { /* 已经断了 */ }
    if (bot.ended || bot._client?.socket?.destroyed) return
    await new Promise(resolve => {
      const timer = setTimeout(resolve, ms)
      bot.once('end', () => { clearTimeout(timer); resolve() })
    })
  }

  /**
   * 拿一个上线成功的 bot。**从不抛异常**，失败返回 null 并写清楚原因 ——
   * 调用方（压测器）据此把这一路标 WARN 而不是让整轮崩掉。
   */
  async acquire (name, timeoutMs = 25000) {
    for (let attempt = 1; attempt <= this.maxAttempts; attempt++) {
      const waited = await this.bucket.take()
      this.stats.waitedMs += waited
      const proxy = this.currentProxy()
      this.stats.attempts++
      const r = await this.connectOnce(name, proxy, timeoutMs)
      this._lastBot = r.bot || this._lastBot

      if (r.ok) {
        this.stats.succeeded++
        return r.bot
      }

      const kind = classify(r.reason)
      this.stats.reasons[r.reason] = (this.stats.reasons[r.reason] || 0) + 1
      console.log(`  [pool] ${name} 第 ${attempt} 次失败：${explain(kind, r.reason)}`)

      if (kind === Kind.HARD) {
        this.stats.hardFailures++
        // 硬性拒绝：立刻停，别重试。重试只会白等然后埋掉真原因。
        console.log(`  [pool] ${name} 放弃（硬性拒绝，不重试）`)
        return null
      }
      if (kind === Kind.THROTTLE) this.stats.throttles++
      else if (kind === Kind.RATE) this.stats.rateLimits++
      else if (kind === Kind.NET) this.stats.netErrors++
      else this.stats.unknowns++

      // 同名会话残留：等旧连接 end 之后再重试，否则服务端当成重复登录直接拒
      if (r.bot) await this.waitClosed(r.bot)
      else await this.waitClosed(this._lastBot)
      this._lastBot = null

      if (attempt === this.maxAttempts) {
        console.log(`  [pool] ${name} 用尽 ${this.maxAttempts} 次尝试，放弃`)
        return null
      }

      // 节流/429 优先换出口：限制多半是跟 IP 走的，换 IP 比等窗口快得多。
      // 网络抖动不换：同一个出口再试一次通常就好，换代理反而引入新变量。
      const shouldRotate = (kind === Kind.THROTTLE || kind === Kind.RATE)
      const rotated = shouldRotate ? this.rotateProxy(kind) : false

      // 退避：指数 + 抖动。没代理可换时必须等得更久（只能等窗口过去）。
      let backoff = Math.min(1000 * Math.pow(2, attempt - 1), 15000)
      if (shouldRotate && !rotated) backoff = Math.min(backoff * 2, 30000)
      const total = backoff + jitter(800)
      console.log(`  [pool] ${name} ${total}ms 后重试${rotated ? '（已换出口）' : ''}`)
      await sleep(total)
    }
    return null
  }

  /** 归还：断开连接。名字是「池」但这个池子不重用连接 —— 复用 bot 会让状态互相污染 */
  release (bot) {
    try { if (bot) bot.quit() } catch (_) {}
  }

  summary () {
    const s = this.stats
    const lines = [
      `连接池统计：尝试 ${s.attempts}，成功 ${s.succeeded}`,
      `  节流 ${s.throttles}，429 ${s.rateLimits}，网络 ${s.netErrors}，`
        + `未知 ${s.unknowns}，硬性拒绝 ${s.hardFailures}`,
      `  换代理 ${s.proxySwitches} 次（可用代理 ${this.proxies.length} 个）`,
      `  限流等待累计 ${Math.round(s.waitedMs / 1000)}s`
    ]
    return lines.join('\n')
  }
}

module.exports = { ConnPool, TokenBucket, classify, explain, Kind, loadProxies, parseProxy }

// 直接运行 = 自检（不连服务器，纯逻辑）
if (require.main === module) {
  const assert = require('assert')
  let n = 0
  const check = (name, fn) => { fn(); n++; console.log(`  ✓ ${name}`) }

  console.log('connpool 自检')
  check('硬性拒绝归类', () => {
    assert.strictEqual(classify('{"translate":"multiplayer.disconnect.server_full"}'), Kind.HARD)
    assert.strictEqual(classify('You are not whitelisted'), Kind.HARD)
  })
  check('节流归类', () => {
    assert.strictEqual(classify('Connection throttled! Please wait before reconnecting.'), Kind.THROTTLE)
  })
  check('429 归类', () => {
    assert.strictEqual(classify('Got 429 Too Many Requests'), Kind.RATE)
  })
  check('网络抖动归类', () => {
    assert.strictEqual(classify('connect ETIMEDOUT'), Kind.NET)
    assert.strictEqual(classify('read ECONNRESET'), Kind.NET)
  })
  check('代理 URL 解析', () => {
    const p = parseProxy('socks5h://user:p%40ss@127.0.0.1:10808')
    assert.strictEqual(p.socks5, '127.0.0.1')
    assert.strictEqual(p.socks5Port, 10808)
    assert.strictEqual(p.socks5Username, 'user')
    assert.strictEqual(p.socks5Password, 'p@ss')
  })
  check('裸 host:port 也能解析', () => {
    const p = parseProxy('127.0.0.1:10808')
    assert.strictEqual(p.socks5, '127.0.0.1')
  })
  check('离线模式不带 microsoft 认证', () => {
    const pool = new ConnPool({ host: 'h', port: 1, version: '1.21.11' })
    assert.strictEqual(pool.buildOptions('X', null).auth, 'offline')
  })
  check('在线模式才用 microsoft', () => {
    const pool = new ConnPool({ host: 'h', port: 1, version: '1.21.11', onlineMode: true })
    assert.strictEqual(pool.buildOptions('X', null).auth, 'microsoft')
  })
  check('没配代理时 rotate 返回 false 且不崩', () => {
    const pool = new ConnPool({ host: 'h', port: 1 })
    assert.strictEqual(pool.rotateProxy('test'), false)
    assert.strictEqual(pool.currentProxy(), null)
  })
  check('配了代理就轮换', () => {
    const pool = new ConnPool({ host: 'h', port: 1, proxies: ['socks5h://127.0.0.1:1080', 'socks5h://127.0.0.1:1081'] })
    assert.strictEqual(pool.currentProxy().socks5Port, 1080)
    assert.strictEqual(pool.rotateProxy('t'), true)
    assert.strictEqual(pool.currentProxy().socks5Port, 1081)
    pool.rotateProxy('t')
    assert.strictEqual(pool.currentProxy().socks5Port, 1080, '轮完应该回到第一个')
  })
  console.log(`\n${n} 项全过`)
}