const fs = require('fs')
const mineflayer = require('mineflayer')
const P = 'D:\\IDEA\\mc-plugins\\ForgingMaster\\tools\\guitest\\botstat.txt'
const log = s => { try { fs.appendFileSync(P, s + '\n') } catch(e){} }
try { fs.writeFileSync(P, '') } catch(e){}
const b = mineflayer.createBot({host:'127.0.0.1',port:25577,username:'TestBot',auth:'offline',version:'1.21.11'})
b.on('spawn', () => log('JOINED'))
b.on('error', e => log('ERR:' + e.message))
b.on('kicked', r => log('KICK:' + JSON.stringify(r)))
b.on('end', r => log('END:' + r))
log('starting')
setInterval(()=>{}, 2000)
