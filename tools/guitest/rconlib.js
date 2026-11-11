// 极简 RCON 客户端（node 版，只依赖 net），供 guitest 脚本做服务端侧断言用。
const net = require('net')

function packet (id, type, body) {
  const payload = Buffer.concat([Buffer.from(body, 'utf8'), Buffer.alloc(2)])
  const buf = Buffer.alloc(12 + payload.length)
  buf.writeInt32LE(8 + payload.length, 0)
  buf.writeInt32LE(id, 4)
  buf.writeInt32LE(type, 8)
  payload.copy(buf, 12)
  return buf
}

function connect ({ host = '127.0.0.1', port = 25599, password = 'testpass' } = {}) {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port }, () => {
      sock.write(packet(1, 3, password))
      let buf = Buffer.alloc(0)
      let authed = false
      const queue = []
      let settleTimer = null
      let current = null

      const flush = () => {
        if (current) {
          const { resolve: res } = current
          current = null
          res(queue.join(''))
          queue.length = 0
        }
      }

      sock.on('data', chunk => {
        buf = Buffer.concat([buf, chunk])
        for (;;) {
          if (buf.length < 4) break
          const len = buf.readInt32LE(0)
          if (buf.length < len + 4) break
          const id = buf.readInt32LE(4)
          const body = buf.slice(12, 4 + len - 2).toString('utf8')
          buf = buf.slice(4 + len)
          if (!authed) {
            authed = id !== -1
            if (!authed) return reject(new Error('RCON 认证失败'))
            resolve(api)
            continue
          }
          queue.push(body)
          clearTimeout(settleTimer)
          settleTimer = setTimeout(flush, 450)
        }
      })
      sock.on('error', e => reject(e))

      let nextId = 2
      const api = {
        send (cmd) {
          return new Promise(res => {
            current = { resolve: res }
            queue.length = 0
            sock.write(packet(nextId++, 2, cmd))
          })
        },
        close () { sock.destroy() }
      }
    })
  })
}

module.exports = { connect }
