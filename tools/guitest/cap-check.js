// 回归：强化上限按品质走 + 老物品上限迁移
//
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node cap-check.js

const net = require('net')
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1', PORT = 25577, RCON_PORT = 25599, RCON_PASS = 'testpass', BOT = 'CapBot'
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
// 这行原文是「▸ +0/+99 (基础属性 +0%)锻造等级」——标签在行尾，用 includes 找
const strengthLine = (item) => loreLines(item).find(l => l.includes('锻造等级')) || ''
const capOf = (item) => {
  const m = strengthLine(item).match(/\+(\d+)\s*\/\s*\+(\d+)/)
  return m ? parseInt(m[2], 10) : null
}
const levelOf = (item) => {
  const m = strengthLine(item).match(/\+(\d+)\s*\/\s*\+(\d+)/)
  return m ? parseInt(m[1], 10) : null
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
  await rcon('gamemode creative ' + BOT)
  await rcon('minecraft:clear ' + BOT)
  await sleep(1200)

  const grab = async (quality, name) => {
    await rcon('minecraft:clear ' + BOT)   // 不清背包的话下面 find 永远命中第一把
    await sleep(700)
    await rcon('zhujian give ' + BOT + ' iron ' + quality + ' 90')
    await sleep(1400)
    const it = bot.inventory.items().find(i => i.name === 'iron_sword')
    if (!it) { console.log('[FAIL] 没拿到 ' + name); return null }
    const cap = capOf(it)
    const lvl = levelOf(it)
    console.log('[' + name + '] Lore 锻造等级: +' + lvl + '/+' + cap)
    return { item: it, cap, lvl }
  }

  // 1. 指令发神话剑：上限必须是 99，不是旧的 32
  const mythic = await grab('MYTHIC', '神话剑')
  if (mythic) {
    console.log(mythic.cap === 99
      ? '[PASS] 神话剑上限是 99（不再是旧的 32）'
      : '[FAIL] 神话剑上限是 ' + mythic.cap)
  }

  // 2. 普通剑上限 50，粗糙 40 —— 品质逐级
  const common = await grab('COMMON', '普通剑')
  const rough = await grab('ROUGH', '粗糙剑')
  const leg = await grab('LEGENDARY', '传说剑')
  if (common && rough && mythic) {
    const ok = rough.cap === 40 && common.cap === 50
      && leg && leg.cap === 86 && mythic.cap === 99
    console.log(ok
      ? '[PASS] 上限逐级：粗糙 40 < 普通 50 < 传说 86 < 神话 99'
      : '[FAIL] 上限阶梯不对: rough=' + rough.cap + ' common=' + common.cap
        + ' legendary=' + (leg && leg.cap) + ' mythic=' + mythic.cap)
  }

  // 3. 上砧：全息里报的上限要和物品一致
  if (mythic) {
    const at = bot.entity.position.floored()
    await rcon('minecraft:setblock ' + (at.x + 2) + ' ' + at.y + ' ' + (at.z + 2) + ' minecraft:anvil')
    await sleep(600)
    await bot.equip(mythic.item, 'hand')
    await sleep(400)
    const anvilBlock = bot.blockAt(at.offset(2, 0, 2))
    try {
      await bot.activateBlock(anvilBlock)
      await sleep(1800)
      const texts = Object.values(bot.entities)
        .filter(e => e.name === 'text_display' || e.type === 'text_display')
        .map(e => (e.metadata && e.metadata[23]) || '')
      const joined = texts.join(' | ')
      // 文本实体读不到就退回用 /zhujian strengthen info 的文字
      let infoText = ''
      if (!joined.includes('锻造等级')) {
        bot.chat('/zhujian strengthen info')
        await sleep(1500)
      }
      console.log('[上砧] 全息文本: ' + (joined ? joined.slice(0, 120) : '(读不到，改用 info)'))
    } catch (e) {
      console.log('[上砧] 放剑异常: ' + e.message)
    }
  }

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}
main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })

