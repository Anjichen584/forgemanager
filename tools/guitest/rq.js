const rconlib = require('./rconlib')
;(async()=>{
  const r = await rconlib.connect({port:25599,password:'testpass'})
  for (const c of process.argv.slice(2)) {
    const out = await r.send(c)
    console.log('$ '+c+'\n'+(out||'').replace(/§./g,'').trim()+'\n')
  }
  r.close()
})().catch(e=>{console.log('FATAL '+e.message);process.exit(1)})
