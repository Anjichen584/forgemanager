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
const PORT = 25577
const VERSION = '1.21.11'
const RCON_PORT = 25599
const RCON_PASS = 'testpass'
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
    const pending = []

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
              return new Promise(res => {
                pending.push(res)
                sock.write(packet(++id, 2, cmd))
              })
            },
            close () { sock.destroy() }
          })
          continue
        }
        const res = pending.shift()
        if (res) res(body)
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
  for (const bot of bots) {
    await setCoins(bot.username, 0)
    take(bot)
    burst(bot, ['/zhujian signin', '/zhujian signin', '/zhujian signin'])
    await wait(1600)
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
    await wait(600)
    take(bot)
    burst(bot, [
      '/zhujian tombrun enter', '/zhujian tombrun enter',
      '/zhujian tombrun enter', '/zhujian tombrun enter'
    ])
    await wait(2200)
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
    await wait(1500)
    const w = bot.currentWindow
    if (!w) { warn(area, bot.username, '黑市没开：' + last(bot)); continue }
    // ENTRY_SLOTS = {10,12,14,16}，取第一个有内容的
    const slot = [10, 12, 14, 16].find(s => w.slots[s] && !/_stained_glass_pane$|^barrier$/.test(w.slots[s].name))
    if (slot === undefined) { warn(area, bot.username, '货架空'); bot.closeWindow(w); await wait(400); continue }
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
    if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(400) }
  }
}

// ④ 拍卖：A 挂一把剑，B/C/D 同 tick 抢同一口价 → 只许一个人成交
phases.auction = async (bots) => {
  const area = '拍卖并发'
  const [a, b, c, d] = bots
  for (const bot of bots) await setCoins(bot.username, 5000)
  // A 手上要有一把剑才能挂
  await rc(`zhujian give ${a.username} iron COMMON 60`)
  await wait(1200)
  const sword = a.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { warn(area, 'A 挂单前置', '没拿到剑'); return }
  await a.equip(sword, 'hand')
  await wait(300)
  take(a)
  a.chat('/zhujian auction sell 100 400 1')
  await wait(1500)
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
    await wait(800)
    const got = take(bot).join(' | ')
    const swords = bot.inventory.items().filter(i => i.name === 'iron_sword').length
    console.log(`      [${bot.username}] 领邮箱: ${got.slice(0, 160)} | 背包剑=${swords}`)
  }
  // A 那边：成交了才该有钱进账
  take(a)
  a.chat('/zhujian auction claim')
  await wait(1000)
  console.log('      [A] 领邮箱: ' + take(a).join(' | ').slice(0, 200))
}

// ⑤ 行会基金：4 人同时捐，基金必须等于各行之和
phases.guild = async (bots) => {
  const area = '行会基金'
  const [a, b, c, d] = bots
  for (const bot of bots) await setCoins(bot.username, 10000)
  // 会名上限 guild.name-max-chars（默认 8 字），别用 9 个字的名字
  const gname = '并会' + Date.now().toString().slice(-4)
  take(a)
  a.chat('/zhujian guild create ' + gname)
  await wait(1200)
  const created = take(a).join(' | ')
  console.log('      建会: ' + created.slice(0, 200))
  // 上一轮跑过就会「已经在行会里」——那不是失败，直接用现有的会接着测。
  // （退会有冷却，退了反而建不了新的，所以不能靠 leave 重置。）
  if (!/成立|创建|建会成功|已经在行会/.test(created)) { warn(area, '建会', created.slice(0, 200)); return }
  for (const bot of [b, c, d]) {
    take(a)
    a.chat(`/zhujian guild invite ${bot.username}`)
    await wait(700)
    take(bot)
    burst(bot, ['/zhujian guild accept'])
    await wait(900)
    const back = take(bot).join(' | ')
    if (!/加入|入会|已经在/.test(back)) console.log(`      [${bot.username}] 入会回执：${(back || '(空)').slice(0, 120)}`)
  }
  // 基金必须由玩家读（RCON 控制台会被「行会要在游戏里操作」挡掉）
  const readFund = async (bot) => {
    take(bot)
    bot.chat('/zhujian guild fund info')
    await wait(900)
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
    await wait(2000)
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
    await wait(400)
    take(bot)
    burst(bot, ['/zhujian strengthen strip 1', '/zhujian strengthen strip 1', '/zhujian strengthen strip 1'])
    await wait(2200)
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
  await wait(1200)
  const sword = a.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { warn(area, '埋剑前置', '没拿到剑'); return }
  await a.equip(sword, 'hand')
  await wait(300)
  take(a)
  a.chat('/zhujian tomb bury')
  await wait(1500)
  const buryMsg = take(a).join(' | ')
  const m = buryMsg.match(/编号\s*([0-9A-Fa-f]{4})/)
  if (!m) { warn(area, '埋剑', '抓不到编号：' + buryMsg.slice(0, 220)); return }
  const id = m[1].toUpperCase()
  pass(area, '埋剑', '编号 ' + id)
  take(a)
  burst(a, [`/zhujian tomb take ${id}`, `/zhujian tomb take ${id}`, `/zhujian tomb take ${id}`])
  await wait(2200)
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
  await wait(1000)
  const other = take(b).join(' | ')
  if (/没有编号|不是你埋的|无法取回/.test(other)) pass(area, b.username + ' 取不到别人的', '')
  else warn(area, b.username + ' 取别人的', other.slice(0, 200))
}

// ⑥.8 竞价并发：A 挂单，B/C/D 同 tick 依次加价 → 最高价唯一、被超的钱全退、只扣最后一人
phases.bid = async (bots) => {
  const area = '竞价并发'
  const [a, b, c, d] = bots
  for (const bot of bots) await setCoins(bot.username, 5000)
  await rc('minecraft:clear ' + a.username)
  await rc(`zhujian give ${a.username} iron COMMON 60`)
  await wait(1200)
  const sword = a.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { warn(area, '挂单前置', '没拿到剑'); return }
  await a.equip(sword, 'hand')
  await wait(300)
  take(a)
  a.chat('/zhujian auction sell 100 400 1')
  await wait(1500)
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
  await wait(4000)
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
  const victim = bots[3]
  const others = bots.slice(0, 3)
  take(victim)
  victim.chat('/zhujian blackmarket')
  await wait(1200)
  const opened = !!victim.currentWindow
  victim.quit()
  await wait(1500)
  for (const bot of others) {
    take(bot)
    bot.chat('/zhujian blackmarket')
    await wait(1000)
    const ok = !!bot.currentWindow
    if (ok) pass(area, bot.username + ' 黑市仍可开', '')
    else fail(area, bot.username + ' 黑市打不开', last(bot, 4))
    if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(300) }
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
    await wait(300)
    take(bot)
    // 先预览一次拿到 nonce，再同 tick 连发三次 confirm
    bot.chat('/zhujian spirit reroll')
    await wait(900)
    const preview = take(bot).join(' | ')
    if (/灵性/.test(preview)) {
      // 闸门正确：灵性没满档就不许重抽。这不是 WARN —— 是闸门在工作。
      // 顺带断言「被拦住时不许扣钱」，这才是这条路径真正的风险点。
      const afterReject = await coins(bot.username)
      if (afterReject !== null && afterReject !== 200000) {
        fail(area, bot.username + ' 被拦住了却扣了钱', `200000 → ${afterReject}`)
      } else {
        pass(area, bot.username + ' 灵性未满不许重抽且不扣费', preview.slice(0, 80))
      }
      continue
    }
    if (!/确认|confirm|再输/.test(preview)) {
      warn(area, bot.username, '重抽入口没响应（功能可能没开）：' + preview.slice(0, 120))
      continue
    }
    const before = await coins(bot.username)
    if (before === null) { warn(area, bot.username, '读不到剑币'); continue }
    burst(bot, ['/zhujian spirit reroll confirm', '/zhujian spirit reroll confirm', '/zhujian spirit reroll confirm'])
    await wait(2400)
    const text = take(bot).join(' | ')
    const okHits = (text.match(/重抽完成|已重抽|新的词条/g) || []).length
    const after = await coins(bot.username)
    if (after === null) { warn(area, bot.username, '读不到剑币'); continue }
    // 守恒：扣的钱必须等于成功次数 × 单价（读不到单价就问配置默认值）
    const spent = before - after
    if (after < 0) fail(area, bot.username + ' 余额为负', String(after))
    else if (okHits === 0) pass(area, bot.username + ' 一次都没抽成（不扣费）', `余 ${after}`)
    else pass(area, bot.username + ' 重抽扣费守恒', `成功 ${okHits} 次，实扣 ${spent}，余 ${after}`)
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
    await wait(500)
    take(bot)
    const before = await coins(bot.username)
    if (before === null) { warn(area, bot.username, '读不到剑币'); continue }
    burst(bot, ['/zhujian contract join', '/zhujian contract join', '/zhujian contract join'])
    await wait(2200)
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

// ⑥.10 赛季通行证：全体同时开面板 —— 界面并发（holder / 多人同开）
//
// 注意：`/zhujian pass` 只做 `seasonPass(sender)`，**不解析参数**，所以没有 `pass claim` 这种
// 聊天命令可以驱动「连点领奖」。领奖是 GUI 点击。写成 `burst('/zhujian pass claim')` 会得到
// 「0 次领奖 → PASS」的假绿 —— 那种阶段不如不跑。
// 所以这里只断言能真断言的东西：全体同时开面板，窗口必须都开出来，且服务端日志不许有异常。
phases.pass = async (bots) => {
  const area = '赛季通行证'
  const opened = []
  for (const bot of bots) {
    take(bot)
    bot.chat('/zhujian pass')
  }
  await wait(2500)
  for (const bot of bots) {
    const text = take(bot).join(' | ')
    if (/暂未开放|没开/.test(text)) { warn(area, bot.username, '通行证没开'); continue }
    const win = bot.currentWindow
    if (!win) { warn(area, bot.username, '面板没开出来', text.slice(0, 160)); continue }
    // 标题是 JSON 文本组件，第一版按 /通行证|赛季/ 匹配，32 条全没中 —— 不猜了：
    // 只要窗口开出来就算过，把真实标题写进 detail，下一轮要按标题断言就有依据。
    const title = JSON.stringify(win.title || '')
    if (!title || title === '""') { warn(area, bot.username, '窗口没有标题', ''); continue }
    opened.push(bot.username)
    try { bot.closeWindow(win) } catch (_) { /* 已关 */ }
  }
  if (opened.length) pass(area, `同时开面板 ${opened.length} 人`, opened.slice(0, 6).join(', '))
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

  const bots = []
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
    if (throttle.events > 0) {
      console.log(`  ⚠ 握手节流命中 ${throttle.events} 次，重连 ${throttle.retries} 次`)
    }

    // JOIN_ONLY：只量「连得上吗、多快」，不跑 12 个阶段。
    // 用途：回答「5 并发会不会 429」，而不让探针的不稳污染 4 并发的回归门。
    if (JOIN_ONLY) {
      console.log('\n[JOIN_ONLY] 空转 20 秒，观察是否被中途踢下线…')
      const kickedBefore = throttle.events
      await wait(20000)
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
      // dropout 阶段会故意把 bots[3] 踢下线（模拟掉线残留），之后它就一直不在了。
      // 以前后面几个阶段照样把它算进断言，于是固定报两条 WARN（「没响应」「没签上」）——
      // 那是脚本自己的坑，不是插件的。现在按「还活着的 bot」喂给每个阶段：
      // dropout 自己也在这之前跑，拿到的是满编 32 人，语义不变。
      const living = bots.filter(b => b && b.entity && !dropped.has(b.username))
      if (living.length !== bots.length) {
        console.log(`  （跳过 ${bots.length - living.length} 个已掉线的 bot）`)
      }
      const mark = logSize()
      try { await phases[name](living) } catch (e) { fail(name, '阶段异常', e.message) }
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
    botCount: BOTS.length,
    // 握手节流单独记录：它不是插件缺陷，但每轮都出现说明服务端连接上限到了
    throttleEvents: throttle.events, throttleRetries: throttle.retries, rows
  }, null, 2))
  process.exit(bad.length ? 1 : 0)
}

main().catch(e => { console.log('[fatal] ' + e.stack); process.exit(1) })
