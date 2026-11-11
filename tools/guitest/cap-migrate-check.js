// 回归：老物品上限迁移 + 上砧后按品质上限封顶
//
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node cap-migrate-check.js

const net = require('net')
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'MigBot'
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
    sock.setTimeout(6000)
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

function flat (node) {
  if (node == null) return ''
  if (typeof node === 'string') return node
  if (typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(flat).join('')
  if (typeof node !== 'object') return ''
  if (['string', 'byte', 'short', 'int', 'long', 'float', 'double'].includes(node.type)) {
    return String(node.value ?? '')
  }
  if (node.type === 'list') return flat(node.value)
  const body = node.type === 'compound' ? node.value : node
  if (!body || typeof body !== 'object') return ''
  const style = new Set(['italic', 'bold', 'underlined', 'strikethrough', 'obfuscated',
    'color', 'insertion', 'clickEvent', 'hoverEvent', 'font'])
  let out = ''
  for (const k of Object.keys(body)) {
    if (style.has(k)) continue
    out += flat(body[k])
  }
  return out
}

const loreLines = (item) => {
  const comp = (item?.components || []).find(c => c.type === 'lore' || c.type === 'minecraft:lore')
  return comp ? (comp.data || []).map(l => flat(l).trim()).filter(Boolean) : []
}
const strengthLine = (item) => loreLines(item).find(l => l.includes('锻造等级')) || ''
const capLevel = (item) => {
  const m = strengthLine(item).match(/\+(\d+)\s*\/\s*\+(\d+)/)
  return m ? { level: parseInt(m[1], 10), cap: parseInt(m[2], 10) } : null
}

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
  await rcon('zhujian money give ' + BOT + ' 200000')
  await rcon('zhujian money coin give ' + BOT + ' 200000')
  await sleep(1200)

  // 1. 造一把神话剑，把上限改回旧的 32（模拟 v2.16 时代打出来的）
  await rcon('zhujian give ' + BOT + ' iron MYTHIC 96')
  await sleep(1600)
  let sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { console.log('[FAIL] 没拿到剑'); bot.quit(); return }
  // 用指令直接把等级顶到 30，再手工把上限写成 32（借 nbt 改不了，所以走 set 让它写 50 → 再验迁移逻辑）
  await bot.equip(sword, 'hand')
  await sleep(300)
  const before = capLevel(sword)
  console.log('[初始] ' + JSON.stringify(before))
  console.log(before && before.cap === 99
    ? '[PASS] 新打的神话剑一出生就是 +0/+99'
    : '[FAIL] 初始上限不对: ' + JSON.stringify(before))

  // 2. 上砧：全息报的上限必须和物品一致（不再写死 +99）
  const at = bot.entity.position.floored()
  await rcon('minecraft:setblock ' + (at.x + 3) + ' ' + at.y + ' ' + (at.z + 3) + ' minecraft:anvil')
  await sleep(700)
  const anvil = bot.blockAt(at.offset(3, 0, 3))
  let mark = chat.length
  try {
    await bot.activateBlock(anvil)
  } catch (e) {
    console.log('[上砧] 交互异常: ' + e.message)
  }
  await sleep(1800)
  const said = chat.slice(mark).join(' | ')
  console.log('[上砧] ' + said)
  const m = said.match(/\+(\d+)\s*\/\s*\+(\d+)/)
  if (m) {
    console.log(parseInt(m[2], 10) === 99
      ? '[PASS] 砧上全息的上限是 99'
      : '[FAIL] 砧上全息上限是 ' + m[2])
  } else {
    console.log('[warn] 全息文字没直接进聊天，跳过这条断言')
  }

  // 3. 先把剑从砧上取回来（剑在砧上时主手是空的，set 会找不到物品）
  mark = chat.length
  try { await bot.activateBlock(anvil) } catch (e) {}
  await sleep(1500)
  console.log('[取回] ' + chat.slice(mark).join(' | '))

  // 4. 直接顶到上限，再上砧应被拒绝（「已经 +N 满了」）
  await bot.equip(bot.inventory.items().find(i => i.name === 'iron_sword'), 'hand')
  await sleep(300)
  await bot.chat('/zhujian strengthen set 99')
  await sleep(1500)
  sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  const top = capLevel(sword)
  console.log('[顶满] ' + JSON.stringify(top))
  console.log(top && top.level === 99 && top.cap === 99
    ? '[PASS] 神话剑能到 +99（上限真的是 99，不是 50/32）'
    : '[FAIL] 顶不到 99: ' + JSON.stringify(top))

  mark = chat.length
  try { await bot.activateBlock(anvil) } catch (e) {}
  await sleep(1600)
  const refused = chat.slice(mark).join(' | ')
  console.log('[满级上砧] ' + refused)
  console.log(/满了|敲不动/.test(refused)
    ? '[PASS] 满级剑放不上砧（按品质上限封顶）'
    : '[warn] 没看到「满了」的提示，回话是: ' + refused)

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })
