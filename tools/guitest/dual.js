const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let bot; const msgs = []
const win = () => bot.currentWindow
const chat = async (c, ms = 1200) => { bot.chat(c); await wait(ms) }
const take = () => { const m = msgs.slice(); msgs.length = 0; return m }
const show = a => a.map(s => s.replace(/§./g, '').slice(0, 120)).join(' | ')

async function main() {
  bot = mineflayer.createBot({ host:'127.0.0.1', port:25577, username:'TestBot', auth:'offline', version:'1.21.11' })
  bot.on('message', m => { const s = m.toString(); if (s.trim()) msgs.push(s) })
  bot.on('error', e => log('err:', e.message))
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(()=>rej(new Error('超时')),30000) })
  await wait(2500)
  await chat('/gamemode creative TestBot', 700)
  await chat('/zhujian money give TestBot 9999999', 900)
  for (const m of ['iron_block','book','nether_star','lapis_lazuli','enchanted_book']) { bot.chat(`/give TestBot minecraft:${m} 64`); await wait(300) }
  await wait(800)

  // 造一把铁剑
  await chat('/zhujian give TestBot iron COMMON 60', 1600)
  for (let s=0;s<9;s++){ const it=bot.inventory.slots[36+s]; if(it&&it.name==='iron_sword'){bot.setQuickBarSlot(s);await wait(400);break} }
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))

  // 看初始 Lore
  const lore0 = bot.heldItem && bot.heldItem.nbt ? 'nbt' : ''
  log('--- 开宝石孔 ×2 ---')
  for (let i=0;i<2;i++){ take(); await chat('/zhujian gem socket', 1500); log('  ' + show(take())) }

  log('--- 开附魔孔 ×2 ---')
  for (let i=0;i<2;i++){ take(); await chat('/zhujian gem esocket', 1500); log('  ' + show(take())) }

  log('--- 再开第 3 个宝石孔（铁上限应为 1，应被拒）---')
  take(); await chat('/zhujian gem socket', 1300); log('  ' + show(take()))
  log('--- 再开第 3 个附魔孔（应被拒）---')
  take(); await chat('/zhujian gem esocket', 1300); log('  ' + show(take()))

  // 读 Lore
  const it = bot.heldItem
  if (it && it.customLore) { log('--- Lore ---'); it.customLore.forEach(l => log('  ' + l.replace(/§./g,''))) }
  else if (it && it.nbt) { log('(无 customLore 字段)') }

  bot.quit(); process.exit(0)
}
main().catch(e => { log('FATAL:', e.message); process.exit(1) })
