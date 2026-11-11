const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let bot; const msgs=[]
const take=()=>{const m=msgs.slice();msgs.length=0;return m}
const plain=s=>String(s).replace(/§./g,'')
const armor=()=>{const a=bot.entity.attributes||{};const k=Object.keys(a).find(x=>x.includes('armor')&&!x.includes('tough'));return k?a[k].value:'?'}
async function main(){
  bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('message',m=>{const s=m.toString();if(s.trim())msgs.push(s)})
  await new Promise((r,j)=>{bot.once('spawn',r);setTimeout(()=>j(new Error('t')),30000)})
  await wait(3000)
  bot.chat('/gamemode creative TestBot'); await wait(800)
  bot.chat('/clear TestBot'); await wait(1200)

  for (const slot of ['helmet','chestplate','leggings','boots']) {
    take(); bot.chat(`/zhujian give TestBot armor-iron-${slot}`); await wait(1600)
    const r = take().map(plain).join(' ')
    log('造 ' + slot + ': ' + (r.includes('已给') ? 'OK' : r.slice(0,80)))
  }
  await wait(1200)

  const pieces = bot.inventory.items().filter(i=>/iron_(helmet|chestplate|leggings|boots)/.test(i.name))
  log('拿到 ' + pieces.length + ' 件铁甲')

  // 逐件穿上（用 equip 按目标槽）
  const dest = {'iron_helmet':'head','iron_chestplate':'torso','iron_leggings':'legs','iron_boots':'feet'}
  for (const it of pieces) {
    try { await bot.equip(it, dest[it.name]); await wait(700) } catch(e) { log('  穿 ' + it.name + ' 失败: ' + e.message) }
  }
  await wait(1500)
  log('四件槽位: ' + [36,37,38,39].map(s=>{const i=bot.inventory.slots[s];return i?i.name:'空'}).join(' / '))
  const full = armor()
  log('★ 四件齐全时 护甲 = ' + full)

  // 关键：脱掉胸甲，看剩下的护甲是否还在
  const chest = bot.inventory.slots[38]
  if (chest) {
    bot.unequip('torso'); await wait(1800)
    log('脱掉胸甲后槽位: ' + [36,37,38,39].map(s=>{const i=bot.inventory.slots[s];return i?i.name:'空'}).join(' / '))
    const after = armor()
    log('★ 脱掉胸甲后 护甲 = ' + after)
    if (Number(after) > 0) log('  ✓ 剩下三件的护甲还在（bug 已修）')
    else log('  ✗ 护甲被清空了（bug 仍在）')
  } else { log('胸甲没穿上，无法测') }

  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
