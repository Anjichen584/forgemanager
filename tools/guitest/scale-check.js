// 回归：剑随强化等级变大（看得见）+ 落锤时剑不再缩放抖动
//
// 用法：node scale-check.js
const net = require('net')
const mineflayer = require('mineflayer')
const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'ScaleBot2'
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

// 从 ItemDisplay 的实体元数据里取缩放：index 12 = transformation（含缩放）
function displayScale (entity) {
  const md = entity.metadata
  if (!md) return null
  for (const entry of md) {
    if (!entry || typeof entry !== 'object') continue
    const v = entry.value
    if (v && typeof v === 'object' && v.scale && typeof v.scale.x === 'number') {
      return v.scale.x
    }
    if (v && typeof v === 'object' && v.translation && v.scale) {
      return v.scale.x
    }
  }
  return null
}

const swordDisplays = (bot) => Object.values(bot.entities)
  .filter(e => e && (e.name === 'item_display' || e.type === 'item_display'))
  .filter(e => {
    const v = displayScale(e)
    return v !== null && v > 0
  })

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
  await sleep(800)

  const at = bot.entity.position.floored()
  const rx = 2 + (Date.now() % 3)
  const rz = 2 + (Date.now() % 3)
  await rcon('minecraft:setblock ' + (at.x + rx) + ' ' + at.y + ' ' + (at.z + rz) + ' minecraft:anvil')
  await sleep(700)
  const anvil = bot.blockAt(at.offset(rx, 0, rz))

  // 普通剑（上限 50），先 +0 放上去量一次
  await rcon('zhujian give ' + BOT + ' iron COMMON 60')
  await sleep(1500)
  const sword = bot.inventory.items().find(i => i.name === 'iron_sword')
  if (!sword) { console.log('[FAIL] 没拿到剑'); bot.quit(); return }
  await bot.equip(sword, 'hand')
  await sleep(300)
  await bot.activateBlock(anvil)
  await sleep(1800)
  let marks = chat.length
  console.log('[上砧] ' + chat.slice(marks - 1).join(' | '))

  const readScale = async (label) => {
    await sleep(400)
    const list = swordDisplays(bot)
    if (!list.length) { console.log('[warn] ' + label + '：读不到剑实体缩放'); return null }
    const s = Math.max(...list.map(e => displayScale(e)))
    console.log('[缩放] ' + label + ' = ' + s.toFixed(3))
    return s
  }

  const at0 = await readScale('+0')

  // 取回 → 直接写到 +5（门槛）→ 再上砧，看是不是明显变大
  await bot.activateBlock(anvil)
  await sleep(1500)
  await bot.equip(bot.inventory.items().find(i => i.name === 'iron_sword'), 'hand')
  await sleep(300)
  await bot.chat('/zhujian strengthen set 5')
  await sleep(1500)
  marks = chat.length
  await bot.activateBlock(anvil)
  await sleep(1800)
  const board5 = chat.slice(marks).join(' | ')
  console.log('[+5 上砧] ' + board5)
  const at5 = await readScale('+5')

  if (at0 && at5) {
    const growth = at5 / at0
    console.log(growth >= 1.10
      ? '[PASS] +5 的剑比 +0 大 ' + ((growth - 1) * 100).toFixed(1) + '%（看得出变大）'
      : '[FAIL] +5 只大 ' + ((growth - 1) * 100).toFixed(1) + '%（老线性曲线就是这毛病）')
    console.log(board5.includes('剑身')
      ? '[PASS] 全息写了剑身倍率'
      : '[FAIL] 全息没写剑身倍率: ' + board5)
  }

  // ---- 落锤：采 30 帧，看剑的缩放有没有抖动 ----
  bot.chat('/zhujian hammer')
  await sleep(1200)
  await bot.equip(bot.inventory.items().find(i =>
    ['iron_axe', 'mace', 'stone_axe', 'iron_pickaxe'].includes(i.name)), 'hand')
  await sleep(300)

  const samples = []
  const hammer = new Promise((resolve) => {
    let n = 0
    const iv = setInterval(() => {
      const s = swordDisplays(bot).map(e => displayScale(e))
      if (s.length) samples.push(Math.max(...s))
      if (++n >= 34) { clearInterval(iv); resolve() }
    }, 40)
  })
  bot._client.write('block_dig', { status: 0, location: anvil.position, face: 1, sequence: 0 })
  await sleep(60)
  bot._client.write('block_dig', { status: 1, location: anvil.position, face: 1, sequence: 0 })
  await hammer
  if (samples.length >= 5) {
    const min = Math.min(...samples), max = Math.max(...samples)
    console.log('[落锤期间缩放] 采样 ' + samples.length + ' 帧，min=' + min.toFixed(3)
      + ' max=' + max.toFixed(3) + ' 波动=' + (max - min).toFixed(3))
    console.log((max - min) < 0.02
      ? '[PASS] 落锤时剑没再缩放抖动（旧的 1.06 脉冲已删）'
      : '[FAIL] 剑还在抖: ' + samples.map(v => v.toFixed(2)).join(' '))
  } else {
    console.log('[warn] 没采到缩放样本')
  }

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })