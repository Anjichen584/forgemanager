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
const mineflayer = require('mineflayer')
const { wait } = require('./common')

const HOST = '127.0.0.1'
const PORT = 25577
const VERSION = '1.21.11'
const RCON_PORT = 25599
const RCON_PASS = 'testpass'
const SERVER = process.env.FM_SERVER || 'D:\\IDEA\\mc-plugins\\ForgingMaster\\testserver'
const LOG = path.join(SERVER, 'logs', 'latest.log')

const BOTS = ['ConcA', 'ConcB', 'ConcC', 'ConcD']

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
function join (name) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: VERSION })
  bot.chatLog = []
  bot.on('message', m => {
    const s = m.toString()
    if (s.trim()) bot.chatLog.push(s.replace(/\u00a7./g, ''))
    if (bot.chatLog.length > 400) bot.chatLog.splice(0, bot.chatLog.length - 400)
  })
  bot.on('error', e => console.log(`  [${name}] error ${e.message}`))
  bot.on('kicked', r => console.log(`  [${name}] kicked ${JSON.stringify(r).slice(0, 200)}`))
  return bot
}

const sp = (bot, ms = 20000) => new Promise((res, rej) => {
  bot.once('spawn', res)
  setTimeout(() => rej(new Error(bot.username + ' spawn 超时')), ms)
})

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
  // 干脆在固定高空造一块 13×13 平台，四个人站上去，砧位必然是空气。
  const BX = 100, BY = 150, BZ = 100
  await rc(`minecraft:fill ${BX - 6} ${BY - 1} ${BZ - 6} ${BX + 14} ${BY - 1} ${BZ + 8} minecraft:stone`)
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
    // 逐个上线（4 个一起连会让测试服握手排队，反而测不到东西）
    for (let i = 0; i < BOTS.length; i++) {
      const bot = join(BOTS[i])
      bots.push(bot)
      await sp(bot, i === 0 ? 40000 : 20000)
      await wait(700)
      await rc('op ' + BOTS[i])
      await rc('gamemode survival ' + BOTS[i])
      await rc('minecraft:clear ' + BOTS[i])
      const pos = bot.entity.position.floored()
      await rc(`minecraft:tp ${BOTS[i]} ${pos.x + i * 4} ${pos.y} ${pos.z}`)
      await wait(400)
    }
    console.log('  4 个 bot 全部上线：' + bots.map(b => b.username).join(', '))

    for (const name of run) {
      if (!phases[name]) { console.log('  未知阶段：' + name); continue }
      console.log('\n--- ' + name + ' ---')
      const mark = logSize()
      try { await phases[name](bots) } catch (e) { fail(name, '阶段异常', e.message) }
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
    at: new Date().toISOString(), phases: run, rows
  }, null, 2))
  process.exit(bad.length ? 1 : 0)
}

main().catch(e => { console.log('[fatal] ' + e.stack); process.exit(1) })