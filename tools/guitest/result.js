// 结果界面验证：点 15「拿走」→ 背包应得成品；再验证关窗口自动发放
const { wait, log, lore, title, isContent, join, traceForge, chat } = require('./common')
const problems = []
const note = s => { problems.push(s); log('  ✗ ' + s) }
const ok = s => log('  ✓ ' + s)

/** 走一遍完整锻造，返回到结果界面 */
async function forgeOnce(bot, material = 'iron') {
  await chat(bot, '/zhujian', 1300)
  bot.clickWindow(11, 0, 0); await wait(1400)
  // 按材料 id 找剑胚槽
  const w = bot.currentWindow
  let slot = -1
  if (w) {
    for (let s = 10; s <= 15; s++) {
      const it = w.slots[s]
      if (!it) continue
      if (lore(it).join(' ').includes('铁剑') && material === 'iron') { slot = s; break }
    }
  }
  if (slot < 0) slot = 10
  bot.clickWindow(slot, 0, 0); await wait(2200)

  // 加热到区间
  for (let i = 0; i < 14; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    const lines = lore(cw.slots[4]).join(' ')
    const tm = lines.match(/当前温度:\s*([\d.]+)/)
    const zm = lines.match(/最佳区间:\s*(\d+)°\s*~\s*(\d+)°/)
    if (!tm || !zm) break
    const t = parseFloat(tm[1]), lo = +zm[1], hi = +zm[2]
    if (t >= lo && t <= hi) break
    bot.clickWindow(10, 0, 0); await wait(500)
  }
  bot.clickWindow(13, 0, 0); await wait(2300)

  // 锻打
  for (let i = 0; i < 200; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    const t4 = lore(cw.slots[4]).join(' ')
    if (!t4.includes('已敲')) break
    let hit = false
    for (let s = 9; s <= 17; s++) {
      const it = cw.slots[s]
      if (it && title(it).includes('▼') && title(it).includes('命中区')) { hit = true; break }
    }
    if (hit) { bot.clickWindow(22, 0, 0); await wait(540) }
    else await wait(50)
  }

  // 叠钢
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

  // 开刃
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

  // 淬火
  for (let i = 0; i < 8; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    if (!lore(cw.slots[4]).join(' ').includes('介质')) break
    bot.clickWindow(10, 0, 0); await wait(2400)
  }
  return bot.currentWindow
}

function swordCount(bot) {
  return bot.inventory.items().filter(i => /_sword$/.test(i.name)).length
}

;(async () => {
  const bot = await join('TestBot')
  traceForge(bot)
  await chat(bot, '/gamemode creative', 700)
  await chat(bot, '/zhujian money give ' + bot.username + ' 300000', 900)
  for (const m of ['iron_ingot', 'coal', 'oak_planks']) {
    await chat(bot, '/give ' + bot.username + ' minecraft:' + m + ' 64', 350)
  }
  await wait(1000)

  // ---------- A. 点 15「拿走」 ----------
  log('=== A. 结果界面点 15「拿走」 ===')
  await chat(bot, '/clear', 700)
  const before = swordCount(bot)
  let w = await forgeOnce(bot)
  if (!w) { note('A: 没走到结果界面'); }
  else {
    const hasSword = isContent(w.slots[13]) && /_sword$/.test(w.slots[13].name)
    if (hasSword) ok('13 号是成品剑'); else note('13 号没有成品')
    log(`  点前背包剑数: ${before}`)
    bot.clickWindow(15, 0, 0); await wait(2000)
    const after = swordCount(bot)
    log(`  点后背包剑数: ${after}`)
    if (after > before) ok('点 15 后成品进了背包')
    else if (!bot.currentWindow) ok('界面已关闭')
    else note(`点 15 后背包剑数没变（${before}→${after}）`)
    // 界面关了的话承认发放成功
    if (!bot.currentWindow && after > before) ok('界面关闭且物品到账')
  }

  // ---------- B. 关窗口自动发放 ----------
  log('=== B. 结果界面直接关窗口，应自动发放 ===')
  await chat(bot, '/clear', 700)
  await wait(500)
  const b2 = swordCount(bot)
  w = await forgeOnce(bot)
  if (!w) { note('B: 没走到结果界面'); }
  else {
    log(`  关窗口前背包剑数: ${b2}`)
    bot.closeWindow(w); await wait(2200)
    const a2 = swordCount(bot)
    log(`  关窗口后背包剑数: ${a2}`)
    if (a2 > b2) ok('关窗口后自动发放生效')
    else note(`关窗口后没拿到成品（${b2}→${a2}）`)
  }

  log('')
  if (!problems.length) log('=== 无问题 ===')
  else { log(`=== ${problems.length} 处问题 ===`); problems.forEach(x => log('  ' + x)) }
  bot.quit(); process.exit(0)
})().catch(e => { log('FATAL:', e.stack || e.message); process.exit(1) })