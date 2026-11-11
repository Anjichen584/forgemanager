const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let bot; const msgs=[]
async function main(){
  bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('message',m=>{const s=m.toString();if(s.trim())msgs.push(s)})
  await new Promise((r,j)=>{bot.once('spawn',r);setTimeout(()=>j(new Error('t')),30000)})
  await wait(2500)
  bot.chat('/give TestBot minecraft:iron_block 64'); await wait(1500)
  log('give 后消息: ' + msgs.map(s=>s.replace(/§./g,'').slice(0,90)).join(' | '))
  log('背包:')
  bot.inventory.items().forEach(i=>log('  ' + i.name + ' x' + i.count))
  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
