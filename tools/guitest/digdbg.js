const mineflayer = require('mineflayer')
const b = mineflayer.createBot({ host:'127.0.0.1', port:25565, username:'TestBot', auth:'offline' })
const sleep = ms => new Promise(r => setTimeout(r, ms))
b.once('spawn', async () => {
  await sleep(1500)
  for (const it of b.inventory.items()) {
    if (it.name === 'brick') {
      console.log('keys:', Object.keys(it).join(','))
      console.log('displayName:', JSON.stringify(it.displayName))
      console.log('customName:', JSON.stringify(it.customName))
      console.log('nbt keys:', it.nbt && it.nbt.value ? Object.keys(it.nbt.value).join(',') : null)
      console.log('components:', JSON.stringify(it.components))
      break
    }
  }
  process.exit(0)
})
