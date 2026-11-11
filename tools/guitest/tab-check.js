// Tab 补全回归：把每条命令的每一层都问服务器一遍，看候选是否为预期
//
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node tab-check.js

const net = require('net')
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'TabBot'
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

async function main () {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, auth: 'offline' })
  bot.on('error', (e) => console.log('[error] ' + e.message))
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('spawn 超时')), 90000)
    bot.once('spawn', () => { clearTimeout(t); res() })
  })
  await sleep(1500)
  await rcon('op ' + BOT)
  await rcon('minecraft:clear ' + BOT)
  await rcon('zhujian give ' + BOT + ' iron LEGENDARY 90')
  await sleep(1500)
  const sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (sword) {
    await bot.equip(sword, 'hand')
    await sleep(400)
    bot.chat('/zhujian tomb bury')   // 造一条剑冢记录，好测 /zhujian tomb take 的编号补全
    await sleep(1800)
  }

  const cases = [
    ['/zhujian ', ['money', 'gem', 'tomb', 'forge', 'event', 'backup', 'restore', 'give']],
    ['/zhujian money ', ['balance', 'give', 'take', 'coin']],
    ['/zhujian money coin ', ['balance', 'give', 'take']],
    ['/zhujian money coin give TabBot ', ['100', '1000']],
    ['/zhujian gem ', ['socket', 'smelt', 'inlay', 'give', 'list', 'extract']],
    ['/zhujian gem give all ', ['random', 'attack', 'crit']],
    ['/zhujian gem give all attack ', ['L1', 'L5']],
    ['/zhujian tomb ', ['list', 'bury', 'take', 'challenge', 'info', 'remove']],
    ['/zhujian tomb take ', null],                      // 只要非空即可（编号是随机的）
    ['/zhujian strengthen ', ['info', 'sim', 'set', 'reforge', 'strip', 'graft']],
    ['/zhujian strengthen sim ', ['10', '20', '99']],
    ['/zhujian grade give ', ['iron_ingot']],
    ['/zhujian grade give iron_ingot ', ['COMMON', 'REFINED', 'CELESTIAL']],
    ['/zhujian event ', ['status', 'start', 'stop']],
    ['/zhujian event start ', null],
    ['/zhujian talent ', ['add', 'reset']],
    ['/zhujian talent add ', null],
    ['/zhujian restore ', null],
    ['/zhujian auction ', ['browse', 'sell', 'bid', 'buy', 'cancel', 'claim']],
    ['/zhujian give ', ['TabBot']],
    ['/zhujian give TabBot ', ['iron', 'gold', 'armor-iron-chestplate']],
    ['/zhujian give TabBot iron ', ['ROUGH', 'LEGENDARY', 'MYTHIC']],
    ['/zhujian enchant ', ['extract']],
    ['/zhujian evolve ', ['sim', 'now']],
    ['/zhujian contract ', ['join', 'quit', 'deposit']],
    ['/zhujian rank ', ['coins', 'reputation', 'streak', 'level', 'text']],
    ['/zhujian forge ', ['risk', 'cancel']],
    ['/zhujian halloffame ', ['set', 'clear']]
  ]

  let pass = 0, fail = 0
  for (const [input, expect] of cases) {
    let got = null
    for (let attempt = 0; attempt < 3 && got === null; attempt++) {
      try {
        const raw = await bot.tabComplete(input, false, false, 6000)
        got = raw.map(x => (typeof x === 'string' ? x : x.match)).filter(Boolean)
      } catch (e) {
        await sleep(500)
      }
    }
    if (got === null) { console.log('[FAIL] ' + JSON.stringify(input) + ' → 服务器没回 tab_complete'); fail++; continue }
    const label = JSON.stringify(input)
    if (expect === null) {
      const ok = got.length > 0
      console.log((ok ? '[PASS] ' : '[FAIL] ') + label + ' → ' + JSON.stringify(got.slice(0, 8))
        + (got.length > 8 ? ' …共 ' + got.length : ''))
      ok ? pass++ : fail++
      await sleep(350)
      continue
    }
    const missing = expect.filter(e => !got.includes(e))
    const ok = got.length > 0 && missing.length === 0
    console.log((ok ? '[PASS] ' : '[FAIL] ') + label + ' → ' + JSON.stringify(got.slice(0, 10))
      + (got.length > 10 ? ' …共 ' + got.length : '')
      + (missing.length ? ' 缺: ' + JSON.stringify(missing) : ''))
    ok ? pass++ : fail++
    await sleep(350)
  }

  console.log('[汇总] PASS ' + pass + ' / FAIL ' + fail)
  bot.quit()
  await sleep(400)
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })
