const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
function dmgOf(it, tag) {
  if (!it) { console.log(tag + ' 空手'); return }
  const comps = it.components || []
  const d = comps.find(c => c.type === 'damage')
  const ub = comps.find(c => c.type === 'unbreakable')
  console.log(`${tag} ${it.name} damage=${d ? JSON.stringify(d.data) : '默认0'} unbreakable=${ub ? 'YES' : 'no'}`)
}
;(async () => {
  const bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('error', e => console.log('ERR ' + e.message))
  await new Promise((r,j)=>{bot.once('spawn',r); setTimeout(()=>j(new Error('timeout')),45000)})
  await wait(10000)                        // ← RCON 切生存 + 发剑
  console.log('MODE=' + bot.game.gameMode)
  dmgOf(bot.heldItem, '挖前:')
  const p = bot.entity.position.floored()
  let dug = 0
  for (const [dx,dy,dz] of [[0,-1,0],[1,-1,0],[-1,-1,0],[0,-1,1],[0,-1,-1],[0,-2,0]]) {
    const b = bot.blockAt(p.offset(dx,dy,dz))
    if (!b || b.name === 'air' || b.name === 'bedrock') continue
    try { await bot.dig(b, true); dug++ } catch (e) { console.log('DIGFAIL ' + e.message) }
    await wait(500)
  }
  console.log('DUG=' + dug)
  await wait(1500)
  dmgOf(bot.heldItem, '挖后:')
  await wait(4000)
  bot.quit(); process.exit(0)
})().catch(e=>{console.log('FATAL '+e.message); process.exit(1)})
