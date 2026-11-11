// 回归：强化上限 = 品质上限，但中途要过 6 道蜕变门槛
//
// 用法：node cap-full-check.js
const net = require('net')
const mineflayer = require('mineflayer')
const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'FullBot'
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
  for (const k of Object.keys(body)) { if (style.has(k)) continue; out += flat(body[k]) }
  return out
}
const loreLines = (item) => {
  const comp = (item?.components || []).find(c => c.type === 'lore' || c.type === 'minecraft:lore')
  return comp ? (comp.data || []).map(l => flat(l).trim()).filter(Boolean) : []
}
const capLevel = (item) => {
  const line = loreLines(item).find(l => l.includes('锻造等级')) || ''
  const m = line.match(/\+(\d+)\s*\/\s*\+(\d+)/)
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
  await rcon('zhujian money give ' + BOT + ' 500000')
  await rcon('zhujian money coin give ' + BOT + ' 500000')
  await rcon('minecraft:give ' + BOT + ' iron_ingot 64')
  await sleep(1000)

  const at = bot.entity.position.floored()
  // 每次换一块新砧位：老砧位可能残留上一轮的会话，撞上去就是「取回」而不是「放剑」
  const rx = 2 + (Date.now() % 3)
  const rz = -2 - (Date.now() % 3)
  await rcon('minecraft:setblock ' + (at.x + rx) + ' ' + at.y + ' ' + (at.z + rz) + ' minecraft:anvil')
  await sleep(600)
  const anvil = bot.blockAt(at.offset(rx, 0, rz))
  const target = anvil.position.offset(0.5, 1.0, 0.5)
  // 上一轮可能留了剑在砧上：先右键两下清空（取回 / 再取回都不会报错）
  for (let i = 0; i < 2; i++) {
    try { await bot.activateBlock(anvil) } catch (e) {}
    await sleep(900)
  }

  const strike = async (times, mark) => {
    for (let i = 0; i < times; i++) {
      await bot.lookAt(target)
      await sleep(120)
      bot._client.write('block_dig', { status: 0, location: anvil.position, face: 1, sequence: 0 })
      await sleep(820)
    }
    await sleep(900)
    return chat.slice(mark)
  }

  // 1. 普通剑（上限 50）打到第 1 阶门槛 +5 —— 先摆到 +4 再敲
  await rcon('zhujian give ' + BOT + ' iron COMMON 60')
  await sleep(1500)
  let sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  await bot.equip(sword, 'hand')
  await sleep(300)
  await bot.chat('/zhujian strengthen set 4')
  await sleep(1300)
  let mark = chat.length
  await bot.activateBlock(anvil)
  await sleep(1600)
  const placed = chat.slice(mark).join(' | ')
  console.log('[上砧] ' + placed)
  console.log(placed.includes('本阶门槛 +5')
    ? '[PASS] 全息提前告诉玩家这一阶只能到 +5'
    : '[FAIL] 全息没写本阶门槛: ' + placed)

  // 领锤子开敲
  bot.chat('/zhujian hammer')
  await sleep(1200)
  const hammer = bot.inventory.items().find(i =>
    ['iron_axe', 'mace', 'stone_axe', 'iron_pickaxe'].includes(i.name))
  await bot.equip(hammer, 'hand')
  await sleep(300)

  mark = chat.length
  const log1 = await strike(6, mark)
  const hitGate = log1.some(l => l.includes('已到这一阶的强化上限 +5'))
  console.log('[敲 6 下 原始] ' + log1.slice(-6).join(' | '))
  console.log(hitGate ? '[PASS] 敲到 +5 就被门槛拦住' : '[WARN] 没撞到门槛（可能已在 +5）')

  // 取回看等级
  await bot.activateBlock(anvil)
  await sleep(1500)
  sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  const st1 = capLevel(sword)
  console.log('[门槛处] ' + JSON.stringify(st1))
  console.log(st1 && st1.level === 5 && st1.cap === 50
    ? '[PASS] 停在 +5/+50：门槛生效、品质上限仍然是 50'
    : '[FAIL] 门槛处等级/上限不对: ' + JSON.stringify(st1))

  // 2. 蜕变一阶后，能否继续往上走（先塞够剑币，蜕变要钱）
  await rcon('zhujian money coin give ' + BOT + ' 200000')
  await sleep(600)
  mark = chat.length
  await bot.equip(sword, 'hand')
  await sleep(300)
  bot.chat('/zhujian evolve now')
  await sleep(2500)
  const evo = chat.slice(mark)
  console.log('[蜕变] ' + evo.filter(l => l.includes('蜕变') || l.includes('阶') || l.includes('成功')).slice(-3).join(' | '))

  sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  const st2 = capLevel(sword)
  console.log('[蜕变后] ' + JSON.stringify(st2))
  const evoLine = loreLines(sword).find(l => l.startsWith('锻阶') || l.includes('锻阶')) || ''
  console.log('[锻阶行] ' + evoLine)

  // 3. 再上砧敲，能过 +5 就说明门槛真的解锁了
  mark = chat.length
  await bot.activateBlock(anvil)
  await sleep(1500)
  console.log('[二次上砧] ' + chat.slice(mark).join(' | '))

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })



