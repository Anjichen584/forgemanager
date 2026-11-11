// 锻造全流程实测 v3：按阶段识别界面，逐阶段验证按钮与推进
const { wait, log, lore, title, isContent, join, traceForge, chat } = require('./common')

const problems = []
const note = (s) => { problems.push(s); log('  ✗ ' + s) }

/** 靠 4 号槽物品名 + lore 判断当前阶段 */
function phase(bot) {
  const w = bot.currentWindow
  if (!w) return 'NONE'
  const it = w.slots[4]
  if (!it) return 'NONE'
  const t = lore(it).join(' ')
  if (t.includes('折叠序列')) return 'FOLD'
  if (t.includes('当前温度')) return 'HEAT'
  if (t.includes('已敲')) return 'HAMMER'
  if (t.includes('压力')) return 'GRIND'
  if (t.includes('介质')) return 'QUENCH'
  // 结果界面 4 号是玻璃板，靠 13 号成品 + 15 号 chest 认
  if (it.name.endsWith('_stained_glass_pane') && isContent(w.slots[13]) && isContent(w.slots[15])) return 'RESULT'
  return 'NONE'
}

function dump(bot, tag) {
  const w = bot.currentWindow
  if (!w) { log(`[${tag}] ✗ 没有窗口`); return null }
  log(`[${tag}] ---`)
  for (let i = 0; i < Math.min(w.slots.length, 27); i++) {
    const it = w.slots[i]
    if (!it) continue
    // 跳过纯装饰的灰色玻璃板，但保留 lime/red（轨道高亮）
    if (/^gray_stained_glass_pane$/.test(it.name)) continue
    log(`  ${i}=${it.name} [${title(it)}] :: ${lore(it).join(' ⏎ ').replace(/\s+/g, ' ').slice(0, 200)}`)
  }
  return w
}

;(async () => {
  const bot = await join('TestBot')
  traceForge(bot)
  log('=== 已进服 ===')

  await chat(bot, '/clear', 600)
  await chat(bot, '/gamemode creative ' + bot.username, 700)
  await chat(bot, '/zhujian money give ' + bot.username + ' 200000', 900)
  for (const m of ['iron_ingot', 'coal', 'oak_planks', 'copper_ingot', 'diamond']) {
    await chat(bot, '/give ' + bot.username + ' minecraft:' + m + ' 64', 380)
  }
  await wait(1200)

  await chat(bot, '/zhujian', 1400)
  bot.clickWindow(11, 0, 0)
  await wait(1500)
  bot.clickWindow(10, 0, 0)
  await wait(2300)

  let p = phase(bot)
  log(`>>> 阶段: ${p}`)
  if (p !== 'HEAT') note(`点铁剑胚后阶段是 ${p}，不是 HEAT`)
  dump(bot, '加热')

  // ---------- 加热：拉风箱到 52~72 ----------
  if (p === 'HEAT') {
    let prevTemp = null
    for (let i = 0; i < 14; i++) {
      const cw = bot.currentWindow
      if (!cw) break
      const lines = lore(cw.slots[4]).join(' ')
      const m = lines.match(/当前温度:\s*([\d.]+)/)
      const temp = m ? parseFloat(m[1]) : null
      const inZone = /最佳区间.*?(\d+)°\s*~\s*(\d+)°/.exec(lines)
      log(`  [${i}] 炉温 ${temp}° ${prevTemp !== null && temp !== null ? '(' + (temp - prevTemp).toFixed(1) + ')' : ''}  ${inZone ? '区间 ' + inZone[1] + '~' + inZone[2] : ''}`)
      if (temp !== null && inZone) {
        const lo = parseFloat(inZone[1]), hi = parseFloat(inZone[2])
        if (temp >= lo && temp <= hi) { log('  → 已在最佳区间，停止拉风箱'); break }
      }
      prevTemp = temp
      bot.clickWindow(10, 0, 0)
      await wait(500)
    }
    log('  --- 点 13 取出 ---')
    bot.clickWindow(13, 0, 0)
    await wait(2400)
    p = phase(bot)
    log(`>>> 阶段: ${p}`)
    dump(bot, '锻打')
    if (p !== 'HAMMER') note(`取出后阶段是 ${p}，不是 HAMMER`)
  }

  // ---------- 锻打：指针进绿格时敲 22 ----------
  if (phase(bot) === 'HAMMER') {
    let strikes = 0
    for (let i = 0; i < 120; i++) {
      const cw = bot.currentWindow
      if (!cw) { log('  窗口关闭'); break }
      const ph = phase(bot)
      if (ph !== 'HAMMER') { log(`  阶段推进: ${ph}（敲了 ${strikes} 次）`); break }
      // 找带 ▼ 的格 = 指针位；标题含「命中区」说明在绿区
      let pointer = -1, pointerHit = false
      for (let s = 9; s <= 17; s++) {
        const it = cw.slots[s]
        if (!it) continue
        const t = title(it)
        if (t.includes('▼')) { pointer = s; pointerHit = t.includes('命中区'); break }
      }
      if (pointer >= 0 && pointerHit) {
        log(`  [${i}] 指针在 ${pointer} 且命中 → 敲击`)
        bot.clickWindow(22, 0, 0)
        strikes++
        await wait(560)
      } else {
        await wait(60) // 等指针移动
      }
    }
    dump(bot, '锻打后')
  }

  // ---------- 叠钢：点 lime 格 ----------
  for (let i = 0; i < 40; i++) {
    const cw = bot.currentWindow
    if (!cw) break
    const ph = phase(bot)
    if (ph !== 'FOLD') { if (i > 0) log(`  阶段推进: ${ph}（叠了 ${i} 次）`); break }
    let target = -1
    for (let s = 9; s <= 17; s++) {
      const it = cw.slots[s]
      if (it && /^lime_stained_glass_pane$/.test(it.name)) { target = s; break }
    }
    if (target < 0) { log('  找不到 lime 目标格'); break }
    log(`  叠钢 ${i + 1}: 点 ${target}`)
    bot.clickWindow(target, 0, 0)
    await wait(420)
  }
  dump(bot, '叠钢后')

  // ---------- 开刃：把压力拉进绿区并保持 ----------
  if (phase(bot) === 'GRIND') {
    log('  --- 开刃：读压力与目标，加减压保持在绿区 ---')
    for (let i = 0; i < 200; i++) {
      const cw = bot.currentWindow
      if (!cw) break
      if (phase(bot) !== 'GRIND') { log(`  阶段推进: ${phase(bot)}`); break }
      const lines = lore(cw.slots[4]).join(' ')
      const pm = lines.match(/压力:\s*([\d.]+)\s*\|\s*目标\s*([\d.]+)\s*~\s*([\d.]+)/)
      if (!pm) { log('  读不到压力'); break }
      const cur = parseFloat(pm[1]), lo = parseFloat(pm[2]), hi = parseFloat(pm[3])
      const mid = (lo + hi) / 2
      if (cur < lo - 1) { bot.clickWindow(0, 0, 0); await wait(160) }
      else if (cur > hi + 1) { bot.clickWindow(8, 0, 0); await wait(160) }
      else await wait(200)
      if (i % 20 === 0) log(`    [${i}] 压力 ${cur} 目标 ${lo}~${hi}`)
    }
    dump(bot, '开刃后')
  }

  // ---------- 淬火：选第一个介质 ----------
  if (phase(bot) === 'QUENCH') {
    log('  --- 淬火：选 10 号介质 ---')
    dump(bot, '淬火')
    bot.clickWindow(10, 0, 0)
    await wait(2600)
  }

  // ---------- 结果 ----------
  const fin = dump(bot, '最终')
  const hp = phase(bot)
  log(`>>> 最终阶段: ${hp}`)
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))
  if (bot.heldItem && /_sword$|_axe$/.test(bot.heldItem.name)) {
    lore(bot.heldItem).forEach((l, i) => log(`  lore[${i}] ${l}`))
  }
  if (hp !== 'RESULT') note(`流程没走到结果界面，停在 ${hp}`)
  if (!bot.heldItem || !/_sword$/.test(bot.heldItem.name)) note('锻造完成后主手没有剑')

  log('')
  if (!problems.length) log('=== 无问题 ===')
  else { log(`=== ${problems.length} 处问题 ===`); problems.forEach(x => log('  ' + x)) }
  bot.quit(); process.exit(0)
})().catch(e => { log('FATAL:', e.stack || e.message); process.exit(1) })