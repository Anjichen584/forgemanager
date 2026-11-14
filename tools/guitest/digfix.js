const mineflayer = require('mineflayer')
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
const b = mineflayer.createBot({ host:'127.0.0.1', port:25577, username:'TestBot', auth:'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
b.on('message', m => console.log('[chat]', strip(m.toString())))
b.once('spawn', async () => {
  await sleep(1500)
  const frags = {}
  for (const it of b.inventory.items()) {
    const d = pdcOf(it)
    if (d && d.frag_artifact) {
      frags[d.frag_artifact] = frags[d.frag_artifact] || new Set()
      frags[d.frag_artifact].add(d.frag_piece)
    }
  }
  console.log('frag sets:', Object.entries(frags).map(([a, s]) => a + ':' + [...s].sort()).join(' | '))
  b.chat('/digs restore')
  await sleep(2000)
  const w1 = b.currentWindow
  console.log('sel-page open:', !!w1)
  const groups = []
  for (let i = 0; i < 45; i++) { const it = w1 && w1.slots[i]; if (it && it.name !== 'barrier') groups.push(i + ':' + nameOf(it).slice(0, 6)) }
  console.log('groups:', groups.join(' | '))
  // 挑一个片段数最多的组（从 groups 里反查不到 id，就逐个点进来看标题）
  let done = false
  for (const g of groups) {
    const idx = parseInt(g.split(':')[0])
    await b.clickWindow(idx, 0, false)
    await sleep(1000)
    const w2 = b.currentWindow
    const title = strip(String(JSON.stringify(w2.title)))
    console.log('clicked', idx, '→', title)
    if (title.includes('就位')) {
      // 点碎片直到 49 绿
      for (let k = 0; k < 8; k++) {
        let slot = -1
        for (let i = 9; i <= 35; i++) {
          const it = w2.slots[i]
          if (it && it.name === 'brick' && !nameOf(it).includes('已指派')) { slot = i; break }
        }
        if (slot < 0) break
        const dn = nameOf(w2.slots[slot])
        await b.clickWindow(slot, 0, false)
        await sleep(900)
        const it49 = b.currentWindow.slots[49]
        console.log('click', slot, dn.slice(0, 22), '→ 49:', nameOf(it49))
        if (nameOf(it49).includes('开始修复')) {
          await b.clickWindow(49, 0, false)
          await sleep(1500)
          const got = b.inventory.items().find(i => { const d = pdcOf(i); return d && d.art_id })
          console.log('ARTIFACT:', got ? nameOf(got) : 'NONE')
          done = true
          break
        }
      }
      if (done) break
    }
    // 返回选择页继续试下一组
    if (b.currentWindow) { await b.clickWindow(45, 0, false); await sleep(800) }
  }
  process.exit(0)
})
