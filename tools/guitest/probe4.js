const mineflayer = require('mineflayer')
const fs = require('fs')
const wait = ms => new Promise(r => setTimeout(r, ms))
const out = []
const p = (...a) => { const s = a.join(' '); console.log(s); out.push(s) }
function attrsOf(it, tag) {
  if (!it) { p('[' + tag + '] 空手'); return }
  const comps = it.components || []
  const found = []
  for (const c of comps) {
    if (/attribute/i.test(c.type) || /attribute/i.test(JSON.stringify(c.data || {}).slice(0, 80))) {
      found.push(c)
    }
  }
  p('[' + tag + '] ' + it.name + ' 组件数=' + comps.length + ' 属性类组件=' + found.length)
  for (const c of found) {
    const s = JSON.stringify(c.data && c.data.value ? c.data.value : c.data)
    p('   ' + c.type + ': ' + s.slice(0, 600))
  }
  if (!found.length) {
    p('   组件类型清单: ' + comps.map(c => c.type).join(','))
  }
}
;(async () => {
  const bot = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
  bot.on('error', e => p('ERR ' + e.message))
  await new Promise((r,j)=>{bot.once('spawn',r); setTimeout(()=>j(new Error('timeout')),40000)})
  p('JOINED'); await wait(2500)
  bot.chat('/clear TestBot'); await wait(1000)
  bot.chat('/item replace entity TestBot weapon.mainhand with iron_sword'); await wait(1500)
  attrsOf(bot.heldItem, '原版铁剑')
  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0'); await wait(2000)
  attrsOf(bot.heldItem, '锻造铁剑')
  fs.writeFileSync(__dirname + '/item-probe.txt', out.join('\n') + '\n', 'utf8')
  bot.quit(); setTimeout(()=>process.exit(0),400)
})().catch(e=>{p('FATAL '+e.message); process.exit(1)})
