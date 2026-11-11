// 回归：点 15「拿走」→ 服务端真实背包里有没有剑（用 /zhujian money balance 之类不方便，改用主手读取）
// 方案：拿走后让 bot 自己执行 /zhujian salvage 熔掉主手锻造品，若成功说明剑真的在背包里
const { wait, log, lore, title, isContent, join, traceForge, chat } = require('./common')
const problems = []
const note = s => { problems.push(s); log('  ✗ ' + s) }
const ok = s => log('  ✓ ' + s)

async function forgeOnce(bot) {
  await chat(bot, '/zhujian', 1300)
  bot.clickWindow(11, 0, 0); await wait(1400)
  bot.clickWindow(10, 0, 0); await wait(2200)
  for (let i = 0; i < 14; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    const lines = lore(cw.slots[4]).join(' ')
    const tm = lines.match(/当前温度:\s*([\d.]+)/)
    const zm = lines.match(/最佳区间:\s*(\d+)°\s*~\s*(\d+)°/)
    if (!tm || !zm) break
    const t = parseFloat(tm[1]), lo = +zm[1], hi = +zm[2]
    if (t >= lo && t <= hi) break
    bot.clickWindow(10, 0, 0); await wait(480)
  }
  bot.clickWindow(13, 0, 0); await wait(2300)
  for (let i = 0; i < 200; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    if (!lore(cw.slots[4]).join(' ').includes('已敲')) break
    let hit = false
    for (let s = 9; s <= 17; s++) {
      const it = cw.slots[s]
      if (it && title(it).includes('▼') && title(it).includes('命中区')) { hit = true; break }
    }
    if (hit) { bot.clickWindow(22, 0, 0); await wait(540) } else await wait(50)
  }
  for (let i = 0; i < 40; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    if (!lore(cw.slots[4]).join(' ').includes('折叠序列')) break
    let target = -1
    for (let s = 9; s <= 17; s++) {
      const it = cw.slots[s]
      if (it && /^lime_stained_glass_pane$/.test(it.name)) { target = s; break }
    }
    if (target < 0) break
    bot.clickWindow(target, 0, 0); await wait(400)
  }
  for (let i = 0; i < 260; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    const lines = lore(cw.slots[4]).join(' ')
    if (!lines.includes('压力:')) break
    const pm = lines.match(/压力:\s*([\d.]+)\s*\|\s*目标\s*([\d.]+)\s*~\s*([\d.]+)/)
    if (!pm) break
    const cur = parseFloat(pm[1]), lo = parseFloat(pm[2]), hi = parseFloat(pm[3])
    if (cur < lo - 1) { bot.clickWindow(0, 0, 0); await wait(170) }
    else if (cur > hi + 1) { bot.clickWindow(8, 0, 0); await wait(170) }
    else await wait(190)
  }
  for (let i = 0; i < 8; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    if (!lore(cw.slots[4]).join(' ').includes('介质')) break
    bot.clickWindow(10, 0, 0); await wait(2400)
  }
  return bot.currentWindow
}

;(async () => {
  const bot = await join('TestBot')
  traceForge(bot)
  await chat(bot, '/gamemode survival', 700)
  await chat(bot, '/zhujian money give ' + bot.username + ' 300000', 900)
  for (const m of ['iron_ingot', 'coal', 'oak_planks']) {
    await chat(bot, '/give ' + bot.username + ' minecraft:' + m + ' 64', 350)
  }
  await wait(900)

  const w = await forgeOnce(bot)
  if (!w) { log('没走到结果界面'); bot.quit(); process.exit(1) }

  log('--- 点 15（拿走）---')
  bot.clickWindow(15, 0, 0)
  await wait(2600)
  log('窗口: ' + (bot.currentWindow ? '还开着' : '已关闭'))

  // 验证 1：主手是否是锻造剑（closeInventory 后 hotbar 第 0 格）
  const held = bot.heldItem
  log('主手: ' + (held ? held.name : '无'))
  if (held) log('  主手 lore[0..2]: ' + lore(held).slice(0, 3).join(' / '))

  // 验证 2：背包里数剑（等更久让背包包同步）
  await wait(1500)
  const swords = bot.inventory.items().filter(i => /_sword$/.test(i.name))
  log('背包缓存里的剑: ' + swords.length)
  swords.forEach(s => log('  - slot ' + s.slot + ' ' + s.name))

  // 验证 3：熔掉主手锻造品，成功说明服务端背包确实有锻造品
  log('--- 用 /zhujian salvage 验证服务端背包 ---')
  bot.chat('/zhujian salvage')
  await wait(2000)

  log('')
  if (!problems.length) log('=== 无问题 ===')
  else { log(`=== ${problems.length} 处问题 ===`); problems.forEach(x => log('  ' + x)) }
  bot.quit(); process.exit(0)
})().catch(e => { log('FATAL:', e.stack || e.message); process.exit(1) })