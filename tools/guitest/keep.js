const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
let bot
const log=(...a)=>console.log(...a)
async function main(){
  bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('message',m=>{const s=m.toString().replace(/§./g,'');if(s.trim())log('[chat] '+s.slice(0,150))})
  await new Promise((r,j)=>{bot.once('spawn',r);setTimeout(()=>j(new Error('t')),30000)})
  log('JOINED y=' + bot.entity.position.y.toFixed(1) + ' mode=' + bot.game.gameMode)
  await wait(90000)
  bot.quit(); process.exit(0)
}
main().catch(e=>{log('ERR:',e.message);process.exit(1)})
