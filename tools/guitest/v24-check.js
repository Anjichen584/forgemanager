// v2.24 新功能抽查：附魔列表、兑换所、（bot 在线才有回执）
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
  const run = async (cmd, ms = 1200) => { lines = []; bot.chat(cmd); await wait(ms); console.log('$ ' + cmd); lines.slice(0, 12).forEach(l => console.log('  ' + l.replace(/§./g, ''))) }
  await run('/zhujian ench list')
  await run('/zhujian redeem info')
  await run('/zhujian redeem coin 10')
  await run('/zhujian guild fund info')
  r.close(); bot.quit(); process.exit(0)
})().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })
