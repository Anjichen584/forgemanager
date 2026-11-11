const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
;(async () => {
  const bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('error', e => console.log('ERR ' + e.message))
  await new Promise((r,j)=>{bot.once('spawn',r); setTimeout(()=>j(new Error('timeout')),45000)})
  await wait(2500)
  bot.chat('/clear TestBot'); await wait(1000)
  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0'); await wait(1500)
  console.log('EQUIPPED t=7s')
  await wait(6000)                        // ← RCON 在这段时间里召唤僵尸
  const p = bot.entity.position
  const zombie = Object.values(bot.entities)
      .filter(e => e !== bot.entity && /zombie/.test(e.name || ''))
      .sort((a,b)=>a.position.distanceTo(p)-b.position.distanceTo(p))[0]
  console.log('TARGET=' + (zombie ? zombie.name + ' d=' + zombie.position.distanceTo(p).toFixed(1) : 'none'))
  if (zombie) { for (let i=0;i<10;i++){ bot.attack(zombie); await wait(700) } }
  console.log('ATTACKED')
  await wait(14000)
  bot.quit(); process.exit(0)
})().catch(e=>{console.log('FATAL '+e.message); process.exit(1)})
