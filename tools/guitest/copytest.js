// 临摹链路实测：登记谱 → copy → copy stop → copy 换谱 → 锻造时种子是否解锁
const { wait, log, lore, title, join, traceForge, chat } = require('./common')
const problems = []
const note = s => { problems.push(s); log('  ✗ ' + s) }
const ok = s => log('  ✓ ' + s)

;(async () => {
  const bot = await join('TestBot')
  const chatLog = []
  bot.on('message', m => {
    const s = m.toString()
    if (s.includes('铸剑')) { log('  » ' + s.slice(0, 200)); chatLog.push(s) }
  })
  await chat(bot, '/gamemode creative', 700)
  await chat(bot, '/zhujian money give ' + bot.username + ' 300000', 900)
  for (const m of ['iron_ingot', 'coal', 'oak_planks']) {
    await chat(bot, '/give ' + bot.username + ' minecraft:' + m + ' 64', 350)
  }
  await chat(bot, '/zhujian give ' + bot.username + ' iron EPIC 78', 1500)
  // 把锻造剑拿到主手
  const sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (sword) {
    if (sword.slot >= 36 && sword.slot <= 44) bot.setQuickBarSlot(sword.slot - 36)
    else { try { await bot.equip(sword, 'hand') } catch (e) { log('equip 失败') } }
    await wait(800)
  }
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))

  // 1. 登记谱
  log('=== 1. 登记剑谱 ===')
  bot.chat('/zhujian record 测试谱')
  await wait(1800)

  // 2. 没在临摹时 stop
  log('=== 2. 未开始时 copy stop ===')
  bot.chat('/zhujian copy stop')
  await wait(1200)

  // 3. 看谱库拿谱号
  log('=== 3. 查谱库 ===')
  bot.chat('/zhujian manual text')
  await wait(1600)

  // 4. 找最近一条临摹命令
  const line = chatLog.find(x => x.includes('/zhujian copy') && !x.includes('stop'))
  if (!line) { note('谱库里没给出临摹命令，无法继续'); }
  else {
    const id = (line.match(/copy ([0-9A-F]{4})/) || [])[1]
    if (!id) { note('没解析到谱号: ' + line) }
    else {
      log('=== 4. 开始临摹 ' + id + ' ===')
      bot.chat('/zhujian copy ' + id)
      await wait(1400)

      log('=== 5. 取消临摹 ===')
      bot.chat('/zhujian copy stop')
      await wait(1400)

      log('=== 6. 取消后再 stop ===')
      bot.chat('/zhujian copy stop')
      await wait(1200)
    }
  }

  log('')
  if (!problems.length) log('=== 无问题 ===')
  else { log(`=== ${problems.length} 处问题 ===`); problems.forEach(x => log('  ' + x)) }
  bot.quit(); process.exit(0)
})().catch(e => { log('FATAL:', e.stack || e.message); process.exit(1) })