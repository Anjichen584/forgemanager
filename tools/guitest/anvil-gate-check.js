// 回归：强化到当前上限（门槛）后再落锤，必须被拦下并让玩家先蜕变
//
// 流程就是玩家真操作：放剑上砧 → 拿重锤左键落锤。
// 用法：测试服开着（25577 / rcon 25599），
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node anvil-gate-check.js

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
  bot.on('kicked', (r) => console.log('[kicked] ' + r))

  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('spawn 超时')), 90000)
    bot.once('spawn', () => { clearTimeout(t); res() })
  })
  console.log('[bot] spawned as ' + bot.username)
  await sleep(1500)

  await rcon('op ' + BOT)
  await rcon('gamemode creative ' + BOT)
  await rcon('minecraft:clear ' + BOT)
  await rcon('zhujian give ' + BOT + ' iron common 60')
  await sleep(1500)

  const sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (!sword) { console.log('[fail] 没拿到锻造剑'); bot.quit(); return }
  await bot.equip(sword, 'hand')
  bot.chat('/zhujian strengthen set 5')
  await sleep(1000)

  // 锤子 + 铁砧
  bot.chat('/zhujian hammer')
  await sleep(800)
  bot.chat('/minecraft:give @s anvil 4')
  await sleep(800)
  const anvilItem = bot.inventory.items().find(i => i.name === 'anvil')
  // 锤子默认是铁斧（hammer.items 第一项），老配置也可能是重锤；都认
  const hammer = bot.inventory.items().find(i => ['iron_axe', 'mace', 'stone_axe', 'iron_pickaxe'].includes(i.name))
  if (!anvilItem || !hammer) {
    console.log('[fail] 素材不全：anvil=' + !!anvilItem + ' hammer=' + !!hammer)
    bot.quit(); return
  }

  // 在身边放一个铁砧（上次跑留下的砧子直接复用，别跟它抢位置）
  const base = bot.blockAt(bot.entity.position.offset(2, -1, 0))
  let anvil = bot.blockAt(bot.entity.position.offset(2, 0, 0))
  if (anvil && anvil.name === 'anvil') {
    console.log('[bot] 复用已有铁砧 ' + anvil.position)
  } else {
    if (!base || !base.boundingBox || base.boundingBox === 'empty') {
      console.log('[fail] 脚下没有可放方块的地面')
      bot.quit(); return
    }
    await bot.equip(anvilItem, 'hand')
    await bot.placeBlock(base, new Vec3(0, 1, 0))
    await sleep(900)
  }
  // 先把可能残留的剑取回来，免得「砧上已经有剑」把这一轮顶掉
  await bot.equip(sword, 'hand')
  await bot.activateBlock(bot.blockAt(bot.entity.position.offset(2, 0, 0)))
  await sleep(900)
  anvil = bot.blockAt(bot.entity.position.offset(2, 0, 0))
  if (!anvil || anvil.name !== 'anvil') {
    console.log('[fail] 铁砧没就位：' + (anvil && anvil.name))
    bot.quit(); return
  }
  console.log('[bot] 铁砧已就位 ' + anvil.position)

  // 放剑上砧
  await bot.equip(sword, 'hand')
  await sleep(400)
  let mark = chat.length
  await bot.activateBlock(anvil)
  await sleep(1200)
  console.log('[放剑] ' + chat.slice(mark).join(' | '))

  // 拿锤落一锤（左键；直接发 block_dig，避免真把砧挖了）
  await bot.equip(hammer, 'hand')
  await sleep(400)
  mark = chat.length
  try {
    bot._client.write('block_dig', { status: 0, location: anvil.position, face: 1, sequence: 0 })
    await sleep(150)
    bot._client.write('block_dig', { status: 1, location: anvil.position, face: 1, sequence: 0 })
  } catch (e) {
    console.log('[warn] block_dig 发不出去，改用 swingArm：' + e.message)
    bot.swingArm('right')
  }
  await sleep(2000)
  const said = chat.slice(mark).join(' | ')
  console.log('[落锤] ' + said)
  const blocked = /强化上限/.test(said) && /evolve/.test(said)
  console.log(blocked ? '[PASS] 到上限后落锤被拦，并指向蜕变'
    : /强化成功|成功/.test(said) ? '[FAIL] 到上限了居然还能继续锻'
    : '[FAIL] 没看到拦下的提示：' + said)

  bot.quit()
  await sleep(400)
  console.log('[done]')
  process.exit(0)
}

main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })