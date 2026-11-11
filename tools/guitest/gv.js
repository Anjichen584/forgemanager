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
  take()
  bot.chat('/minecraft:give TestBot minecraft:iron_block 64'); await wait(2500)
  log('消息: ' + take().map(plain).join(' | ').slice(0,150))
  const items = bot.inventory.items()
  log('iron_block=' + items.filter(i=>i.name==='iron_block').reduce((a,b)=>a+b.count,0))
  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
