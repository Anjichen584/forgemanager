// bot 测试公共库：连接、lore 文本提取、界面转储、阶段识别
const mineflayer = require('mineflayer')
const nbt = require('prismarine-nbt')

const HOST = '127.0.0.1'
const PORT = 25565  // v2.26.5：随服务器迁移
const VERSION = '1.21.10'  // v2.26.5：随服务器迁移（paper-1.21.10-130）
const GLASS = /_stained_glass_pane$/

const wait = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(...a)

/** 从简化后的 chat component 里抽纯文本 */
function textOf(c) {
  if (c == null) return ''
  if (typeof c === 'string') return c
  if (typeof c === 'number' || typeof c === 'boolean') return String(c)
  let s = ''
  if (typeof c.text === 'string') s += c.text
  if (Array.isArray(c.extra)) s += c.extra.map(textOf).join('')
  return s
}

/** 从 components 数组里取 custom_name（1.20.5+ 的自定义显示名） */
function customName(it) {
  if (!it || !Array.isArray(it.components)) return ''
  const cn = it.components.find(c => c && c.type === 'custom_name')
  if (!cn) return ''
  try {
    return textOf(nbt.simplify({ type: 'compound', value: cn.data.value }))
  } catch (e) {
    return ''
  }
}

/** ItemStack → lore 文本行数组 */
function lore(it) {
  if (!it) return []
  const raw = it.customLore || it.lore || []
  return raw.map(entry => {
    try {
      return textOf(nbt.simplify({ type: 'compound', value: entry.value }))
    } catch (e) {
      return String(entry)
    }
  })
}

/** 物品的「标题文本」：custom_name 优先，退化为 lore 首行 */
function title(it) {
  if (!it) return ''
  return customName(it) || lore(it)[0] || ''
}

/** 物品名（去掉 minecraft: 前缀） */
function nameOf(it) {
  return it ? it.name : '(空)'
}

/** 某格是否「有内容」（排除装饰玻璃板与空） */
function isContent(it) {
  return !!it && !GLASS.test(it.name)
}

/** 创建 bot 并等 spawn */
async function join(botName = 'TestBot') {
  const bot = mineflayer.createBot({
    host: HOST, port: PORT, username: botName, auth: 'offline', version: VERSION
  })
  bot.problems = []
  bot.on('error', e => log('bot error:', e.message))
  bot.on('kicked', r => log('kicked:', JSON.stringify(r).slice(0, 300)))
  await new Promise((res, rej) => {
    bot.once('spawn', res)
    setTimeout(() => rej(new Error('spawn 超时')), 30000)
  })
  await wait(2000)
  return bot
}

/** 只打印铸剑相关聊天 */
function traceForge(bot) {
  bot.on('message', m => {
    const s = m.toString()
    if (s.includes('铸剑')) log('  » ' + s.slice(0, 200))
  })
}

/** 转储当前窗口的前 27 格 */
function dump(bot, tag, withLore = false) {
  const w = bot.currentWindow
  if (!w) { log(`[${tag}] ✗ 没有窗口`); return null }
  const real = []
  for (let i = 0; i < Math.min(w.slots.length, 27); i++) {
    const it = w.slots[i]
    if (isContent(it)) real.push(`${i}=${it.name}`)
  }
  log(`[${tag}] ${real.join('  ') || '(无内容)'}`)
  if (withLore) {
    for (let i = 0; i < Math.min(w.slots.length, 27); i++) {
      const it = w.slots[i]
      if (isContent(it)) log(`    ${i}=${it.name} :: ${lore(it).join(' ⏎ ').slice(0, 320)}`)
    }
  }
  return w
}

/** 界面识别：靠 4 号槽的标题行（各阶段 4 号都放了说明纸/图标） */
function stageOf(bot, expected) {
  const w = bot.currentWindow
  if (!w) return '(无窗口)'
  const title = lore(w.slots[4]).join(' ')
  if (title.includes('折叠钢坯')) return 'FOLDING'
  if (title.includes('开刃') || title.includes('研磨')) return 'GRINDING'
  if (title.includes('淬火')) return 'QUENCHING'
  if (title.includes('锻打') || title.includes('敲')) return 'HAMMERING'
  if (title.includes('炉温') || title.includes('加热')) return 'HEATING'
  if (title.includes('成')) return 'RESULT'
  return '(未知)'
}

async function chat(bot, cmd, ms = 900) { bot.chat(cmd); await wait(ms) }

async function closeWin(bot) {
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await wait(700) }
}

module.exports = { wait, log, lore, title, customName, nameOf, isContent, join, traceForge, dump, stageOf, chat, closeWin, GLASS, HOST, PORT }