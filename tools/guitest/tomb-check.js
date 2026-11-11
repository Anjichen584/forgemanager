// 回归：剑冢埋剑 → 查看 → 挑战自己（应被拒）→ 取回，验证「剑冢里的剑怎么拿回来 / 怎么用」
//
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node tomb-check.js

const net = require('net')
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'TombBot'
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

function rcon (command) {
  return new Promise((resolve) => {
    const sock = net.connect(RCON_PORT, HOST)
    let id = 0, buf = Buffer.alloc(0), authed = false
    const done = (t) => { try { sock.destroy() } catch (e) {} resolve(t) }
    const send = (type, body) => {
      const b = Buffer.from(body, 'utf8')
      const p = Buffer.alloc(14 + b.length)
      p.writeInt32LE(10 + b.length, 0); p.writeInt32LE(++id, 4); p.writeInt32LE(type, 8)
      b.copy(p, 12); p[12 + b.length] = 0; p[13 + b.length] = 0
      sock.write(p)
    }
    sock.setTimeout(5000)
    sock.on('timeout', () => done(''))
    sock.on('error', () => done(''))
    sock.on('connect', () => send(3, RCON_PASS))
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (len < 10 || buf.length < len + 4) return
        const body = buf.slice(12, len + 4 - 2).toString('utf8')
        buf = buf.slice(len + 4)
        if (!authed) { authed = true; send(2, command); continue }
        return done(body)
      }
    })
  })
}

const strip = (s) => s.replace(/§[0-9a-fk-or]/gi, '').replace(/\[Rcon\] /g, '')

async function main () {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, auth: 'offline' })
  const chat = []
  bot.on('message', (m) => { const s = m.toString().trim(); if (s) chat.push(s) })
  bot.on('error', (e) => console.log('[error] ' + e.message))
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('spawn 超时')), 90000)
    bot.once('spawn', () => { clearTimeout(t); res() })
  })
  await sleep(1500)
  await rcon('op ' + BOT)
  await rcon('gamemode creative ' + BOT)
  await rcon('minecraft:clear ' + BOT)
  await sleep(1200)

  const swords = () => bot.inventory.items().filter(i => /_sword$/.test(i.name))
  const say = (from) => chat.slice(from).map(strip).join(' | ')

  // 1. 发两把剑：一把埋，一把留着对比
  await rcon('zhujian give ' + BOT + ' iron LEGENDARY 88')
  await sleep(1500)
  console.log('[剑] 手上有 ' + swords().length + ' 把锻造剑')
  if (!swords().length) { console.log('[FAIL] 没拿到剑'); bot.quit(); return }

  // 2. 埋进剑冢
  let mark = chat.length
  await bot.equip(swords()[0], 'hand')
  await sleep(400)
  bot.chat('/zhujian tomb bury')
  await sleep(1500)
  const burySaid = say(mark)
  console.log('[埋剑] ' + burySaid)
  const idMatch = burySaid.match(/编号\s*([0-9A-Fa-f]{3,6})/)
  const id = idMatch ? idMatch[1].toUpperCase() : null
  console.log(id ? '[PASS] 拿到剑冢编号 ' + id : '[FAIL] 回话里没有编号')
  console.log(swords().length === 0
    ? '[PASS] 埋进去之后剑从手上消失了（不是复制一把出来）'
    : '[FAIL] 埋完手上还有剑，说明是复制')

  if (!id) { bot.quit(); return }

  // 3. info 看状态
  mark = chat.length
  bot.chat('/zhujian tomb info ' + id)
  await sleep(1200)
  console.log('[详情] ' + say(mark))

  // 4. 挑战自己的剑 → 应被拒
  mark = chat.length
  bot.chat('/zhujian tomb challenge ' + id)
  await sleep(1200)
  const selfChallenge = say(mark)
  console.log('[自己挑战] ' + selfChallenge)

  // 5. 取回
  mark = chat.length
  bot.chat('/zhujian tomb take ' + id)
  await sleep(1500)
  const takeSaid = say(mark)
  console.log('[取回] ' + takeSaid)
  const back = swords().length
  console.log(back >= 1
    ? '[PASS] 剑取回到手上（' + swords().map(s => s.name).join(', ') + '）'
    : '[FAIL] 取回后手上没有剑')

  // 6. 再取一次 → 应该提示没有这个编号（条目已消费）
  mark = chat.length
  bot.chat('/zhujian tomb take ' + id)
  await sleep(1200)
  const again = say(mark)
  console.log('[重复取回] ' + again)
  console.log(/没有编号/.test(again)
    ? '[PASS] 取回后条目已被消费，不会无限取'
    : '[FAIL] 同一条目还能再取，等于复制剑')

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })
