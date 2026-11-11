// 回归：手里的剑明明能蜕变，/zhujian evolve 却报「主手先拿一件锻造品」
//
// 场景 A：刚锻出来的剑（PDC 里没有 evolution 键，锻阶 = 0）
//        → 期望：不再被当成「不是锻造品」，而是弹出蜕变界面（或提示门槛等级）
// 场景 B：普通钻石剑（真不是锻造品）
//        → 期望：仍然被顶回来，提示「不是锻造品」
//
// 用法：测试服开着（25577 / rcon 25599），先装 mineflayer：
//   set NODE_PATH=D:\IDEA\mc-plugins\ForgingMaster\tools\guitest\node_modules
//   node evolve-check.js

const net = require('net')
const mineflayer = require('mineflayer')

const HOST = '127.0.0.1'
const PORT = 25577
const RCON_PORT = 25599
const RCON_PASS = 'testpass'
const BOT = 'SelfBot'

const sleep = (ms) => new Promise(r => setTimeout(r, ms))

function rcon(command) {
  return new Promise((resolve) => {
    const sock = net.connect(RCON_PORT, HOST)
    let id = 0
    let buf = Buffer.alloc(0)
    let authed = false
    const done = (text) => { try { sock.destroy() } catch (e) {} resolve(text) }
    const send = (type, body) => {
      const b = Buffer.from(body, 'utf8')
      const p = Buffer.alloc(14 + b.length)
      p.writeInt32LE(10 + b.length, 0)
      p.writeInt32LE(++id, 4)
      p.writeInt32LE(type, 8)
      b.copy(p, 12)
      p[12 + b.length] = 0
      p[13 + b.length] = 0
      sock.write(p)
    }
    sock.setTimeout(4000)
    sock.on('timeout', () => done(''))
    sock.on('error', () => done(''))
    sock.on('connect', () => send(3, RCON_PASS))
    sock.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk])
      while (buf.length >= 4) {
        const len = buf.readInt32LE(0)
        if (len < 10 || buf.length < len + 4) return
        const body = buf.slice(12, len + 4 - 2).toString('utf8')
        buf = buf.slice(len + 4)
        if (!authed) { authed = true; send(2, command); continue }
        return done(body)
      }
    })
  })
}

function safeTitle(win) {
  try {
    if (!win.title) return ''
    return typeof win.title === 'string' ? win.title : JSON.stringify(win.title)
  } catch (e) { return '' }
}

// mineflayer 把 lore 塞成带类型的 NBT：{type:'compound', value:{extra:{type:'list', value:{type:'compound', value:[{text:{type:'string',value:'✧'}}, ...]}}}}
// 直接 toString 只会得到 [object Object]，这里按 type/value 递归拍平
function flat(n) {
  if (n == null) return ''
  if (typeof n === 'string') return n
  if (typeof n === 'number' || typeof n === 'boolean') return String(n)
  if (Array.isArray(n)) return n.map(flat).join('')
  if (typeof n === 'object') {
    if (typeof n.type === 'string') {
      if (n.type === 'string') return typeof n.value === 'string' ? n.value : flat(n.value)
      if (n.type === 'list') return flat(n.value && n.value.value !== undefined ? n.value.value : n.value)
      if (n.type === 'compound') {
        const v = n.value || {}
        // 只取文字与子组件，color / italic / bold 这类样式键一律跳过
        let out = ''
        if (v.text !== undefined) out += flat(v.text)
        if (v.translate !== undefined) out += flat(v.translate)
        if (v.with !== undefined) out += flat(v.with)
        if (v.extra !== undefined) out += flat(v.extra)
        if (out === '') {
          const styleOnly = ['color', 'italic', 'bold', 'underlined', 'strikethrough', 'obfuscated', 'shadow_color', 'font']
          const keys = Object.keys(v)
          if (!keys.every(k => styleOnly.includes(k))) out = flat(Object.values(v))
        }
        return out
      }
      return ''
    }
    // 内层组件有时不带 type 包装，直接长成 {color, text, extra} 这样
    if (n.text !== undefined || n.extra !== undefined || n.translate !== undefined) {
      let out = ''
      if (n.text !== undefined) out += flat(n.text)
      if (n.translate !== undefined) out += flat(n.translate)
      if (n.with !== undefined) out += flat(n.with)
      if (n.extra !== undefined) out += flat(n.extra)
      return out
    }
    if (n.value !== undefined) return flat(n.value)
  }
  return ''
}

function loreLines(item) {
  if (!item || !item.customLore) return []
  return item.customLore.map(flat).filter(s => s && s.trim())
}

async function main() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, auth: 'offline' })
  const chat = []
  let opened = null
  bot.on('message', (m) => { const s = m.toString().trim(); if (s) { chat.push(s); console.log('[chat] ' + s) } })
  bot.on('windowOpen', (w) => {
    opened = safeTitle(w)
    console.log('[window] type=' + (w.type || '?') + ' title=' + opened)
  })
  bot.on('kicked', (r) => console.log('[kicked] ' + r))
  bot.on('error', (e) => console.log('[error] ' + e.message))

  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('spawn 超时')), 90000)
    bot.once('spawn', () => { clearTimeout(t); res() })
  })
  console.log('[bot] spawned as ' + bot.username)
  await sleep(1200)

  await rcon('op ' + BOT)
  await rcon('gamemode creative ' + BOT)
  // CMI 抢了 /clear，走原版命名空间，否则上一轮蜕变过的剑会留在背包里干扰判定
  await rcon('minecraft:clear ' + BOT)
  await sleep(800)
  if (bot.inventory.items().length) {
    console.log('[warn] 清背包没清干净，还剩: ' + bot.inventory.items().map(i => i.name).join(', '))
  }

  // ---------- 场景 A：刚锻出来的剑 ----------
  console.log('\n===== A. 刚锻好的铁剑（锻阶 0，PDC 无 evolution 键） =====')
  await rcon('zhujian give ' + BOT + ' iron common 60')
  await sleep(1500)
  let sword = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (!sword) {
    console.log('[fail] 背包里没有剑，实际物品：' + bot.inventory.items().map(i => i.name).join(', '))
  } else {
    const lore = loreLines(sword)
    console.log('[bot] 拿到 ' + sword.name + '，装备到主手')
    console.log('[bot] lore: ' + (lore.length ? lore.join(' | ') : '(无)'))
    console.log('[bot] 锻阶行: ' + (lore.some(l => l.includes('锻阶')) ? '有（不是出厂剑！）' : '无 → 未蜕变'))
    await bot.equip(sword, 'hand')
    await sleep(500)
    const before = chat.length
    opened = null
    await rcon('say §7— 场景A 开始 —')
    bot.chat('/zhujian evolve')
    await sleep(2000)
    const said = chat.slice(before).join(' | ')
    const wrong = said.includes('主手先拿一件锻造品')
    console.log('[A] 弹窗: ' + (opened || '（无）'))
    console.log('[A] 回话: ' + said)
    console.log(wrong ? '[A] FAIL —— 手里握着锻造剑还是被判成「不是锻造品」'
      : '[A] PASS —— 锻造剑被认出来了')
    if (opened) bot.closeWindow(bot.currentWindow)
  }

  // ---------- 场景 B：普通钻石剑 ----------
  console.log('\n===== B. 普通钻石剑（不是锻造品） =====')
  // CMI 抢了 /give 并且不认 @s，这里走原版命名空间
  bot.chat('/minecraft:give @s diamond_sword 1')
  await sleep(1200)
  if (!bot.inventory.items().some(i => i.name === 'diamond_sword')) {
    await rcon('minecraft:give ' + BOT + ' diamond_sword 1')
    await sleep(1200)
  }
  const plain = bot.inventory.items().find(i => i.name === 'diamond_sword')
  if (!plain) {
    console.log('[fail] 拿不到普通钻石剑，实际物品：' + bot.inventory.items().map(i => i.name).join(', '))
  } else {
    await bot.equip(plain, 'hand')
    await sleep(500)
    const before = chat.length
    opened = null
    bot.chat('/zhujian evolve')
    await sleep(1800)
    const said = chat.slice(before).join(' | ')
    const rejected = said.includes('不是锻造品') || said.includes('主手先拿一件锻造品')
    console.log('[B] 弹窗: ' + (opened || '（无）'))
    console.log('[B] 回话: ' + said)
    console.log(rejected && !opened ? '[B] PASS —— 非锻造品仍然被拦下' : '[B] FAIL —— 非锻造品居然放行了')
    if (opened) bot.closeWindow(bot.currentWindow)
  }

  // ---------- 场景 C：锻造剑 + now（直掷） ----------
  console.log('\n===== C. 锻造剑 + /zhujian evolve now（直掷路径） =====')
  if (sword) {
    await bot.equip(bot.inventory.items().find(i => /_sword$/.test(i.name)) || sword, 'hand')
    await sleep(400)
    const before = chat.length
    bot.chat('/zhujian evolve now')
    await sleep(1800)
    console.log('[C] 回话: ' + chat.slice(before).join(' | '))
  }

  // ---------- 场景 D：等级到门槛后，界面点「确认蜕变」 ----------
  console.log('\n===== D. +5 的锻造剑：界面里点确认，真蜕一次 =====')
  await rcon('zhujian money coin give ' + BOT + ' 100000')
  await rcon('minecraft:give ' + BOT + ' iron_ingot 64')
  await sleep(900)
  const forged = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (!forged) {
    console.log('[D] FAIL —— 背包里没有锻造剑')
  } else {
    await bot.equip(forged, 'hand')
    await sleep(400)
    bot.chat('/zhujian strengthen set 5')
    await sleep(1000)
    const before = chat.length
    opened = null
    bot.chat('/zhujian evolve')
    await sleep(1800)
    const win = bot.currentWindow
    if (!win) {
      console.log('[D] FAIL —— 界面没打开')
    } else {
      const loreOf = (slot) => {
        const it = win.slots[slot]
        const lines = loreLines(it)
        return lines.length ? lines.join(' / ') : '(无)'
      }
      let rateCard = loreOf(10)
      if (rateCard === '(无)') {
        await sleep(700)
        rateCard = loreOf(10)
        if (rateCard === '(无)') {
          const it = win.slots[10]
          console.log('[D][dbg] slots=' + win.slots.length + ' slot10=' + JSON.stringify(it && {
            name: it.name, lore: it.customLore
          }).slice(0, 300))
        }
      }
      console.log('[D] 槽10 成功率牌: ' + rateCard)
      console.log('[D] 槽22 确认牌: ' + loreOf(22))
      bot.clickWindow(22, 0, 0)
      await sleep(2200)
      const said = chat.slice(before).join(' | ')
      console.log('[D] 回话: ' + said)
      console.log(/蜕变成功|蜕变失败/.test(said) ? '[D] PASS —— 门槛过了就能真掷'
        : /材料不够|剑币不够|门槛/.test(said) ? '[D] 部分通过 —— 放行了但资源不足：' + said
        : '[D] FAIL —— 点确认没有任何蜕变结果')
    }
  }

  // ---------- 场景 E：强化没满就想点确认 ----------
  console.log('\n===== E. +0 出厂剑：界面点确认，应该拦住并提示继续强化 =====')
  await rcon('minecraft:clear ' + BOT)
  await sleep(700)
  await rcon('zhujian give ' + BOT + ' iron common 60')
  await sleep(1500)
  const fresh = bot.inventory.items().find(i => /_sword$/.test(i.name))
  if (!fresh) {
    console.log('[E] FAIL —— 背包里没有锻造剑')
  } else {
    await bot.equip(fresh, 'hand')
    await sleep(400)
    const before = chat.length
    opened = null
    bot.chat('/zhujian evolve')
    await sleep(1800)
    const win = bot.currentWindow
    if (!win) {
      console.log('[E] FAIL —— 界面没打开')
    } else {
      const lines = loreLines(win.slots[10])
      console.log('[E] 槽10 成功率牌: ' + (lines.length ? lines.join(' / ') : '(无)'))
      console.log('[E] 槽22 确认牌: ' + loreLines(win.slots[22]).join(' / '))
      bot.clickWindow(22, 0, 0)
      await sleep(2000)
      const said = chat.slice(before).join(' | ')
      console.log('[E] 回话: ' + said)
      const told = said.includes('强化还没满') && said.includes('继续强化')
      console.log(told ? '[E] PASS —— 没锻满时点确认，明确让玩家继续强化'
        : /蜕变成功/.test(said) ? '[E] FAIL —— 没锻满居然直接蜕变了'
        : '[E] FAIL —— 提示没说清「继续强化」：' + said)
    }
  }

  bot.quit()
  await sleep(500)
  console.log('\n[done]')
  process.exit(0)
}

main().catch(e => { console.log('[fatal] ' + e.message); process.exit(1) })