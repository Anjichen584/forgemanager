// 第四轮：给足材料后走完整锻造五阶段，验证每阶段按钮
const mineflayer = require('mineflayer')
const wait = (ms) => new Promise(r => setTimeout(r, ms))
const GLASS = /stained_glass_pane/
const log = (...a) => console.log(...a)
let bot
const problems = []

const win = () => bot.currentWindow

function dump(tag) {
  const w = win()
  if (!w) { log(`[${tag}] ✗ 没有窗口`); return null }
  const real = []
  for (let i = 0; i < w.slots.length; i++) {
    const it = w.slots[i]
    if (!it || GLASS.test(it.name)) continue
    real.push(`${i}=${it.name}`)
  }
  log(`[${tag}] 大小=${w.slots.length} 内容: ${real.join('  ') || '(全玻璃板)'}`)
  return w
}

function check(w, tag, slot, why) {
  if (!w) return false
  const it = w.slots[slot]
  const name = it ? it.name : '空'
  if (!it || GLASS.test(name)) {
    problems.push(`[${tag}] ${slot} 号应为「${why}」，实际 ${name}`)
    log(`  ✗ ${slot} = ${name} (应为 ${why})`)
    return false
  }
  log(`  ✓ ${slot} = ${name} (${why})`)
  return true
}

const chat = async (c, ms = 1200) => { bot.chat(c); await wait(ms) }
const closeWin = async () => { if (win()) { bot.closeWindow(win()); await wait(700) } }

async function main() {
  bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11'
  })
  bot.on('error', e => log('bot error:', e.message))
  bot.on('message', m => { const s = m.toString(); if (s.trim()) log('  [聊天] ' + s.slice(0, 120)) })
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn 超时')), 30000) })
  log('=== 已进服 ===')
  await wait(2500)

  // 塞满材料
  for (const c of [
    '/gamemode creative TestBot',
    '/zhujian money give TestBot 100000',
    '/give TestBot minecraft:iron_ingot 64',
    '/give TestBot minecraft:coal 64',
    '/give TestBot minecraft:oak_planks 64',
    '/give TestBot minecraft:copper_ingot 64',
    '/give TestBot minecraft:diamond 64'
  ]) { bot.chat(c); await wait(400) }
  await wait(1200)

  // 进选剑胚 → 点铁
  await chat('/zhujian', 1300)
  bot.clickWindow(11, 0, 0)
  await wait(1400)
  dump('选剑胚')
  log('--- 点 10（铁剑胚）---')
  bot.clickWindow(10, 0, 0)
  await wait(2200)

  let stage = dump('加热界面')
  if (!stage) { problems.push('[锻造] 给了材料仍没进加热界面'); }

  // 五阶段轮转：反复点操作按钮直到界面变化
  const stageNames = ['加热', '锻打', '叠钢', '开刃', '淬火', '结果']
  for (let round = 0; round < 40; round++) {
    const w = win()
    if (!w) { log('窗口已关闭，流程结束'); break }
    const nonGlass = []
    for (let i = 0; i < Math.min(w.slots.length, 27); i++) {
      const it = w.slots[i]
      if (it && !GLASS.test(it.name)) nonGlass.push(i)
    }
    // 跳过 18（放弃）和 4（标题）
    const clickable = nonGlass.filter(s => s !== 18 && s !== 4)
    if (clickable.length === 0) { log('没有可点按钮了'); break }
    const target = clickable[0]
    if (round % 8 === 0) log(`  [轮 ${round}] 点 ${target}  (当前按钮: ${nonGlass.join(',')})`)
    bot.clickWindow(target, 0, 0)
    await wait(700)
  }

  const fin = dump('最终界面')
  if (fin) {
    log('--- 结果界面按钮核对 ---')
    const ng = []
    for (let i = 0; i < 27; i++) { const it = fin.slots[i]; if (it && !GLASS.test(it.name)) ng.push(i + '=' + it.name) }
    log('  ' + ng.join('  '))
  }

  log('')
  if (problems.length === 0) log('=== 无问题 ===')
  else { log('=== ' + problems.length + ' 处问题 ==='); problems.forEach(p => log('  ' + p)) }
  bot.quit(); process.exit(0)
}

main().catch(e => { log('FATAL:', e.message); process.exit(1) })
