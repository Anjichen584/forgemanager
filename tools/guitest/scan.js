// GUI 玻璃板审查 bot：连测试服，逐个打开界面，扫每一格材质，再实测点击
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1'
const PORT = 25577
const wait = (ms) => new Promise(r => setTimeout(r, ms))

const GLASS = /STAINED_GLASS_PANE/
const log = (...a) => console.log(...a)

let bot

function dumpWindow(tag) {
  const win = bot.currentWindow
  if (!win) {
    log(`[${tag}] 没有打开任何窗口`)
    return null
  }
  const rows = []
  for (let i = 0; i < win.slots.length; i++) {
    const it = win.slots[i]
    const buf = []
    if (it) buf.push(`${i}:${it.name}${it.count > 1 ? ' x' + it.count : ''}`)
    else buf.push(`${i}:空`)
    rows.push(buf[0])
  }
  log(`[${tag}] 标题="${win.title || win.type}" 大小=${win.slots.length}`)
  // 只打印非玻璃板、非空的格
  const real = []
  for (let i = 0; i < win.slots.length; i++) {
    const it = win.slots[i]
    if (!it) continue
    if (GLASS.test(it.name)) continue
    real.push(`${i}=${it.name}`)
  }
  log(`[${tag}] 非玻璃板内容: ${real.join('  ') || '(无)'}`)
  return win
}

function slotName(win, slot) {
  if (!win) return '无窗口'
  const it = win.slots[slot]
  if (!it) return '空'
  return it.name
}

async function openCmd(cmd, tag) {
  bot.chat(cmd)
  await wait(1200)
  return dumpWindow(tag)
}

async function main() {
  bot = mineflayer.createBot({
    host: HOST, port: PORT, username: 'TestBot', auth: 'offline',
    version: '1.21.11', hideErrors: false
  })

  bot.on('error', e => log('bot error:', e.message))
  bot.on('kicked', r => log('kicked:', JSON.stringify(r).slice(0, 300)))

  await new Promise((res, rej) => {
    bot.once('spawn', res)
    setTimeout(() => rej(new Error('spawn 超时')), 30000)
  })
  log('=== 已进服 ===')
  await wait(2500)

  // ---------- 1. 主菜单 ----------
  const main = await openCmd('/zhujian', '主菜单')
  if (!main) { bot.quit(); return }

  const expectMain = {
    4: 'ANVIL', 11: 'IRON_SWORD', 13: 'ANVIL', 15: 'IRON_CHESTPLATE',
    18: 'PAPER', 20: 'WRITTEN_BOOK', 22: 'BOOK', 24: 'GOLDEN_SWORD',
    25: 'GOLD_INGOT', 26: 'WRITABLE_BOOK'
  }
  log('--- 主菜单按钮材质核对 ---')
  for (const [slot, want] of Object.entries(expectMain)) {
    const got = slotName(main, Number(slot))
    const ok = got === want
    log(`  ${slot} 号: 期望 ${want} / 实际 ${got} ${ok ? '✓' : '✗ 被盖或被换'}`)
  }

  // ---------- 2. 点 24 大赛，看是否真开面板 ----------
  log('--- 点击 24 号（锻造大赛）---')
  bot.clickWindow(24, 0, 0)
  await wait(1800)
  const contest = dumpWindow('点24后')

  // ---------- 3. 回到主菜单点 13 重铸 ----------
  bot.closeWindow(bot.currentWindow)
  await wait(800)
  const main2 = await openCmd('/zhujian', '主菜单2')
  log('--- 点击 13 号（词条重铸）---')
  bot.clickWindow(13, 0, 0)
  await wait(1800)
  dumpWindow('点13后')

  // ---------- 4. 点 22 图鉴 ----------
  bot.closeWindow(bot.currentWindow)
  await wait(800)
  await openCmd('/zhujian', '主菜单3')
  log('--- 点击 22 号（图鉴）---')
  bot.clickWindow(22, 0, 0)
  await wait(1500)
  dumpWindow('点22后')

  // ---------- 5. 直接扫其余界面 ----------
  for (const [cmd, tag] of [
    ['/zhujian contest gui', '赛况面板'],
    ['/zhujian rank', '排行榜'],
    ['/zhujian shop', '商会'],
    ['/zhujian tomb', '剑冢'],
    ['/zhujian tomb list', '剑冢list'],
    ['/zhujian codex', '图鉴'],
    ['/zhujian stats', '档案'],
    ['/zhujian achievements', '成就'],
    ['/zhujian gem', '宝石台'],
    ['/zhujian auction', '拍卖行'],
    ['/zhujian manuals', '剑谱库'],
    ['/zhujian contest watch', '观战面板'],
    ['/zhujian strengthen reforge gui', '重铸界面'],
  ]) {
    if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(600) }
    await openCmd(cmd, tag)
  }

  log('=== 完成 ===')
  bot.quit()
  process.exit(0)
}

main().catch(e => { log('FATAL:', e.message); process.exit(1) })
