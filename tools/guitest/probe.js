const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const out = (...a) => console.log(...a)
async function main(){
  const bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('error', e => out('ERR ' + e.message))
  await new Promise((r,j)=>{bot.once('spawn',r); setTimeout(()=>j(new Error('join timeout')),30000)})
  out('JOINED')
  await wait(2500)

  const readAttrs = (tag) => {
    const a = bot.entity.attributes || {}
    const keys = Object.keys(a)
    out('[' + tag + '] keys=' + keys.filter(k=>k.includes('attack')||k.includes('armor')).join(','))
    for (const k of keys) {
      if (k.includes('attack_damage') || k.includes('attack_speed') || k === 'armor') {
        out('   ' + k + ' = ' + a[k].value)
      }
    }
  }

  bot.chat('/clear TestBot'); await wait(1200)
  bot.chat('/item replace entity TestBot hotbar.0 with air'); await wait(800)
  bot.chat('/item replace entity TestBot weapon.mainhand with iron_sword'); await wait(1500)
  out('手持 = ' + (bot.heldItem ? bot.heldItem.name : '空'))
  readAttrs('原版铁剑')

  await wait(800)
  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(1800)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0'); await wait(1800)
  out('手持 = ' + (bot.heldItem ? bot.heldItem.name : '空'))
  readAttrs('锻造铁剑')

  bot.quit(); process.exit(0)
}
main().catch(e=>{out('FATAL ' + e.message); process.exit(1)})
