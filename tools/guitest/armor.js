const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let bot; const msgs=[]
const take=()=>{const m=msgs.slice();msgs.length=0;return m}
const plain=s=>String(s).replace(/§./g,'')
async function main(){
  bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('message',m=>{const s=m.toString();if(s.trim())msgs.push(s)})
  await new Promise((r,j)=>{bot.once('spawn',r);setTimeout(()=>j(new Error('t')),30000)})
  await wait(3000)

  // 造四件铁甲
  for (const slot of ['helmet','chestplate','leggings','boots']) {
    take(); bot.chat(`/zhujian give TestBot armor-iron-${slot}`); await wait(1500)
    log('造 ' + slot + ': ' + take().map(plain).join(' | ').slice(0,90))
  }
  await wait(1000)
  log('--- 背包 ---')
  bot.inventory.items().forEach(i=>{ if(i.name.includes('iron')) log('  ' + i.name + ' x' + i.count) })

  // 穿上四件，读护甲属性
  const armorItems = bot.inventory.items().filter(i=>/iron_(helmet|chestplate|leggings|boots)/.test(i.name))
  log('--- 穿上 ' + armorItems.length + ' 件 ---')
  for (const it of armorItems) {
    try { await bot.equip(it, 'head'); } catch(e) {}
  }
  await wait(1500)
  log('装备槽: ' + [36,37,38,39].map(s=>{const i=bot.inventory.slots[s];return i?i.name:'-'}).join(' / '))
  log('attributes.armor = ' + (bot.entity.attributes && bot.entity.attributes['generic.armor'] ? bot.entity.attributes['generic.armor'].value : '?'))
  const attrs = bot.entity.attributes || {}
  Object.keys(attrs).filter(k=>k.includes('armor')).forEach(k=>log('  ' + k + ' = ' + attrs[k].value))

  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
