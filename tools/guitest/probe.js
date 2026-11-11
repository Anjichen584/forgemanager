// 现场探针：验证 /clear、/give、givefull 产物真伪
const { wait, lore, title, join } = require('./common')

async function main() {
  const bot = await join('TestBot')
  const lines = []
  bot.on('message', m => { lines.push(m.toString()); if (lines.length > 60) lines.shift() })
  await wait(800)
  console.log('gamemode=', bot.game && bot.game.gameMode)

  const run = async (cmd, ms = 900) => { lines.length = 0; bot.chat(cmd); await wait(ms); return lines.join(' | ') }

  console.log('clear 回执:', await run('/clear', 1200))
  await wait(400)
  console.log('清空后背包:', JSON.stringify(bot.inventory.items().map(i => `${i.slot}:${i.name}x${i.count}`)))

  console.log('give 书回执:', await run('/give TestBot minecraft:book 32', 1200))
  await wait(400)
  console.log('给书后背包:', JSON.stringify(bot.inventory.items().map(i => `${i.slot}:${i.name}x${i.count}`)))

  console.log('givefull 回执:', await run('/zhujian givefull TestBot iron mythic', 2500))
  await wait(800)
  const swords = bot.inventory.items().filter(i => /_sword$/.test(i.name))
  for (const s of swords) {
    console.log(`--- 剑 @${s.slot} ${title(s)} ---`)
    console.log(lore(s).slice(0, 14).join('\n'))
  }
  await run('/clear', 800)
  bot.quit()
  await wait(300)
  process.exit(0)
}
main().catch(e => { console.error('FATAL', e); process.exit(2) })
