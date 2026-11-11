// GUI 暴力点击：每个界面把 0..26 每一格都点一遍，服务端若有异常会被日志抓到
const { wait, log, lore, title, isContent, join, traceForge, chat, closeWin } = require('./common')
const fs = require('fs')

const LOG = 'D:/IDEA/mc-plugins/ForgingMaster/testserver/logs/latest.log'

/** 记录当前日志行数，用于事后 diff */
function logSize() {
  try { return fs.statSync(LOG).size } catch (e) { return 0 }
}
/** 从某字节偏移开始读新增日志，抓 ERROR/Exception */
function readNew(from) {
  try {
    const buf = fs.readFileSync(LOG)
    if (buf.length <= from) return ''
    return buf.slice(from).toString('utf8')
  } catch (e) { return '' }
}

const MENUS = [
  ['/zhujian', '主菜单'],
  ['/zhujian contest', '大赛'],
  ['/zhujian rank', '排行榜'],
  ['/zhujian shop', '商会'],
  ['/zhujian tomb', '剑冢'],
  ['/zhujian codex', '图鉴'],
  ['/zhujian stats', '档案'],
  ['/zhujian achievements', '成就'],
  ['/zhujian gem', '宝石台'],
  ['/zhujian auction', '拍卖行'],
  ['/zhujian manual', '剑谱库'],
  ['/zhujian board', '委托板'],
  ['/zhujian season', '赛季'],
  ['/zhujian top', '名人堂'],
  ['/zhujian env', '环境'],
  ['/zhujian story', '剧情'],
  ['/zhujian talent', '天赋'],
  ['/zhujian profile', '档案面板'],
  ['/zhujian strengthen', '强化'],
  ['/zhujian strengthen reforge gui', '重铸界面'],
]

;(async () => {
  const bot = await join('FuzzBot')
  traceForge(bot)
  await chat(bot, '/gamemode creative', 700)
  await chat(bot, '/zhujian money give ' + bot.username + ' 500000', 900)
  for (const m of ['iron_ingot', 'coal', 'oak_planks', 'copper_ingot', 'diamond', 'gold_ingot', 'copper_block', 'diamond_block', 'iron_block', 'gold_block', 'netherite_block']) {
    await chat(bot, '/give ' + bot.username + ' minecraft:' + m + ' 64', 320)
  }
  // 给一把带词条的锻造剑并拿在手上
  await chat(bot, '/zhujian give ' + bot.username + ' iron LEGENDARY 95', 1500)
  const sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (sword) {
    if (sword.slot >= 36 && sword.slot <= 44) bot.setQuickBarSlot(sword.slot - 36)
    else { try { await bot.equip(sword, 'hand') } catch (e) { log('equip 失败') } }
    await wait(700)
  }
  log('主手: ' + (bot.heldItem ? bot.heldItem.name : '无'))

  const errors = []
  for (const [cmd, tag] of MENUS) {
    await closeWin(bot)
    let from = logSize()
    bot.chat(cmd)
    await wait(1300)
    const w = bot.currentWindow
    if (!w) { log(`[${tag}] 没有开窗（命令 ${cmd}）`); continue }
    log(`[${tag}] size=${w.slots.length}，开始逐格点`)

    for (let s = 0; s < Math.min(w.slots.length, 27); s++) {
      const cw = bot.currentWindow
      if (!cw) break
      const it = cw.slots[s]
      if (!it) continue
      from = logSize()
      try { bot.clickWindow(s, 0, 0) } catch (e) { /* 忽略客户端报错 */ }
      await wait(230)
      const delta = readNew(from)
      const hit = delta.split('\n').filter(l => /ERROR|Exception|\tat com\.forgemaster/.test(l))
      if (hit.length) {
        errors.push({ tag, slot: s, name: it.name, lines: hit.slice(0, 6) })
        log(`  ✗ ${s}=${it.name} 触发异常:`)
        hit.slice(0, 4).forEach(l => log('      ' + l.trim().slice(0, 180)))
      }
      // 界面被点没了就重开
      if (!bot.currentWindow) {
        bot.chat(cmd)
        await wait(900)
      }
    }
    await closeWin(bot)
  }

  log('')
  if (!errors.length) log('=== 无服务端异常 ===')
  else {
    log(`=== ${errors.length} 处触发异常 ===`)
    errors.forEach(e => log(`  [${e.tag}] ${e.slot}=${e.name} → ${e.lines[0].trim().slice(0, 160)}`))
  }
  bot.quit(); process.exit(0)
})().catch(e => { log('FATAL:', e.stack || e.message); process.exit(1) })