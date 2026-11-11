// 护甲虚拟耐久实测：穿锻造甲 → AI 僵尸持续攻击 → 读四件 PDC 耐久
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
const dur = it => (pub(it) || {})['forgingmaster:durability']
const durs = b => [5, 6, 7, 8].map(s => { const i = b.inventory.slots[s]; return i ? i.name.replace('netherite_', '') + '=' + dur(i) : s + '=空' }).join(' ')
;(async () => {
  const r = await rconlib.connect({ port: 25599, password: 'testpass' })
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  bot.on('message', m => { const s = m.toString().trim(); if (s.includes('DBG')) console.log('  [chat] ' + s) })
  await new Promise((res, j) => { bot.once('spawn', res); setTimeout(() => j(new Error('timeout')), 45000) })
  await wait(3000)
  await r.send('minecraft:gamemode creative TestBot'); await wait(400)
  await r.send('minecraft:tp TestBot 200 70 200'); await wait(1300)
  await r.send('minecraft:clear TestBot'); await wait(400)
  await r.send('kill @e[type=zombie]'); await wait(400)
  await r.send('minecraft:gamemode survival TestBot'); await wait(600)
  for (const slot of ['helmet', 'chestplate', 'leggings', 'boots']) {
    await r.send(`zhujian give TestBot armor-netherite-${slot}`); await wait(1400)
  }
  const pieces = bot.inventory.items().filter(i => /netherite_(helmet|chestplate|leggings|boots)/.test(i.name))
  console.log('拿到 ' + pieces.length + ' 件甲: ' + pieces.map(p => p.name).join(','))
  const dest = { netherite_helmet: 'head', netherite_chestplate: 'torso', netherite_leggings: 'legs', netherite_boots: 'feet' }
  for (const it of pieces) {
    try { await bot.equip(it, dest[it.name]); await wait(600) } catch (e) { console.log('穿 ' + it.name + ' 失败: ' + e.message) }
  }
  await wait(1000)
  console.log('穿戴后: ' + durs(bot))
  const p = bot.entity.position
  await r.send(`summon zombie ${p.x + 3} ${Math.round(p.y)} ${p.z + 3} {Tags:["adurt"],PersistenceRequired:1b,Silent:1b}`)
  await wait(1500)
  await r.send('attribute @e[tag=adurt,limit=1] minecraft:max_health base set 99999')
  await r.send('data merge entity @e[tag=adurt,limit=1] {Health:99999f}')
  for (let t = 1; t <= 6; t++) {
    await wait(5000)
    console.log(`t+${t * 5}s: ${durs(bot)} | botHP=${Math.round(bot.health || 20)}`)
  }
  r.close(); bot.quit(); process.exit(0)
})().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
