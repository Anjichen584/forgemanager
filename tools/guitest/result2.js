// 结果界面「拿走」后的状态一致性
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
  await chat(bot, '/gamemode creative', 700)
  await chat(bot, '/zhujian money give ' + bot.username + ' 300000', 900)
  for (const m of ['iron_ingot', 'coal', 'oak_planks']) {
    await chat(bot, '/give ' + bot.username + ' minecraft:' + m + ' 64', 350)
  }
  await wait(900)

  const w = await forgeOnce(bot)
  if (!w) { log('没走到结果界面'); bot.quit(); process.exit(1) }
  const bagBefore = bot.inventory.items().filter(i => /_sword$/.test(i.name)).length
  log(`点「拿走」前：背包剑 ${bagBefore}，13 号 = ${w.slots[13] ? w.slots[13].name : '空'}`)

  log('--- 点 15（拿走）---')
  bot.clickWindow(15, 0, 0)
  await wait(2000)
  const w2 = bot.currentWindow
  const bagAfter = bot.inventory.items().filter(i => /_sword$/.test(i.name)).length
  log(`点后：窗口=${w2 ? '还开着' : '已关闭'}，背包剑 ${bagAfter}，13 号 = ${w2 && w2.slots[13] ? w2.slots[13].name : '空'}`)
  if (bagAfter > bagBefore) ok('成品到账')
  else note('成品没到账')
  if (w2 && isContent(w2.slots[13])) note('拿走之后 13 号成品格还在显示，玩家会以为没拿到')

  log('--- 窗口开着的情况下再执行 /zhujian ---')
  bot.chat('/zhujian')
  await wait(1600)
  const w3 = bot.currentWindow
  log(`  /zhujian 之后窗口: ${w3 ? w3.slots.length : '无'}`)

  log('--- 再点一次 15 ---')
  if (w3) {
    bot.clickWindow(15, 0, 0)
    await wait(1500)
    const bag3 = bot.inventory.items().filter(i => /_sword$/.test(i.name)).length
    log(`  再点 15 后背包剑 ${bag3}（应仍是 ${bagAfter}，不能多给）`)
    if (bag3 > bagAfter) note(`重复点「拿走」多给了剑：${bagAfter}→${bag3}`)
    else ok('重复点不会多给')
  }

  log('')
  if (!problems.length) log('=== 无问题 ===')
  else { log(`=== ${problems.length} 处问题 ===`); problems.forEach(x => log('  ' + x)) }
  bot.quit(); process.exit(0)
})().catch(e => { log('FATAL:', e.stack || e.message); process.exit(1) })