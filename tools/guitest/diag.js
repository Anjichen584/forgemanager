// 诊断：监听 windowOpen / windowClose / 聊天，确认点击主菜单 13 后到底发生了什么
const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)

let bot

// 把背包里第一把锻造剑拿到手上：先试 equip，再退化成切 hotbar
async function holdSword() {
  const sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (!sword) { log('！背包里没有剑'); return false }
  if (sword.slot >= 36 && sword.slot <= 44) {
    bot.setQuickBarSlot(sword.slot - 36)
    await wait(600)
  } else {
    try { await bot.equip(sword, 'hand'); await wait(600) } catch (e) { log('equip 失败: ' + e.message) }
  }
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))
  if (bot.heldItem) {
    log('主手 lore: ' + JSON.stringify((bot.heldItem.customLore || []).map(x => x.toString())))
  }
  return bot.heldItem && /_sword$/.test(bot.heldItem.name)
}

async function dumpWindow(tag) {
  const w = bot.currentWindow
  if (!w) { log(`[${tag}] 没有窗口`); return null }
  const real = []
  for (let i = 0; i < w.slots.length; i++) {
    const it = w.slots[i]
    if (it && !/stained_glass_pane/.test(it.name)) real.push(`${i}=${it.name}`)
  }
  log(`[${tag}] size=${w.slots.length} :: ${real.join('  ')}`)
  return w
}

async function main() {
  bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25577, username: 'DiagBot', auth: 'offline', version: '1.21.11'
  })
  bot.on('error', e => log('bot error:', e.message))
  bot.on('kicked', r => log('kicked:', JSON.stringify(r).slice(0, 300)))
  bot.on('message', m => { const s = m.toString(); if (s.includes('铸剑')) log('  [聊天] ' + s.slice(0, 200)) })
  bot.on('windowOpen', w => log(`>> windowOpen size=${w.slots.length} type=${w.type}`))
  bot.on('windowClose', () => log(`<< windowClose`))

  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn 超时')), 30000) })
  log('=== 已进服 ===')
  await wait(2000)

  bot.chat('/clear')
  await wait(600)
  bot.chat('/zhujian give ' + bot.username + ' iron LEGENDARY 95')
  await wait(1800)
  if (!await holdSword()) { log('拿不到剑，退出'); bot.quit(); process.exit(1) }

  // ---------- 1. 主菜单点 13 ----------
  log('--- 主菜单点 13（词条重铸）---')
  bot.chat('/zhujian')
  await wait(1500)
  await dumpWindow('主菜单')
  bot.clickWindow(13, 0, 0)
  await wait(2500)
  await dumpWindow('点13后')

  // ---------- 2. 命令行直开 ----------
  log('--- 命令行 /zhujian strengthen reforge gui ---')
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(700) }
  bot.chat('/zhujian strengthen reforge gui')
  await wait(2000)
  await dumpWindow('命令行重铸')

  // ---------- 3. 锻造：加热阶段拉 8 次风箱，看炉温有没有涨 ----------
  log('--- 锻造：进加热界面，拉风箱 8 次 ---')
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(700) }
  bot.chat('/zhujian')
  await wait(1400)
  bot.clickWindow(11, 0, 0)
  await wait(1500)
  await dumpWindow('选剑胚')
  bot.clickWindow(10, 0, 0)
  await wait(2200)
  const heat = await dumpWindow('加热')
  if (heat) {
    const loreOf = (s) => {
      const it = heat.slots[s]
      return it ? (it.customLore || []).map(x => x.toString()).join(' | ') : '(空)'
    }
    log('  4 号炉温 lore: ' + loreOf(4))
    log('  10 号风箱 lore: ' + loreOf(10))
    for (let i = 1; i <= 8; i++) {
      bot.clickWindow(10, 0, 0)
      await wait(700)
      const cw = bot.currentWindow
      log(`  第 ${i} 次拉风箱 → 4 号: ` + (cw && cw.slots[4] ? (cw.slots[4].customLore || []).map(x => x.toString()).join(' | ') : '(无)'))
    }
    log('--- 点 13 取出（进锻打）---')
    bot.clickWindow(13, 0, 0)
    await wait(2200)
    await dumpWindow('锻打')
  }

  bot.quit(); process.exit(0)
}
main().catch(e => { log('FATAL:', e.message); process.exit(1) })