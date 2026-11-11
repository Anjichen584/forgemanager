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

  log('健康=' + bot.health + ' 饥饿=' + bot.food + ' 模式=' + bot.game.gameMode + ' y=' + bot.entity.position.y.toFixed(1))

  // 一次性给足，每步都等
  for (const it of ['iron_block','book']) {
    bot.chat(`/give TestBot minecraft:${it} 64`); await wait(2000)
  }
  await wait(1500)
  const items = bot.inventory.items()
  log('iron_block=' + items.filter(i=>i.name==='iron_block').reduce((a,b)=>a+b.count,0)
     + ' book=' + items.filter(i=>i.name==='book').reduce((a,b)=>a+b.count,0))

  take()
  bot.chat('/zhujian gem'); await wait(1800)
  let w = bot.currentWindow
  if (!w) { log('宝石台没开: ' + take().map(plain).join(' | ')); bot.quit(); return }
  log('宝石台已开，槽位 ' + w.slots.length)
  for (const slot of [4,10,11,15,16]) {
    const s = w.slots[slot]
    if (!s) continue
    const name = s.customName ? JSON.parse(s.customName).text || '' : s.name
    log(`[${slot}] ${s.name} :: ${plain(s.customName ? JSON.stringify(s.customName) : '')}`)
    if (s.customLore) s.customLore.forEach(l=>log('     ' + plain(JSON.stringify(l)).slice(0,90)))
  }
  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
