const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')
const rconlib = require('./rconlib')
const wait = ms => new Promise(r => setTimeout(r, ms))
const clean = s => (s || '').replace(/§./g, '').trim()
function pub (it) {
  if (!it) return null
  const cd = (it.components || []).find(c => c.type === 'custom_data'); if (!cd) return null
  const s = nbt.simplify({ type: 'compound', value: cd.data.value })
  return s.PublicBukkitValues || {}
}
const dur = it => (pub(it) || {})['forgingmaster:durability']
function lore (it) {
  if (!it) return []
  return (it.customLore || it.lore || []).map(e => { try { const c = nbt.simplify({ type: 'compound', value: e.value }); const t = []; (function w (x) { if (!x) return; if (typeof x === 'string') { t.push(x); return } if (x.text) t.push(x.text); if (x.extra) x.extra.forEach(w) })(c); return t.join('') } catch (err) { return '' } })
}
const durLine = it => (lore(it).find(x => x.includes('耐久')) || '(无)').replace(/[▰▱]/g, '').trim()
const swordOf = bot => (bot.heldItem && /sword/.test(bot.heldItem.name) ? bot.heldItem : bot.inventory.items().find(i => /sword/.test(i.name)))
;(async () => {
  const r = await rconlib.connect({ port: 25599, password: 'testpass' })
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  bot.on('error', e => console.log('ERR ' + e.message))
  await new Promise((res, j) => { bot.once('spawn', res); setTimeout(() => j(new Error('timeout')), 45000) })
  await wait(3000)
  await r.send('minecraft:gamemode creative TestBot'); await wait(400)
  await r.send('minecraft:tp TestBot 200 70 200'); await wait(1300)
  await r.send('minecraft:clear TestBot'); await wait(400)
  await r.send('zhujian give TestBot netherite LEGENDARY 95'); await wait(1600)
  const sw = bot.inventory.items().find(i => /sword/.test(i.name))
  if (sw && sw.slot >= 36) { bot.setQuickBarSlot(sw.slot - 36); await wait(900) }
  console.log('start dur=' + dur(swordOf(bot)) + ' | ' + durLine(swordOf(bot)))
  await r.send('minecraft:gamemode survival TestBot'); await wait(600)
  const p = bot.entity.position
  await r.send(`summon zombie ${p.x + 1.5} ${Math.round(p.y)} ${p.z} {Tags:["durt"],NoAI:1b,PersistenceRequired:1b,Silent:1b}`)
  await wait(1500)
  await r.send('attribute @e[tag=durt,limit=1] minecraft:max_health base set 20000')
  await r.send('attribute @e[tag=durt,limit=1] minecraft:knockback_resistance base set 1')
  await r.send('data merge entity @e[tag=durt,limit=1] {Health:20000f}')
  const z = Object.values(bot.entities).filter(e => e.name === 'zombie').sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position))[0]
  for (let i = 0; i < 12; i++) {
    const tgt = bot.entities[z.id] || z
    try { await bot.lookAt(tgt.position.offset(0, 1.2, 0), true); bot.attack(tgt) } catch (e) {}
    await wait(700)
    console.log(`  hit${i + 1}: PDC=${dur(swordOf(bot))} | ${durLine(swordOf(bot))}`)
  }
  r.close(); bot.quit(); process.exit(0)
})().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
