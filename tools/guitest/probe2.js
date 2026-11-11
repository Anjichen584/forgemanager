// 口径探针：从客户端收到的属性表读「真实生效值」。
// 目的：判定 ItemFactory 里的 meta.addAttributeModifier 是「叠在原版默认值上」还是「把默认值顶掉」。
// 输出同时写 conflict-probe.txt，避免像上一轮那样被截断丢失。
const mineflayer = require('mineflayer')
const fs = require('fs')
const wait = ms => new Promise(r => setTimeout(r, ms))

const lines = []
const out = (...a) => { const s = a.join(' '); console.log(s); lines.push(s) }

// mineflayer 的属性表在不同版本里可能是 Map、也可能是 {name:{value}} 对象，两种都吃
function attrEntries(bot) {
  const raw = bot.entity && bot.entity.attributes
  if (!raw) return []
  if (typeof raw.entries === 'function' && typeof raw.get === 'function') {
    return Array.from(raw.entries())
  }
  return Object.entries(raw)
}

function dumpAttrs(bot, tag) {
  const entries = attrEntries(bot)
  out('[' + tag + '] 属性条数=' + entries.length)
  if (!entries.length) { out('   (客户端没下发属性表)'); return {} }
  const got = {}
  for (const [k, v] of entries) {
    const name = String(k).toLowerCase()
    const val = (v && typeof v === 'object') ? v.value : v
    got[name] = val
    if (/attack|armor|toughness|speed/.test(name)) {
      out('   ' + k + ' = ' + val)
    }
  }
  return got
}

const pick = (got, ...needles) => {
  for (const n of needles) {
    for (const k of Object.keys(got)) if (k.includes(n)) return got[k]
  }
  return undefined
}

async function main() {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  bot.on('error', e => out('ERR ' + e.message))
  bot.on('kicked', r => out('KICKED ' + JSON.stringify(r)))
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('join timeout')), 45000) })
  out('JOINED as ' + bot.username)
  await wait(2500)

  bot.chat('/clear TestBot'); await wait(1200)

  // ---- 1) 空手基线
  bot.chat('/item replace entity TestBot weapon.mainhand with air'); await wait(1200)
  out('手持 = ' + (bot.heldItem ? bot.heldItem.name : '空'))
  const base = dumpAttrs(bot, '空手')

  // ---- 2) 原版铁剑
  bot.chat('/item replace entity TestBot weapon.mainhand with iron_sword'); await wait(1600)
  out('手持 = ' + (bot.heldItem ? bot.heldItem.name : '空'))
  const vanilla = dumpAttrs(bot, '原版铁剑')

  // ---- 3) 锻造铁剑（LEGENDARY 95 → 高品质，属性应该明显高于原版）
  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0'); await wait(2000)
  out('手持 = ' + (bot.heldItem ? bot.heldItem.name : '空'))
  const forged = dumpAttrs(bot, '锻造铁剑')

  // ---- 4) 原版铁胸甲
  bot.chat('/item replace entity TestBot armor.chest with iron_chestplate'); await wait(1600)
  const vArmor = dumpAttrs(bot, '原版铁胸')

  out('')
  out('===== 结论数据 =====')
  const bAtk = pick(base, 'attack_damage')
  const vAtk = pick(vanilla, 'attack_damage')
  const fAtk = pick(forged, 'attack_damage')
  out('空手 attack_damage   = ' + bAtk)
  out('原版铁剑 attack_damage = ' + vAtk)
  out('锻造铁剑 attack_damage = ' + fAtk)
  out('原版铁胸 armor         = ' + pick(vArmor, 'armor'))
  if (typeof vAtk === 'number' && typeof fAtk === 'number') {
    out('原版 vs 空手 净增 = ' + (vAtk - (bAtk || 0)).toFixed(3) + '（原版铁剑应为 +5）')
    out('锻造 vs 原版 比值 = ' + (fAtk / Math.max(0.01, vAtk) * 100).toFixed(1) + '%')
    if (fAtk + 1e-6 < vAtk) {
      out('>>> 判定：锻造剑比原版还弱 —— addAttributeModifier 把原版默认 +5 顶掉了')
    } else if (Math.abs((fAtk - vAtk) - ((fAtk - vAtk))) < 1e-9) {
      out('>>> 判定：锻造 ≥ 原版，默认值仍在（叠加语义），差值 = ' + (fAtk - vAtk).toFixed(3))
    }
  }

  fs.writeFileSync(__dirname + '/conflict-probe.txt', lines.join('\n') + '\n', 'utf8')
  bot.quit()
  setTimeout(() => process.exit(0), 500)
}

main().catch(e => {
  out('FATAL ' + e.message)
  try { fs.writeFileSync(__dirname + '/conflict-probe.txt', lines.join('\n') + '\n', 'utf8') } catch (_) {}
  process.exit(1)
})