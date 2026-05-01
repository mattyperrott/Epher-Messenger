const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const HyperDHT = require('hyperdht')
const BlindRelay = require('blind-relay')

const runtimeRoot = path.resolve(__dirname, '..')
const seedPath = path.join(runtimeRoot, '.local-relay-seed.hex')
const publicKeyPath = path.join(runtimeRoot, '.local-relay-public-key')
const bootstrapHost = process.env.EPHER_BOOTSTRAP_HOST

const bootstrapPort = process.env.EPHER_BOOTSTRAP_PORT || '49737'
const bootstrap = bootstrapHost ? [`${bootstrapHost}:${bootstrapPort}`] : undefined

function ensureSeedHex() {
  if (fs.existsSync(seedPath)) {
    return fs.readFileSync(seedPath, 'utf8').trim()
  }

  const seedHex = crypto.randomBytes(32).toString('hex')
  fs.writeFileSync(seedPath, `${seedHex}\n`, { mode: 0o600 })
  return seedHex
}

async function main() {
  const seedHex = ensureSeedHex()
  const keyPair = HyperDHT.keyPair(Buffer.from(seedHex, 'hex'))

  fs.writeFileSync(publicKeyPath, `${keyPair.publicKey.toString('hex')}\n`, { mode: 0o644 })

  const dht = new HyperDHT({
    bootstrap,
    keyPair,
    port: 0,
    connectionKeepAlive: 45000,
    randomPunchInterval: 15000
  })

  const relay = new BlindRelay.Server({
    createStream: (opts) => dht.createRawStream({ framed: true, ...opts })
  })
  const server = dht.createServer()

  server.on('connection', (socket) => {
    const remote = socket.remotePublicKey ? socket.remotePublicKey.toString('hex').slice(0, 12) : 'unknown-peer'
    console.log(`[relay] session accepted ${remote}`)
    const session = relay.accept(socket, { id: socket.remotePublicKey })
    session.on('open', () => {
      console.log(`[relay] channel open ${remote}`)
    })
    session.on('pair', (isInitiator, token) => {
      console.log(
        `[relay] paired ${remote} role=${isInitiator ? 'initiator' : 'responder'} token=${token.toString('hex').slice(0, 12)}`
      )
    })
    session.on('error', (err) => {
      console.log(`[relay] session error ${remote}: ${err.message}`)
    })
    session.on('close', () => {
      console.log(`[relay] session closed ${remote}`)
    })
    socket.on('error', (err) => {
      console.log(`[relay] socket error ${remote}: ${err.message}`)
    })
    socket.on('close', () => {
      console.log(`[relay] socket closed ${remote}`)
    })
  })

  await server.listen(keyPair)

  console.log(`Relay bootstrap: ${bootstrap ? bootstrap.join(', ') : 'default HyperDHT bootstrap nodes'}`)
  console.log(`Relay public key: ${keyPair.publicKey.toString('hex')}`)
  console.log(`Relay key saved: ${publicKeyPath}`)
  console.log('Local relay ready')

  const shutdown = async () => {
    await relay.close().catch(() => {})
    await server.close().catch(() => {})
    await dht.destroy().catch(() => {})
    process.exit(0)
  }

  process.on('SIGINT', shutdown)
  process.on('SIGTERM', shutdown)
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
