const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
;(async () => {
  const bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('error', e => console.log('ERR ' + e.message))
  await new Promise((r,j)=>{bot.once('spawn',r); setTimeout(()=>j(new Error('timeout')),45000)})
  await wait(3000)
  bot.chat('/clear TestBot'); await wait(1000)
  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0'); await wait(1500)
  const p = bot.entity.position
  bot.chat(`/summon zombie ${(p.x+1.5).toFixed(2)} ${p.y.toFixed(2)} ${p.z.toFixed(2)}`)
  await wait(2500)
  const all = Object.values(bot.entities).filter(e => e !== bot.entity)
  console.log('ENTITIES: ' + all.map(e => e.name + '@' + e.position.distanceTo(p).toFixed(1)).join(', '))
  const zombie = all.filter(e => e.name === 'zombie').sort((a,b)=>a.position.distanceTo(p)-b.position.distanceTo(p))[0]
  console.log('TARGET=' + (zombie ? zombie.name + ' ' + zombie.id : 'none'))
  if (zombie) { for (let i=0;i<10;i++){ bot.attack(zombie); await wait(700) } }
  console.log('DONE_ATTACK')
  await wait(12000)
  bot.quit(); process.exit(0)
})().catch(e=>{console.log('FATAL '+e.message); process.exit(1)})
