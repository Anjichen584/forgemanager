const mineflayer = require('mineflayer')
function customText(nbt, out = []) {
  if (!nbt) return out.join('')
  const t = nbt.type
  if (t === 'string') { out.push(nbt.value); return out.join('') }
  if (t === 'compound') { for (const v of Object.values(nbt.value)) customText(v, out) }
  else if (t === 'list') { for (const v of nbt.value.value) customText(v, out) }
  return out.join('')
}
const b = mineflayer.createBot({ host:'127.0.0.1', port:25577, username:'TestBot', auth:'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
b.on('windowOpen', w => console.log('[evt]', customText(w.title)))
b.once('spawn', async () => {
  await sleep(1500)
  b.chat('/digs codex')
  await sleep(2500)
  console.log('cur:', b.currentWindow ? customText(b.currentWindow.title) : 'NULL')
  process.exit(0)
})
