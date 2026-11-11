// 复现「锻造品掉本体耐久」：拿锻造剑砍僵尸，服务端 Damage 组件应该涨。
// 用法：node repro-durability.js   然后 RCON 读 SelectedItem.components."minecraft:damage"
const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))

async function main() {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11'
  })
  bot.on('error', e => console.log('ERR ' + e.message))
  await new Promise((r, j) => { bot.once('spawn', r); setTimeout(() => j(new Error('timeout')), 45000) })
  console.log('JOINED')
  await wait(3000)

  bot.chat('/clear TestBot'); await wait(1000)
  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0')
  await wait(1500)

  const me = bot.entity.position
  bot.chat(`/summon zombie ${me.x + 1.5} ${me.y} ${me.z} {NoAI:1b,PersistenceRequired:1b,Silent:1b}`)
  await wait(2000)

  const zombie = Object.values(bot.entities).find(e =>
    e.name === 'zombie' && e.position.distanceTo(bot.entity.position) < 4)
  console.log('ZOMBIE=' + (zombie ? zombie.id : 'not found'))

  for (let i = 0; i < 8; i++) {
    if (zombie) bot.attack(zombie)
    await wait(650)
  }
  console.log('ATTACKED x8')
  await wait(1200)
  console.log('READY-FOR-RCON')
  await wait(12000)
  bot.quit(); process.exit(0)
}
main().catch(e => { console.log('FATAL ' + e.message); process.exit(1) })