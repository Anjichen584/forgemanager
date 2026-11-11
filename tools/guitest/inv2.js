const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let bot
async function main(){
  bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  await new Promise((r,j)=>{bot.once('spawn',r);setTimeout(()=>j(new Error('t')),30000)})
  await wait(3000)
  // 先清干净
  bot.chat('/clear TestBot'); await wait(1200)
  bot.chat('/give TestBot minecraft:iron_block 64'); await wait(3000)
  log('背包（3 秒后）:')
  const items = bot.inventory.items()
  items.forEach(i=>log('  slot=' + i.slot + ' ' + i.name + ' x' + i.count))
  log('共 ' + items.length + ' 组')
  log('iron_block 计数: ' + items.filter(i=>i.name==='iron_block').reduce((a,b)=>a+b.count,0))
  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
