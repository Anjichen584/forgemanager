// 第三轮：主菜单入口全链路 + 锻造五阶段按钮
const mineflayer = require('mineflayer')
const wait = (ms) => new Promise(r => setTimeout(r, ms))
const GLASS = /stained_glass_pane/
const log = (...a) => console.log(...a)
let bot
const problems = []

function win() { return bot.currentWindow }

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
  if (!w) return
  const it = w.slots[slot]
  const name = it ? it.name : '空'
  if (!it || GLASS.test(name)) {
    problems.push(`[${tag}] ${slot} 号应为「${why}」，实际 ${name}`)
    log(`  ✗ ${slot} = ${name} (应为 ${why})`)
  } else {
    log(`  ✓ ${slot} = ${name} (${why})`)
  }
}

async function chat(cmd, ms = 1300) { bot.chat(cmd); await wait(ms) }
async function closeWin() { if (win()) { bot.closeWindow(win()); await wait(700) } }

async function main() {
  bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11'
  })
  bot.on('error', e => log('bot error:', e.message))
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn 超时')), 30000) })
  log('=== 已进服 ===')
  await wait(2500)

  // 拿一把带词条的剑（主手）
  await chat('/zhujian give TestBot iron LEGENDARY 95', 1600)
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))

  // ---------- 1. 主菜单 13 → 重铸界面（这一轮主手有货，应该放行） ----------
  await chat('/zhujian', 1300)
  const m = dump('主菜单')
  check(m, '主菜单', 13, '词条重铸')
  check(m, '主菜单', 24, '锻造大赛')
  log('--- 点 13，验证是否真开重铸界面 ---')
  bot.clickWindow(13, 0, 0)
  await wait(2000)
  const rf = dump('点13后')
  if (!rf) {
    problems.push('[主菜单13] 点击后没有打开任何界面')
  } else {
    check(rf, '重铸界面', 4, '信息')
    check(rf, '重铸界面', 18, '返回')
    check(rf, '重铸界面', 26, '关闭')
  }

  // ---------- 2. 主菜单 11 → 选剑胚 → 点 iron_ingot → 加热界面 ----------
  await closeWin()
  await chat('/zhujian', 1300)
  bot.clickWindow(11, 0, 0)
  await wait(1500)
  const wsel = dump('选剑胚')
  check(wsel, '选剑胚', 10, '铁剑胚')
  log('--- 点 10（铁剑胚），应进入锻造第一阶段 ---')
  bot.clickWindow(10, 0, 0)
  await wait(2000)
  const heat = dump('加热界面')
  if (heat) {
    check(heat, '加热', 4, '炉温')
    check(heat, '加热', 10, '吹火/操作')
    check(heat, '加热', 13, '操作')
    check(heat, '加热', 16, '材料')
    check(heat, '加热', 18, '放弃')
  } else {
    problems.push('[选剑胚] 点铁剑胚后没进锻造界面')
  }

  // ---------- 3. 加热：找到炉温按钮反复点，直到进入锻打 ----------
  if (heat) {
    for (let round = 0; round < 8; round++) {
      const w = win()
      if (!w) break
      // 加热阶段的按钮槽（10/13/16 里挑非玻璃板的）
      const cand = [10, 13, 16].filter(s => w.slots[s] && !GLASS.test(w.slots[s].name) && s !== 18)
      if (cand.length === 0) break
      log(`  [加热第 ${round + 1} 次] 点 ${cand[0]}`)
      bot.clickWindow(cand[0], 0, 0)
      await wait(900)
      const after = win()
      if (!after) { log('  阶段结束（窗口关闭）'); break }
      const has = [10, 13, 16].filter(s => after.slots[s] && !GLASS.test(after.slots[s].name))
      if (has.length !== cand.length) { dump(`加热后变化`); }
    }
    dump('加热阶段末')
  }

  // ---------- 4. 锻打界面 ----------
  const now = win()
  if (now) {
    log('--- 当前界面按钮扫描 ---')
    for (let i = 0; i < now.slots.length; i++) {
      const it = now.slots[i]
      if (it && !GLASS.test(it.name)) log(`  ${i}=${it.name}`)
    }
  }

  log('')
  if (problems.length === 0) log('=== 无问题 ===')
  else { log('=== ' + problems.length + ' 处问题 ==='); problems.forEach(p => log('  ' + p)) }
  bot.quit(); process.exit(0)
}

main().catch(e => { log('FATAL:', e.message); process.exit(1) })
