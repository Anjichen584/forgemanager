// 回归：落锤演出（砧上浮起一把锤子 + 火花）+ 剑随等级变大 + 铁斧能当锤
//
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node hammer-anim-check.js

const net = require('net')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const HOST = '127.0.0.1'
const PORT = 25577
const RCON_PORT = 25599
const RCON_PASS = 'testpass'
const BOT = 'AnvilBot'
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
    sock.setTimeout(4000)
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

async function main() {
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
  await rcon('zhujian money coin give ' + BOT + ' 100000')
  await rcon('minecraft:give ' + BOT + ' iron_ingot 64')
  await rcon('minecraft:give ' + BOT + ' anvil 4')
  await rcon('zhujian give ' + BOT + ' iron common 60')
  await sleep(1800)

  // ---- 1. /zhujian hammer 发的是铁斧（门槛降低） ----
  bot.chat('/zhujian hammer')
  await sleep(1200)
  const hammer = bot.inventory.items().find(i => i.name === 'iron_axe' || i.name === 'mace')
  console.log('[锤子] 拿到的锤子: ' + (hammer ? hammer.name : '(没有)'))
  console.log(hammer ? (hammer.name === 'iron_axe'
    ? '[PASS] /zhujian hammer 发的是铁斧（不用再去打试炼密室拿重锤）'
    : '[WARN] 发的还是 ' + hammer.name + '，看下 config 里的 hammer.items') : '[FAIL] 没发出锤子')

  // ---- 2. 放砧、放剑 ----
  const sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (!sword) { console.log('[FAIL] 没拿到锻造剑'); bot.quit(); return }
  await bot.equip(sword, 'hand')
  await sleep(300)
  let anvil = bot.blockAt(bot.entity.position.offset(2, 0, 0))
  if (!anvil || anvil.name !== 'anvil') {
    const base = bot.blockAt(bot.entity.position.offset(2, -1, 0))
    const anvilItem = bot.inventory.items().find(i => i.name === 'anvil')
    await bot.equip(anvilItem, 'hand')
    await bot.placeBlock(base, new Vec3(0, 1, 0))
    await sleep(800)
    anvil = bot.blockAt(bot.entity.position.offset(2, 0, 0))
  }
  if (!anvil || anvil.name !== 'anvil') { console.log('[FAIL] 铁砧没就位'); bot.quit(); return }
  const at = anvil.position

  const displaysNear = () => Object.values(bot.entities).filter(e =>
    e.name === 'item_display' && e.position.distanceTo(at.offset(0.5, 1, 0.5)) < 3)
  // 演出的锤子是位置最高的那个（剑躺在 y+1.03，锤子从 2.05 砸下来）
  const topDisplay = () => {
    const list = displaysNear()
    if (!list.length) return null
    return list.reduce((a, b) => (b.position.y > a.position.y ? b : a))
  }

  await bot.equip(sword, 'hand')
  // 上一轮可能把剑留在砧上：右键是「取回」，再右键一次才是「放上」。最多试三次。
  for (let i = 0; i < 3; i++) {
    const before = chat.length
    await bot.activateBlock(anvil)
    await sleep(900)
    const said = chat.slice(before).join(' ')
    if (said.includes('搭上砧')) break
    if (!said.includes('取回')) break
    await bot.equip(sword, 'hand')
    await sleep(300)
  }
  const baseCount = displaysNear().length
  console.log('[砧上] 放剑后 item_display 数量 = ' + baseCount + '（1 = 只有剑）')

  // ---- 3. 拿铁斧落锤，采样演出期间锤子的坐标 ----
  const axe = bot.inventory.items().find(i => i.name === (hammer ? hammer.name : 'iron_axe'))
  await bot.equip(axe, 'hand')
  await sleep(400)
  let mark = chat.length
  bot._client.write('block_dig', { status: 0, location: at, face: 1, sequence: 0 })
  await sleep(120)
  bot._client.write('block_dig', { status: 1, location: at, face: 1, sequence: 0 })

  let peak = 0
  const track = []          // 锤子每一帧的坐标
  for (let i = 0; i < 90; i++) {   // 90 × 20ms = 1.8s，比 12 tick 的演出密得多
    const n = displaysNear().length
    peak = Math.max(peak, n)
    // 场上出现两件 display（剑 + 锤子）时，锤子一定是位置更高的那个
    if (n >= baseCount + 1) {
      const top = topDisplay()
      if (top) {
        track.push({
          x: +top.position.x.toFixed(3),
          y: +top.position.y.toFixed(3),
          z: +top.position.z.toFixed(3),
        })
      }
    }
    await sleep(20)
  }
  console.log('[演出] 实体数量峰值 = ' + peak + '，结束 = ' + displaysNear().length)
  console.log(peak >= baseCount + 1
    ? '[PASS] 落锤时砧上真的多出一把锤子'
    : '[FAIL] 没看到锤子实体（peak=' + peak + '）')
  console.log(displaysNear().length === baseCount
    ? '[PASS] 演出结束锤子收干净了'
    : '[FAIL] 演出结束后还留着 ' + (displaysNear().length - baseCount) + ' 个实体')

  // ---- 4. 锤子是不是竖着砸下来的：Y 单调下落、XZ 别乱飘 ----
  if (track.length >= 3) {
    const ys = track.map(p => p.y)
    const xs = track.map(p => p.x)
    const zs = track.map(p => p.z)
    console.log('[轨迹] Y: ' + ys.join(' → '))
    console.log('[轨迹] 帧数 = ' + ys.length)
    const highest = Math.max(...ys)
    const lowest = Math.min(...ys)
    const drop = highest - lowest
    const driftX = Math.max(...xs) - Math.min(...xs)
    const driftZ = Math.max(...zs) - Math.min(...zs)
    console.log('[轨迹] 落差 = ' + drop.toFixed(3) + ' 格，最高点 ' + highest.toFixed(3)
      + '，最低点 ' + lowest.toFixed(3) + '，X 漂移 ' + driftX.toFixed(3) + '，Z 漂移 ' + driftZ.toFixed(3))
    console.log(drop >= 0.5
      ? '[PASS] 落差够大（' + drop.toFixed(2) + ' 格），看得出来是「举起 → 砸下」'
      : '[FAIL] 落差只有 ' + drop.toFixed(2) + ' 格，像是原地抖')
    console.log(driftX < 0.05 && driftZ < 0.05
      ? '[PASS] 水平位置几乎不动（竖直下落）'
      : '[FAIL] 水平漂移过大 X=' + driftX.toFixed(3) + ' Z=' + driftZ.toFixed(3) + '，在平面里乱扭')
    // 客户端采样有插值滞后，只做一个弱断言：至少有一帧贴近砧面附近，说明确实落下来了
    const nearAnvil = lowest - at.y <= 1.35
    console.log(nearAnvil
      ? '[PASS] 最低点贴到砧面附近（' + (lowest - at.y).toFixed(2) + ' 格高）'
      : '[FAIL] 最低点离砧面还有 ' + (lowest - at.y).toFixed(2) + ' 格，没砸到砧上')
  } else {
    console.log('[FAIL] 没采到锤子轨迹（track=' + track.length + ' 帧）')
  }

  const said = chat.slice(mark).join(' | ')
  console.log('[回话] ' + said)

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}

main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })