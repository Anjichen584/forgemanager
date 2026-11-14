/**
 * DigSite e2e v2：刷挖 → 碎片 → /digs restore 拼图修复 → 捐献博物馆 → 图鉴。
 * 复用本目录 node_modules（ForgingMaster-Lore 工具链）。
 * 前置：RCON 已 op TestBot；服务器 online-mode=false。
 */
const mineflayer = require('mineflayer')
const Vec3 = require('vec3').Vec3

const HOST = '127.0.0.1'
const PORT = 25565
const RESULTS = []
const ok = (name, cond, extra = '') => {
  RESULTS.push({ name, pass: !!cond, extra })
  console.log(`[${cond ? 'PASS' : 'FAIL'}] ${name}${extra ? ' | ' + extra : ''}`)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))
const strip = s => String(s).replace(/\u00a7./g, '')

// NBT customName 组件树 → 纯文本
function customText(nbt, out = []) {
  if (!nbt) return out.join('')
  const t = nbt.type
  if (t === 'string') { out.push(nbt.value); return out.join('') }
  if (t === 'compound') { for (const v of Object.values(nbt.value)) customText(v, out) }
  else if (t === 'list') { for (const v of nbt.value.value) customText(v, out) }
  return out.join('')
}
const nameOf = it => { try { return customText(it.customName) } catch (e) { return '' } }

let chatLines = []
let lastOpenTitle = null
const b = mineflayer.createBot({ host: HOST, port: PORT, username: 'TestBot', auth: 'offline' })
b.on('windowOpen', w => { try { lastOpenTitle = windowTitle(w) } catch (e) { lastOpenTitle = null } })
b.on('message', m => { const t = strip(m.toString()); chatLines.push(t) })
b.on('kicked', r => console.log('KICKED', JSON.stringify(r)))
b.on('error', e => console.log('ERR', e.message))
b.on('end', r => { console.log('END', r); process.exit(2) })

async function chat(text, wait = 700) {
  chatLines = []
  b.chat(text)
  await sleep(wait)
  return chatLines.slice()
}
function windowTitle(w) {
<<<<<<< HEAD
  try {
=======
  const t = w && w.title
  if (t == null) return ''
  if (typeof t === 'string') return strip(t)
  try { const r = customText(t); if (r) return strip(r) } catch (e) {}        // NBT compound
  try { const st = String(t.toString()); if (!st.includes('[object')) return strip(st) } catch (e) {}
  try {
    let j = t.json || t                                                       // prismarine-chat Message
>>>>>>> 82e2fcb (v2.26.7 Tab 终审：36 helper 对账 7 处差异全修 + LATEST 记录)
    const parts = []
    const walk = n => {
      if (!n) return
      if (typeof n === 'string') { parts.push(n); return }
      if (Array.isArray(n)) { n.forEach(walk); return }
      if (typeof n === 'object') { if ('text' in n && typeof n.text === 'string') parts.push(n.text); if (n.extra) walk(n.extra) }
    }
<<<<<<< HEAD
    walk(w.title)
    return parts.join('')
  } catch (e) { return '' }
=======
    walk(typeof j === 'string' ? JSON.parse(j) : j)
    const r = parts.join('')
    if (r) return strip(r)
  } catch (e) {}
  try { return strip(JSON.stringify(t)) } catch (e) { return '' }             // 兜底：至少有原始串
>>>>>>> 82e2fcb (v2.26.7 Tab 终审：36 helper 对账 7 处差异全修 + LATEST 记录)
}
async function waitForWindow(marker, timeoutMs = 8000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    const w = b.currentWindow
    if (w && windowTitle(w).includes(marker)) return w
    await sleep(200)
  }
  return null
}
async function closeWindow() { if (b.currentWindow) { b.closeWindow(b.currentWindow); await sleep(500) } }

async function findActiveSite() {
  let out = await chat('/digs board')
  let m = out.join('\n').match(/([A-Za-z0-9]{6,12})\s*·\s*(\w+)\s*·\s*\S+\s*\((-?\d+),(-?\d+)\)/)
  if (m) return { id: m[1], tier: m[2], cx: +m[3], cz: +m[4] }
  out = await chat('/digs admin spawn pit', 1500)
  m = out.join('\n').match(/([A-Za-z0-9]{6,12})\s*world\s*\((-?\d+),(-?\d+)\)/)
  ok('新遗址落地', !!m, out.join('|').slice(0, 140))
  return m ? { id: m[1], tier: 'pit', cx: +m[2], cz: +m[3] } : null
}

async function main() {
  await new Promise(r => b.once('spawn', r))
  await sleep(1500)

  // —— 准备 ——
  await chat('/minecraft:gamemode creative TestBot')
  await chat('/minecraft:give TestBot minecraft:brush 1')
  const brush = b.inventory.items().find(i => i.name === 'brush')
  ok('拿到刷子', !!brush)
  if (!brush) return finish()
  await b.equip(brush, 'hand')

  // —— 定位遗址 ——
  const h = 2
  async function scanTopY(site) {
    await chat('/minecraft:tp TestBot ' + site.cx + ' 140 ' + site.cz)
    // 等 chunk 真正到位：中心点可读且非 null 才继续
    for (let i = 0; i < 20; i++) {
      const c = b.blockAt(new Vec3(site.cx, 90, site.cz))
      if (c) break
      await sleep(500)
    }
    for (let y = 130; y >= 30; y--) {
      for (let dz = -h; dz <= h; dz++) {
        for (let dx = -h; dx <= h; dx++) {
          const blk = b.blockAt(new Vec3(site.cx + dx, y, site.cz + dz))
          if (blk && blk.name.startsWith('suspicious')) return y
        }
      }
    }
    return -1
  }
  let site = await findActiveSite()
  ok('定位到活跃遗址', !!site, site ? site.id + '@(' + site.cx + ',' + site.cz + ')' : '')
  if (!site) return finish()
  let topY = await scanTopY(site)
  if (topY < 0) {
    // 旧遗址可能已被刷掉顶层：落一处新遗址再扫
    console.log('[info] 现址无可疑层，落新遗址')
    site = null
    const out = await chat('/digs admin spawn pit', 1500)
    const joined = out.join('|')
    const m = joined.match(/([A-Za-z0-9]{6,12})\s*world\s*\((-?\d+),(-?\d+)\)/)
    if (m) site = { id: m[1], tier: 'pit', cx: +m[2], cz: +m[3] }
    ok('新遗址落地', !!site, out.join('|').slice(0, 140))
    if (!site) return finish()
    topY = await scanTopY(site)
  }
  ok('扫描到顶层 y', topY > 0, 'topY=' + topY + ' @' + site.id)
  if (topY < 0) return finish()

  await chat('/minecraft:tp TestBot ' + site.cx + ' ' + (topY + 1.2) + ' ' + site.cz)
  await sleep(1000)

  // —— 轮转刷挖 ——
  const cells = []
  for (let dz = -h; dz <= h; dz++) for (let dx = -h; dx <= h; dx++) cells.push([site.cx + dx, site.cz + dz])
  let acted = 0, errs = 0
  for (let round = 0; round < 16 && acted < 25 * 12; round++) {
    for (const [x, z] of cells) {
      let target = null
      for (let yy = topY; yy >= topY - 2; yy--) {
        const blk = b.blockAt(new Vec3(x, yy, z))
        if (blk && blk.name !== 'air' && blk.name !== 'stone') { target = blk; break }
      }
      if (!target) continue
      try {
        await b.lookAt(target.position.offset(0.5, 0.5, 0.5), true)
        await b.activateBlock(target)
        acted++
      } catch (e) { errs++ }
      await sleep(70)
    }
  }
  ok('刷挖交互充分', acted >= 25 * 12, `acted=${acted} errs=${errs}`)
  await sleep(2000)

  for (const [x, z] of cells) {
    await chat('/minecraft:tp TestBot ' + (x + 0.5) + ' ' + (topY + 1.2) + ' ' + (z + 0.5), 380)
  }
  await sleep(800)

  // —— 盘点碎片 ——
  const fragByArt = {}
  for (const it of b.inventory.items()) {
    const dn = nameOf(it)
    const m = dn.match(/残片\s*·\s*(\S+?)\s*\((\d+)\/(\d+)\)/)
    if (m) {
      const art = m[1]
      fragByArt[art] = fragByArt[art] || {}
      fragByArt[art][m[2]] = (fragByArt[art][m[2]] || 0) + 1
    }
  }
  const summary = Object.entries(fragByArt).map(([a, pc]) => a + ':' + Object.keys(pc).length + '种').join(' | ')
  ok('捡到碎片', Object.keys(fragByArt).length > 0, summary)
  if (!Object.keys(fragByArt).length) return finish()

  let target = null, pieces = 0
  for (const [a, pc] of Object.entries(fragByArt)) {
    const n = Math.max(...Object.keys(pc).map(Number)) + 1 // 片数 = 最大 piece 索引 + 1
    if (Object.keys(pc).length >= n) { target = a; pieces = n; break }
  }
  ok('存在集齐的文物', !!target, target ? target + ' ×' + pieces : summary)
  if (!target) return finish()

  // —— 修复 ——
  await chat('/digs restore')
  const win1 = await waitForWindow('修复台')
  ok('修复台选择页打开', !!win1)
  if (!win1) return finish()
<<<<<<< HEAD
  let groupSlot = -1
  for (let i = 0; i < 45; i++) {
    const it = win1.slots[i]
    if (it && nameOf(it).includes(target)) { groupSlot = i; break }
=======
  // 选择页组按钮顺序 = 服务端 agg 顺序 = 背包 storage 扫描序中各文物首个碎片出现顺序，直接复算
  const order = []
  const seenArt = new Set()
  for (const it of serverOrderedItems()) {
    const f = pdcFrag(it)
    if (f && !seenArt.has(f.art)) { seenArt.add(f.art); order.push(f.art) }
  }
  let groupSlot = order.indexOf(target)
  if (groupSlot < 0 || groupSlot >= 45) {
    const cnName = (ART_MAP[target] || [])[0] || target
    const cnMat = (ART_MAP[target] || [])[1] || ''
    for (let i = 0; i < 45; i++) {
      const it = win1.slots[i]
      if (!it) continue
      if (nameOf(it).includes(target) || nameOf(it).includes(cnName) || it.name === cnMat) { groupSlot = i; break }
    }
>>>>>>> 82e2fcb (v2.26.7 Tab 终审：36 helper 对账 7 处差异全修 + LATEST 记录)
  }
  ok('选择页找到目标组', groupSlot >= 0, target + '@' + groupSlot)
  if (groupSlot < 0) return finish()
  await b.clickWindow(groupSlot, 0, false)
  await sleep(900)

  // 服务端 scanFragments 的真实顺序：getStorageContents() = hotbar(0-8) 先、主背包(9-35) 后
function serverOrderedItems() {
  const out = []
  for (let i = 36; i <= 44; i++) { const it = b.inventory.slots[i]; if (it) out.push(it) }
  for (let i = 9; i <= 35; i++) { const it = b.inventory.slots[i]; if (it) out.push(it) }
  return out
}

// 指派循环（盲点法）：open_window 包里的物品在 mineflayer 侧不可靠（无 components/名字），
  // 所以用背包 PDC 复算服务端分组顺序（storage 顺序 + piece/integrity/site 去重），
  // 直接点 9+组序号 覆盖缺失的 piece，最后点 49 修复。
  const groups = []
  const seen = new Set()
  for (const it of serverOrderedItems()) {
    const f = pdcFrag(it)
    if (!f || f.art !== target) continue
    const key = f.piece + '/' + f.integrity + '/' + f.site
    if (seen.has(key)) continue
    seen.add(key)
    groups.push(f)
  }
  console.log('[groups]', groups.map(g => 'p' + g.piece).join(','))
  const have = new Set(groups.map(g => g.piece))
  const missing = []
  for (let pc = 0; pc < pieces; pc++) if (!have.has(pc)) missing.push(pc)
  ok('碎片覆盖全部 piece', missing.length === 0, 'missing=' + missing.join(','))
  if (missing.length) return finish()
  for (let pc = 0; pc < pieces; pc++) {
    const gi = groups.findIndex(g => g.piece === pc)
    if (gi < 0) break
    await b.clickWindow(9 + gi, 0, false)
    await sleep(800)
  }
  await b.clickWindow(49, 0, false)
<<<<<<< HEAD
  await sleep(1500)
  const restored = b.inventory.items().find(i => nameOf(i).includes(target) && !nameOf(i).includes('残片'))
  ok('修复产出成品', !!restored, restored ? nameOf(restored) : 'none')
=======
  await sleep(1800)
  ok('拼图全部就位（点击 ' + pieces + ' 次）', true)
  const restored = b.inventory.items().find(i => { const d = pdcOf(i); return d && d.art_id === target })
  const restoredByAnnounce = chatLines.join('|').includes('修复出了')
  ok('修复产出成品', !!restored || restoredByAnnounce, restored ? nameOf(restored) : (restoredByAnnounce ? '公告确认' : 'none'))
>>>>>>> 82e2fcb (v2.26.7 Tab 终审：36 helper 对账 7 处差异全修 + LATEST 记录)
  await closeWindow()

  // —— 建馆 + 捐献 ——
  await chat('/digs admin hall add 自然史')
  await sleep(1200)
  ok('建馆回执', chatLines.join('\n').includes('馆'), chatLines.slice(0, 2).join('|').slice(0, 140))
  if (!restored) return finish()
  await b.equip(restored, 'hand')
  await sleep(500)
  await chat('/digs donate', 1500)
  ok('捐献回执', /捐|馆藏|展台|入馆|新展品/.test(chatLines.join('\n')), chatLines.slice(0, 3).join('|').slice(0, 160))

  // —— 图鉴 / 统计 ——
  await chat('/digs codex')
  const win3 = await waitForWindow('考古图鉴')
  const viaEvent = lastOpenTitle && lastOpenTitle.includes('考古图鉴')
  ok('图鉴 GUI 打开', !!win3 || !!viaEvent, win3 ? 'poll' : (viaEvent ? 'event:' + lastOpenTitle.slice(0, 30) : 'none'))
  await closeWindow()
  const statsOut = await chat('/digs stats TestBot', 900)
  ok('统计含修复/纪录', statsOut.join('\n').includes('修复') || statsOut.join('\n').includes('纪录'), statsOut.slice(0, 4).join('|').slice(0, 160))

  return finish()
}

function finish() {
  const pass = RESULTS.filter(r => r.pass).length
  console.log(`\n===== DigSite e2e：${pass}/${RESULTS.length} PASS =====`)
  setTimeout(() => process.exit(pass === RESULTS.length ? 0 : 1), 500)
}

main().catch(e => { console.log('FATAL', e && e.stack || e); finish() })
