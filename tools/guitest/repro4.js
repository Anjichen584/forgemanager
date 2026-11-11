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
  const held = bot.heldItem
  console.log('HELD=' + (held ? held.name : 'none'))
  const p = bot.entity.position.floored()
  let dug = 0
  for (const [dx,dy,dz] of [[0,-1,0],[1,-1,0],[-1,-1,0],[0,-1,1],[0,-1,-1],[0,-2,0]]) {
    const b = bot.blockAt(p.offset(dx,dy,dz))
    if (!b || b.name === 'air' || b.name === 'bedrock') continue
    try { await bot.dig(b, true); dug++; console.log('DUG ' + b.name) } catch (e) { console.log('DIGFAIL ' + e.message) }
    await wait(400)
  }
  console.log('DUG=' + dug)
  await wait(2000)
  console.log('READY-RCON')
  await wait(14000)
  bot.quit(); process.exit(0)
})().catch(e=>{console.log('FATAL '+e.message); process.exit(1)})
