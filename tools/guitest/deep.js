// 第二轮：给 bot 一把带词条的剑，实测重铸 / 淬火 / 锻造全流程界面，并验证点击真的生效
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

// 断言某槽位不是玻璃板
function mustNotBeGlass(w, tag, slot, why) {
  if (!w) return
  const it = w.slots[slot]
  const name = it ? it.name : '空'
  const bad = !it || GLASS.test(name)
  if (bad) {
    problems.push(`[${tag}] ${slot} 号应为「${why}」，实际是 ${name}`)
    log(`  ✗ ${slot} 号应为 ${why}，实际 ${name}`)
  } else {
    log(`  ✓ ${slot} 号 = ${name} (${why})`)
  }
}

async function chat(cmd, ms = 1200) {
  bot.chat(cmd)
  await wait(ms)
}

async function closeWin() {
  if (win()) { bot.closeWindow(win()); await wait(600) }
}

async function main() {
  bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline',
    version: '1.21.11'
  })
  bot.on('error', e => log('bot error:', e.message))
  bot.on('kicked', r => log('kicked:', JSON.stringify(r).slice(0, 200)))

  await new Promise((res, rej) => {
    bot.once('spawn', res)
    setTimeout(() => rej(new Error('spawn 超时')), 30000)
  })
  log('=== 已进服 ===')
  await wait(2500)

  // ---------- A. 给一把高品质剑 ----------
  await chat('/zhujian give TestBot iron LEGENDARY 95', 1600)
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))

  // ---------- B. 重铸界面（主手已有锻造品） ----------
  await chat('/zhujian strengthen reforge gui', 1600)
  const rf = dump('重铸界面')
  if (rf) {
    mustNotBeGlass(rf, '重铸界面', 4, '信息')
    mustNotBeGlass(rf, '重铸界面', 18, '返回')
    mustNotBeGlass(rf, '重铸界面', 26, '关闭')
    // 词条行 10..16，有多少条词条就有几个按钮
    const affixSlots = [10, 11, 12, 13, 14, 15, 16]
    const filled = affixSlots.filter(s => rf.slots[s] && !GLASS.test(rf.slots[s].name))
    log(`  词条按钮: ${filled.length} 个 -> ${filled.map(s => s + '=' + rf.slots[s].name).join(', ')}`)
    if (filled.length === 0) problems.push('[重铸界面] 一条词条按钮都没有（LEGENDARY 应有 4 条）')
  }

  // ---------- C. 点一个词条按钮，看是否进入确认态 ----------
  if (rf) {
    const before = rf.slots[10] ? rf.slots[10].name : null
    log('--- 点击 10 号词条 ---')
    bot.clickWindow(10, 0, 0)
    await wait(1200)
    const after = dump('点词条后')
    if (after && after.slots[10] && after.slots[10].name === 'red_stained_glass_pane') {
      log('  ✓ 进入二次确认态（变红玻璃板）')
    } else {
      log('  ? 未变成红色，材质=' + (after && after.slots[10] ? after.slots[10].name : '空'))
    }
    void before
  }

  // ---------- D. 淬火界面的介质按钮 ----------
  await closeWin()
  await chat('/zhujian', 1200)
  log('--- 点 11 号（锻造武器）---')
  bot.clickWindow(11, 0, 0)
  await wait(1500)
  const wsel = dump('选剑胚')
  if (wsel) mustNotBeGlass(wsel, '选剑胚', 10, '第一个剑胚')
  if (wsel) mustNotBeGlass(wsel, '选剑胚', 17, '返回')

  // ---------- E. 排行榜点指标 ----------
  await closeWin()
  await chat('/zhujian rank', 1400)
  const rk = dump('排行榜')
  if (rk) {
    for (const s of [45, 46, 47, 48, 50]) mustNotBeGlass(rk, '排行榜', s, '指标')
    mustNotBeGlass(rk, '排行榜', 49, '关闭')
    log('--- 点 46 号指标，看是否换榜 ---')
    const t0 = rk.slots[45] ? rk.slots[45].name : null
    bot.clickWindow(46, 0, 0)
    await wait(1400)
    const rk2 = dump('点指标后')
    if (rk2) log('  45 号材质 ' + t0 + ' -> ' + (rk2.slots[45] ? rk2.slots[45].name : '空'))
  }

  // ---------- F. 宝石台 5 个按钮全点一遍 ----------
  await closeWin()
  await chat('/zhujian gem', 1300)
  const gm = dump('宝石台')
  if (gm) {
    for (const [s, why] of [[11, '开孔'], [13, '星熔'], [15, '镶嵌'], [20, '拆卸'], [24, '合成']]) {
      mustNotBeGlass(gm, '宝石台', s, why)
    }
    log('--- 点 13 号（星熔宝石）---')
    bot.clickWindow(13, 0, 0)
    await wait(1400)
    dump('点星熔后')
  }

  // ---------- G. 委托板 ----------
  await closeWin()
  await chat('/zhujian commission', 1400)
  const cb = dump('委托板')

  // ---------- 汇总 ----------
  log('')
  if (problems.length === 0) {
    log('=== 无问题 ===')
  } else {
    log('=== 发现 ' + problems.length + ' 处问题 ===')
    problems.forEach(p => log('  ' + p))
  }
  bot.quit()
  process.exit(0)
}

main().catch(e => { log('FATAL:', e.message); process.exit(1) })
