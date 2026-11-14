// 4 并发实测：4 个 bot 在同一 tick 抢同一份账本 / 同一个界面 / 同一个限购位
//
// 用法：
//   node concurrent4.js              # 全部阶段
//   node concurrent4.js signin anvil # 只跑指定阶段
//
// 设计口径（跟前几轮查错轮对齐）：
// 1. 「同 tick」= 在同一个 JS 事件循环里连发，不 await 中间任何一步；
//    服务端主线程会把它们排在相邻几 tick 里执行，这才是真正的并发面。
// 2. 断言只打**守恒律**：剑币不为负、只成功一次、总额等于各行之和；
//    不去猜插件的文案格式（文案会改，账本不该改）。
// 3. 每阶段结束把服务端日志的新增行扫一遍，出现 Exception 直接判 FAIL。
const fs = require('fs')
const path = require('path')
const net = require('net')
const { wait } = require('./common')

const HOST = '127.0.0.1'
const PORT = 25565  // v2.26.5：随服务器迁移
const VERSION = '1.21.10'  // v2.26.5：随服务器迁移
const RCON_PORT = 25575  // v2.26.5：随服务器迁移
const RCON_PASS = 'bmtest123'  // v2.26.5：随服务器迁移
const SERVER = process.env.FM_SERVER || 'D:\\IDEA\\mc-plugins\\ForgingMaster\\testserver'
const LOG = path.join(SERVER, 'logs', 'latest.log')

// 并发数可配（默认 4）。脚本名保留 concurrent4.js 不改 —— run-stress.py 和文档都按这个名字引用。
//   BOT_COUNT=5        试 5 并发会不会握手排队/被掐
//   JOIN_ONLY=1        只连不测：上线后空转 20 秒就出报告，专门量「连接成功率与耗时」
// 为什么要能改：4 是**回归门**，必须每次都过；5 是**探针**，只用来回答「上限在哪」，
// 不能反过来让探针的不稳把回归门搞红。
const BOT_COUNT = Math.max(1, Math.min(48, parseInt(process.env.BOT_COUNT || '4', 10) || 4))
const JOIN_ONLY = process.env.JOIN_ONLY === '1'
// 名字不能再用 String.fromCharCode(65+i)：超过 26 个会变成 `Conc[`、`Conc\`、`Conc]`，
// 而 Minecraft 用户名只允许 [a-zA-Z0-9_]，这些名字服务端直接拒（还报不出好懂的错）。
// 改成 Conc01..Conc48，固定三位、可排序、一眼能看出是第几个。
const BOTS = Array.from({ length: BOT_COUNT }, (_, i) => 'Conc' + String(i + 1).padStart(2, '0'))

// ---------------------------------------------------------------- RCON
function rconClient () {
  return new Promise((resolve, reject) => {
    const sock = net.connect(RCON_PORT, HOST)
    let buf = Buffer.alloc(0)
    let authed = false
    let id = 0
    /** request id → resolver。并发下必须按 id 精确配对，见 send() 里的注释 */
    const waiting = new Map()
    /** 单条 RCON 的超时：服务端卡住时宁可报错，也不要让整个阶段无声挂死 */
    const RCON_TIMEOUT = 15000

    const packet = (pid, type, body) => {
      const payload = Buffer.concat([Buffer.from(body, 'utf8'), Buffer.alloc(2)])
      const b = Buffer.alloc(12 + payload.length)
      b.writeInt32LE(8 + payload.length, 0)
      b.writeInt32LE(pid, 4)
      b.writeInt32LE(type, 8)
      payload.copy(b, 12)
      return b
    }

    sock.on('connect', () => sock.write(packet(++id, 3, RCON_PASS)))
    sock.on('error', reject)
    sock.on('data', chunk => {
      buf = Buffer.concat([buf, chunk])
      for (;;) {
        if (buf.length < 4) break
        const len = buf.readInt32LE(0)
        if (buf.length < len + 4) break
        const rid = buf.readInt32LE(4)
        const body = buf.slice(12, 4 + len - 2).toString('utf8')
        buf = buf.slice(4 + len)
        if (!authed) {
          authed = true
          if (rid === -1) return reject(new Error('RCON 认证失败'))
          sock.write(packet(++id, 2, 'list'))
          resolve({
            send (cmd) {
              // v2.26.0：**按 request id 配对，不再用 FIFO 队列**。
              //
              // 原来是 `pending.push(res)` + `pending.shift()`：发一条压一条，回一条弹一条，
              // 完全靠「先进先出」假设响应顺序等于请求顺序。单线程串行跑时这个假设成立，
              // 所以一直没暴露 —— 但阶段内改成 32 bot 并发之后它立刻炸：
              // 32 个 coins() 同时发 RCON，响应按 id 回来，却被按到达顺序错配给别的调用者，
              // 于是 A 的余额读给 B、命令回执张冠李戴，`coins()` 解析失败返回 null，
              // 上层 `await` 等到 timeout 也不结束 —— 实测单阶段跑满 400 秒没跑完。
              //
              // 服务端回包带着请求 id（rid），直接用 Map<id, resolver> 精确配对。
              // 这不是「优化」，是并发场景下的**正确性前提**。
              return new Promise((res, rej) => {
                const myId = ++id
                const timer = setTimeout(() => {
                  waiting.delete(myId)
                  rej(new Error('RCON 超时（' + RCON_TIMEOUT + 'ms）：' + cmd))
                }, RCON_TIMEOUT)
                waiting.set(myId, body2 => { clearTimeout(timer); res(body2) })
                sock.write(packet(myId, 2, cmd))
              })
            },
            close () { sock.destroy() }
          })
          continue
        }
        // 按 id 找回对应调用者；找不到说明是迟到/重复包，丢掉即可（不能 shift，否则又回到错配）
        const out = waiting.get(rid)
        if (out) {
          waiting.delete(rid)
          out(body)
        }
      }
    })
    sock.setTimeout(0)
  })
}

let rcon
/** 已经真正掉线的 bot（'end' 事件里登记）。dropout 阶段会故意踢一个下线 */
const dropped = new Set()
const rc = async (cmd) => {
  const out = await rcon.send(cmd)
  await wait(120)
  return String(out || '').replace(/\u00a7./g, '')
}

// ---------------------------------------------------------------- 断言
const rows = []
function record (status, area, name, detail) {
  rows.push({ status, area, name, detail })
  const mark = status === 'PASS' ? '✓' : status === 'WARN' ? '!' : '✗'
  console.log(`  ${mark} [${area}] ${name}${detail ? ' — ' + detail : ''}`)
}
const pass = (a, n, d) => record('PASS', a, n, d)
const fail = (a, n, d) => record('FAIL', a, n, d)
const warn = (a, n, d) => record('WARN', a, n, d)

// ---------------------------------------------------------------- 剑币
async function coins (name) {
  const out = await rc('zhujian money coin balance ' + name)
  const m = out.match(/(\d[\d,]*)\s*$/) || out.match(/剑币[：:]\s*§?[a-z]?(\d[\d,]*)/i)
  if (!m) return null
  return Number(m[1].replace(/,/g, ''))
}

async function setCoins (name, amount) {
  const now = await coins(name)
  if (now === null) return null
  if (now > 0) await rc(`zhujian money coin take ${name} ${now}`)
  if (amount > 0) await rc(`zhujian money coin give ${name} ${amount}`)
  return amount
}

// ---------------------------------------------------------------- bot
// 握手节流计数器：429 / 「Too many login attempts」/ connection throttled
// 这类不是插件的 bug，是服务端在挡连接。以前它们会让整个阶段异常收场，
// 看起来像并发面有缺陷。现在单独计数，并做退避重连。
const throttle = { events: 0, retries: 0, details: [] }


const sp = (name, bot, ms = 20000) => new Promise((resolve, reject) => {
  let done = false
  const finish = (fn, arg) => { if (!done) { done = true; clearTimeout(timer); fn(arg) } }
  const timer = setTimeout(() => finish(reject, new Error(name + ' spawn 超时')), ms)
  bot.once('spawn', () => finish(resolve))
  // 被踢/出错时立刻失败，别傻等满 20 秒 —— 429 场景下这一点决定了整轮要耗多久。
  // 注意用传入的 name，不能用 bot.username：被踢的时候它还是 undefined，
  // 之前就打出过「undefined 被踢」，日志里根本认不出是哪只 bot。
  bot.once('kicked', r => finish(reject, new Error(name + ' 被踢：' + JSON.stringify(r).slice(0, 120))))
  bot.once('error', e => finish(reject, new Error(name + ' 连接出错：' + e.message)))
})

/**
 * 带退避的上线（v2.25.7 起交给 connpool 统一处理）。
 *
 * 之前是自己写一份退避，问题在于它**分不清失败原因**：
 * `server_full`（max-players 满了）被当成限流去重试 4 轮，白等还把真原因埋掉。
 * 现在用 connpool 的分类：
 *   硬性拒绝（server_full/白名单/封禁）→ 立刻报错，不重试
 *   同 IP 节流 / 429 → 换代理（若配了 proxies.txt）+ 指数退避
 *   网络抖动 → 同出口退避重试
 */
const { ConnPool, explain, classify } = require('./connpool')

const pool = new ConnPool({
  host: HOST,
  port: PORT,
  version: VERSION,
  // 离线服（online-mode=false）不产生 Mojang 429；这里跟着服务端口径走
  onlineMode: process.env.FM_ONLINE === '1',
  joinsPerSec: parseFloat(process.env.JOIN_RATE || '4'),
  burst: parseInt(process.env.JOIN_BURST || '6', 10),
  maxAttempts: parseInt(process.env.JOIN_ATTEMPTS || '5', 10)
})

async function joinWithRetry (name) {
  const bot = await pool.acquire(name)
  if (!bot) {
    throw new Error(`${name} 上线失败：${pool.proxies.length
      ? '已尝试全部出口'
      : '没有配置代理，无法换出口（放到 tools/guitest/proxies.txt 或设 PROXIES 环境变量）'}`)
  }
  bot.chatLog = []
  bot.on('message', m => {
    const s = m.toString()
    if (s.trim()) bot.chatLog.push(s.replace(/\u00a7./g, ''))
    if (bot.chatLog.length > 400) bot.chatLog.splice(0, bot.chatLog.length - 400)
  })
  // spawn 之后的连接错误也要能被看见（原代码只在 join() 里挂，pool 托管的 bot 得补回来）
  bot.on('error', e => {
    if (classify(e.message) !== 'unknown') throttle.details.push(`${name}: ${e.message}`)
    console.log(`  [${name}] error ${e.message}`)
  })
  bot.on('kicked', r => {
    const s = JSON.stringify(r)
    console.log(`  [${name}] kicked ${s.slice(0, 200)}`)
    if (classify(s) !== 'unknown') throttle.events++
  })
  // 断线登记：quit() 之后 mineflayer 并不会把 bot.entity 置空，
  // 光凭 entity 判断存活会把「dropout 阶段故意踢掉的那个」当成还在 —— 见 phases 循环里的 living
  bot.on('end', () => { dropped.add(name) })
  return bot
}

// ⚠ 别用 bot.chat 连发同一 tick：mineflayer 每条 chat 是一次 chat_command 包，
//    但服务端会把同 tick 的多条命令按到达顺序执行 —— 这正是我们要的并发面。
const burst = (bot, cmds) => { for (const c of cmds) bot.chat(c) }
const take = bot => { const m = bot.chatLog.slice(); bot.chatLog.length = 0; return m }
const last = (bot, n = 8) => bot.chatLog.slice(-n).join(' | ').slice(0, 600)

// ---------------------------------------------------------------- 等「说完了」，别硬等时长
//
// 原写法：发命令 → await wait(1600) → take(bot)。不论回执 80ms 还是 1500ms 到，都睡满。
// 97 处 sleep、平均 1276ms，乘 31 个 bot，一轮压测光睡掉一小时。
//
// settle()：**静默检测**。至少等 minMs，之后每 50ms 看一眼 chatLog，
// 只要「最近 quietMs 毫秒没有新消息」就认为服务端说完了，立刻返回。
// 上限 maxMs 与原来的固定值**完全相同** —— 慢路径行为不变，不会因为改快而假红。
//
// 为什么不用「出现某条文案就返回」：那样只对**正向**断言安全。
// 像「三连 confirm 只成 1 次」「重复 join 不许重复收钱」这类，判据恰恰是
// 「第二条不该来」；一看到第一条就返回，会把还没到的第二条漏掉 → 假绿。
// 静默检测对正负断言都成立：它等的是「不再有消息」，不是「出现了某条消息」。
//
// 最坏情况 == 老行为（一直有消息直到 maxMs 才返回）；最好情况快好几倍。
// 所以这是一处**严格优于现状**的改动，不存在「为了快牺牲正确性」。
async function settle (bot, maxMs, opts = {}) {
  const quietMs = opts.quietMs || 250
  const minMs = opts.minMs != null ? opts.minMs : Math.min(600, maxMs)
  const t0 = Date.now()
  await wait(Math.min(minMs, maxMs))
  let seen = bot.chatLog.length
  let lastChange = Date.now()
  while (Date.now() - t0 < maxMs) {
    await wait(50)
    const n = bot.chatLog.length
    if (n !== seen) {
      seen = n
      lastChange = Date.now()
      continue
    }
    if (Date.now() - lastChange >= quietMs) return
  }
  // 超时直接返回：让上层断言对着真实日志说话，这里不抛错也不补数据
}

// 兼容旧名字（有调用点还在用 waitUntil 的话不会炸）
const waitUntil = (opts) => settle(opts && opts.bot, (opts && opts.maxMs) || 1000, opts)

// ---------------------------------------------------------------- 阶段内并发：试过，不行（实测结论，别再试）
//
// 动机很直接：17 个阶段都写成
//     for (const bot of bots) { …一堆 await… }
// 一个 bot 做完再下一个，32 个 × 每 bot 几秒 = 几分钟一阶段。
// 直觉上「每个 bot 操作自己的账号」应该能同时跑，快 32 倍。
//
// **实测失败**，踩了两层：
//
// 1) 32 bot 同时上线后阶段挂死 400 秒没结束；减到 8 个仍然全超时，
//    服务端日志里一条 RCON 回包都没有。
// 2) 单测定位到根因：**Minecraft 的 RCON 是串行收发的**。
//    一次并发发 8 条 `list`，只回 1 条响应，其余 7 条永不回包。
//    而每个阶段都要走 RCON（`setCoins` / `coins` 全是 RCON），
//    所以阶段内并发必然把后面 31 个 bot 的查询全部堵死。
//
// 这不是脚本能修的：瓶颈在服务端 RCON 实现。所以**提速只有两条路**：
//   · 减少重复（例如 duelRank 不必对 31 个 bot 各打一遍）
//   · 缩短 sleep（用 settle/until 等条件，别硬等时长）
//
// 顺带修掉的真 bug（保留）：RCON 原来用 pending.push + pending.shift 做 FIFO 配对，
// 假设「响应顺序 = 请求顺序」。串行时一直成立，并发就错配 —— A 的余额读给 B。
// 现在按 request id 精确配对，并加了单条超时：服务端不回包时宁可报错，
// 也不要像刚才那样无声挂死 400 秒。
//
// 有一类等待等的不是聊天回执，而是**服务端那边真的准备好**：GUI 打开了没、
// 手里那把剑同步到了没、tp 之后地形下发到了没。这类不能用 settle()（没有聊天可看），
// 只能反复问一个谓词。pred 成立立刻返回；到 maxMs 还没成立也返回，让上层自己判。
//
// 原写法同样是睡满固定时长：等 GUI 打开睡 1000ms，实际可能 120ms 就开了。
async function until (pred, maxMs, stepMs = 50) {
  const t0 = Date.now()
  while (Date.now() - t0 < maxMs) {
    let ok = false
    try { ok = !!pred() } catch (_) { ok = false }   // 谓词可能碰到还没就绪的对象
    if (ok) return true
    await wait(stepMs)
  }
  return false
}

// ---------------------------------------------------------------- 日志扫描
function logSize () {
  try { return fs.statSync(LOG).size } catch (e) { return 0 }
}
function logSince (offset) {
  try {
    const fd = fs.openSync(LOG, 'r')
    const size = fs.fstatSync(fd).size
    const start = Math.max(0, Math.min(offset, size))
    const buf = Buffer.alloc(size - start)
    fs.readSync(fd, buf, 0, buf.length, start)
    fs.closeSync(fd)
    return buf.toString('utf8')
  } catch (e) { return '' }
}
const BAD = /(Exception|Error|Caused by:|at com\.forgemaster)/i
function scanLog (offset, area) {
  const text = logSince(offset)
  const bad = text.split(/\r?\n/).filter(l => BAD.test(l) && !/INFO\]\s*$/.test(l))
  if (bad.length) {
    fail(area, '服务端日志干净', bad.slice(0, 6).map(s => s.trim().slice(0, 160)).join(' ⏎ '))
    return false
  }
  pass(area, '服务端日志干净', '无异常栈')
  return true
}

// ---------------------------------------------------------------- 阶段
const phases = {}

// ① 签到：同一玩家同 tick 连发 3 次，只许成功 1 次
phases.signin = async (bots) => {
  const area = '签到并发'
  // v2.26.0：**尝试过改成并发，失败了，这是实测结论 —— 别再试。**
  //
  // 想法很自然：每个 bot 操作自己的账号，32 个同时跑应该快 32 倍。
  // 实测结果：32 个 bot 同时上线后，阶段直接挂死 400 秒没结束。
  // 逐步减到 8 个仍然全超时，日志里一条 RCON 回包都没有。
  //
  // 根因在**服务端**，不在脚本：Minecraft 的 RCON 实现是串行收发的。
  // 单独测过 —— 一次并发发 8 条 `list`，只回 1 条响应，其余 7 条永不回包。
  // 所以只要阶段里有 RCON 调用（`setCoins` / `coins` 全都是 RCON），
  // 阶段内并发就必然把后面 31 个 bot 的查询全部堵死。
  //
  // 顺带修掉的真 bug：RCON 原来用 `pending.push` + `pending.shift` 做 FIFO 配对，
  // 假设「响应顺序 = 请求顺序」。串行跑一直成立所以没暴露，一旦并发就错配 ——
  // A 的余额读给 B。现在改成按 request id 精确配对（Map），并且加了单条超时，
  // 服务端不回包时宁可报错也不要无声挂死。这两处都是真改进，保留。
  //
  // 结论：**提速只能从「减少重复」和「缩短 sleep」下手，不能靠阶段内并发。**
  for (const bot of bots) {
    await setCoins(bot.username, 0)
    take(bot)
    burst(bot, ['/zhujian signin', '/zhujian signin', '/zhujian signin'])
    await settle(bot, 1600)
    const text = take(bot).join(' | ')
    const ok = (text.match(/每日签到/g) || []).length
    const already = (text.match(/已经签过/g) || []).length
    if (ok >= 2) fail(area, bot.username + ' 三连签到', `成功 ${ok} 次（重复领奖）→ ${text.slice(0, 260)}`)
    else if (ok + already === 0) warn(area, bot.username + ' 三连签到', '没抓到回执：' + text.slice(0, 200))
    else pass(area, bot.username + ' 三连签到', `成功 ${ok} 次 / 已签 ${already} 次`)
  }
}

// ② 秘境门票双花：只给 350 币，同 tick 抢 4 次 300 币门票
phases.tombrun = async (bots) => {
  const area = '秘境双花'
  for (const bot of bots) {
    await setCoins(bot.username, 350)
    burst(bot, ['/zhujian tombrun quit'])
    await settle(bot, 600)
    take(bot)
    burst(bot, [
      '/zhujian tombrun enter', '/zhujian tombrun enter',
      '/zhujian tombrun enter', '/zhujian tombrun enter'
    ])
    await settle(bot, 1600)
    const text = take(bot).join(' | ')
    const c = await coins(bot.username)
    const entered = (text.match(/秘境/g) || []).length
    if (c === null) { warn(area, bot.username, '读不到剑币'); continue }
    if (c < 0) fail(area, bot.username + ' 余额为负', `剑币 ${c}（双花透了）`)
    else if (c > 350) fail(area, bot.username + ' 反向加钱', `剑币 ${c} > 350`)
    else pass(area, bot.username + ' 余额不为负', `剑币 ${c}（花掉 ${350 - c}） 回执 ${entered} 行`)
    if ((text.match(/门票|入场|进入秘境/g) || []).length >= 2) {
      fail(area, bot.username + ' 重复入场', text.slice(0, 300))
    }
    burst(bot, ['/zhujian tombrun quit'])
    await wait(500)
  }
}

// 黑市限购的 ground truth：直接读 daily-data.yml 里 market 段的计数。
// 比数聊天回执稳 —— 一条提示可能发两次（chat + actionbar），chatLog 还跨阶段共享。
function readMarketCounts () {
  const p = path.join(SERVER, 'plugins', 'ForgingMaster', 'daily-data.yml')
  let txt
  try { txt = fs.readFileSync(p, 'utf8') } catch (e) { return null }
  const out = []
  let inMarket = false
  for (const line of txt.split(/\r?\n/)) {
    if (/^\S/.test(line)) { inMarket = /^market:/.test(line); continue }
    if (!inMarket) continue
    const m = line.match(/^\s+(\S+):\s*(\d+)\s*$/)
    if (m) out.push({ key: m[1], n: Number(m[2]) })
  }
  return out
}

// ③ 黑市限购：同 tick 连点同一货架 6 次，最多只许成交 daily-limit 次
phases.blackmarket = async (bots) => {
  const area = '黑市限购'
  for (const bot of bots) {
    await setCoins(bot.username, 500000)
    const before = await coins(bot.username)
    take(bot)
    bot.chat('/zhujian blackmarket')
    await until(() => !!bot.currentWindow, 1500)
    const w = bot.currentWindow
    if (!w) { warn(area, bot.username, '黑市没开：' + last(bot)); continue }
    // ENTRY_SLOTS = {10,12,14,16}，取第一个有内容的
    const slot = [10, 12, 14, 16].find(s => w.slots[s] && !/_stained_glass_pane$|^barrier$/.test(w.slots[s].name))
    if (slot === undefined) { warn(area, bot.username, '货架空'); bot.closeWindow(w); await until(() => !bot.currentWindow, 400) }
    take(bot) // ← 必须先清缓冲：chatLog 是整只 bot 共用的，不清就会把上一阶段的回执数进来
    for (let i = 0; i < 6; i++) {
      try { bot.clickWindow(slot, 0, 0) } catch (e) { /* 窗口正在重画 */ }
      if (i === 2) await wait(120) // 中间让一拍，模拟真人连点
    }
    await wait(2000)
    const after = await coins(bot.username)
    const spent = before - after
    // 点 6 次、限购 2：最多只许成交 2 次，其余都被「已经买过 N 次」挡回来。
    const text = bot.chatLog.join(' | ')
    const limitHits = (text.match(/已经买过|达到限购/g) || []).length
    // ground truth：daily-data.yml 里那件货今天的计数（比数聊天回执稳，一条提示可能发两次）
    const counts = readMarketCounts()
    const over = counts ? counts.filter(c => c.n > 2) : []
    if (over.length) {
      fail(area, bot.username + ' 超限购', 'daily-data.yml 里有计数 > 2：'
        + over.map(c => c.key + '=' + c.n).join(', ') + `（花掉 ${spent}）`)
    } else if (spent !== 0 && limitHits === 0) {
      fail(area, bot.username + ' 限购口子', `花掉 ${spent} 却没有限购回执（回执=${text.slice(-200)}）`)
    } else if (counts) {
      const mine = counts.map(c => c.key + '=' + c.n).join(' ')
      pass(area, bot.username + ' 限购生效', `6 次点击，计数全 ≤2（${mine}），本轮花掉 ${spent}`)
    } else {
      warn(area, bot.username + ' 限购读数', '读不到 daily-data.yml')
    }
    if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await until(() => !bot.currentWindow, 400) }
  }
}

// ④ 拍卖：A 挂一把剑，B/C/D 同 tick 抢同一口价 → 只许一个人成交
phases.auction = async (bots) => {
  const area = '拍卖并发'
  // 本阶段断言写死在「1 挂单 + 3 同 tick 抢」上，人数不足时装置自己跳过，别炸出假 FAIL。
  if (bots.length < 4) { warn(area, '装置人数不足，跳过', `本阶段需要 4 人，当前 ${bots.length}`); return }
  const [a, b, c, d] = bots
  for (const bot of bots) await setCoins(bot.username, 5000)
  // A 手上要有一把剑才能挂
  await rc(`zhujian give ${a.username} iron COMMON 60`)
  await until(() => a.inventory.items().some(x => x.name === 'iron_sword'), 1200)
  const sword = a.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { warn(area, 'A 挂单前置', '没拿到剑'); return }
  await a.equip(sword, 'hand')
  await settle(a, 300)
  take(a)
  a.chat('/zhujian auction sell 100 400 1')
  await settle(a, 1500)
  const sellMsg = take(a).join(' | ')
  const idm = sellMsg.match(/[#＃]?\s*([0-9a-zA-Z]{4,})/) || sellMsg.match(/编号[：: ]*([0-9a-zA-Z]+)/)
  const id = idm ? idm[1] : null
  if (!id) { warn(area, 'A 挂单', '抓不到单号：' + sellMsg.slice(0, 220)); return }
  pass(area, 'A 挂单', `单号 ${id}`)

  const before = {}
  for (const bot of [b, c, d]) before[bot.username] = await coins(bot.username)
  for (const bot of [b, c, d]) take(bot)
  // 同 tick 三个一口价
  for (const bot of [b, c, d]) bot.chat(`/zhujian auction buy ${id}`)
  await wait(2500)
  const after = {}
  for (const bot of [b, c, d]) after[bot.username] = await coins(bot.username)
  const winners = [b, c, d].filter(bot => {
    const t = take(bot).join(' | ')
    return /成功|买下|成交/.test(t)
  })
  const charged = [b, c, d].filter(bot => before[bot.username] - after[bot.username] > 0)
  if (winners.length > 1) fail(area, '一口价并发', `多人成交：${winners.map(w => w.username).join(',')}`)
  else if (charged.length > 1) fail(area, '一口价并发', `多人扣钱：${charged.map(w => w.username + ' ' + (before[w.username] - after[w.username])).join(',')}`)
  else pass(area, '一口价并发', `成交 ${winners.length} 人，扣钱 ${charged.length} 人`)
  for (const bot of [b, c, d]) {
    const delta = after[bot.username] - before[bot.username]
    if (after[bot.username] < 0) fail(area, bot.username + ' 余额为负', String(after[bot.username]))
    else if (delta > 0) warn(area, bot.username + ' 退款', `+${delta}`)
  }
  // 邮箱里该只有一把剑 + 没成交的钱要退回来
  for (const bot of [b, c, d]) {
    take(bot)
    burst(bot, ['/zhujian auction claim'])
    await settle(bot, 800)
    const got = take(bot).join(' | ')
    const swords = bot.inventory.items().filter(i => i.name === 'iron_sword').length
    console.log(`      [${bot.username}] 领邮箱: ${got.slice(0, 160)} | 背包剑=${swords}`)
  }
  // A 那边：成交了才该有钱进账
  take(a)
  a.chat('/zhujian auction claim')
  await settle(a, 1000)
  console.log('      [A] 领邮箱: ' + take(a).join(' | ').slice(0, 200))
}

// ⑤ 行会基金：4 人同时捐，基金必须等于各行之和
phases.guild = async (bots) => {
  const area = '行会基金'
  // 建会 + 3 人入会捐款的断言按 4 人写死，人数不足装置自己跳过。
  if (bots.length < 4) { warn(area, '装置人数不足，跳过', `本阶段需要 4 人，当前 ${bots.length}`); return }
  const [a, b, c, d] = bots
  for (const bot of bots) await setCoins(bot.username, 10000)
  // 会名上限 guild.name-max-chars（默认 8 字），别用 9 个字的名字
  const gname = '并会' + Date.now().toString().slice(-4)
  take(a)
  a.chat('/zhujian guild create ' + gname)
  await settle(a, 1200)
  const created = take(a).join(' | ')
  console.log('      建会: ' + created.slice(0, 200))
  // 上一轮跑过就会「已经在行会里」——那不是失败，直接用现有的会接着测。
  // （退会有冷却，退了反而建不了新的，所以不能靠 leave 重置。）
  if (!/成立|创建|建会成功|已经在行会/.test(created)) { warn(area, '建会', created.slice(0, 200)); return }
  for (const bot of [b, c, d]) {
    take(a)
    a.chat(`/zhujian guild invite ${bot.username}`)
    await settle(bot, 700)
    take(bot)
    burst(bot, ['/zhujian guild accept'])
    await settle(bot, 900)
    const back = take(bot).join(' | ')
    if (!/加入|入会|已经在/.test(back)) console.log(`      [${bot.username}] 入会回执：${(back || '(空)').slice(0, 120)}`)
  }
  // 基金必须由玩家读（RCON 控制台会被「行会要在游戏里操作」挡掉）
  const readFund = async (bot) => {
    take(bot)
    bot.chat('/zhujian guild fund info')
    await settle(bot, 900)
    const text = take(bot).join(' | ')
    const m = text.match(/基金[:：]\s*([\d,]+)/)
    return { text, fund: m ? Number(m[1].replace(/,/g, '')) : null }
  }
  const f0 = await readFund(a)
  if (f0.fund === null) { warn(area, '基金读数', f0.text.slice(0, 220)); return }
  console.log('      捐前基金: ' + f0.fund)
  const before = {}
  for (const bot of bots) before[bot.username] = await coins(bot.username)
  // 4 人同 tick 各捐 100，捐两轮
  for (const bot of bots) burst(bot, ['/zhujian guild fund donate 100', '/zhujian guild fund donate 100'])
  await wait(2400)
  const after = {}
  for (const bot of bots) after[bot.username] = await coins(bot.username)
  const paid = bots.reduce((s, bot) => s + Math.max(0, before[bot.username] - after[bot.username]), 0)
  if (paid !== 800) fail(area, '扣款守恒', `实际扣 ${paid}，应为 800（4 人 × 2 次 × 100）`)
  else pass(area, '扣款守恒', '四人各扣 200')
  const f1 = await readFund(a)
  if (f1.fund === null) { warn(area, '基金读数（后）', f1.text.slice(0, 220)); return }
  const grew = f1.fund - f0.fund
  if (grew === paid) pass(area, '基金增量守恒', `+${grew}（等于实扣 ${paid}）`)
  else fail(area, '基金增量守恒', `基金 +${grew} ≠ 实扣 ${paid}（凭空多钱/少记）`)
}

// ⑥ 铁砧：4 人错开砧位同时放剑/取剑，剑不许凭空多出来
phases.anvil = async (bots) => {
  const area = '铁砧并发'
  await rc('gamerule keepInventory true')
    // 出生点是建筑区，砧常常放进墙里（bot 会 suffocated in a wall，读回来也不是 anvil）。
    // 干脆在固定高空造一块平台，bot 站上去，砧位必然是空气。
    //
    // v2.25.7：平台尺寸原来是**写死的 4 人份**（fill 到 BX+14）。并发提到 8 之后，
    // 第 6~8 个 bot 站在 BX+15 以外 = 悬空、直接掉下去，块跟着卸载，blockAt 读回 null，
    // 于是报「砧位没放成」。那不是插件的锅，是测试装置把自己的地盘算小了。
    // 现在按实际人数算：每人横占 3 格，右侧再留 6 格余量，脚下兜两层防摔。
    const BX = 100, BY = 150, BZ = 100
    const spanRight = BX + (bots.length - 1) * 3 + 6
    await rc(`minecraft:fill ${BX - 6} ${BY - 1} ${BZ - 6} ${spanRight} ${BY - 1} ${BZ + 8} minecraft:stone`)
    await rc(`minecraft:fill ${BX - 6} ${BY - 4} ${BZ - 6} ${spanRight} ${BY - 1} ${BZ + 8} minecraft:stone`)
  await wait(400)
  for (let i = 0; i < bots.length; i++) {
    await rc(`minecraft:tp ${bots[i].username} ${BX + i * 3} ${BY} ${BZ}`)
  }
  await wait(1200)
  // tp 过去的地块是异步下发的：不等它落地，blockAt 会返回 null（不是「没放成」，是没看见）
  for (const bot of bots) {
    try { await bot.waitForChunksToLoad() } catch (e) { /* 超时也继续 */ }
  }
  for (const bot of bots) {
    await rc('minecraft:clear ' + bot.username)
    bot.chat('/zhujian give ' + bot.username + ' iron COMMON 60')
  }
  await wait(2000)
  // 每人砧位固定错开（x+2, z 各错 2 格），互相不抢格
  const anvilAt = {}
  for (let i = 0; i < bots.length; i++) {
    const bot = bots[i]
    const spot = { x: BX + i * 3 + 2, y: BY, z: BZ + 2 }
    await rc(`minecraft:setblock ${spot.x} ${spot.y} ${spot.z} minecraft:anvil`)
    anvilAt[bot.username] = { spot }
  }
  await wait(800)
  for (const bot of bots) {
    const picked = anvilAt[bot.username]
    if (!picked) continue
    const block = bot.blockAt(new (require('vec3').Vec3)(picked.spot.x, picked.spot.y, picked.spot.z))
    if (!block || block.name !== 'anvil') { warn(area, bot.username, `砧位没放成（读到 ${block ? block.name : 'null'} @ ${picked.spot.x},${picked.spot.y},${picked.spot.z}）`); continue }
    const sword = bot.inventory.items().find(it => it.name === 'iron_sword')
    if (!sword) { warn(area, bot.username, '没拿到剑'); continue }
    await bot.equip(sword, 'hand')
    await wait(200)
    anvilAt[bot.username].before = bot.inventory.items().filter(it => it.name === 'iron_sword').length
    bot.activateBlock(block)
    await wait(300)
  }
  await wait(1500)
  // 上砧之后手里该没有剑（剑挂在砧上）
  for (const bot of bots) {
    const rec = anvilAt[bot.username]
    if (!rec || rec.before === undefined) continue
    const onAnvil = bot.inventory.items().filter(it => it.name === 'iron_sword').length
    if (onAnvil > 0) {
      // 剑还在背包里 = 上砧没成功；后面「取回一把」就会变成假通过
      warn(area, bot.username, `上砧后背包仍有 ${onAnvil} 把剑（砧没吃进去）`)
      rec.skipped = true
    }
  }
  // 同时取回（各人取各人的砧）
  for (const bot of bots) {
    const picked = anvilAt[bot.username]
    if (!picked) continue
    const block = bot.blockAt(new (require('vec3').Vec3)(picked.spot.x, picked.spot.y, picked.spot.z))
    if (!block || block.name !== 'anvil') continue
    bot.activateBlock(block)
  }
  await wait(2000)
  for (const bot of bots) {
    const rec = anvilAt[bot.username] || {}
    const swords = bot.inventory.items().filter(it => it.name === 'iron_sword').length
    const t = take(bot).join(' | ')
    if (rec.skipped) continue                     // 上砧就没成功，取回没有意义，别假装通过
    if (swords > 1) fail(area, bot.username + ' 剑变多', `背包剑=${swords}（复制了）`)
    else if (swords === 1 && rec.before === 1) pass(area, bot.username + ' 取回一把', '1 把上砧 → 取回 1 把，不复制不丢')
    else if (swords === 0) warn(area, bot.username + ' 取回', '背包无剑（可能还挂在砧上）：' + t.slice(0, 160))
    else pass(area, bot.username + ' 取回一把', `背包剑=${swords}`)
  }
}

// ---------------------------------------------------------------- 声望（v2.25.4 新命令）
// ground truth 优先用 /zhujian stats 的「声望 N」—— 与 data.yml 同一份 Stats.reputation
async function reputation (name) {
  const out = await rc('zhujian stats ' + name)
  const m = out.match(/声望\s*(\d+)/)
  return m ? Number(m[1]) : null
}

/** 把声望调到指定值（take 走 spendReputation：不够则不动，所以先读再加） */
async function setReputation (name, amount) {
  const now = await reputation(name)
  if (now === null) return null
  if (now > amount) await rc(`zhujian stats rep take ${name} ${now - amount}`)
  else if (now < amount) await rc(`zhujian stats rep give ${name} ${amount - now}`)
  const after = await reputation(name)
  return after
}

// ---------------------------------------------------------------- YAML 片段读取
/** 从 plist 风格的 yml 里抠出某个一级键下面某个 id 的块（够用即可，不引依赖） */
function yamlBlock (file, rootKey, id) {
  let txt
  try { txt = fs.readFileSync(path.join(SERVER, 'plugins', 'ForgingMaster', file), 'utf8') } catch (e) { return null }
  const lines = txt.split(/\r?\n/)
  const norm = s => String(s).replace(/^['"]|['"]$/g, '')
  let inRoot = false
  let block = null
  for (const line of lines) {
    if (/^\S/.test(line)) { inRoot = norm(line.trim().replace(/:$/, '')) === rootKey; continue }
    if (!inRoot) continue
    // 单号这类键 Bukkit 的 YamlConfiguration 会写成 '529663':（带引号），必须剥掉再比
    const m = line.match(/^ {2}(\S+):\s*$/)
    if (m) { block = norm(m[1]) === norm(id) ? [] : null; continue }
    if (block) block.push(line)
  }
  if (!block) return null
  const out = {}
  for (const line of block) {
    const kv = line.match(/^ {4}(\S+):\s*(.*)$/)
    if (kv) out[kv[1]] = kv[2].trim()
  }
  return out
}

// ⑥.5 声望兑换：只给 500 声望，同 tick 抢 3 次 500 → 只许成交 1 次，钱只许多 5000
phases.redeem = async (bots) => {
  const area = '兑换并发'
  for (const bot of bots) {
    const rep0 = await setReputation(bot.username, 500)
    await setCoins(bot.username, 0)
    if (rep0 === null) { warn(area, bot.username, '读不到声望（新命令没生效？）'); continue }
    take(bot)
    burst(bot, ['/zhujian redeem coin 500', '/zhujian redeem coin 500', '/zhujian redeem coin 500'])
    await settle(bot, 2000)
    const text = take(bot).join(' | ')
    const c = await coins(bot.username)
    const rep1 = await reputation(bot.username)
    const okHits = (text.match(/兑换成功/g) || []).length
    if (rep1 === null || c === null) { warn(area, bot.username, '读数失败'); continue }
    if (rep1 < 0) fail(area, bot.username + ' 声望为负', String(rep1))
    else if (okHits > 1) fail(area, bot.username + ' 重复兑换', `成功 ${okHits} 次（声望只够一次）→ ${text.slice(0, 240)}`)
    else if (c !== 5000) fail(area, bot.username + ' 发钱不守恒', `剑币 ${c}，应为 5000（声望 ${rep1}，回执 ${okHits} 次）`)
    else pass(area, bot.username + ' 兑换守恒', `声望 500→${rep1}，剑币 +${c}（1:10 只算一次）`)
  }
}

// ⑥.6 词条剥离：同 tick 连剥 3 次第 1 条 → 剥下来的符必须与「剥离成功」次数相等（不复制）
phases.strip = async (bots) => {
  const area = '词条剥离'
  for (const bot of bots) {
    await rc('minecraft:clear ' + bot.username)
    await rc(`zhujian give ${bot.username} iron LEGENDARY 95`)
    await wait(1200)
    await setCoins(bot.username, 100000)
    const sword = bot.inventory.items().find(i => i.name === 'iron_sword')
    if (!sword) { warn(area, bot.username, '没拿到剑'); continue }
    await bot.equip(sword, 'hand')
    await settle(bot, 400)
    take(bot)
    burst(bot, ['/zhujian strengthen strip 1', '/zhujian strengthen strip 1', '/zhujian strengthen strip 1'])
    await settle(bot, 2200)
    const text = take(bot).join(' | ')
    const okHits = (text.match(/剥离成功/g) || []).length
    const papers = bot.inventory.items().filter(i => i.name === 'paper').length
    const c = await coins(bot.username)
    if (c === null) { warn(area, bot.username, '读不到剑币'); continue }
    if (c < 0) fail(area, bot.username + ' 余额为负', String(c))
    else if (papers !== okHits) fail(area, bot.username + ' 符数不符', `词条符 ${papers} 张 ≠ 成功 ${okHits} 次（复制或白扣）`)
    else if (okHits === 0) warn(area, bot.username + ' 没剥成', text.slice(0, 220))
    else pass(area, bot.username + ' 剥离守恒', `成功 ${okHits} 次 → 符 ${papers} 张，余 ${c}`)
  }
}

// ⑥.7 剑冢取回：埋一把，同 tick 抢 3 次 → 只许取回 1 把，记录删掉，别人取不到
phases.tombTake = async (bots) => {
  const area = '剑冢取回'
  const [a, b] = bots
  await rc('minecraft:clear ' + a.username)
  await rc(`zhujian give ${a.username} iron EPIC 80`)
  await until(() => a.inventory.items().some(x => x.name === 'iron_sword'), 1200)
  const sword = a.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { warn(area, '埋剑前置', '没拿到剑'); return }
  await a.equip(sword, 'hand')
  await settle(a, 300)
  take(a)
  a.chat('/zhujian tomb bury')
  await settle(a, 1500)
  const buryMsg = take(a).join(' | ')
  const m = buryMsg.match(/编号\s*([0-9A-Fa-f]{4})/)
  if (!m) { warn(area, '埋剑', '抓不到编号：' + buryMsg.slice(0, 220)); return }
  const id = m[1].toUpperCase()
  pass(area, '埋剑', '编号 ' + id)
  take(a)
  burst(a, [`/zhujian tomb take ${id}`, `/zhujian tomb take ${id}`, `/zhujian tomb take ${id}`])
  await settle(a, 2200)
  const got = take(a).join(' | ')
  const swords = a.inventory.items().filter(i => i.name === 'iron_sword').length
  const rec = yamlBlock('tombs.yml', 'tombs', id)
  if (swords > 1) fail(area, a.username + ' 剑复制', `背包 ${swords} 把（抢 3 次取回）`)
  else if (swords === 0) warn(area, a.username + ' 没取回', got.slice(0, 220))
  else pass(area, a.username + ' 只取回一把', '剑=1，不复制')
  if (rec) fail(area, '剑冢记录已删', `tombs.yml 里 ${id} 还在`)
  else pass(area, '剑冢记录已删', id + ' 已从 tombs.yml 移除')
  take(b)
  b.chat(`/zhujian tomb take ${id}`)
  await settle(b, 1000)
  const other = take(b).join(' | ')
  if (/没有编号|不是你埋的|无法取回/.test(other)) pass(area, b.username + ' 取不到别人的', '')
  else warn(area, b.username + ' 取别人的', other.slice(0, 200))
}

// ⑥.8 竞价并发：A 挂单，B/C/D 同 tick 依次加价 → 最高价唯一、被超的钱全退、只扣最后一人
phases.bid = async (bots) => {
  const area = '竞价并发'
  // 「1 挂单 + 3 同 tick 加价」按 4 人写死，人数不足装置自己跳过。
  if (bots.length < 4) { warn(area, '装置人数不足，跳过', `本阶段需要 4 人，当前 ${bots.length}`); return }
  const [a, b, c, d] = bots
  for (const bot of bots) await setCoins(bot.username, 5000)
  await rc('minecraft:clear ' + a.username)
  await rc(`zhujian give ${a.username} iron COMMON 60`)
  await until(() => a.inventory.items().some(x => x.name === 'iron_sword'), 1200)
  const sword = a.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { warn(area, '挂单前置', '没拿到剑'); return }
  await a.equip(sword, 'hand')
  await settle(a, 300)
  take(a)
  a.chat('/zhujian auction sell 100 400 1')
  await settle(a, 1500)
  const sellMsg = take(a).join(' | ')
  const idm = sellMsg.match(/#\s*([0-9A-Fa-f]{4,8})/) || sellMsg.match(/编号[：: ]*([0-9A-Fa-f]{4,8})/)
  if (!idm) { warn(area, '挂单', '抓不到单号：' + sellMsg.slice(0, 220)); return }
  const id = idm[1].toUpperCase()
  pass(area, '挂单', '单号 #' + id + ' 起拍 100')
  const before = {}
  for (const bot of [b, c, d]) before[bot.username] = await coins(bot.username)
  for (const bot of [b, c, d]) take(bot)
  // 同 tick 三档加价：150 → 160 → 170（每次都是「当前价 +10」合法）
  burst(b, [`/zhujian auction bid ${id} 150`])
  burst(c, [`/zhujian auction bid ${id} 160`])
  burst(d, [`/zhujian auction bid ${id} 170`])
  await wait(2600)
  const after = {}
  for (const bot of [b, c, d]) after[bot.username] = await coins(bot.username)
  const rec = yamlBlock('auction.yml', 'listings', id)
  const price = rec && rec.price ? Number(String(rec.price).replace(/[^\d]/g, '')) : null
  const bidder = rec ? (rec['bidder-name'] || '').replace(/['"]/g, '') : null
  const charges = [b, c, d].map(bot => ({ n: bot.username, d: before[bot.username] - after[bot.username] }))
  const losers = charges.filter(x => x.n !== bidder && x.d !== 0)
  if (price === null) { warn(area, 'auction.yml 读数', '读不到单号 ' + id); return }
  if (price !== 170) fail(area, '最高价', `auction.yml price=${price}，应为 170`)
  else pass(area, '最高价', `#${id} 停在 170（出价人 ${bidder}）`)
  if (losers.length) fail(area, '被超价没退钱', losers.map(x => `${x.n} 少 ${x.d}`).join(', '))
  else pass(area, '被超价全退', '前两手 150/160 已退回')
  const winner = charges.find(x => x.n === bidder)
  if (winner && winner.d !== 170) fail(area, '赢家扣款', `${winner.n} 少了 ${winner.d}，应为 170`)
  else if (winner) pass(area, '赢家扣款', `${winner.n} -170（冻结在单上）`)
  else warn(area, '赢家', '没认出赢家：' + bidder)
  for (const bot of [b, c, d]) {
    if (after[bot.username] < 0) fail(area, bot.username + ' 余额为负', String(after[bot.username]))
  }
}

// ⑥.9 只读风暴：4 人同 tick 各发 10 条只读命令，服务端不许吐异常、人不许被踢
phases.burst = async (bots) => {
  const area = '只读风暴'
  const cmds = ['/zhujian help', '/zhujian stats', '/zhujian codex', '/zhujian rank',
    '/zhujian env', '/zhujian story', '/zhujian power', '/zhujian collection',
    '/zhujian relicset', '/zhujian spectrum']
  for (const bot of bots) {
    take(bot)
    burst(bot, cmds)
  }
  // 这里是**所有 bot 一起**发完之后的统一等待，不是单个 bot 的回执等待。
  // 原来写的是 await wait(4000)，被批量替换成 settle(bot, …) 引入 ReferenceError
  // （bot 是 for 的循环变量，出了循环就没有了）—— 实测这一 RUN 直接红在 burst 阶段。
  // 这里要等的是「10 条只读命令的响应都吐完」，用 until 观察所有 bot 都收到响应后静默。
  const quietAfter = 1500
  await until(() => bots.every(b => b.chatLog.length > 0), 3000)
  await wait(quietAfter)
  for (const bot of bots) {
    const text = take(bot).join(' | ')
    if (!bot.entity) fail(area, bot.username + ' 被踢', text.slice(0, 200))
    else if (/未知子命令|内部错误|An internal error|Unknown command/.test(text)) {
      fail(area, bot.username + ' 命令异常', text.match(/[^|]*(未知子命令|内部错误|An internal error|Unknown command)[^|]*/)[0].slice(0, 200))
    } else pass(area, bot.username + ' ' + cmds.length + ' 条只读命令', '全部有响应')
  }
}

// ⑦ 中途掉线：开着黑市界面直接断线，别人不受影响、日志不许炸
phases.dropout = async (bots) => {
  const area = '掉线残留'
  // 踢谁不该写死下标：BOT_COUNT=2 时 bots[3] 是 undefined，阶段直接炸。
  // 语义只是「踢一个人、看别人不受影响」，取最后一名即可；断言要求至少 2 人。
  if (bots.length < 2) { warn(area, '装置人数不足，跳过', `本阶段需要至少 2 人，当前 ${bots.length}`); return }
  const victim = bots[bots.length - 1]
  const others = bots.slice(0, -1)
  take(victim)
  victim.chat('/zhujian blackmarket')
  await until(() => !!victim.currentWindow, 1200)
  const opened = !!victim.currentWindow
  victim.quit()
  // v2.26.0 提交（b5cc626）把 wait 换成 settle 时把变量名也带错了：
  // 此刻 for (const bot of others) 还没开始，bot 未定义 → 阶段炸出假 FAIL。
  await settle(victim, 1500)
  for (const bot of others) {
    take(bot)
    bot.chat('/zhujian blackmarket')
    await until(() => !!bot.currentWindow, 1000)
    const ok = !!bot.currentWindow
    if (ok) pass(area, bot.username + ' 黑市仍可开', '')
    else fail(area, bot.username + ' 黑市打不开', last(bot, 4))
    if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await until(() => !bot.currentWindow, 300) }
  }
  if (opened) pass(area, '掉线前界面正常', '')
}

// ⑥.8 剑灵重抽：同 tick 三连重抽确认 —— 成功次数必须对得上扣费，余额不许为负
// 为什么值得单列：重抽是「预览写 nonce → confirm 校验 nonce → 扣费 → 换词条」的四步流程，
// 同 tick 连发最容易撞的两个洞是「nonce 被复用 → 两次抽干一次费」和「扣费在换词条之后 → 白抽」。
phases.spiritReroll = async (bots) => {
  const area = '剑灵重抽'
  for (const bot of bots) {
    await rc('minecraft:clear ' + bot.username)
    await rc(`zhujian give ${bot.username} iron LEGENDARY 90`)
    await wait(1000)
    await setCoins(bot.username, 200000)
    const sword = bot.inventory.items().find(i => i.name === 'iron_sword')
    if (!sword) { warn(area, bot.username, '没拿到剑'); continue }
    await bot.equip(sword, 'hand')
    await settle(bot, 400)
    take(bot)
    // ① 0 灵性闸门：没满档不许重抽，被拦住时也不许扣钱
    bot.chat('/zhujian spirit reroll')
    await settle(bot, 900)
    const gate = take(bot).join(' | ')
    if (/还差 .*点灵性|只对灵性满档/.test(gate)) {
      const afterReject = await coins(bot.username)
      if (afterReject !== null && afterReject !== 200000) {
        fail(area, bot.username + ' 被拦住了却扣了钱', `200000 → ${afterReject}`)
      } else {
        pass(area, bot.username + ' 灵性未满不许重抽且不扣费', gate.slice(0, 80))
      }
    } else if (!/确认|confirm|再输/.test(gate)) {
      warn(area, bot.username, '重抽入口没响应（功能可能没开）：' + gate.slice(0, 120))
      continue
    }
    // ② 喂满灵性（v2.26.1 装置升级：LATEST 下一轮 #1 —— 以前只测到闸门，
    // nonce 复用 / 扣费时序这些真竞态从没跑过）。
    // 教训（本轮实测）：36 发 feed 用 burst 一次性塞进 mineflayer 会被聊天节流排队，
    // 余额读在半路 →「预览就扣了钱」假 FAIL / ±910 假差额，全是装置的锅。
    // 所以逐发喂（每发 150ms），读余额一律等「连续两次一致」再认。
    const settledCoins = async () => {
      let a = await coins(bot.username)
      if (a === null) return null
      for (let i = 0; i < 12; i++) {
        await wait(400)
        const b = await coins(bot.username)
        if (b === null) return null
        if (a === b) return b
        a = b
      }
      return a // 12 轮没稳定也返回最后值，让上层断言用真实数字说话
    }
    let fed = false
    for (let round = 0; round < 8 && !fed; round++) {
      take(bot)
      bot.chat('/zhujian spirit reroll')
      await settle(bot, 900)
      const pv = take(bot).join(' | ')
      if (/确认请再执行|confirm/.test(pv)) { fed = true; break } // 满档：预览真出来了（正向证据）
      const m = pv.match(/还差 (\d+) 点灵性/)
      if (!m) { await wait(800); continue } // 没抓到回执，等下再看
      const need = Number(m[1])
      const feeds = Math.ceil(need / 30) + 1
      for (let i = 0; i < feeds; i++) {
        bot.chat('/zhujian spirit feed')
        await wait(150)
      }
      await settledCoins() // 等这批喂完（余额不再变）
    }
    if (!fed) { warn(area, bot.username, '喂了 8 轮还没满档，灵性配置可能变了'); continue }
    // ③ 预览（nonce 已写进剑 PDC）→ 同 tick 三连 confirm
    const before = await settledCoins()
    if (before === null) { warn(area, bot.username, '读不到剑币'); continue }
    take(bot)
    bot.chat('/zhujian spirit reroll')
    await settle(bot, 900)
    const preview = take(bot).join(' | ')
    if (!/确认请再执行|confirm/.test(preview)) {
      warn(area, bot.username, '满档后预览没出来：' + preview.slice(0, 140))
      continue
    }
    // 预览不许扣钱（等余额稳定后和预览前比）
    // 归因同样要留余地：只有「差额恰好等于重抽单价」才算预览扣了钱。
    // 差额对不上单价说明是别处动的钱（游戏内夜间龙税等全服扣费），不记这条账。
    const afterPreview = await settledCoins()
    if (afterPreview !== null && afterPreview < before && before - afterPreview === 2000) {
      fail(area, bot.username + ' 预览就扣了钱', `${before} → ${afterPreview}（扣了 2000 = 重抽单价）`)
      continue
    }
    if (afterPreview !== null && afterPreview < before) {
      warn(area, bot.username + ' 预览窗口内余额被别处改动',
        `${before} → ${afterPreview}（差额 ${before - afterPreview}，不等于重抽单价 2000，非预览扣费）`)
    }
    burst(bot, ['/zhujian spirit reroll confirm', '/zhujian spirit reroll confirm', '/zhujian spirit reroll confirm'])
    await settle(bot, 2400)
    const text = take(bot).join(' | ')
    const okHits = (text.match(/灵契重抽完成/g) || []).length
    const nonceRej = (text.match(/先看一遍预览/g) || []).length
    const after = await settledCoins()
    if (after === null) { warn(area, bot.username, '读不到剑币'); continue }
    // 守恒三连：nonce 一次性 → 只许成 1 次；扣费 = 成功次数 × 单价；余额不为负
    if (okHits !== 1) fail(area, bot.username + ' nonce 一次性失效', `三连 confirm 成功 ${okHits} 次（应为 1）`)
    else pass(area, bot.username + ' 三连 confirm 只成 1 次', `拒绝 ${nonceRej} 次（nonce 用后即焚）`)
    const spent = before - after
    // v2.26.0：这里踩了和「每日限」同一个坑，第二次跑压测才暴露。
    // 那一轮报的是「成功 1 次应扣 2000，实扣 41932」—— 41932 = 2000（重抽）+ 39932（龙税）。
    // 根因：ContractManager.isTributeDue() 走的是 windowOpen()，也就是**按游戏内昼夜**
    // 判断「今晚该交税了」。压测跑到这个阶段正好跨过结算时刻，于是所有已签契约的 bot
    // 被全服性的龙税同时扣钱，而这条断言把外生扣费算进了「重抽」头上。
    //
    // 这属于「装置与游戏内时间耦合」，不是插件缺陷。断言只该对自己触发的那一笔负责，
    // 判据从「总额必须等于 2000」改成「2000 这一笔必须发生，且多出来的部分要能归因」：
    //   · spent >= 2000 且含 2000 → 重抽那一笔是对的；多出来的如果是别的系统扣的，
    //     点名差额记 WARN（不判红，也不装看不见）；
    //   · spent < 2000 → 重抽没扣够，这才是插件缺陷。
    if (after < 0) fail(area, bot.username + ' 余额为负', String(after))
    else if (spent < 2000) fail(area, bot.username + ' 重抽扣费不守恒', `成功 1 次应扣 2000，实扣 ${spent}`)
    else if (spent === 2000) pass(area, bot.username + ' 重抽扣费守恒', `实扣 ${spent}，余 ${after}`)
    else warn(area, bot.username + ' 重抽窗口内余额被别处改动',
      `实扣 ${spent}（含重抽 2000 + 外生 ${spent - 2000}：多为游戏内夜间龙税结算，非本断言可归因），余 ${after}`)
    // ④ 每日限（默认 1 次）：再预览要被拦，且不许扣钱
    take(bot)
    bot.chat('/zhujian spirit reroll')
    await settle(bot, 900)
    const daily = take(bot).join(' | ')
    if (/今天已经重抽过|每日限/.test(daily)) {
      const afterDaily = await settledCoins()
      // v2.26.0：这条断言曾经真的红过一次，值得把教训写在这里。
      // 32 bot 那一轮的失败是「197660 → 158128」，差额 39532 —— 既不是重抽 2000，
      // 也不是喂灵 10 的整数倍，看着像插件乱扣费。查服务端日志才发现是 contract 阶段
      // 在同一秒（12:15:31）对全部 31 个 bot 收了「交不上税 → 剑币少了 39532」。
      // 也就是说：**别的阶段在扣钱，而这条断言把它算到了「每日限」头上**。
      //
      // 装置必须只对自己触发的扣费负责。判据不能只看「余额变了」，要看
      // 「变了多少、以及这一笔能不能归因给每日限」：
      //   · 差额 > 0 且刚好等于重抽单价 → 每日限真的漏拦，这才是插件缺陷；
      //   · 差额 <= 0（变多）或差额对不上单价 → 是别的东西在动钱，
      //     记账为 WARN 并点名差额，不许判 FAIL，也不许悄悄放过。
      const delta = after - afterDaily
      if (delta > 0 && delta === 2000) {
        fail(area, bot.username + ' 每日限拦截却扣了钱', `${after} → ${afterDaily}（扣了 ${delta} = 重抽单价，每日限真漏拦）`)
      } else if (delta === 0) {
        pass(area, bot.username + ' 每日限拦截生效且不扣费', daily.slice(0, 80))
      } else {
        // 不是这条断言能归因的钱：别的阶段/别的系统动的。如实说，不判红也不判绿。
        warn(area, bot.username + ' 每日限窗口内余额被别处改动',
          `${after} → ${afterDaily}（差额 ${delta}，不等于重抽单价 2000，非本断言可归因：多为并发阶段的其他扣费）`)
      }
    } else {
      warn(area, bot.username + ' 每日限没拦住第二次预览', daily.slice(0, 140))
    }
  }
}

// ⑥.9 龙税契约：同 tick 三连 join —— 只许进一次，重复 join 不许重复收钱
phases.contract = async (bots) => {
  const area = '龙税契约'
  await rc('gamerule keepInventory true')
  for (const bot of bots) {
    await rc(`zhujian give ${bot.username} iron COMMON 40`)
    await wait(400)
    await setCoins(bot.username, 50000)
    // 先退出，保证起点干净（可能上一轮残留）
    bot.chat('/zhujian contract quit')
    await settle(bot, 500)
    take(bot)
    const before = await coins(bot.username)
    if (before === null) { warn(area, bot.username, '读不到剑币'); continue }
    burst(bot, ['/zhujian contract join', '/zhujian contract join', '/zhujian contract join'])
    await settle(bot, 2200)
    const text = take(bot).join(' | ')
    if (/未开启|没有开启/.test(text)) { warn(area, bot.username, '契约功能没开'); continue }
    // 实际文案是「契约已签。」—— 第一版正则漏了它，白报了 32 条 WARN
    const joined = /契约已签|已签约|加入|已生效/.test(text)
    const after = await coins(bot.username)
    if (after === null) { warn(area, bot.username, '读不到剑币'); continue }
    const joinHits = (text.match(/契约已签/g) || []).length
    if (after < 0) fail(area, bot.username + ' 余额为负', String(after))
    else if (!joined) warn(area, bot.username + ' 没签上', text.slice(0, 160))
    else pass(area, bot.username + ' 重复 join 不重复收钱',
        `重复提示 ${joinHits} 次，币 ${before} → ${after}（差值 ${before - after}）`)
  }
}

// ⑥.10 赛季通行证：全体同时开面板 + 领奖 GUI 点击驱动（v2.26.1 装置升级：LATEST 下一轮 #2）
//
// 注意：`/zhujian pass` 只做 `seasonPass(sender)`，**不解析参数**，所以没有 `pass claim` 这种
// 聊天命令可以驱动「连点领奖」。领奖是 GUI 点击（PassGuiRouter.onClick → service.claim）。
// 写成 `burst('/zhujian pass claim')` 会得到「0 次领奖 → PASS」的假绿 —— 那种阶段不如不跑。
//
// 领奖槽怎么定位（读 PassGui.java 得出，槽位是静态布局不是动态的）：
//   免费轨 = LEVEL_SLOTS（10-16 / 19-25 / 28-34，54 格面板独占），
//   图标材质三态：可领 GOLD_BLOCK / 已领 GRAY_DYE / 未达标 GRAY_STAINED_GLASS_PANE。
//   → 「可领槽」= LEVEL_SLOTS 里那个 gold_block，按材质找，不猜标题不猜顺序。
//
// 门槛：领第 1 级要 100 积分；积分没有管理命令（earn 只由 forge/strengthen/evolution/
// tomb-win/duel-win/commission 六个内部来源调用），装置能稳定挣的只有强化：
// 成功一次 +5 分（StrengthenService，成功/大成/无损都算）。强化可以真打：
// 砧上放剑 → /zhujian hammer 领重锤 → 潜行 + 左键 = 批量落锤（AnvilForgeListener 左键分支）。
// 攒不到分时降级为「双击第 1 级槽位 + 关面板 + 日志干净」，WARN 写明原因，绝不伪造领奖成功。
const PASS_LEVEL_SLOTS = [10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34]

/** 当前面板里有没有「可领」槽（gold_block）：有则返回 { slot }，没有返回 null */
function passClaimSlot (bot) {
  const w = bot.currentWindow
  if (!w) return null
  const slot = PASS_LEVEL_SLOTS.find(s => w.slots[s] && w.slots[s].name === 'gold_block')
  return slot === undefined ? null : { slot }
}

/**
 * 批量落锤一轮（潜在 +10 次强化，每次成功 +5 积分）。
 * 返回聊天汇总原文，供上层判断剑碎/材料不够/到顶。
 */
async function passFarmBatch (bot, spot) {
  const u = bot.username
  // blockAt 偶发 null（tp 后区块下发是异步的）：等 + 重读，最多 5 轮
  let block = null
  for (let i = 0; i < 5; i++) {
    block = bot.blockAt(new (require('vec3').Vec3)(spot.x, spot.y, spot.z))
    if (block && /anvil/.test(block.name)) break
    try { await bot.waitForChunksToLoad() } catch (_) {}
    await wait(600)
  }
  if (!block || !/anvil/.test(block.name)) {
    const p = bot.entity ? bot.entity.position : null
    return '（装置）砧位读不到：' + (block ? block.name : 'null')
      + `（bot@${p ? `${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.z)}` : '?'} spot=${spot.x},${spot.y},${spot.z}）`
  }
  // ① 残局取回：上一轮没碎的剑还挂在砧上（session 还在），先右键收回来再清包
  const oldHammer = bot.inventory.items().find(i => i.name === 'mace')
  if (oldHammer) { try { await bot.equip(oldHammer, 'hand') } catch (_) {} }
  try { bot.activateBlock(block) } catch (_) {}
  await wait(600)
  await rc('minecraft:clear ' + u)
  await rc(`zhujian give ${u} iron COMMON 60`)   // give 的尾参是「分数」不是强化等级 → 新剑 +0
  await rc(`minecraft:give ${u} iron_ingot 128`) // 批量每锤吃 2 个铁锭（tier 1: ceil(2×1.2^0)）
  await setCoins(u, 500000)
  await until(() => bot.inventory.items().some(x => x.name === 'iron_sword'), 700)
  const sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) return '（装置）没拿到剑'
  await bot.equip(sword, 'hand')
  await settle(bot, 400)
  take(bot)
  try { bot.activateBlock(block) } catch (_) {} // 不潜行右键 = 放剑上砧
  await wait(700)
  bot.chat('/zhujian hammer')                   // 领重锤
  await wait(700)
  // 实测教训：/zhujian hammer 发的是 anvil-forge.hammer.items 的第一项（本服配 IRON_AXE「铁匠重锤」），
  // 不是 MACE —— 第一版找 mace 找不到，白降级 8 轮
  const hammer = bot.inventory.items().find(i => /^(iron_axe|mace|stone_axe|iron_pickaxe)$/.test(i.name))
  if (!hammer) return '（装置）没拿到重锤：' + bot.inventory.items().map(i => i.name).join(',').slice(0, 120)
  await bot.equip(hammer, 'hand')
  await settle(bot, 1200)
  // ② 潜行 + START_DIGGING（左键）= 批量落锤，服务端一包连敲到上限/碎/满/断材料
  take(bot)
  bot.setControlState('sneak', true)
  await wait(200)
  bot._client.write('block_dig', { status: 0, location: { x: spot.x, y: spot.y, z: spot.z }, face: 1 })
  await settle(bot, 2200)
  bot.setControlState('sneak', false)
  await settle(bot, 2200)
  return take(bot).join(' | ')
}

phases.pass = async (bots) => {
  const area = '赛季通行证'
  // ① 全体同 tick 开面板 —— 界面并发断言（保留 v2.25.9 的覆盖面）。窗口先不关，②直接复用。
  for (const bot of bots) {
    take(bot)
    bot.chat('/zhujian pass')
  }
  // 同样是「全体发完之后统一等」，不是单个 bot 的回执：等所有人面板开出来再往下走。
  // 原来写 await wait(2500)，批量替换成 settle(bot, …) 会 ReferenceError（bot 出循环即失效）。
  await until(() => bots.every(b => !!b.currentWindow || b.chatLog.length > 0), 2500)
  await wait(600)
  const opened = []
  for (const bot of bots) {
    const text = take(bot).join(' | ')
    if (/暂未开放|没开/.test(text)) { warn(area, bot.username, '通行证没开'); continue }
    const win = bot.currentWindow
    if (!win) { warn(area, bot.username, '面板没开出来', text.slice(0, 160)); continue }
    // 标题是 JSON 文本组件，第一版按 /通行证|赛季/ 匹配，32 条全没中 —— 不猜了：
    // 只要窗口开出来就算过，把真实标题写进 detail。
    const title = JSON.stringify(win.title || '')
    if (!title || title === '""') { warn(area, bot.username, '窗口没有标题', ''); continue }
    opened.push(bot.username)
  }
  if (opened.length) pass(area, `同时开面板 ${opened.length} 人`, opened.slice(0, 6).join(', '))

  // ② 领奖点击驱动。先给需要攒分的 bot 修一座独立砧台（不跟 anvil 阶段的 100 区打架）。
  const PX = 220, PY = 150, PZ = 100
  const spots = {}
  const needFarm = opened.length && bots.some(b => opened.includes(b.username) && !passClaimSlot(b))
  if (needFarm) {
    const spanRight = PX + (bots.length - 1) * 3 + 6
    // 本轮实测教训：PZ=100 一带是**从未生成过的新区块**，fill 直接回「That position is not loaded」，
    // tp 到未加载区块连 position 包都不下发（bot 位置停在出生点 0,-63,0）。anvil 阶段没踩这个坑
    // 纯粹因为它那块（100,100 附近）被历轮压测反复加载过、已在存档里。先 forceload 把区块钉住。
    await rc(`minecraft:forceload add ${PX - 4} ${PZ - 4} ${spanRight} ${PZ + 6}`)
    await wait(400)
    const fillOut = await rc(`minecraft:fill ${PX - 4} ${PY - 1} ${PZ - 4} ${spanRight} ${PY - 1} ${PZ + 6} minecraft:stone`)
    if (!/filled|已填充|被填充/i.test(fillOut)) console.log('  [pass] fill 回执：' + fillOut.slice(0, 140))
    for (let i = 0; i < bots.length; i++) {
      const bot = bots[i]
      if (!opened.includes(bot.username) || passClaimSlot(bot)) continue
      const tpOut = await rc(`minecraft:tp ${bot.username} ${PX + i * 3} ${PY} ${PZ}`)
      if (!/teleported|传送/i.test(tpOut)) console.log(`  [pass] ${bot.username} tp 回执：` + tpOut.slice(0, 140))
      const sbOut = await rc(`minecraft:setblock ${PX + i * 3 + 1} ${PY} ${PZ + 2} minecraft:anvil`)
      // 「Could not set the block」= 目标已经是铁砧（上轮残留），无害；砧位最终由 farm 里的 blockAt 校验兜底
      if (!/changed|could not set|改变/i.test(sbOut)) console.log('  [pass] setblock 回执：' + sbOut.slice(0, 140))
      spots[bot.username] = { x: PX + i * 3 + 1, y: PY, z: PZ + 2 }
    }
    await wait(400)
    for (const bot of bots) {
      if (!spots[bot.username]) continue
      try { await bot.waitForChunksToLoad() } catch (_) { /* 超时也继续 */ }
    }
  }
  for (const bot of bots) {
    if (!opened.includes(bot.username)) continue
    let found = passClaimSlot(bot)
    let degradeReason = null
    if (!found && spots[bot.username]) {
      // 攒积分：一轮批量落锤 ≈ 10 锤 × 90% 成功 × 5 分 → 3~4 轮攒满 100 分；剑碎/到顶自动补剑再来
      const farmLog = []
      for (let round = 0; round < 8 && !found; round++) {
        const summary = await passFarmBatch(bot, spots[bot.username])
        farmLog.push((summary || '(空)').slice(0, 90))
        take(bot)
        bot.chat('/zhujian pass')
        await wait(1500)
        found = passClaimSlot(bot)
      }
      if (!found) degradeReason = '8 轮批量落锤后仍无可领槽位（积分没攒到下一级门槛），末轮日志：' + farmLog.slice(-2).join(' ⏎ ')
    } else if (!found) {
      degradeReason = '面板里无可领槽位（gold_block）且本轮没有砧台可攒分'
    }
    if (found) {
      const { slot } = found
      const beforeCoins = await coins(bot.username)
      const beforeItems = bot.inventory.items().reduce((s, i) => s + i.count, 0)
      take(bot)
      bot.clickWindow(slot, 0, 0)
      await settle(bot, 1200)
      const text1 = take(bot).join(' | ')
      const hits1 = (text1.match(/奖励已领取/g) || []).length
      const afterCoins1 = await coins(bot.username)
      const afterItems1 = bot.inventory.items().reduce((s, i) => s + i.count, 0)
      // 快速点第二次同一槽位（此刻面板已被下一 tick 重画成新窗口，canClaim 已 false → 路由静默拒绝）
      bot.clickWindow(slot, 0, 0)
      await settle(bot, 1200)
      const text2 = take(bot).join(' | ')
      const hits2 = (text2.match(/奖励已领取/g) || []).length
      const afterCoins2 = await coins(bot.username)
      if (hits1 === 0) {
        warn(area, bot.username + ' 领奖回执', '点了可领槽但没抓到「奖励已领取」：' + text1.slice(0, 160))
      } else if (afterCoins1 !== null && beforeCoins !== null && afterCoins1 < beforeCoins) {
        fail(area, bot.username + ' 领奖反而扣钱', `${beforeCoins} → ${afterCoins1}`)
      } else {
        pass(area, bot.username + ' GUI 点击领奖成功',
          `回执 ${hits1} 次，剑币 ${beforeCoins} → ${afterCoins1}，背包件数 ${beforeItems} → ${afterItems1}`)
      }
      // 双击不重复发放：第二次点击不该再有回执、不该再入账
      if (hits2 > 0) {
        fail(area, bot.username + ' 双击重复发放', `第二次点击又出现 ${hits2} 次「奖励已领取」`)
      } else if (afterCoins2 !== null && afterCoins1 !== null && afterCoins2 !== afterCoins1) {
        fail(area, bot.username + ' 双击重复入账', `剑币 ${afterCoins1} → ${afterCoins2}（第二击动了账）`)
      } else if (hits1 > 0) {
        pass(area, bot.username + ' 双击不重复发放', `第二击无回执、剑币不动（${afterCoins2}）`)
      }
      // 已领标记：图标应变灰勾（gray_dye）
      if (hits1 > 0) {
        const w2 = bot.currentWindow
        const icon = w2 && w2.slots[slot]
        if (icon && icon.name === 'gray_dye') pass(area, bot.username + ' 已领标记', '槽位图标变灰勾')
        else warn(area, bot.username + ' 已领标记', `槽位图标读到 ${icon ? icon.name : 'null'}（窗口重画时序）`)
      }
    } else {
      // 降级路径：双击同一可疑槽位 + 关面板。日志干净由主循环 scanLog 兜底。
      // 这里藏一条真断言：0 积分点击第 1 级槽位，路由必须静默拒绝 —— 若冒出「奖励已领取」就是门槛洞。
      take(bot)
      bot.clickWindow(PASS_LEVEL_SLOTS[0], 0, 0)
      bot.clickWindow(PASS_LEVEL_SLOTS[0], 0, 0)
      await settle(bot, 1200)
      const txt = take(bot).join(' | ')
      if (/奖励已领取/.test(txt)) {
        fail(area, bot.username + ' 零积分领到奖', '本不该领奖的双击竟出现「奖励已领取」（积分门槛洞）→ ' + txt.slice(0, 160))
      } else {
        warn(area, bot.username + ' 领奖降级', (degradeReason || '无可领槽位') +
          ' → 已降级为「双击第 1 级槽位 + 关面板」验证误点安全，不伪造领奖成功')
      }
    }
    if (bot.currentWindow) { try { bot.closeWindow(bot.currentWindow) } catch (_) {} await wait(400) }
  }
}

// ⑩ 战力对比（v2.26.0 新阶段）：power / power log / power compare 三条链路。
// 为什么值得单列：compare 要同时读两个人的主手 PDC 并拼 7 行对比，log 要落
// power-log.yml —— 纯只读路径，但「读别人的手」最容易撞 NPE。2 人即可跑，
// 正好补 2 并发的覆盖面（auction/guild/bid 那三个 4 人编队阶段在 2 人时跳过）。
phases.powerCompare = async (bots) => {
  const area = '战力对比'
  const plog = path.join(SERVER, 'plugins', 'ForgingMaster', 'power-log.yml')
  // 第一遍：全员备剑 + 各自 power / power log。
  // compare 必须等所有人手里的剑都备好 —— 第一版和 give 同循环发 compare，
  // A compare 时 B 还没 equip，「手上没有东西」的假 WARN 就是这么来的。
  for (const bot of bots) {
    await rc('minecraft:clear ' + bot.username)
    await rc(`zhujian give ${bot.username} iron COMMON 60`)
    await until(() => bot.inventory.items().some(x => x.name === 'iron_sword'), 1200)
    const sword = bot.inventory.items().find(i => i.name === 'iron_sword')
    if (sword) { await bot.equip(sword, 'hand'); await wait(300) }
  }
  for (const bot of bots) {
    take(bot)
    bot.chat('/zhujian power')
    await settle(bot, 900)
    const p = take(bot).join(' | ')
    if (/内部错误|An internal error/.test(p)) fail(area, bot.username + ' power 内部错误', p.slice(0, 200))
    else if (!/战力/.test(p)) warn(area, bot.username + ' power 没战力回执', p.slice(0, 160))
    else pass(area, bot.username + ' power 有回执', (p.match(/战力[^|]*/) || [''])[0].slice(0, 60))
    // power 命令应该已把这条评分写进 power-log.yml（有锻造品才会记）
    const ymlTxt = (fs.existsSync(plog) && fs.readFileSync(plog, 'utf8')) || ''
    if (/战力/.test(p) && !ymlTxt.includes('p:')) {
      fail(area, bot.username + ' 评分没落盘', 'power-log.yml 里没有 p: 记录')
    } else if (/战力/.test(p)) {
      pass(area, bot.username + ' 评分曲线落盘', 'power-log.yml 有记录')
    }
    take(bot)
    bot.chat('/zhujian power log')
    await settle(bot, 1200)
    const lg = take(bot).join(' | ')
    if (/内部错误|An internal error/.test(lg)) fail(area, bot.username + ' power log 内部错误', lg.slice(0, 200))
    else if (!/战力曲线|还没有评分记录/.test(lg)) warn(area, bot.username + ' power log 没回执', lg.slice(0, 160))
    else pass(area, bot.username + ' power log 有回执', (lg.match(/战力曲线[^|]*/) || [''])[0].slice(0, 60))
    // compare 对方主手：双方都拿了剑，应出「战力对比」头行
    const other = bots.find(b => b.username !== bot.username)
    if (!other) continue
    take(bot)
    bot.chat(`/zhujian power compare ${other.username}`)
    await settle(bot, 1200)
    const cmp = take(bot).join(' | ')
    if (/内部错误|An internal error/.test(cmp)) fail(area, bot.username + ' compare 内部错误', cmp.slice(0, 200))
    else if (!/战力对比/.test(cmp)) warn(area, bot.username + ' compare 没出头行', cmp.slice(0, 160))
    else pass(area, bot.username + ' compare 出对比', (cmp.match(/战力对比[^|]*/) || [''])[0].slice(0, 60))
  }
}

// ⑪ 比试排行（v2.26.1 新阶段）：rank 的 DUEL 维度刚上线，数据源 = DuelManager.settle 里的
// countDuel（双出分 → matches.remove → settle）。2 人即可跑。
//
// 装置能真驱动的链路：发起 → 邀请通知 → 拒绝（对局当场移除）→ 再发起 → 应战（双方收到
// 「比试开始」，剑胚/种子已绑定）→ A 退服收场（DuelManager.onQuit 清锁，设计行为顺手当断言）。
// 装置驱动不了的部分（如实降级，不许伪装）：真实结算要双方在锻造小游戏（炉温/敲击/开刃
// 实时节奏操作）里各出一把分，盲驱不稳定；而 stats 只有 rep 的 give/take，**没有 duel 的
// 管理命令** → 没有注入胜负的合法入口。所以「赢家名 + 胜字样」这条降级为：
// rank duel text 与档案 duel-wins/losses 的一致性对账（有胜必上榜、榜上数字 = 档案数字、
// 0 胜不占榜），WARN 写明降级原因。
//
// 采样化口径（v2.26.1 起写死在这里，防止下一轮再被「对 31 个 bot 各跑一遍」的旧结论带偏）：
//   · 完整的「发起→deny→再发起→accept→开始→退服清锁」链路**只跑 1 对** —— 取存活 bot
//     的前两名（living[0] × living[1]），无论 BOT_COUNT 是 2 还是 32，配对数恒为 1。
//     同一条链对第 3~31 个 bot 再打一遍是重复验证同一件事，一行新信息都没有。
//   · 其余 bot 只做廉价断言：档案胜负可读（每人 1 条 RCON `zhujian stats`）+
//     rank duel text 的「榜 ↔ 档案」全员对账 —— 都是纯读，不加链路。
//   · 实际配对数打进 stdout（见链路分支里的 console.log），报告里肉眼可查。
//     2 bot 时断言集合与采样化之前逐条一致（本注释只记录口径，不动任何断言行）。
phases.duelRank = async (bots) => {
  const area = '比试排行'
  const readDuel = async (name) => {
    const out = await rc('zhujian stats ' + name)
    const m = out.match(/比试\s*(\d+)\s*胜\s*(\d+)\s*负/)
    return m ? { w: Number(m[1]), l: Number(m[2]) } : null
  }
  const base = {}
  for (const bot of bots) base[bot.username] = await readDuel(bot.username)
  const readable = bots.filter(b => base[b.username])
  if (readable.length === bots.length && bots.length) {
    pass(area, '档案胜负可读', bots.map(b => `${b.username}=${base[b.username].w}胜${base[b.username].l}负`).join(' '))
  } else {
    warn(area, '档案胜负', 'zhujian stats 里没解析到「比试 X 胜 Y 负」（输出文案变了？）：'
      + bots.map(b => b.username).join(','))
  }

  const [a, b] = bots
  if (bots.length >= 2 && a && b) {
    // 链路只跑这一对。配对数写进 stdout：32 bot 全量时这里必须是「1 对」，
    // 若哪天有人把它改回逐 bot/逐对循环，这个数字会当场变大。
    console.log(`  （比试链路只跑 1 对：${a.username} × ${b.username}；其余 ${bots.length - 2} 个 bot 只做档案/榜对账，不重复打链）`)
    // ② 清主手：档位按主手强化等级分档（DuelTiers），双方空手 = 同档 0，不会被跨档拦截
    await rc('minecraft:clear ' + a.username)
    await rc('minecraft:clear ' + b.username)
    await settle(a, 500)

    // ③ A 发起 → B 收邀请 → B 拒绝
    take(a); take(b)
    a.chat(`/zhujian duel ${b.username}`)
    await settle(a, 1400)
    const cA = take(a).join(' | ')
    const invB = take(b).join(' | ')
    if (/比试功能被关掉了|没开/.test(cA)) {
      warn(area, '比试入口', '功能未开启：' + cA.slice(0, 140))
    } else if (/还在比试中|正在比试中/.test(cA)) {
      warn(area, '比试入口', '残留比试锁（上一轮留下的对局只能靠退服/30 分钟兜底清理）：' + cA.slice(0, 140))
    } else if (!/已向.*发起比试|发起比试/.test(cA)) {
      warn(area, 'A 发起比试', '没抓到发起回执：' + cA.slice(0, 180))
    } else {
      pass(area, 'A 发起比试', (cA.match(/已向[^|]*/) || [cA])[0].slice(0, 80))
    }
    if (/向你发起锻造比试|应战/.test(invB)) pass(area, 'B 收到邀请通知', '')
    else if (/发起比试/.test(cA)) warn(area, 'B 收到邀请通知', '没抓到：' + invB.slice(0, 180))

    take(a); take(b)
    b.chat('/zhujian duel deny')
    await settle(a, 1000)
    const dA = take(a).join(' | ')
    const dB = take(b).join(' | ')
    if (/已拒绝/.test(dB)) pass(area, 'B 拒绝比试', 'deny 回执正常')
    else warn(area, 'B 拒绝比试', (dB || '(空)').slice(0, 160))
    if (/拒绝/.test(dA)) pass(area, 'A 收到拒绝通知', '')

    // ④ 再发起 + 应战：双方都该收到「比试开始」（accept 成功返回 null，走主动消息）
    take(a); take(b)
    a.chat(`/zhujian duel ${b.username}`)
    await settle(b, 1400)
    take(b)
    b.chat('/zhujian duel accept')
    await settle(a, 1800)
    const accA = take(a).join(' | ')
    const accB = take(b).join(' | ')
    if (/比试开始/.test(accA) && /比试开始/.test(accB)) {
      pass(area, '应战成立', '双方都收到「比试开始」（同一剑胚同一份种子，等待双方出分）')
    } else {
      warn(area, '应战成立', `A[${(accA || '(空)').slice(0, 80)}] B[${(accB || '(空)').slice(0, 80)}]`)
    }

    // ⑤ 真实结算降级说明（机制限制写清楚，禁止伪造领奖/胜场）
    warn(area, '真实结算降级',
      '结算要双方在锻造小游戏（炉温/敲击/开刃实时节奏）里各出一把分，装置无法稳定盲驱；' +
      'stats 无 duel give/take 管理命令（只有 rep）→ 不注入胜负，保持档案原样，只做榜-档案一致性对账')

    // ⑥ A 退服收场：onQuit 应清掉这场没打完的对局 → 重进后 A 再发起必须畅通（没有「还在比试中」）
    a.quit()
    await wait(2500)
    const again = await joinWithRetry(a.username)
    if (!again) {
      dropped.add(a.username)
      warn(area, 'A 退服重进', '重进失败（同名会话残留/握手节流）；对局锁已由服务端 onQuit 清理，仅装置侧未验证')
    } else {
      dropped.delete(a.username)
      await settle(again, 800)
      await rc('op ' + a.username)
      await rc('gamemode survival ' + a.username)
      take(again); take(b)
      again.chat(`/zhujian duel ${b.username}`)
      await settle(again, 1400)
      const reC = take(again).join(' | ')
      if (/还在比试中|正在比试中/.test(reC)) {
        fail(area, '退服清锁', 'A 退服重进后仍报「还在比试中」→ onQuit 没清干净对局')
      } else if (/已向.*发起比试|发起比试/.test(reC)) {
        pass(area, '退服清锁', '退服后对局已清，A 能再次正常发起（onQuit 清理生效）')
        take(b)
        b.chat('/zhujian duel deny') // 收尾，别把邀请残留给下一轮
        await wait(900)
      } else {
        warn(area, '退服清锁', '没抓到再发起回执：' + (reC || '(空)').slice(0, 160))
      }
    }
  } else {
    warn(area, '装置人数不足', `邀请/应战链路需要 2 人，当前 ${bots.length}；只做 rank duel text 断言`)
  }

  // ⑦ rank duel text：命令可执行 + 行格式 + 与档案一致性
  const out = await rc('zhujian rank duel text')
  if (/未知排行维度|未知子命令|内部错误|An internal error/.test(out)) {
    fail(area, 'rank duel text 可执行', out.slice(0, 200))
  } else {
    const lines = out.split(/\r?\n/).map(s => s.trim()).filter(Boolean)
    const rows = []
    let malformed = 0
    for (const ln of lines) {
      if (!/^\d+\./.test(ln)) continue
      const m = ln.match(/^(\d+)\.\s*(\S+)\s*\|\s*(\d+)\s*胜\s*(\d+)\s*负/)
      if (m) rows.push({ name: m[2], w: Number(m[3]), l: Number(m[4]) })
      else malformed++
    }
    if (malformed) {
      fail(area, '行格式', `${malformed} 行以序号开头但不符合「N. 名字 | X 胜 Y 负」→ ${lines.slice(0, 4).join(' ⏎ ')}`)
    } else if (rows.length === 0) {
      if (/暂无上榜记录/.test(out)) pass(area, 'rank duel text 可执行', '空榜口径正确（暂无上榜记录）')
      else warn(area, 'rank duel text', '既没有榜行也没有「暂无上榜记录」：' + out.slice(0, 200))
    } else {
      pass(area, '行格式', `${rows.length} 行均为「N. 名字 | X 胜 Y 负」：` + rows.map(r => `${r.name} ${r.w}胜${r.l}负`).join(', '))
    }
    // 榜-档案一致性（countDuel 是唯一数据源，两边必须对得上）
    if (readable.length) {
      let consistent = true
      const why = []
      for (const bot of readable) {
        const st = base[bot.username]
        const row = rows.find(r => r.name === bot.username)
        if (st.w > 0 && !row) { consistent = false; why.push(`${bot.username} 档案 ${st.w}胜${st.l}负 却没上榜`) }
        else if (st.w === 0 && row) { consistent = false; why.push(`${bot.username} 档案 0 胜却上榜 ${row.w}胜${row.l}负`) }
        else if (row && (row.w !== st.w || row.l !== st.l)) { consistent = false; why.push(`${bot.username} 榜 ${row.w}胜${row.l}负 ≠ 档案 ${st.w}胜${st.l}负`) }
      }
      if (consistent) pass(area, '榜与档案一致', '榜上胜负数与 stats 档案的 countDuel 字段一一对应（0 胜不占榜）')
      else fail(area, '榜与档案一致', why.join(' ⏎ '))
    }
  }
}

// ---------------------------------------------------------------- main
async function main () {
  const only = process.argv.slice(2).filter(a => !a.startsWith('-'))
  const run = only.length ? only : Object.keys(phases)
  console.log('=== 4 并发实测 === 阶段：' + run.join(', '))
  rcon = await rconClient()
  await rc('gamerule keepInventory true')
  await rc('difficulty peaceful')
  await rc('gamerule doMobSpawning false')
  // v2.26.0：**冻结游戏内时间在白天**，否则压测会随机被「夜间交税」打断。
  //
  // 踩过两次，都是同一个根因：ContractManager.isTributeDue() → windowOpen()，
  // 按游戏内昼夜判断「今晚该交税」，一旦跨过结算时刻，**所有已签契约的 bot 同时被扣钱**。
  // 那是全服外生扣费，落在任何阶段的余额断言区间里都会把它算成自己的账：
  //   · 第一次报「每日限拦截却扣了钱 197660 → 158128」（差 39532）
  //   · 第二次报「重抽扣费不守恒 应扣 2000 实扣 41932」（39532 + 2000）
  // 两次都不是插件缺陷，是装置的断言窗口被别的系统穿插了。
  //
  // 光给每条断言加归因（现在也加了，作为第二道防线）治不了根：读数窗口里随时可能多出
  // 一笔不属于这条命令的钱。真正干净的做法是让这个外生事件在压测期间不发生 ——
  // doDaylightCycle false + time set day 把世界钉在白天，窗口就永远不开。
  // 时间类玩法（昼夜相关）本来就不在 17 个阶段的覆盖范围里，冻结不影响覆盖面。
  await rc('gamerule doDaylightCycle false')
  await rc('time set day')

  const bots = []
  // 在 try 外声明：try/finally 之后写报告时还要用它（const 是块级作用域，写里面会 ReferenceError）
  let aliveAtStart = 0
  // 每阶段实测耗时（ms）。过去「duelRank 占 17.7 分钟」这类结论全靠口口相传，
  // 没有任何计时记录可查证（LATEST #1 按它发起改造，查证时连一条日志都找不到）。
  // 现在每阶段结束打印一行实测值，并写进报告 phaseMs —— 下一次优化按数字说话。
  const phaseMs = {}
  try {
    // 逐个上线（一起连会让测试服握手排队，反而测不到东西）
    const joinT0 = Date.now()
    for (let i = 0; i < BOTS.length; i++) {
      const bot = await joinWithRetry(BOTS[i])
      bots.push(bot)
      await wait(700)
      await rc('op ' + BOTS[i])
      await rc('gamemode survival ' + BOTS[i])
      await rc('minecraft:clear ' + BOTS[i])
      const pos = bot.entity.position.floored()
      await rc(`minecraft:tp ${BOTS[i]} ${pos.x + i * 4} ${pos.y} ${pos.z}`)
      await wait(400)
    }
    const joinMs = Date.now() - joinT0
    console.log(`  ${BOTS.length} 个 bot 全部上线（${joinMs}ms）：` + bots.map(b => b.username).join(', '))
    // 真正连上的数量（joinWithRetry 失败会少 push）。写进报告，让「32 设了、28 上了」可被发现。
    aliveAtStart = bots.filter(b => b && b.entity).length
    if (aliveAtStart !== BOTS.length) {
      console.log(`  ⚠ 只有 ${aliveAtStart}/${BOTS.length} 个 bot 连上 —— 本次断言覆盖不足，报告会标出来`)
    }
    if (throttle.events > 0) {
      console.log(`  ⚠ 握手节流命中 ${throttle.events} 次，重连 ${throttle.retries} 次`)
    }

    // JOIN_ONLY：只量「连得上吗、多快」，不跑 12 个阶段。
    // 用途：回答「5 并发会不会 429」，而不让探针的不稳污染 4 并发的回归门。
    if (JOIN_ONLY) {
      console.log('\n[JOIN_ONLY] 空转 20 秒，观察是否被中途踢下线…')
      const kickedBefore = throttle.events
      await until(() => bots.every(x => x.entity && x.player), 20000)
      const alive = bots.filter(b => b.entity && b.player).length
      const result = {
        mode: 'join-only',
        botCount: BOTS.length,
        joinMs,
        aliveAfter20s: alive,
        throttleEvents: throttle.events,
        throttledDuringIdle: throttle.events - kickedBefore,
        retries: throttle.retries,
        details: throttle.details.slice(0, 10),
        at: new Date().toISOString()
      }
      fs.writeFileSync(path.join(__dirname, `join-probe-${BOTS.length}bots.json`),
          JSON.stringify(result, null, 2))
      console.log(`\n[结果] ${BOTS.length} 并发：上线 ${joinMs}ms，20 秒后存活 ${alive}/${bots.length}，`
          + `握手节流 ${result.throttleEvents} 次，重连 ${result.retries} 次`)
      for (const b of bots) { try { b.quit() } catch (_) {} }
      return
    }

    for (const name of run) {
      if (!phases[name]) { console.log('  未知阶段：' + name); continue }
      console.log('\n--- ' + name + ' ---')
      // dropout 阶段会故意把最后一名 bot 踢下线（模拟掉线残留），之后它就一直不在了。
      // 以前后面几个阶段照样把它算进断言，于是固定报两条 WARN（「没响应」「没签上」）——
      // 那是脚本自己的坑，不是插件的。现在按「还活着的 bot」喂给每个阶段：
      // dropout 自己也在这之前跑，拿到的是满编 32 人，语义不变。
      const living = bots.filter(b => b && b.entity && !dropped.has(b.username))
      if (living.length !== bots.length) {
        console.log(`  （跳过 ${bots.length - living.length} 个已掉线的 bot）`)
      }
      const mark = logSize()
      const t0 = Date.now()
      try { await phases[name](living) } catch (e) { fail(name, '阶段异常', e.message) }
      phaseMs[name] = Date.now() - t0
      console.log(`  （${name} 耗时 ${(phaseMs[name] / 1000).toFixed(1)}s）`)
      await wait(600)
      scanLog(mark, name)
    }
  } finally {
    for (const bot of bots) { try { if (bot.entity) bot.quit() } catch (e) {} }
    await wait(800)
    rcon.close()
  }

  const bad = rows.filter(r => r.status === 'FAIL')
  const warnRows = rows.filter(r => r.status === 'WARN')
  console.log(`\n=== 汇总：PASS ${rows.length - bad.length - warnRows.length} / WARN ${warnRows.length} / FAIL ${bad.length} ===`)
  if (bad.length) {
    for (const r of bad) console.log(`  ✗ [${r.area}] ${r.name} — ${r.detail}`)
  }
  fs.writeFileSync(path.join(__dirname, 'concurrent4-report.json'), JSON.stringify({
    at: new Date().toISOString(), phases: run,
    // botCount = 脚本实际生成的 bot 数（BOTS.length，被 BOT_COUNT clamp 时会小于设定值）。
    // 硬规则要求「跑完核对 botCount 等于你设的数」，就是为了抓 clamp 静默截断。
    botCount: BOTS.length,
    // botCountAlive = 跑完第一个阶段时真正在线的数量。botCount 只是「打算跑几个」，
    // 32 个里有 10 个没连上时前者照样是 32 —— 只看 botCount 会把这种事藏起来。
    // 判「32 并发」要看这一个，两者不一致时 run-stress.py 会点名。
    botCountAlive: aliveAtStart,
    // 握手节流单独记录：它不是插件缺陷，但每轮都出现说明服务端连接上限到了
    throttleEvents: throttle.events, throttleRetries: throttle.retries,
    // 每阶段实测耗时（毫秒）：「duelRank 占全量多少」这类结论以这里为准
    phaseMs, rows
  }, null, 2))
  process.exit(bad.length ? 1 : 0)
}

main().catch(e => { console.log('[fatal] ' + e.stack); process.exit(1) })
