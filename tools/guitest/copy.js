// 临摹解锁实测：开临摹 → 换剑胚被拒 → 放弃锻造 → 换剑胚应放行
const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const GLASS = /stained_glass_pane/
const log = (...a) => console.log(...a)
let bot
const msgs = []

const win = () => bot.currentWindow
const chat = async (c, ms = 1200) => { bot.chat(c); await wait(ms) }

function takeMsgs() { const m = msgs.slice(); msgs.length = 0; return m }

async function main() {
  bot = mineflayer.createBot({
    host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11'
  })
  bot.on('error', e => log('err:', e.message))
  bot.on('message', m => { const s = m.toString(); if (s.trim()) msgs.push(s) })

  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn超时')), 30000) })
  log('=== 进服 ===')
  await wait(2500)

  await chat('/gamemode creative TestBot', 700)
  await chat('/zhujian money give TestBot 999999', 800)
  for (const m of ['iron_ingot', 'diamond', 'coal', 'oak_planks', 'copper_ingot']) {
    bot.chat(`/give TestBot minecraft:${m} 64`); await wait(350)
  }
  await wait(1000)

  // 1. 拿一把剑并登记成谱
  await chat('/zhujian give TestBot iron LEGENDARY 95', 1800)
  // give 的剑不一定落在当前热键栏槽，找一下再切过去
  for (let s = 0; s < 9; s++) {
    const it = bot.inventory.slots[36 + s]
    if (it && it.name === 'iron_sword') { bot.setQuickBarSlot(s); await wait(500); break }
  }
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))
  takeMsgs()
  await chat('/zhujian record', 1800)
  const recMsgs = takeMsgs()
  log('[登记] ' + recMsgs.map(s => s.slice(0, 110)).join(' | '))

  // 从消息里抠出谱号
  let manualId = null
  for (const s of recMsgs) {
    const m = s.match(/谱号[：:]\s*([A-Z0-9]+)/i) || s.match(/\(([A-Z0-9]{4,10})\)/)
    if (m) { manualId = m[1]; break }
  }
  log('谱号: ' + manualId)
  if (!manualId) { log('✗ 没拿到谱号，中止'); bot.quit(); process.exit(1) }

  // 2. 开临摹
  takeMsgs()
  await chat(`/zhujian copy ${manualId}`, 1500)
  log('[开临摹] ' + takeMsgs().map(s => s.slice(0, 110)).join(' | '))

  // 3. 确认锁定：/zhujian copy 无参应显示正在临摹
  takeMsgs()
  await chat('/zhujian copy', 1300)
  log('[查状态] ' + takeMsgs().map(s => s.slice(0, 120)).join(' | '))

  // 4. 试锻另一个剑胚（钻石），应被拒绝
  await chat('/zhujian', 1200)
  let w = win()
  if (!w) { log('✗ 主菜单没开'); bot.quit(); process.exit(1) }
  bot.clickWindow(11, 0, 0); await wait(1300)
  w = win()
  if (!w) { log('✗ 选剑胚没开'); bot.quit(); process.exit(1) }
  // 找钻石（13 号）——与铁不同的剑胚
  takeMsgs()
  log('--- 点 13（钻石剑胚），应被临摹拦下 ---')
  bot.clickWindow(13, 0, 0); await wait(1600)
  const blockMsgs = takeMsgs()
  log('[结果] ' + blockMsgs.map(s => s.slice(0, 130)).join(' | '))
  const blocked = blockMsgs.some(s => s.includes('临摹') || s.includes('只能用'))
  log(blocked ? '  ✓ 确实被拦住了（符合预期）' : '  ✗ 没被拦住')
  w = win()
  log('  窗口还在吗: ' + (w ? '在（被拒后停在选胚界面）' : '关了'))

  // 5. 用铁剑胚开工，然后中途放弃（点 18）
  if (!w) { await chat('/zhujian', 1200); bot.clickWindow(11, 0, 0); await wait(1300) }
  w = win()
  takeMsgs()
  log('--- 点 10（铁剑胚，与谱一致）应能开工 ---')
  bot.clickWindow(10, 0, 0); await wait(2000)
  log('[开工] ' + takeMsgs().map(s => s.slice(0, 100)).join(' | '))
  w = win()
  if (!w) { log('✗ 没进锻造'); bot.quit(); process.exit(1) }

  takeMsgs()
  log('--- 点 18（放弃锻造）---')
  bot.clickWindow(18, 0, 0); await wait(1800)
  const quitMsgs = takeMsgs()
  log('[放弃] ' + quitMsgs.map(s => s.slice(0, 130)).join(' | '))
  const released = quitMsgs.some(s => s.includes('临摹已取消'))
  log(released ? '  ✓ 放弃时提示了解锁' : '  ✗ 放弃时没提示解锁')

  // 6. 关键验证：现在锻钻石剑胚应该放行
  await wait(800)
  await chat('/zhujian', 1200)
  bot.clickWindow(11, 0, 0); await wait(1300)
  w = win()
  if (!w) { log('✗ 选胚没开'); bot.quit(); process.exit(1) }
  takeMsgs()
  log('--- 再点 13（钻石剑胚），修好后应能开工 ---')
  bot.clickWindow(13, 0, 0); await wait(2000)
  const afterMsgs = takeMsgs()
  log('[结果] ' + afterMsgs.map(s => s.slice(0, 130)).join(' | '))
  const opened = !!win()
  const stillBlocked = afterMsgs.some(s => s.includes('只能用'))
  if (opened && !stillBlocked) log('  ✓✓ 修复生效：放弃后能锻别的剑胚了')
  else if (stillBlocked) log('  ✗ 还被锁着（没修好）')
  else log('  ? 状态不明')

  log('')
  log(released && opened && !stillBlocked ? '=== 全部通过 ===' : '=== 有问题 ===')
  bot.quit(); process.exit(0)
}
main().catch(e => { log('FATAL:', e.message); process.exit(1) })
