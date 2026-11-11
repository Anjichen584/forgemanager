// 全功能 bot 实测：命令分派 + GUI 契约 + 满配物品链路 + 原创附魔台闭环
//
// 设计原则：
// 1. 命令清单从 ForgingCommand 的真实 switch 分支抄下来，避免“测了别名、漏了主名”。
// 2. 每个命令先当“无参调用”跑一遍：只断言不出现未知子命令 / 内部异常，
//    有窗口就记录窗口尺寸与内容格数，不把“该开 GUI”写死在脚本里。
// 3. 真正的功能闭环单独放在 deep 段：满配发放 -> 装备 -> 战力/修复/强化 -> 附魔台产书 -> 再升级。
// 4. 任何一项失败都把命令、聊天回执、窗口快照写进 JSON，方便复现。
const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')
const { wait, lore, title, isContent, join, chat } = require('./common')

const BOT_NAME = process.env.FULLTEST_BOT || 'TestBot'
const REPORT = path.join(__dirname, 'fulltest-report.json')

// ForgingCommand 顶层 switch 的主名（别名不重复跑；别名各留一条在 alias 段）
const TOP_LEVEL = [
  'reload', 'give', 'spirit', 'tomb', 'tombrun', 'achievements', 'top', 'stats',
  'profile', 'rank', 'season', 'codex', 'relicset', 'collection', 'salvage',
  'dissolve', 'imprint', 'board', 'duel', 'repair', 'manual', 'record', 'copy',
  'contest', 'grade', 'gem', 'enchant', 'ench', 'strengthen', 'hammer', 'evolve',
  'contract', 'shangjin', 'money', 'env', 'story', 'spectrum', 'pass', 'halloffame',
  'hall', 'power', 'fixattrs', 'config', 'metrics', 'givefull', 'auction', 'order',
  'master', 'title', 'accept', 'guild', 'shop', 'blackmarket', 'signin', 'talent',
  'forge', 'event', 'backup', 'restore', 'help'
]

// selftest 由 RCON 单独跑（输出 483 行，走 bot 聊天会把有效回执淹没）
const SKIP = new Set(['selftest'])
const SLOW = new Set(['reload', 'backup', 'restore', 'metrics', 'give', 'givefull'])

const results = []
let bot
let chatLines = []
let currentTag = ''

function push(status, area, name, detail) {
  const row = { status, area, name, detail, at: new Date().toISOString() }
  results.push(row)
  const mark = status === 'PASS' ? '✓' : status === 'WARN' ? '!' : '✗'
  console.log(`  ${mark} [${area}] ${name}${detail ? ' — ' + detail : ''}`)
}

function pass(area, name, detail) { push('PASS', area, name, detail) }
function warn(area, name, detail) { push('WARN', area, name, detail) }
function fail(area, name, detail) { push('FAIL', area, name, detail) }

function onChat(m) {
  const s = m.toString()
  chatLines.push(s)
  if (chatLines.length > 300) chatLines.splice(0, chatLines.length - 300)
}

function recentChat(n = 8) {
  return chatLines.slice(-n).join(' | ').replace(/\s+/g, ' ').slice(0, 900)
}

function crashIn(text) {
  return /未知子命令|An internal error occurred|Internal error|Exception|NullPointerException|ClassCastException|IndexOutOfBoundsException|NoSuchElementException/i.test(text)
}

async function closeWin(ms = 350) {
  if (bot.currentWindow) {
    bot.closeWindow(bot.currentWindow)
    await wait(ms)
  }
}

async function runRaw(command, waitMs = 1100) {
  chatLines = []
  currentTag = command
  if (bot.currentWindow) await closeWin(250)
  bot.chat(command)
  await wait(waitMs)
  await wait(250)
  const text = chatLines.join('\n')
  return { text, window: bot.currentWindow }
}

function contentCount(win) {
  if (!win) return 0
  let n = 0
  for (const it of win.slots) if (isContent(it)) n++
  return n
}

function windowDetail(win) {
  if (!win) return ''
  const content = []
  for (let i = 0; i < win.slots.length; i++) {
    const it = win.slots[i]
    if (isContent(it) && content.length < 8) content.push(`${i}:${it.name}`)
  }
  return `窗口 ${win.slots.length} 格 / 内容 ${contentCount(win)} / ${content.join(',')}`
}

async function commandSmoke(cmd) {
  if (SKIP.has(cmd)) {
    push('SKIP', '命令', `/zhujian ${cmd}`, '由 RCON 单独执行')
    return
  }
  const ms = SLOW.has(cmd) ? 3000 : 1100
  const { text, window } = await runRaw(`/zhujian ${cmd}`, ms)
  const detail = window ? windowDetail(window) : (recentChat(3) || '(无聊天回执)')
  if (crashIn(text)) {
    fail('命令', `/zhujian ${cmd}`, recentChat(6))
  } else if (!window && !text.trim()) {
    warn('命令', `/zhujian ${cmd}`, '无窗口也无聊天回执')
  } else {
    pass('命令', `/zhujian ${cmd}`, detail)
  }
  if (window) await closeWin(250)
}

async function expectChat(area, command, regex, waitMs = 1600, display) {
  const { text, window } = await runRaw(command, waitMs)
  const name = display || command
  const detail = recentChat(4) || (window ? windowDetail(window) : '(无回执)')
  if (crashIn(text)) fail(area, name, recentChat(6))
  else if (regex && !regex.test(text)) fail(area, name, detail)
  else pass(area, name, detail)
  if (window) await closeWin(250)
  return text
}

function findWindowItem(win, pred) {
  if (!win) return null
  for (let i = 0; i < win.slots.length; i++) {
    const it = win.slots[i]
    if (it && pred(it, i)) return { slot: i, item: it }
  }
  return null
}

function findFreePlayerSlot(win) {
  if (!win) return -1
  // 容器前 N 格是 GUI，后面才是玩家背包；4 格成品/输入不碰
  for (let i = win.inventoryStart || 54; i < win.slots.length; i++) {
    if (!win.slots[i]) return i
  }
  return -1
}

async function deepItemFlow() {
  console.log('\n=== deep: 满配物品链路 ===')
  // 注意：CMI 接管了 /clear（要点击确认）且创造模式下 /give 不同步背包，
  // 所以这里强制生存模式 + 命名空间原版命令，绕过插件拦截与创造同步漏洞
  await runRaw('/minecraft:gamemode survival ' + BOT_NAME, 700)
  await runRaw('/minecraft:clear ' + BOT_NAME, 1200)
  await wait(400)
  const leftover = bot.inventory.items()
  if (leftover.length) fail('物品', '清空背包', '仍有 ' + leftover.length + ' 堆: ' + leftover.slice(0, 5).map(i => i.name).join(','))
  else pass('物品', '清空背包', '背包已空')
  await expectChat('物品', `/zhujian money coin give ${BOT_NAME} 100000`, /剑币|已给|余额/, 1400)
  await expectChat('物品', `/zhujian givefull ${BOT_NAME} iron mythic`, /已给|满配/, 2800)

  const sword = bot.inventory.items().find(it => /_sword$/.test(it.name))
  if (!sword) {
    fail('物品', 'givefull 产物', '背包里没有找到剑')
    return null
  }
  pass('物品', 'givefull 产物', `${sword.name} @${sword.slot} ${title(sword)}`)
  await bot.equip(sword, 'hand')
  await wait(600)

  const powered = await expectChat('物品', '/zhujian power', /战力|评分/, 1500)
  const info = await expectChat('物品', '/zhujian strengthen info', /强化|\+\d+/, 1500)
  await expectChat('物品', '/zhujian strengthen plan 20 100', /规划|期望/, 2200)
  await expectChat('物品', '/zhujian fixattrs', /修复|属性|无需/, 1600)
  await expectChat('物品', '/zhujian repair', /耐久|修复|无需|完好|不用修/, 1500)

  // 只读断言：Lore 里必须同时有强化段和战力段，证明不是空壳物品
  const lines = lore(bot.heldItem || sword).join('\n')
  if (/\+99/.test(lines)) pass('物品', '满配 Lore 强化段', '+99')
  else fail('物品', '满配 Lore 强化段', lines.slice(0, 500))
  if (/战力|评分/.test(lines)) pass('物品', '满配 Lore 战力段', '存在')
  else warn('物品', '满配 Lore 战力段', '未在 Lore 中看到战力，可能只走命令输出')

  if (!/战力|评分/.test(powered)) fail('物品', 'power 语义', powered.slice(0, 300))
  if (!/强化|\+\d+/.test(info)) fail('物品', 'strengthen info 语义', info.slice(0, 300))
  return sword
}

async function deepEnchantFlow() {
  console.log('\n=== deep: 原创附魔台闭环 ===')
  await runRaw('/minecraft:clear ' + BOT_NAME, 1200)
  await runRaw(`/minecraft:give ${BOT_NAME} minecraft:book 32`, 800)
  await runRaw(`/minecraft:give ${BOT_NAME} minecraft:lapis_lazuli 64`, 800)
  await expectChat('附魔', '/zhujian ench list', /原创附魔|条/, 1400)

  const { window } = await runRaw('/zhujian ench', 1400)
  if (!window) {
    fail('附魔', '打开附魔台', '没有窗口')
    return
  }
  // 容器本体 54 格，mineflayer 的 slots 还带了 36 格玩家背包，所以总数是 90
  if (window.slots.length < 54) fail('附魔', '界面尺寸', String(window.slots.length))
  else pass('附魔', '界面尺寸', String(window.slots.length))

  // 契约（EnchantGui）：4 成品 / 20 书槽 / 24 矿槽是 FREE_SLOTS，初始必须为空；
  // 22 信息 / 49 附魔按钮 / 45 关闭必须有内容
  for (const [slot, why] of [[4, '成品'], [20, '书槽'], [24, '矿槽']]) {
    const it = window.slots[slot]
    if (!it) pass('附魔', `${slot} 号 ${why}`, '空（输入/输出槽，符合设计）')
    else fail('附魔', `${slot} 号 ${why}`, '应为空槽却有 ' + it.name)
  }
  for (const [slot, why] of [[22, '信息'], [49, '附魔按钮'], [45, '关闭']]) {
    const it = window.slots[slot]
    if (it && isContent(it)) pass('附魔', `${slot} 号 ${why}`, it.name)
    else fail('附魔', `${slot} 号 ${why}`, it ? it.name : '空')
  }

  // 书与青金石在玩家背包区（容器格之后），只在背包区找，避免误点 GUI 装饰
  const invStart = window.inventoryStart || 54
  const book = findWindowItem(window, (it, i) => i >= invStart && it.name === 'book')
  const lapis = findWindowItem(window, (it, i) => i >= invStart && it.name === 'lapis_lazuli')
  if (!book || !lapis) {
    fail('附魔', '输入物定位', `书=${!!book} 青金石=${!!lapis}`)
    await closeWin()
    return
  }
  await bot.moveSlotItem(book.slot, 20)
  await wait(450)
  await bot.moveSlotItem(lapis.slot, 24)
  await wait(600)

  chatLines = []
  bot.clickWindow(49, 0, 0)
  await wait(1400)
  const first = chatLines.join('\n')
  if (/附魔成功/.test(first)) pass('附魔', '空白书生成', recentChat(2))
  else fail('附魔', '空白书生成', recentChat(5) || '(无回执)')

  const w1 = bot.currentWindow
  const made = w1 && w1.slots[4]
  if (made && made.name === 'enchanted_book') {
    pass('附魔', '成品格产出', `${title(made)} ${lore(made).slice(0, 2).join(' / ')}`)
  } else {
    fail('附魔', '成品格产出', made ? made.name : '空')
    await closeWin()
    return
  }

  // 把成品收回背包，再放回书槽做一次必定成功的升级（目标 II 概率 100%）
  const free = findFreePlayerSlot(w1)
  if (free >= 0) {
    await bot.moveSlotItem(4, free)
    await wait(500)
    await bot.moveSlotItem(free, 20)
    await wait(500)
    chatLines = []
    bot.clickWindow(49, 0, 0)
    await wait(1400)
    const second = chatLines.join('\n')
    if (/附魔成功/.test(second)) pass('附魔', '附魔书升级', recentChat(2))
    else fail('附魔', '附魔书升级', recentChat(5) || '(无回执)')
    const w2 = bot.currentWindow
    if (w2 && w2.slots[4] && w2.slots[4].name === 'enchanted_book') {
      pass('附魔', '升级产物', title(w2.slots[4]))
    } else {
      fail('附魔', '升级产物', w2 && w2.slots[4] ? w2.slots[4].name : '空')
    }
  } else {
    warn('附魔', '回收到背包', '找不到空格，跳过升级段')
  }

  const w3 = bot.currentWindow
  if (w3) {
    bot.clickWindow(45, 0, 0)
    await wait(500)
    if (!bot.currentWindow) pass('附魔', '关闭按钮', '窗口已关')
    else fail('附魔', '关闭按钮', '窗口还在')
  }
}

async function deepGuiContracts() {
  console.log('\n=== deep: GUI 契约 ===')
  const { window: main } = await runRaw('/zhujian', 1400)
  if (!main) {
    fail('GUI', '主菜单', '没有窗口')
  } else {
    const need = [4, 11, 13, 15, 18, 20, 22, 24, 25, 26]
    const missing = need.filter(s => !main.slots[s] || !isContent(main.slots[s]))
    if (missing.length) fail('GUI', '主菜单槽位', `缺 ${missing.join(',')}`)
    else pass('GUI', '主菜单槽位', '10 个入口齐')
    await closeWin()
  }

  const { window: gem } = await runRaw('/zhujian gem', 1400)
  if (!gem) {
    fail('GUI', '宝石台', '没有窗口')
  } else {
    const need = [11, 13, 15, 20, 24]
    const missing = need.filter(s => !gem.slots[s] || !isContent(gem.slots[s]))
    if (missing.length) fail('GUI', '宝石台按钮', `缺 ${missing.join(',')}`)
    else pass('GUI', '宝石台按钮', '开孔/熔炼/镶嵌/拆卸/合成 齐')
    await closeWin()
  }

  // 其余无参 GUI：只断言“能开、不是空壳”，具体槽位由各自既有脚本覆盖。
  // codex 是纯文本图鉴（GUI 版是 collection），guild 无参是文本面板、GUI 在 guild list，
  // tomb 无参即剑冢 GUI（tomb list 是同一窗口的显式入口）
  const guis = ['rank', 'board', 'manual', 'achievements', 'shop', 'blackmarket', 'auction', 'order', 'guild list', 'pass', 'title', 'collection', 'relicset', 'spectrum']
  for (const name of guis) {
    const { window } = await runRaw(`/zhujian ${name}`, 1500)
    if (!window) fail('GUI', `/zhujian ${name}`, '没有窗口')
    else if (contentCount(window) === 0) fail('GUI', `/zhujian ${name}`, windowDetail(window))
    else pass('GUI', `/zhujian ${name}`, windowDetail(window))
    await closeWin(300)
  }
}

async function deepNested() {
  console.log('\n=== deep: 关键子命令 ===')
  const nested = [
    ['strengthen sim 10', /期望|模拟|锤/],
    ['strengthen curve', /\+|曲线|概率/],
    ['gem list', /宝石|id|L[1-5]/],
    ['rank text', /排行|名|榜/],
    ['contest list', /大赛|榜|届/],
    ['metrics sample', /采样|指标|已/],
    ['metrics trend 7', /趋势|近|日/],
    ['config check', /配置|缺失|键/],
    ['money balance', /剑币|余额/]
  ]
  for (const [cmd, re] of nested) await expectChat('子命令', `/zhujian ${cmd}`, re, 2200)

  // tomb list 按设计是打开剑冢 GUI（TombManager: case "list","gui" -> openInventory）
  const { window: tombWin } = await runRaw('/zhujian tomb list', 1800)
  if (!tombWin) fail('子命令', '/zhujian tomb list', '没有窗口')
  else pass('子命令', '/zhujian tomb list', windowDetail(tombWin))
  if (tombWin) await closeWin(300)
}

async function deepAliases() {
  console.log('\n=== deep: 别名抽样 ===')
  // 两层别名都测：/zj 是 plugin.yml 的命令别名；zhanli/fumotai/qianghua/paihang 是子命令别名
  const aliases = [
    ['zj→zhujian', '/zj power', /战力|评分/],
    ['zhanli→power', '/zhujian zhanli', /战力|评分/],
    ['fumotai→ench', '/zhujian fumotai list', /原创附魔|条/],
    ['qianghua→strengthen', '/zhujian qianghua info', /强化|\+\d+/],
    ['paihang→rank', '/zhujian paihang text', /排行|名|榜/]
  ]
  for (const [tag, cmd, re] of aliases) await expectChat('别名', cmd, re, 1800, `[${tag}] ${cmd}`)
}

async function main() {
  console.log(`=== 全功能 bot 实测：${BOT_NAME} @ 127.0.0.1:25577 ===`)
  bot = await join(BOT_NAME)
  bot.on('message', onChat)
  await wait(800)
  console.log('已进服\n')

  console.log('=== 命令分派（顶层） ===')
  for (const cmd of TOP_LEVEL) await commandSmoke(cmd)

  const sword = await deepItemFlow()
  if (sword) await deepNested()
  await deepAliases()
  await deepEnchantFlow()
  await deepGuiContracts()

  const fails = results.filter(r => r.status === 'FAIL')
  const warns = results.filter(r => r.status === 'WARN')
  const passes = results.filter(r => r.status === 'PASS')
  const report = {
    bot: BOT_NAME,
    at: new Date().toISOString(),
    totals: { pass: passes.length, warn: warns.length, fail: fails.length, skip: results.filter(r => r.status === 'SKIP').length },
    results
  }
  fs.writeFileSync(REPORT, JSON.stringify(report, null, 2), 'utf8')
  console.log(`\n=== 汇总：PASS ${passes.length} / WARN ${warns.length} / FAIL ${fails.length} ===`)
  if (warns.length) {
    console.log('WARN:')
    for (const w of warns) console.log(`  - ${w.name}: ${w.detail}`)
  }
  if (fails.length) {
    console.log('FAIL:')
    for (const f of fails) console.log(`  - ${f.name}: ${f.detail}`)
  }
  console.log('报告: ' + REPORT)
  await closeWin(200)
  bot.quit()
  await wait(300)
  process.exit(fails.length ? 1 : 0)
}

main().catch(async e => {
  console.error('FATAL:', e && (e.stack || e.message))
  try { if (bot) bot.quit() } catch (_) { /* ignore */ }
  process.exit(2)
})
