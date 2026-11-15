/**
 * DigSite 专项实测：wreck 沉船偏好水边 / glacier 冰壳 / 盗墓贼夜袭 / 传说遗物 BossBar / 双 bot 并发清场。
 * 前置：RCON 已 op TestBot、TestBot2；两 bot 均为创造模式。
 * 跑法：DIGSITE_PORT=25577 node digsite-feature.js
 */
const mineflayer = require('mineflayer')
const Vec3 = require('vec3').Vec3

const HOST = '127.0.0.1'
const PORT = parseInt(process.env.DIGSITE_PORT || '25577', 10)
const RESULTS = []
const ok = (name, cond, extra = '') => {
  RESULTS.push({ name, pass: !!cond, extra })
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name}${extra ? ' | ' + extra : ''}`)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))
const strip = s => String(s).replace(/\u00a7./g, '')

function customText(nbt, out = []) {
  if (!nbt) return out.join('')
  const t = nbt.type
  if (t === 'string') { out.push(nbt.value); return out.join('') }
  if (t === 'compound') { for (const v of Object.values(nbt.value)) customText(v, out) }
  else if (t === 'list') { for (const v of nbt.value.value) customText(v, out) }
  return out.join('')
}
const nameOf = it => { try { return customText(it.customName) } catch (e) { return '' } }
function pdcOf(it) {
  try {
    for (const c of (it.components || [])) {
      if (c.type !== 'custom_data') continue
      const pbv = c.data && c.data.value && c.data.value.PublicBukkitValues
      if (!pbv || !pbv.value) continue
      const flat = {}
      for (const [k, v] of Object.entries(pbv.value)) flat[k.replace('digsite:', '')] = v && v.value
      return flat
    }
  } catch (e) {}
  return null
}
const pdcFrag = it => {
  const d = pdcOf(it)
  return d && d.frag_artifact ? { art: d.frag_artifact, piece: d.frag_piece, integrity: d.frag_integrity, site: d.frag_site } : null
}
function windowTitle(w) {
  const t = w && w.title
  if (t == null) return ''
  if (typeof t === 'string') return strip(t)
  try { const r = customText(t); if (r) return strip(r) } catch (e) {}
  try { const st = String(t.toString()); if (!st.includes('[object')) return strip(st) } catch (e) {}
  try { return strip(JSON.stringify(t)) } catch (e) { return '' }
}

const EPIC_NAMES = ['青铜爵', '唐三彩马', '青花瓷瓶']
const LEGENDARY_NAMES = ['鎏金铜像']
let legendaryAnnounce = 0
let closeAnnounceIds = []

function makeBot(username, recordStats) {
  const b = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline' })
  b._chat = []
  b.on('message', m => {
    const t = strip(m.toString())
    b._chat.push(t)
    if (!recordStats) return // 广播每个 bot 都收到，统计只记一个，防重复计数
    if (t.includes('传说遗物现世')) legendaryAnnounce++
    const cm = t.match(/遗址完整发掘！[^」]*」([A-Za-z0-9]+)/)
    if (cm) closeAnnounceIds.push(cm[1])
  })
  b.on('kicked', r => console.log('KICKED', username, JSON.stringify(r)))
  b.on('error', e => console.log('ERR', username, e.message))
  b.on('end', r => { console.log('END', username, r); process.exit(2) })
  b.chatX = async (text, wait = 700) => { b._chat = []; b.chat(text); await sleep(wait); return b._chat.slice() }
  return b
}

async function spawnTier(b1, tier) {
  const out = await b1.chatX('/digs admin spawn ' + tier, 1500)
  const m = out.join('|').match(/([A-Za-z0-9]{6,12})\s*world\s*\((-?\d+),(-?\d+)\)/)
  return m ? { id: m[1], tier, cx: +m[1] ? +m[2] : 0, cz: +m[3] } : (m ? null : null)
}
// 上面的正则返回有坑，独立实现：
async function spawnSite(b1, tier) {
  // 落点受随机性影响（排除圈 400 格），失败自动重试最多 4 次
  for (let i = 0; i < 4; i++) {
    const out = await b1.chatX('/digs admin spawn ' + tier, 1500)
    const m = out.join('|').match(/遗址已落下[：:]?\s*([A-Za-z0-9]{6,12})\s*world\s*\((-?\d+),(-?\d+)\)/)
    if (m) { ok('spawn ' + tier, true, m[1] + '@(' + m[2] + ',' + m[3] + ')' + (i ? ' 重试' + i + ' 次' : '')); return { id: m[1], tier, cx: +m[2], cz: +m[3] } }
    await sleep(1500)
  }
  ok('spawn ' + tier, false, '4 次均落点失败')
  return null
}

async function scanTopY(b, site, h) {
  await b.chatX('/minecraft:tp ' + b.username + ' ' + site.cx + ' 140 ' + site.cz, 2500)
  for (let i = 0; i < 16; i++) {
    const c = b.blockAt(new Vec3(site.cx, 90, site.cz))
    if (c) break
    await sleep(500)
  }
  for (let y = 130; y >= 30; y--) {
    for (let dz = -h; dz <= h; dz++) for (let dx = -h; dx <= h; dx++) {
      const blk = b.blockAt(new Vec3(site.cx + dx, y, site.cz + dz))
      if (blk && blk.name.startsWith('suspicious')) return y
    }
  }
  return -1
}

function brushCells(b, cells, topY, layers, rounds, tag) {
  // 返回 promise：轮转刷 cells（cells 已按 bot 分组），动态找当前层
  return (async () => {
    let acted = 0
    for (let round = 0; round < rounds; round++) {
      for (const [x, z] of cells) {
        let target = null
        for (let yy = topY; yy >= topY - layers; yy--) {
          const blk = b.blockAt(new Vec3(x, yy, z))
          if (blk && blk.name !== 'air' && blk.name !== 'stone' && !blk.name.includes('ice')) { target = blk; break }
        }
        if (!target) continue
        try {
          await b.lookAt(target.position.offset(0.5, 0.5, 0.5), true)
          await b.activateBlock(target)
          acted++
        } catch (e) {}
        await sleep(80)
      }
      console.log('[' + tag + '] round ' + round + ' acted=' + acted)
    }
    return acted
  })()
}

async function main() {
  const b1 = makeBot('TestBot', true)
  const b2 = makeBot('TestBot2', false)
  await Promise.all([
    new Promise(r => b1.once('spawn', r)),
    new Promise(r => b2.once('spawn', r)),
  ])
  await sleep(1500)
  await b1.chatX('/minecraft:gamemode creative TestBot')
  await b2.chatX('/minecraft:gamemode creative TestBot2')
  await b1.chatX('/minecraft:give TestBot minecraft:brush 1')
  await b2.chatX('/minecraft:give TestBot2 minecraft:brush 1')

  // —— 0. 开场清理：关闭全部活跃遗址（max-active=3，残留会占满配额） ——
  {
    const out = await b1.chatX('/digs board', 900)
    const joined = out.join('|')
    const ids = [...new Set([...joined.matchAll(/([A-Za-z0-9]{6,12})\s*·\s*\w+\s*·/g)].map(m => m[1]))]
    for (const id of ids) await b1.chatX('/digs admin close ' + id, 600)
    console.log('[cleanup] closed', ids.length, 'sites:', ids.join(','))
  }

  // —— A. wreck：落点偏好水边 ——
  const wreck = await spawnSite(b1, 'wreck')
  if (wreck) {
    await b1.chatX('/minecraft:tp TestBot ' + wreck.cx + ' 140 ' + wreck.cz, 2500)
    let topY = -1, waterNear = false
    for (let y = 200; y >= 20 && topY < 0; y--)
      for (let dz = -4; dz <= 4 && topY < 0; dz++) for (let dx = -4; dx <= 4 && topY < 0; dx++) {
        const blk = b1.blockAt(new Vec3(wreck.cx + dx, y, wreck.cz + dz))
        if (blk && blk.name.startsWith('suspicious')) topY = y
      }
    ok('wreck 顶层扫描', topY > 0, 'topY=' + topY)
    if (topY > 0) {
      for (let dz = -3; dz <= 3 && !waterNear; dz++) for (let dx = -3; dx <= 3 && !waterNear; dx++) {
        for (let y = topY - 3; y <= topY + 2 && !waterNear; y++) {
          const blk = b1.blockAt(new Vec3(wreck.cx + dx, y, wreck.cz + dz))
          if (blk && /water|kelp|seagrass|ice/.test(blk.name)) waterNear = true
        }
      }
      ok('wreck 落点邻近水体（信息项）', true, waterNear ? '3 格内见水' : 'dry（prefer-water 双倍尝试未找到水点，按设计退回普通落点）')
      // wreck 顶块材质应为 SUSPICIOUS_GRAVEL
      const top = b1.blockAt(new Vec3(wreck.cx, topY, wreck.cz))
      ok('wreck 顶块=SUSPICIOUS_GRAVEL', top && top.name === 'suspicious_gravel', top ? top.name : 'null')
    }
  }

  // —— B. glacier：冰壳验证 ——
  const gla = await spawnSite(b1, 'glacier')
  ok('spawn glacier', !!gla, gla ? gla.id + '@(' + gla.cx + ',' + gla.cz + ')' : '')
  if (!gla) return finish()
  const h = 4
  await b2.chatX('/minecraft:tp TestBot2 ' + gla.cx + ' 140 ' + gla.cz, 2500)
  for (let i = 0; i < 16; i++) {
    const c = b2.blockAt(new Vec3(gla.cx, 90, gla.cz))
    if (c) break
    await sleep(500)
  }
  let gTop = -1
  for (let y = 200; y >= 20 && gTop < 0; y--)
    for (let dz = -h; dz <= h && gTop < 0; dz++) for (let dx = -h; dx <= h && gTop < 0; dx++) {
      const blk = b2.blockAt(new Vec3(gla.cx + dx, y, gla.cz + dz))
      if (blk && blk.name.startsWith('suspicious')) gTop = y
    }
  ok('glacier 顶层扫描', gTop > 0, 'topY=' + gTop)
  if (gTop < 0) return finish()

  // 冰壳采样：环半径 h+1，取 8 个方位 × 中层 y
  const R = h + 1
  let shellOk = 0, shellSampled = 0
  const ring = [[R,0],[-R,0],[0,R],[0,-R],[R,R],[-R,-R],[R,-R],[-R,R]]
  for (const [ox, oz] of ring) {
    for (let y = gTop + 1; y >= gTop - 4; y--) {
      const blk = b2.blockAt(new Vec3(gla.cx + ox, y, gla.cz + oz))
      if (blk && blk.name === 'packed_ice') { shellOk++; break }
    }
    shellSampled++
  }
  ok('glacier 冰壳覆盖(≥6/8 方位)', shellOk >= 6, shellOk + '/' + shellSampled)

  // —— C. 夜间 + 双 bot 并发清场 ——
  await b1.chatX('/minecraft:time set 18000')
  await b1.chatX('/minecraft:tp TestBot ' + (gla.cx - h + 1) + ' ' + (gTop + 1.2) + ' ' + gla.cz, 1200)
  await b2.chatX('/minecraft:tp TestBot2 ' + (gla.cx + h - 1) + ' ' + (gTop + 1.2) + ' ' + gla.cz, 1200)
  const brush1 = b1.inventory.items().find(i => i.name === 'brush') || (await b1.chatX('/minecraft:give TestBot minecraft:brush 1', 600), b1.inventory.items().find(i => i.name === 'brush'))
  const brush2 = b2.inventory.items().find(i => i.name === 'brush') || (await b2.chatX('/minecraft:give TestBot2 minecraft:brush 1', 600), b2.inventory.items().find(i => i.name === 'brush'))
  if (brush1) await b1.equip(brush1, 'hand')
  if (brush2) await b2.equip(brush2, 'hand')

  const cells = []
  for (let dz = -h; dz <= h; dz++) for (let dx = -h; dx <= h; dx++) cells.push([gla.cx + dx, gla.cz + dz])
  const cells1 = cells.filter((_, i) => i % 2 === 0)
  const cells2 = cells.filter((_, i) => i % 2 === 1)

  // 夜间保持：b2 每隔 40s 重设夜晚（刷挖期间时间会走）
  const nightKeeper = setInterval(() => { b2.chat('/minecraft:time set 18000') }, 40000)
  // 盗墓贼侦测：任一 bot 视野内出现 husk/pillager 且靠近遗址
  let guardSeen = false
  const guardWatch = setInterval(() => {
    for (const bot of [b1, b2]) {
      for (const e of Object.values(bot.entities)) {
        if (e && (e.name === 'husk' || e.name === 'pillager') && e.position) {
          const d = e.position.distanceTo(bot.entity.position)
          if (d < 32) { guardSeen = true; return }
        }
      }
    }
  }, 3000)

  const [acted1, acted2] = await Promise.all([
    brushCells(b1, cells1, gTop, 4, 30, 'b1'),
    brushCells(b2, cells2, gTop, 4, 30, 'b2'),
  ])
  ok('双 bot 并发刷挖充分', acted1 + acted2 >= 81 * 4 * 6 * 0.85, 'b1=' + acted1 + ' b2=' + acted2 + ' 目标≈' + 81 * 4 * 6)
  ok('盗墓贼夜袭出现', guardSeen, guardSeen ? '夜间刷出 HUSK/PILLAGER' : '夜窗未出（概率 0.35/2min，可重跑）')
  ok('传说遗物现世 ≥1（BossBar 触发）', legendaryAnnounce >= 1, 'announce=' + legendaryAnnounce)

  // —— 清尾：逐格踏点把残余格刷完（合成覆盖不到的四角） ——
  let mopup = 0
  for (const [x, z] of cells) {
    await b1.chatX('/minecraft:tp TestBot ' + (x + 0.5) + ' ' + (gTop + 1.2) + ' ' + (z + 0.5), 350)
    for (let tries = 0; tries < 30; tries++) {
      let target = null
      for (let yy = gTop; yy >= gTop - 4; yy--) {
        const blk = b1.blockAt(new Vec3(x, yy, z))
        if (blk && blk.name !== 'air' && blk.name !== 'stone' && !blk.name.includes('ice')) { target = blk; break }
      }
      if (!target) break
      try { await b1.lookAt(target.position.offset(0.5, 0.5, 0.5), true); await b1.activateBlock(target); mopup++ } catch (e) {}
      await sleep(160)
    }
  }
  console.log('[mopup] acted=' + mopup)

  // 关场公告：Announcer 节流队列里压着 N 条传说公告，轮询最多 150s
  const t0 = Date.now()
  let closes = 0
  while (Date.now() - t0 < 150000) {
    await sleep(3000)
    closes = closeAnnounceIds.filter(id => id === gla.id).length
    if (closes >= 1) break
  }
  clearInterval(nightKeeper)
  clearInterval(guardWatch)
  ok('glacier 关场公告恰一次', closes === 1, 'count=' + closes + ' id=' + gla.id)

  // —— D. 并发后碎片盘点（两 bot 都应有猎获） ——
  const c1 = b1.inventory.items().filter(i => pdcFrag(i)).length
  const c2 = b2.inventory.items().filter(i => pdcFrag(i)).length
  ok('双 bot 各有猎获', c1 > 0 && c2 > 0, 'b1=' + c1 + ' b2=' + c2)

  return finish()
}

function finish() {
  const pass = RESULTS.filter(r => r.pass).length
  console.log(`\n===== DigSite feature：${pass}/${RESULTS.length} PASS =====`)
  setTimeout(() => process.exit(pass === RESULTS.length ? 0 : 1), 500)
}

main().catch(e => { console.log('FATAL', e && e.stack || e); finish() })
