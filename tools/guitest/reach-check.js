// 武器耐久回归：僵尸每刀拉回身边 + 最近实体瞄准 + 背包兜底读 PDC
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const rconlib = require('./rconlib')
const wait = ms => new Promise(r => setTimeout(r, ms))
function pub (it) {
  if (!it) return null
  const cd = (it.components || []).find(c => c.type === 'custom_data'); if (!cd) return null
  const s = nbt.simplify({ type: 'compound', value: cd.data.value })
  return s.PublicBukkitValues || {}
}
let bot
const dur = it => (pub(it) || {})['forgingmaster:durability']
const swordOf = () => (bot.heldItem && /sword/.test(bot.heldItem.name) ? bot.heldItem : bot.inventory.items().find(i => /sword/.test(i.name)))
;(async () => {
  const r = await rconlib.connect({ port: 25599, password: 'testpass' })
  bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  await new Promise((res, j) => { bot.once('spawn', res); setTimeout(() => j(new Error('timeout')), 45000) })
  await wait(3000)
  await r.send('minecraft:gamemode creative TestBot'); await wait(400)
  await r.send('minecraft:tp TestBot 200 70 200'); await wait(1300)
  await r.send('minecraft:clear TestBot'); await wait(400)
  await r.send('kill @e[type=zombie]'); await wait(400)
  await r.send('zhujian give TestBot netherite LEGENDARY 95'); await wait(1600)
  const sw = bot.inventory.items().find(i => /sword/.test(i.name))
  if (sw && sw.slot >= 36) { bot.setQuickBarSlot(sw.slot - 36); await wait(900) }
  await r.send('minecraft:gamemode survival TestBot'); await wait(600)
  const p = bot.entity.position
  await r.send(`summon zombie ${p.x + 1.2} ${Math.round(p.y)} ${p.z} {Tags:["durt"],NoAI:1b,PersistenceRequired:1b,Silent:1b}`)
  await wait(1500)
  await r.send('attribute @e[tag=durt,limit=1] minecraft:max_health base set 20000')
  await r.send('data merge entity @e[tag=durt,limit=1] {Health:20000f}')
  console.log('start=' + dur(swordOf()))
  for (let i = 0; i < 10; i++) {
    await r.send(`minecraft:tp @e[tag=durt,limit=1] ${p.x + 1.2} ${Math.round(p.y)} ${p.z}`)
    await wait(500)
    const z2 = bot.nearestEntity(e => e.name === 'zombie' && e.position.distanceTo(bot.entity.position) < 5)
    if (!z2) { console.log('hit' + (i + 1) + ': 僵尸不在 5 格内'); continue }
    const before = dur(swordOf())
    try { await bot.lookAt(z2.position.offset(0, 1.2, 0), true); bot.attack(z2) } catch (e) {}
    await wait(700)
    const it = swordOf(); console.log('hit' + (i + 1) + ': ' + before + ' -> ' + dur(it) + ' | item=' + (it && it.name) + ' hasCD=' + !!(it && (it.components || []).find(c => c.type === 'custom_data')))
  }
  r.close(); bot.quit(); process.exit(0)
})().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
