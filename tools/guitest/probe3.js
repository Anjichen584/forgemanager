// 端到端核对：客户端收到的物品 NBT 里有没有原版属性条目。
// 服务端算伤害用的就是这份组件，客户端收到的也就是它 —— 比读实体属性表靠谱。
const mineflayer = require('mineflayer')
const fs = require('fs')
const wait = ms => new Promise(r => setTimeout(r, ms))
const lines = []
const out = (...a) => { const s = a.join(' '); console.log(s); lines.push(s) }

function dumpItem(bot, tag) {
  const it = bot.heldItem
  if (!it) { out('[' + tag + '] 手里是空的'); return }
  out('[' + tag + '] ' + it.name + ' count=' + it.count)
  const nbt = it.nbt || it.components || null
  const raw = JSON.stringify(nbt)
  const hits = raw.match(/"minecraft:(base_attack_damage|base_attack_speed|armor\.chestplate|attribute_modifiers)"[^}]{0,160}/g) || []
  if (!hits.length) {
    out('   组件里没找到属性相关键（可能版本把组件放在别处）')
    const idx = raw.indexOf('attribute')
    if (idx >= 0) out('   片段: ' + raw.slice(Math.max(0, idx - 80), idx + 240))
  } else {
    for (const h of hits) out('   ' + h)
  }
}

async function main() {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25577, username: 'TestBot', auth: 'offline', version: '1.21.11' })
  bot.on('error', e => out('ERR ' + e.message))
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('join timeout')), 45000) })
  out('JOINED')
  await wait(3000)

  bot.chat('/clear TestBot'); await wait(1200)
  bot.chat('/item replace entity TestBot weapon.mainhand with iron_sword'); await wait(1500)
  dumpItem(bot, '原版铁剑')

  bot.chat('/zhujian give TestBot iron LEGENDARY 95'); await wait(2000)
  bot.chat('/item replace entity TestBot weapon.mainhand from entity TestBot hotbar.0'); await wait(2000)
  dumpItem(bot, '锻造铁剑')

  fs.writeFileSync(__dirname + '/item-probe.txt', lines.join('\n') + '\n', 'utf8')
  bot.quit(); setTimeout(() => process.exit(0), 500)
}
main().catch(e => {
  out('FATAL ' + e.message)
  try { fs.writeFileSync(__dirname + '/item-probe.txt', lines.join('\n') + '\n', 'utf8') } catch (_) {}
  process.exit(1)
})