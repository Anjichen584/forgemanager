// 回归：管理员发装备 / 发宝石 / 宝石台五点流程
//
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node gem-give-check.js

const net = require('net')
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1'
const PORT = 25577
const RCON_PORT = 25599
const RCON_PASS = 'testpass'
const BOT = 'GemBot'
const sleep = (ms) => new Promise(r => setTimeout(r, ms))

function rcon(command) {
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

// 从 mineflayer 的 typed NBT lore 里抠出纯文本。
// 1.21 的结构是：组件 {type:'lore', data:[{type:'compound', value:{extra:{type:'list', value:[...]}}}]}
// 里面每一层字段本身就是 typed node（{type:'string', value:'…'}），别再包一层 compound。
function flat (node) {
  if (node == null) return ''
  if (typeof node === 'string') return node
  if (typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(flat).join('')
  if (typeof node !== 'object') return ''
  if (node.type === 'string' || node.type === 'byte' || node.type === 'short'
      || node.type === 'int' || node.type === 'long' || node.type === 'float'
      || node.type === 'double') {
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

function loreLines (item) {
  const comp = (item?.components || []).find(c => c.type === 'lore' || c.type === 'minecraft:lore')
  if (!comp) return []
  return (comp.data || []).map(l => flat(l).trim()).filter(Boolean)
}

function nameOf (item) {
  const comp = (item?.components || []).find(c => c.type === 'custom_name' || c.type === 'minecraft:custom_name')
  return comp ? flat(comp.data).trim() : ''
}

const isGem = (item) => loreLines(item).some(l => l.includes('锻造宝石'))

async function main () {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, auth: 'offline' })
  const chat = []
  bot.on('message', (m) => { const s = m.toString().trim(); if (s) { chat.push(s); console.log('[chat] ' + s) } })
  bot.on('error', (e) => console.log('[error] ' + e.message))

  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('spawn 超时')), 90000)
    bot.once('spawn', () => { clearTimeout(t); res() })
  })
  console.log('[bot] spawned as ' + bot.username)
  await sleep(1500)

  await rcon('op ' + BOT)
  await rcon('gamemode creative ' + BOT)
  await rcon('minecraft:clear ' + BOT)
  await sleep(1200)

  const inv = () => bot.inventory.items()
  const gems = () => inv().filter(isGem)

  // ---- 1. 发装备：armor-<剑胚>-<部位> ----
  await rcon('zhujian give ' + BOT + ' armor-iron-chestplate LEGENDARY 90')
  await sleep(1500)
  const chest = inv().find(i => i.name === 'iron_chestplate')
  console.log('[装备] 手上拿到: ' + (chest ? chest.name : '(没有)'))
  if (chest) {
    const lines = loreLines(chest)
    console.log('[装备] Lore: ' + lines.join(' | '))
    console.log(lines.some(l => l.includes('锻造')) || lines.some(l => l.includes('品质'))
      ? '[PASS] 发出来的装备带锻造品质 Lore（是真锻造品，不是原版铁胸甲）'
      : '[FAIL] 装备没有锻造 Lore，可能发成了原版物品')
  } else {
    console.log('[FAIL] armor-iron-chestplate 没发出来')
  }

  // ---- 2. 发宝石：gem give ----
  await rcon('zhujian gem give ' + BOT + ' attack L5 3')
  await sleep(1500)
  const got = gems()
  console.log('[宝石] 背包里认出 ' + got.length + ' 件宝石物品（'
    + inv().map(i => i.name).join(', ') + '）')
  const gem = got[0]
  if (gem) {
    const lines = loreLines(gem)
    console.log('[宝石] 名称: ' + nameOf(gem) + '（底材 ' + gem.name + '）')
    console.log('[宝石] Lore: ' + lines.join(' | '))
    console.log(lines.some(l => l.includes('锻造宝石'))
      ? '[PASS] 发出来的是宝石物品（Lore 带「锻造宝石」）'
      : '[FAIL] 物品没有宝石标记')
    console.log(lines.some(l => /Lv\.5/.test(l))
      ? '[PASS] 等级是 Lv.5'
      : '[FAIL] 等级不对: ' + lines.join(' | '))
    console.log(got.length >= 3
      ? '[PASS] 数量 3 颗发足了'
      : '[FAIL] 只发到 ' + got.length + ' 颗')
  } else {
    console.log('[FAIL] gem give 没发出宝石')
  }

  // ---- 3. 发随机宝石给 all ----
  await rcon('minecraft:clear ' + BOT)
  await sleep(800)
  await rcon('zhujian gem give all random L3 1')
  await sleep(1500)
  const anyGem = gems()[0]
  console.log(anyGem
    ? '[PASS] gem give all random 也能发（' + nameOf(anyGem) + '）'
    : '[FAIL] gem give all random 没发出来')

  // ---- 4. 开孔 + 镶嵌整条流程 ----
  await rcon('minecraft:clear ' + BOT)
  await sleep(600)
  await rcon('zhujian give ' + BOT + ' armor-iron-chestplate LEGENDARY 90')
  await rcon('zhujian gem give ' + BOT + ' attack L5 1')
  await rcon('minecraft:give ' + BOT + ' iron_block 16')   // 开孔要 2 个铁块
  await rcon('zhujian money give ' + BOT + ' 50000')       // 开孔费走外部经济（Vault/CMI）
  await rcon('zhujian money coin give ' + BOT + ' 50000')  // 剑币也补一点，商店里能花
  await sleep(1600)
  const gear = inv().find(i => i.name === 'iron_chestplate')
  const g2 = gems()[0]
  if (gear && g2) {
    let mark = chat.length
    await bot.equip(gear, 'hand')
    await sleep(400)
    bot.chat('/zhujian gem socket')
    await sleep(2000)
    const socketSaid = chat.slice(mark).join(' | ')
    console.log('[开孔] ' + socketSaid)
    console.log(/孔|成功|开启/.test(socketSaid)
      ? '[PASS] 开孔指令有回话（' +/(开|孔)/.test(socketSaid) + '）'
      : '[FAIL] 开孔没反应')

    const after = bot.inventory.items().find(i => i.name === 'iron_chestplate')
    const sockets = loreLines(after).filter(l => /孔|◇|◈/.test(l)).join(' | ')
    console.log('[开孔] Lore 孔位: ' + (sockets || '(没看到孔位行)'))
    console.log(socketSaid.includes('开') && !socketSaid.includes('需要')
      ? '[PASS] 开孔成功'
      : '[FAIL] 开孔没成功: ' + socketSaid)

    mark = chat.length
    await bot.equip(after ?? gear, 'hand')
    await sleep(300)
    await bot.equip(g2, 'off-hand')
    await sleep(300)
    bot.chat('/zhujian gem inlay')
    await sleep(2000)
    const inlaySaid = chat.slice(mark).join(' | ')
    console.log('[镶嵌] ' + inlaySaid)
    console.log(inlaySaid.includes('镶') || inlaySaid.includes('孔')
      ? '[PASS] 镶嵌指令有回话'
      : '[FAIL] 镶嵌没反应')
    const finalGear = bot.inventory.items().find(i => i.name === 'iron_chestplate')
    const gemLine = loreLines(finalGear).filter(l => /赤玉/.test(l)).join(' | ')
    console.log('[镶嵌] 镶嵌后 Lore 里的宝石行: ' + (gemLine || '(没有)'))
    console.log(gemLine && inlaySaid.includes('镶')
      ? '[PASS] 发出来的宝石真能镶进发出来的装备，整条链通了'
      : '[FAIL] 镶嵌后 Lore 里看不到赤玉')
  } else {
    console.log('[FAIL] 开孔测试缺素材 gear=' + !!gear + ' gem=' + !!g2)
  }

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}

main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })