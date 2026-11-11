const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let bot; const msgs = []
const win = () => bot.currentWindow
const chat = async (c, ms = 1200) => { bot.chat(c); await wait(ms) }
const take = () => { const m = msgs.slice(); msgs.length = 0; return m }

async function main() {
  bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  bot.on('message', m => { const s = m.toString(); if (s.trim()) msgs.push(s) })
  bot.on('error', e => log('err:', e.message))
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('超时')), 30000) })
  await wait(2500)
  await chat('/gamemode creative TestBot', 700)
  await chat('/zhujian money give TestBot 999999', 800)
  for (const m of ['iron_ingot','diamond','coal','oak_planks']) { bot.chat(`/give TestBot minecraft:${m} 64`); await wait(300) }
  await wait(800)
  await chat('/zhujian give TestBot iron LEGENDARY 90', 1600)
  for (let s = 0; s < 9; s++) { const it = bot.inventory.slots[36+s]; if (it && it.name === 'iron_sword') { bot.setQuickBarSlot(s); await wait(400); break } }

  // 找一本已有的谱
  // 先看谱库
  take(); await chat('/zhujian manual', 1800)
  const listMsgs = take()
  log('[谱库] ' + listMsgs.map(s=>s.slice(0,110)).join(' | '))
  let id = null
  for (const s of listMsgs) { const m = s.match(/([A-Z0-9]{4})/); if (m && !/^\d+$/.test(m[1])) { id = m[1]; break } }

  if (!id) {
    log('没谱 → 登记手上那把')
    for (let s = 0; s < 9; s++) { const it = bot.inventory.slots[36+s]; if (it && it.name === 'iron_sword') { bot.setQuickBarSlot(s); await wait(400); break } }
    log('  主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))
    take(); await chat('/zhujian record', 2000)
    const rm = take()
    log('  [登记] ' + rm.map(s=>s.slice(0,110)).join(' | '))
    for (const s of rm) { const m = s.match(/\(([A-Z0-9]{4,10})\)/); if (m) { id = m[1]; break } }
  }
  if (!id) { id = '87FD'; log('回退到已知谱号 87FD') }
  log('用谱号: ' + id)

  // 场景 A：开工后直接关界面
  take(); await chat(`/zhujian copy ${id}`, 1300)
  log('[开临摹] ' + take().map(s=>s.slice(0,90)).join(' | '))
  await chat('/zhujian', 1100); bot.clickWindow(11, 0, 0); await wait(1200)
  take(); bot.clickWindow(10, 0, 0); await wait(1800)   // 铁剑胚
  log('[开工] ' + (win() ? '在锻造界面' : '没进'))

  take()
  if (win()) { bot.closeWindow(win()); await wait(1800) }   // 直接关，不点放弃
  const closeMsgs = take()
  log('[关界面] ' + closeMsgs.map(s=>s.slice(0,110)).join(' | '))
  log(closeMsgs.some(s=>s.includes('临摹已取消')) ? '  ✓ 关界面也解锁了' : '  ✗ 关界面没解锁')

  // 场景 B：copy stop 命令
  take(); await chat(`/zhujian copy ${id}`, 1200)
  log('[再开临摹] ' + take().map(s=>s.slice(0,80)).join(' | '))
  take(); await chat('/zhujian copy stop', 1200)
  log('[copy stop] ' + take().map(s=>s.slice(0,80)).join(' | '))
  take(); await chat('/zhujian copy', 1100)
  const st = take()
  log('[查状态] ' + st.map(s=>s.slice(0,80)).join(' | '))
  log(!st.some(s=>s.includes('你正在临摹')) ? '  ✓ stop 生效，状态已清' : '  ✗ stop 没清掉')

  // 场景 C：重复 stop
  take(); await chat('/zhujian copy stop', 1100)
  log('[重复stop] ' + take().map(s=>s.slice(0,80)).join(' | '))

  bot.quit(); process.exit(0)
}
main().catch(e => { log('FATAL:', e.message); process.exit(1) })
