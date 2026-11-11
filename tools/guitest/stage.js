const mineflayer = require('mineflayer')
const wait = ms => new Promise(r => setTimeout(r, ms))
const GLASS = /stained_glass_pane/
const log = (...a) => console.log(...a)
const problems = []
let bot
const win = () => bot.currentWindow

function dump(tag) {
  const w = win()
  if (!w) { log(`[${tag}] 没有窗口`); return null }
  const real = []
  for (let i = 0; i < Math.min(w.slots.length, 27); i++) {
    const it = w.slots[i]
    if (it && !GLASS.test(it.name)) real.push(`${i}=${it.name}`)
  }
  log(`[${tag}] ${real.join('  ') || '(全玻璃板)'}`)
  return w
}
const chat = async (c, ms = 1200) => { bot.chat(c); await wait(ms) }

async function main() {
  bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  bot.on('error', e => log('err:', e.message))
  bot.on('message', m => { const s = m.toString(); if (s.includes('铸剑')) log('  [聊天] ' + s.slice(0, 130)) })
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn 超时')), 30000) })
  await wait(2500)

  await chat('/gamemode creative TestBot', 800)
  await chat('/zhujian', 1200)
  bot.clickWindow(11, 0, 0); await wait(1300)
  bot.clickWindow(10, 0, 0); await wait(1800)
  dump('加热')

  // 拉几次风箱再取出
  for (let i = 0; i < 4; i++) { bot.clickWindow(10, 0, 0); await wait(500) }
  dump('拉风箱后')
  log('--- 点 13（取出剑坯）---')
  bot.clickWindow(13, 0, 0); await wait(1800)
  let w = dump('锻打')

  if (w) {
    log('--- 点 22（锻打）---')
    bot.clickWindow(22, 0, 0); await wait(1500)
    w = dump('锻打后')
  }
  // 继续推进：找非放弃按钮反复点
  for (let i = 0; i < 12; i++) {
    const c = win(); if (!c) break
    const cand = []
    for (let s = 0; s < Math.min(c.slots.length, 27); s++) {
      const it = c.slots[s]
      if (it && !GLASS.test(it.name) && s !== 18 && s !== 4) cand.push(s)
    }
    if (!cand.length) break
    bot.clickWindow(cand[0], 0, 0); await wait(800)
  }
  dump('阶段末')

  log(problems.length ? '=== 问题 ===' : '=== 无问题 ===')
  problems.forEach(p => log('  ' + p))
  bot.quit(); process.exit(0)
}
main().catch(e => { log('FATAL:', e.message); process.exit(1) })
