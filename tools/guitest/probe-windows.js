// 现场探针：主菜单→选剑胚→铁剑胚；宝石台→点星熔——打印每一步的窗口标题与聊天
const mineflayer = require('mineflayer')
const rconlib = require('./rconlib')
const wait = ms => new Promise(r => setTimeout(r, ms))
;(async () => {
  const r = await rconlib.connect({ port: 25599, password: 'testpass' })
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  let lines = []
  bot.on('message', m => { const s = m.toString().trim(); if (s) lines.push(s) })
  await new Promise((res, j) => { bot.once('spawn', res); setTimeout(() => j(new Error('timeout')), 45000) })
  await wait(3000)
  const show = tag => {
    console.log('[' + tag + '] 窗口=' + (bot.currentWindow ? bot.currentWindow.title().replace(/§./g, '') + ' (' + bot.currentWindow.slots.filter(Boolean).length + ' 格)' : '无'))
    lines.slice(-4).forEach(l => console.log('  msg: ' + l.replace(/§./g, '')))
    lines = []
  }
  // 材料垫上（锻造要耗材）
  await r.send('minecraft:gamemode creative TestBot'); await wait(300)
  await r.send('minecraft:give TestBot minecraft:iron_ingot 64'); await wait(400)
  await r.send('minecraft:give TestBot minecraft:coal 64'); await wait(400)
  await r.send('minecraft:gamemode survival TestBot'); await wait(400)
  await r.send('minecraft:clear TestBot'); await wait(400)
  await r.send('minecraft:give TestBot minecraft:iron_ingot 32'); await wait(300)
  await r.send('minecraft:give TestBot minecraft:coal 32'); await wait(300)
  await r.send('zhujian forge'); await wait(1500)
  show('主菜单')
  // 找铁剑胚（iron_ingot）在窗口里的槽位
  const win = bot.currentWindow
  const iron = win ? win.slots.findIndex(s => s && s.name === 'iron_ingot') : -1
  console.log('[找胚] iron_ingot @ slot ' + iron)
  if (iron >= 0) { await bot.clickWindow(iron, 0, 0); await wait(1500) }
  show('选胚后')
  const w2 = bot.currentWindow
  const click1 = w2 ? w2.slots.findIndex(s => s && /ingot|coal|ham/.test(s.name) && s.slot < w2.inventoryStart) : -1
  console.log('[阶段1] 可点物 @ ' + click1)
  if (click1 >= 0) { await bot.clickWindow(click1, 0, 0); await wait(1500) }
  show('点第一格后')
  await bot.closeWindow(); await wait(600)
  // 宝石台
  await r.send('zhujian give TestBot iron legendary 90'); await wait(1500)
  await r.send('zhujian gem'); await wait(1500)
  show('宝石台')
  const g = bot.currentWindow
  const g13 = g ? g.slots[13] : null
  console.log('[宝石台] 13 号 = ' + (g13 ? g13.name : '空'))
  if (g13) { await bot.clickWindow(13, 0, 0); await wait(1500) }
  show('点星熔后')
  await bot.closeWindow()
  // 委托板
  await r.send('zhujian order'); await wait(1500)
  show('委托板')
  const o = bot.currentWindow
  // 点 45 号（订单入口 minecart）
  const o45 = o ? o.slots[45] : null
  console.log('[委托] 45 号 = ' + (o45 ? o45.name : '空'))
  if (o45) { await bot.clickWindow(45, 0, 0); await wait(1500) }
  show('点委托入口后')
  await bot.closeWindow()
  r.close(); bot.quit(); process.exit(0)
})().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
