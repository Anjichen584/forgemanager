const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const say = (bot, c) => { bot.chat(c) }
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
  bot.on('messagestr', m => { if (/权限|permission|Unknown|未知|错误|Incorrect/i.test(m)) console.log('SRV: ' + m) })
  await new Promise((r,j)=>{bot.once('spawn',r); setTimeout(()=>j(new Error('timeout')),45000)})
  console.log('JOINED')
  await wait(9000)                        // ← 等 RCON 给自己 op

  say(bot, '/gamemode survival'); await wait(800)
  say(bot, '/clear'); await wait(800)
  say(bot, '/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  console.log('HELD=' + (bot.heldItem ? bot.heldItem.name : 'none'))

  const p = bot.entity.position.floored()
  // 脚下铺一层实心方块，保证有东西可挖
  say(bot, `/fill ${p.x-2} ${p.y-1} ${p.z-2} ${p.x+2} ${p.y-1} ${p.z+2} minecraft:dirt`)
  await wait(1500)
  dmgOf(bot.heldItem, '挖前:')

  let dug = 0
  for (const [dx,dy,dz] of [[0,-1,0],[1,-1,0],[-1,-1,0],[0,-1,1],[0,-1,-1],[0,-2,0]]) {
    const b = bot.blockAt(p.offset(dx,dy,dz))
    if (!b || b.name === 'air' || b.name === 'bedrock') { console.log('SKIP ' + (b ? b.name : 'null')); continue }
    try { await bot.dig(b, true); dug++; console.log('DUG ' + b.name) } catch (e) { console.log('DIGFAIL ' + e.message) }
    await wait(500)
  }
  console.log('DUG=' + dug)
  await wait(1500)
  dmgOf(bot.heldItem, '挖后:')
  await wait(2000)
  bot.quit(); process.exit(0)
})().catch(e=>{console.log('FATAL '+e.message); process.exit(1)})
