const Hyperswarm = require('hyperswarm')
const HyperDHT = require('hyperdht')
const IPC = globalThis.BareKit && globalThis.BareKit.IPC

const pendingEvents = []
const MAX_PENDING_EVENTS = 2048
const PROBE_HEARTBEAT_INTERVAL = 3000
const PROBE_MAX_ATTEMPTS = 5
const LOOKUP_PROBE_TIMEOUT = 10000

const transport = {
  swarm: null,
  dht: null,
  transportSeedHex: null,
  bootstrap: undefined,
  bootstrapRelayPublicKeys: [],
  sockets: new Map(),
  heartbeat: null,
  topicHex: null,
  discovery: null,
  directServer: null,
  directServerPublicKeyHex: null,
  directClientSocket: null,
  loopbackServer: null,
  loopbackClientSocket: null,
  initPromise: null
}

function emit(event) {
  pendingEvents.push(event)
  if (pendingEvents.length > MAX_PENDING_EVENTS) {
    pendingEvents.splice(0, pendingEvents.length - MAX_PENDING_EVENTS)
  }
}

function log(line) {
  emit({ type: 'log', line })
}

function relayBuffers() {
  return transport.bootstrapRelayPublicKeys.length > 0
    ? transport.bootstrapRelayPublicKeys.map((hex) => Buffer.from(hex, 'hex'))
    : null
}

function localTransportPublicKeyHex() {
  return transport.swarm ? transport.swarm.keyPair.publicKey.toString('hex') : ''
}

function peerLabel(info) {
  if (!info || !info.publicKey) return 'unknown-peer'
  return info.publicKey.toString('hex').slice(0, 12)
}

function decodeChunk(data) {
  if (typeof data === 'string') return data
  if (Buffer.isBuffer(data)) return data.toString('utf8')
  return Buffer.from(data).toString('utf8')
}

function encodeProbe(prefix, remotePublicKeyHex, attempt) {
  return Buffer.from(
    `${prefix}:${localTransportPublicKeyHex().slice(0, 12)}:${remotePublicKeyHex.slice(0, 12)}:${attempt}\n`,
    'utf8'
  )
}

function safeWrite(socket, frame) {
  if (socket.destroyed) return
  const queued = socket.write(frame, (err) => {
    if (err) {
      log(`ordered probe write failed: ${err.message}`)
    } else {
      log(`ordered probe write flushed ${frame.byteLength} byte(s)`)
    }
  })
  log(`ordered probe write queued ${frame.byteLength} byte(s), backpressure=${queued === false}`)
}

function safeSend(socket, frame) {
  if (socket.destroyed) return
  if (!socket.connected || typeof socket.send !== 'function') return
  Promise.resolve(socket.send(frame)).catch((err) => {
    log(`unordered probe send failed: ${err.message}`)
  })
}

function sendProbe(socketState, reason) {
  if (socketState.closed || socketState.attempts >= PROBE_MAX_ATTEMPTS) return

  socketState.attempts += 1
  socketState.lastSentAt = Date.now()

  const orderedFrame = encodeProbe('__probe_stream__', socketState.remotePublicKeyHex, socketState.attempts)
  const unorderedFrame = encodeProbe('__probe_datagram__', socketState.remotePublicKeyHex, socketState.attempts)

  safeWrite(socketState.socket, orderedFrame)
  safeSend(socketState.socket, unorderedFrame)
  log(`probe sent (${reason}) attempt ${socketState.attempts} -> ${socketState.remotePublicKeyHex.slice(0, 12)}`)
}

function startHeartbeat() {
  if (transport.heartbeat) return

  transport.heartbeat = setInterval(() => {
    const now = Date.now()
    for (const socketState of transport.sockets.values()) {
      if (socketState.closed || socketState.received) continue
      if (socketState.attempts >= PROBE_MAX_ATTEMPTS) continue
      if (now - socketState.lastSentAt < PROBE_HEARTBEAT_INTERVAL) continue
      sendProbe(socketState, 'heartbeat')
    }
  }, PROBE_HEARTBEAT_INTERVAL)
}

function formatAddress(address) {
  if (!address) return 'unavailable'
  const publicKey = address.publicKey ? ` publicKey=${address.publicKey.toString('hex').slice(0, 16)}...` : ''
  return `${address.host || 'unknown'}:${address.port || 0}${publicKey}`
}

function logDhtSnapshot(reason) {
  if (!transport.dht) return

  const local = typeof transport.dht.localAddress === 'function'
    ? transport.dht.localAddress()
    : null
  const remote = typeof transport.dht.remoteAddress === 'function'
    ? transport.dht.remoteAddress()
    : null

  log(
    `dht snapshot (${reason}) local=${formatAddress(local)} remote=${formatAddress(remote)} ` +
    `firewalled=${transport.dht.firewalled} online=${transport.dht.online} bootstrapped=${transport.dht.bootstrapped}`
  )
}

function inspectLookup(topicHex, reason) {
  if (!transport.dht) return

  const query = transport.dht.lookup(Buffer.from(topicHex, 'hex'))
  let replies = 0
  let peers = 0
  let closed = false

  const timeout = setTimeout(() => {
    if (closed) return
    log(`lookup probe timeout (${reason}) replies=${replies} peers=${peers}`)
    if (typeof query.destroy === 'function') query.destroy()
  }, LOOKUP_PROBE_TIMEOUT)

  query.on('data', (reply) => {
    replies += 1
    const peerCount = Array.isArray(reply.peers) ? reply.peers.length : 0
    peers += peerCount
    const from = reply.from ? `${reply.from.host}:${reply.from.port}` : 'unknown'
    log(`lookup probe data (${reason}) from=${from} peers=${peerCount}`)
    if (Array.isArray(reply.peers)) {
      for (const peer of reply.peers.slice(0, 5)) {
        const key = peer.publicKey ? peer.publicKey.toString('hex').slice(0, 16) : 'unknown'
        const nodes = Array.isArray(peer.nodes) ? peer.nodes.length : 0
        log(`lookup peer key=${key}... nodes=${nodes}`)
      }
    }
  })

  query.on('error', (err) => {
    log(`lookup probe error (${reason}): ${err.message}`)
  })

  query.on('close', () => {
    closed = true
    clearTimeout(timeout)
    log(`lookup probe closed (${reason}) replies=${replies} peers=${peers}`)
  })

  query.resume()
}

async function ensureTransport(command) {
  if (transport.swarm) return

  if (transport.initPromise) {
    await transport.initPromise
    return
  }

  transport.initPromise = initializeTransport(command)
  try {
    await transport.initPromise
  } finally {
    transport.initPromise = null
  }
}

async function initializeTransport(command) {
  transport.transportSeedHex = command.transportSeedHex
  transport.bootstrap = Array.isArray(command.bootstrap) && command.bootstrap.length > 0
    ? command.bootstrap
    : undefined
  transport.bootstrapRelayPublicKeys = Array.isArray(command.bootstrapRelays)
    ? command.bootstrapRelays.filter(Boolean)
    : []

  const keyPair = HyperDHT.keyPair(Buffer.from(command.transportSeedHex, 'hex'))

  transport.dht = new HyperDHT({
    bootstrap: transport.bootstrap,
    port: 0,
    connectionKeepAlive: 45000,
    randomPunchInterval: 15000
  })

  transport.swarm = new Hyperswarm({
    keyPair,
    dht: transport.dht,
    relayThrough: () => relayBuffers()
  })

  await transport.swarm.listen()
  startHeartbeat()
  logDhtSnapshot('after swarm listen')

  transport.swarm.on('connection', (socket, info) => {
    attachConnection(socket, info, info && info.client ? 'discovery-client' : 'discovery-server')
  })

  transport.swarm.on('update', () => {
    log(
      `probe swarm update connecting=${transport.swarm.connecting} ` +
      `connections=${transport.swarm.connections ? transport.swarm.connections.size : 0} ` +
      `peers=${transport.swarm.peers ? transport.swarm.peers.size : 0}`
    )
  })

  log(`probe dht ready ${transport.swarm.keyPair.publicKey.toString('hex').slice(0, 16)}...`)
  log(`probe transport ready ${localTransportPublicKeyHex().slice(0, 16)}...`)
}

function attachConnection(socket, info, source) {
  const remotePublicKeyHex = info && info.publicKey ? info.publicKey.toString('hex') : (
    socket.remotePublicKey ? socket.remotePublicKey.toString('hex') : 'unknown-peer'
  )
  const socketState = {
    socket,
    remotePublicKeyHex,
    source,
    attempts: 0,
    lastSentAt: 0,
    received: false,
    closed: false
  }

  transport.sockets.set(remotePublicKeyHex, socketState)
  log(
    `probe peer transport connected ${peerLabel(info)} via ${source} ` +
    `connected=${socket.connected === true} writable=${socket.writable !== false}`
  )

  let readyHandled = false
  const handleReady = (eventName) => {
    if (readyHandled || socketState.closed) return
    readyHandled = true
    const handshakeHash = socket.handshakeHash
      ? socket.handshakeHash.toString('hex').slice(0, 16)
      : 'none'
    log(`probe socket ready ${remotePublicKeyHex.slice(0, 12)} via ${source}/${eventName} handshake=${handshakeHash}`)
    sendProbe(socketState, eventName)
  }

  socket.once('connect', () => handleReady('connect'))
  socket.once('open', () => handleReady('open'))

  socket.on('data', (data) => {
    const chunkLength =
      typeof data === 'string'
        ? Buffer.byteLength(data, 'utf8')
        : typeof data?.byteLength === 'number'
          ? data.byteLength
          : typeof data?.length === 'number'
            ? data.length
            : 0
    const text = decodeChunk(data).trim()
    if (text.startsWith('__probe_stream__')) {
      socketState.received = true
      log(`probe ordered rx ${chunkLength} from ${remotePublicKeyHex.slice(0, 12)} -> ${text}`)
      return
    }
    log(`probe ordered rx ${chunkLength} from ${remotePublicKeyHex.slice(0, 12)} -> ${JSON.stringify(text.slice(0, 120))}`)
  })

  socket.on('message', (data) => {
    const chunkLength =
      typeof data === 'string'
        ? Buffer.byteLength(data, 'utf8')
        : typeof data?.byteLength === 'number'
          ? data.byteLength
          : typeof data?.length === 'number'
            ? data.length
            : 0
    const text = decodeChunk(data).trim()
    if (text.startsWith('__probe_datagram__')) {
      socketState.received = true
      log(`probe datagram rx ${chunkLength} from ${remotePublicKeyHex.slice(0, 12)} -> ${text}`)
      return
    }
    log(`probe datagram rx ${chunkLength} from ${remotePublicKeyHex.slice(0, 12)} -> ${JSON.stringify(text.slice(0, 120))}`)
  })

  socket.on('error', (err) => {
    log(`probe peer error ${peerLabel(info)}: ${err.message}`)
  })

  socket.on('close', () => {
    socketState.closed = true
    if (transport.sockets.get(remotePublicKeyHex) === socketState) {
      transport.sockets.delete(remotePublicKeyHex)
    }
    log(`probe peer closed ${peerLabel(info)}`)
  })

  if (typeof socket.resume === 'function') socket.resume()

  if (socket.connected) {
    handleReady('already-connected')
  }
}

async function startDirectProbe(command) {
  await ensureTransport(command)

  const directKeyPair = HyperDHT.keyPair(Buffer.from(command.directSeedHex, 'hex'))
  const remotePublicKey = command.remotePublicKeyHex
    ? Buffer.from(command.remotePublicKeyHex, 'hex')
    : HyperDHT.keyPair(Buffer.from(command.remoteSeedHex, 'hex')).publicKey

  if (command.role === 'server') {
    if (!transport.directServer) {
      const server = transport.dht.createServer()
      server.on('connection', (socket) => {
        attachConnection(
          socket,
          { publicKey: socket.remotePublicKey, client: false },
          'direct-server'
        )
      })
      transport.directServer = server
      await server.listen(directKeyPair)
      transport.directServerPublicKeyHex = directKeyPair.publicKey.toString('hex')
      log(`direct server listening ${transport.directServerPublicKeyHex}`)
      log(`direct server address ${formatAddress(server.address ? server.address() : null)}`)
      logDhtSnapshot('after direct server listen')
    } else {
      log(`direct server already listening ${transport.directServerPublicKeyHex}`)
    }
    return
  }

  if (transport.directClientSocket && !transport.directClientSocket.destroyed) {
    log(`direct client already connecting ${remotePublicKey.toString('hex').slice(0, 12)}`)
    return
  }

  const socket = transport.dht.connect(remotePublicKey, {
    keyPair: directKeyPair,
    localConnection: command.localConnection !== false
  })
  transport.directClientSocket = socket
  log(`direct client dialing ${remotePublicKey.toString('hex')} localConnection=${command.localConnection !== false}`)
  attachConnection(
    socket,
    { publicKey: remotePublicKey, client: true },
    command.localConnection === false ? 'direct-client-remote-only' : 'direct-client'
  )
}

async function startLoopbackProbe(command) {
  await ensureTransport(command)

  if (transport.loopbackServer || transport.loopbackClientSocket) {
    log('loopback probe already running')
    return
  }

  const serverKeyPair = HyperDHT.keyPair(Buffer.from(command.loopbackServerSeedHex, 'hex'))
  const clientKeyPair = HyperDHT.keyPair(Buffer.from(command.loopbackClientSeedHex, 'hex'))
  const server = transport.dht.createServer()

  server.on('connection', (socket) => {
    log('loopback server accepted connection')
    attachConnection(
      socket,
      { publicKey: socket.remotePublicKey || clientKeyPair.publicKey, client: false },
      'loopback-server'
    )
  })

  transport.loopbackServer = server
  await server.listen(serverKeyPair)
  log(`loopback server listening ${serverKeyPair.publicKey.toString('hex')}`)
  log(`loopback server address ${formatAddress(server.address ? server.address() : null)}`)

  const socket = transport.dht.connect(serverKeyPair.publicKey, {
    keyPair: clientKeyPair,
    localConnection: true
  })
  transport.loopbackClientSocket = socket
  log(`loopback client dialing ${serverKeyPair.publicKey.toString('hex')}`)
  attachConnection(
    socket,
    { publicKey: serverKeyPair.publicKey, client: true },
    'loopback-client'
  )
}

async function startProbe(command) {
  await ensureTransport(command)

  if (transport.discovery) {
    log(`probe already joined ${transport.topicHex}`)
    return
  }

  transport.topicHex = command.topicHex
  transport.discovery = transport.swarm.join(Buffer.from(command.topicHex, 'hex'), {
    server: true,
    client: true
  })

  await transport.discovery.flushed()
  log(`probe topic announced ${command.topicHex}`)
  logDhtSnapshot('after topic announce')
  inspectLookup(command.topicHex, 'after topic announce')
}

async function suspendNetworking() {
  if (!transport.swarm) return
  await transport.swarm.suspend({ log })
  log('probe transport suspended')
}

async function resumeNetworking() {
  if (!transport.swarm) return
  await transport.swarm.resume({ log })
  log('probe transport resumed')
}

function runAsyncCommand(task) {
  Promise.resolve()
    .then(task)
    .catch((err) => {
      const message = err && err.stack ? err.stack : String(err)
      log(`probe runtime error: ${message}`)
    })
}

function handleCommand(command) {
  switch (command.type) {
    case 'drain_events':
      return { ok: true, events: pendingEvents.splice(0, pendingEvents.length) }

    case 'start_probe':
      runAsyncCommand(() => startProbe(command))
      return { ok: true }

    case 'start_direct_probe':
      runAsyncCommand(() => startDirectProbe(command))
      return { ok: true }

    case 'start_loopback_probe':
      runAsyncCommand(() => startLoopbackProbe(command))
      return { ok: true }

    case 'suspend_networking':
      runAsyncCommand(() => suspendNetworking())
      return { ok: true }

    case 'resume_networking':
      runAsyncCommand(() => resumeNetworking())
      return { ok: true }

    default:
      return { ok: false, error: `unknown command: ${command.type}` }
  }
}

emit({
  type: 'runtime.ready',
  runtime: 'Bare Worklet + Hyperswarm Probe',
  detail: 'Minimal Android transport probe ready',
  realTransportActive: true
})

log('probe runtime boot complete')

if (!IPC) {
  throw new Error('BareKit IPC is not available in the mobile worklet runtime')
}

IPC.setEncoding('utf8')

let pendingInput = ''
let pendingOutput = []
let flushingOutput = false

function queueReply(payload) {
  pendingOutput.push(JSON.stringify(payload) + '\n')
  flushReplies()
}

function flushReplies() {
  if (flushingOutput) return
  flushingOutput = true

  while (pendingOutput.length > 0) {
    const next = pendingOutput[0]
    const canContinue = IPC.write(next)
    pendingOutput.shift()
    if (canContinue === false) {
      IPC.once('drain', () => {
        flushingOutput = false
        flushReplies()
      })
      return
    }
  }

  flushingOutput = false
}

IPC.on('data', (chunk) => {
  pendingInput += chunk

  while (true) {
    const newlineIndex = pendingInput.indexOf('\n')
    if (newlineIndex === -1) break

    const line = pendingInput.slice(0, newlineIndex)
    pendingInput = pendingInput.slice(newlineIndex + 1)

    if (line.trim().length === 0) continue

    let command
    try {
      command = JSON.parse(line)
    } catch (err) {
      queueReply({ ok: false, error: `invalid json: ${err.message}` })
      continue
    }

    queueReply(handleCommand(command))
  }
})
