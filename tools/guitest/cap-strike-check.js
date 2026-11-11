// 回归：在铁砧上真的敲，普通剑敲到 +50 就停，神话剑还能继续
//
// 用法：node cap-strike-check.js
const net = require('net')
const mineflayer = require('mineflayer')
const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'StrikeBot'
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
  bot.on('message', (m) => { const s = m.toString().trim(); if (s) { chat.push(s); console.log('[chat] ' + s) } })
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
  await sleep(1200)

  // 普通剑，先写到 +49，再锤一锤看会不会越过 50
  await rcon('zhujian give ' + BOT + ' iron COMMON 60')
  await sleep(1500)
  let sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { console.log('[FAIL] 没拿到剑'); bot.quit(); return }
  await bot.equip(sword, 'hand')
  await sleep(300)
  await bot.chat('/zhujian strengthen set 49')
  await sleep(1400)
  sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  console.log('[铺垫] ' + JSON.stringify(capLevel(sword)))

  const at = bot.entity.position.floored()
  await rcon('minecraft:setblock ' + (at.x + 4) + ' ' + at.y + ' ' + (at.z + 4) + ' minecraft:anvil')
  await sleep(700)
  const anvil = bot.blockAt(at.offset(4, 0, 4))
  await bot.activateBlock(anvil)
  await sleep(1600)

  // 领锤子，左键砸几下
  bot.chat('/zhujian hammer')
  await sleep(1200)
  const hammer = bot.inventory.items().find(i =>
    ['iron_axe', 'mace', 'stone_axe', 'iron_pickaxe'].includes(i.name))
  if (!hammer) { console.log('[FAIL] 没领到锤子'); bot.quit(); return }
  await bot.equip(hammer, 'hand')
  await sleep(400)

  let hits = 0
  const target = anvil.position.offset(0.5, 1.0, 0.5)
  for (let i = 0; i < 8; i++) {
    await bot.lookAt(target)
    await sleep(150)
    // 对着砧子左键：触发 placeForge 的落锤分支
    bot._client.write('block_dig', { status: 0, location: anvil.position, face: 1, sequence: 0 })
    await sleep(950)
    hits++
  }
  await sleep(1500)
  const after = chat.filter(c => /强化|成功|大成|损伤|空锤|碎了|满了/.test(c)).slice(-6)
  console.log('[砸 ' + hits + ' 下] ' + after.join(' | '))

  // 从砧上取回，看等级有没有越过 50
  await bot.activateBlock(anvil)
  await sleep(1600)
  sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  const st = capLevel(sword)
  console.log('[结果] ' + JSON.stringify(st))
  console.log(st && st.cap === 50
    ? '[PASS] 普通剑上限还是 50'
    : '[FAIL] 普通剑上限不对: ' + JSON.stringify(st))
  console.log(st && st.level <= 50
    ? '[PASS] 等级没越过上限（' + st.level + ' ≤ 50）'
    : '[FAIL] 等级越界: ' + JSON.stringify(st))

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })

