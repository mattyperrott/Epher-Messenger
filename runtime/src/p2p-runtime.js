const Hyperswarm = require('hyperswarm')
const HyperDHT = require('hyperdht')
const IPC = globalThis.BareKit && globalThis.BareKit.IPC

const rooms = new Map()
const pendingEvents = []
const MAX_PENDING_EVENTS = 4096
const DISCOVERY_REFRESH_INTERVAL = 15000
const HEARTBEAT_INTERVAL = 5000
const HANDSHAKE_RETRY_INTERVAL = 2000
const TRANSPORT_PROBE_INTERVAL = 3000
const TRANSPORT_PROBE_MAX_ATTEMPTS = 3
const DELIVERY_RETRY_INTERVAL = 3500
const DELIVERY_MAX_ATTEMPTS = 5

const transport = {
  swarm: null,
  dht: null,
  transportSeedHex: null,
  bootstrap: undefined,
  bootstrapRelayPublicKeys: [],
  sockets: new Map(),
  explicitPeerRooms: new Map(),
  explicitPeerDialTimers: new Map(),
  heartbeat: null,
  initPromise: null
}

function emit(event) {
  pendingEvents.push(event)
  if (pendingEvents.length > MAX_PENDING_EVENTS) {
    pendingEvents.splice(0, pendingEvents.length - MAX_PENDING_EVENTS)
  }
}

function log(line, roomId = null) {
  emit({ type: 'log', roomId, line })
}

function roomState(room, state, detail) {
  // Validate participant count: local peer (1) + connected peers
  // Cap at 500 to prevent memory exhaustion from buggy or malicious peer tracking
  const MAX_ROOM_PARTICIPANTS = 500
  const participantCount = Math.min(room.connectedPeerKeys.size + 1, MAX_ROOM_PARTICIPANTS)
  
  // Warn if count exceeds reasonable threshold without hitting cap
  if (room.connectedPeerKeys.size > 200) {
    log(`WARNING: room ${room.roomId} has ${room.connectedPeerKeys.size} connected peers`, room.roomId)
  }
  
  const nextStateKey = `${state}|${detail}|${participantCount}`
  if (room.lastStateKey === nextStateKey) return
  room.lastStateKey = nextStateKey

  emit({
    type: 'room.state',
    roomId: room.roomId,
    state,
    participantCount,
    detail
  })
}

function sendJson(socket, payload) {
  if (socket.destroyed) return
  sendSocketFrame(socket, encodeSocketFrame(payload))
}

function sendHandshakeJson(socket, payload, roomId) {
  if (socket.destroyed) return
  const frame = encodeSocketFrame(payload)

  if (socket.connected && typeof socket.send === 'function') {
    Promise.resolve(socket.send(frame)).catch((err) => {
      log(`handshake datagram send failed: ${err.message}`, roomId)
    })
  }

  socket.write(frame)
}

function decodeSocketChunk(data) {
  if (typeof data === 'string') return data
  if (Buffer.isBuffer(data)) return data.toString('utf8')
  return Buffer.from(data).toString('utf8')
}

function encodeSocketFrame(payload) {
  return Buffer.from(JSON.stringify(payload) + '\n', 'utf8')
}

function encodeTransportProbe(prefix, remotePublicKeyHex, attempt) {
  return Buffer.from(`${prefix}:${localTransportPublicKeyHex().slice(0, 12)}:${remotePublicKeyHex.slice(0, 12)}:${attempt}\n`, 'utf8')
}

function sendSocketFrame(socket, frame) {
  if (socket.destroyed) return

  // Room frames are part of the reliable chat/control plane. Do not prefer
  // unordered datagrams here: `trySend()` can drop silently on unsupported
  // transports, which leaves peers "connected" with no delivered payloads.
  socket.write(frame)
}

function sendTransportProbe(socketState, reason) {
  if (socketState.closed || socketState.probeAttempts >= TRANSPORT_PROBE_MAX_ATTEMPTS) return

  socketState.probeAttempts += 1
  socketState.probeLastSentAt = Date.now()

  const streamProbe = encodeTransportProbe('__epher_probe_stream__', socketState.remotePublicKeyHex, socketState.probeAttempts)
  socketState.socket.write(streamProbe)

  if (socketState.socket.connected && typeof socketState.socket.send === 'function') {
    const datagramProbe = encodeTransportProbe('__epher_probe_datagram__', socketState.remotePublicKeyHex, socketState.probeAttempts)
    Promise.resolve(socketState.socket.send(datagramProbe)).catch((err) => {
      log(`transport probe datagram send failed: ${err.message}`)
    })
  }

  log(`transport probe sent (${reason}) attempt ${socketState.probeAttempts} to ${socketState.remotePublicKeyHex.slice(0, 12)}`)
}

function peerLabel(info) {
  if (!info || !info.publicKey) return 'unknown-peer'
  return info.publicKey.toString('hex').slice(0, 12)
}

function safeJsonParse(value) {
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

function peerFingerprint(encodedPeerCard) {
  const parsed = safeJsonParse(encodedPeerCard)
  return parsed ? parsed.fingerprint : null
}

function localTransportPublicKeyHex() {
  return transport.swarm ? transport.swarm.keyPair.publicKey.toString('hex') : ''
}

function relayBuffers() {
  return transport.bootstrapRelayPublicKeys.length > 0
    ? transport.bootstrapRelayPublicKeys.map((hex) => Buffer.from(hex, 'hex'))
    : null
}

function roomSockets(room) {
  const sockets = []

  for (const remotePublicKeyHex of room.connectedPeerKeys) {
    const socketState = transport.sockets.get(remotePublicKeyHex)
    if (socketState && !socketState.closed) sockets.push(socketState)
  }

  return sockets
}

function dialExplicitPeer(publicKeyHex, roomId, reason = 'track') {
  if (!transport.swarm || !publicKeyHex) return
  if (publicKeyHex === localTransportPublicKeyHex()) return

  const existing = transport.sockets.get(publicKeyHex)
  if (existing && !existing.closed) {
    log(`explicit peer already connected ${publicKeyHex.slice(0, 12)}`, roomId)
    return
  }

  if (transport.explicitPeerDialTimers.has(publicKeyHex)) return

  const timer = setTimeout(() => {
    transport.explicitPeerDialTimers.delete(publicKeyHex)

    const active = transport.sockets.get(publicKeyHex)
    if (active && !active.closed) return
    if (!transport.explicitPeerRooms.has(publicKeyHex)) return

    transport.swarm.joinPeer(Buffer.from(publicKeyHex, 'hex'))
    log(`dialing explicit peer ${publicKeyHex.slice(0, 12)} (${reason})`, roomId)
  }, reason === 'reconnect' ? 1000 : 0)

  transport.explicitPeerDialTimers.set(publicKeyHex, timer)
}

function trackExplicitPeer(roomId, publicKeyHex) {
  if (!transport.swarm || !publicKeyHex) return
  if (publicKeyHex === localTransportPublicKeyHex()) return

  let roomIds = transport.explicitPeerRooms.get(publicKeyHex)
  if (!roomIds) {
    roomIds = new Set()
    transport.explicitPeerRooms.set(publicKeyHex, roomIds)
    log(`tracking explicit peer ${publicKeyHex.slice(0, 12)}`, roomId)
  }

  roomIds.add(roomId)
  dialExplicitPeer(publicKeyHex, roomId)
}

function untrackExplicitPeer(roomId, publicKeyHex) {
  if (!publicKeyHex) return

  const roomIds = transport.explicitPeerRooms.get(publicKeyHex)
  if (!roomIds) return

  roomIds.delete(roomId)
  if (roomIds.size > 0) return

  transport.explicitPeerRooms.delete(publicKeyHex)
  const timer = transport.explicitPeerDialTimers.get(publicKeyHex)
  if (timer) {
    clearTimeout(timer)
    transport.explicitPeerDialTimers.delete(publicKeyHex)
  }
  if (transport.swarm) {
    transport.swarm.leavePeer(Buffer.from(publicKeyHex, 'hex'))
  }
  log(`stopped tracking explicit peer ${publicKeyHex.slice(0, 12)}`, roomId)
}

function untrackExplicitPeersForRoom(room) {
  for (const entry of room.knownPeers.values()) {
    untrackExplicitPeer(room.roomId, entry.transportPublicKeyHex)
  }

  for (const publicKeyHex of room.explicitPeerTransportKeys) {
    untrackExplicitPeer(room.roomId, publicKeyHex)
  }
}

function topicHexList(info) {
  if (!info || !Array.isArray(info.topics)) return []
  return info.topics.map((topic) => topic.toString('hex'))
}

function preferredSocketSource(remotePublicKeyHex) {
  const localPublicKeyHex = localTransportPublicKeyHex()
  if (!localPublicKeyHex) return 'swarm-client'
  return localPublicKeyHex > remotePublicKeyHex ? 'swarm-client' : 'swarm-server'
}

function roomIdsForTopics(info) {
  const advertisedTopics = new Set(topicHexList(info))
  if (advertisedTopics.size === 0) return null

  const roomIds = []
  for (const room of rooms.values()) {
    if (advertisedTopics.has(room.topicHex)) roomIds.push(room.roomId)
  }

  return roomIds
}

function registerKnownPeer(room, encodedPeerCard, transportPublicKeyHex) {
  const fingerprint = peerFingerprint(encodedPeerCard)
  if (!fingerprint) return false

  const previous = room.knownPeers.get(fingerprint)
  if (
    previous &&
    previous.encodedPeerCard === encodedPeerCard &&
    previous.transportPublicKeyHex === transportPublicKeyHex
  ) {
    return false
  }

  if (previous && previous.transportPublicKeyHex !== transportPublicKeyHex) {
    untrackExplicitPeer(room.roomId, previous.transportPublicKeyHex)
  }

  room.knownPeers.set(fingerprint, { encodedPeerCard, transportPublicKeyHex })
  trackExplicitPeer(room.roomId, transportPublicKeyHex)
  emit({
    type: 'peer.card',
    roomId: room.roomId,
    encodedPeerCard,
    transportPublicKeyHex
  })
  return true
}

function sendRoster(room, socket) {
  const peers = [...room.knownPeers.values()].map((entry) => ({
    encodedPeerCard: entry.encodedPeerCard,
    transportPublicKeyHex: entry.transportPublicKeyHex
  }))
  log(`sending roster (${peers.length} peer card(s))`, room.roomId)
  sendHandshakeJson(socket, { type: 'roster', roomId: room.roomId, peers }, room.roomId)
}

function sendHello(room, socket) {
  log('sending hello', room.roomId)
  sendHandshakeJson(socket, {
    type: 'hello',
    roomId: room.roomId,
    encodedPeerCard: room.localPeerCard,
    transportPublicKeyHex: localTransportPublicKeyHex()
  }, room.roomId)
}

function flushPending(room, socketState) {
  for (const pending of room.pendingAcks.values()) {
    sendSocketFrame(socketState.socket, pending.frame)
  }
}

function markRoomMembership(room, socketState) {
  if (socketState.roomIds.has(room.roomId)) return

  socketState.roomIds.add(room.roomId)
  room.connectedPeerKeys.add(socketState.remotePublicKeyHex)
  emit({
    type: 'peer.presence',
    roomId: room.roomId,
    transportPublicKeyHex: socketState.remotePublicKeyHex,
    presence: 'joined'
  })
  log(`peer connected ${socketState.remotePublicKeyHex.slice(0, 12)}`, room.roomId)
  roomState(room, 'connected', `${room.connectedPeerKeys.size} peer link(s) active`)
  flushPending(room, socketState)
}

function announceRoomOnSocket(room, socketState) {
  if (socketState.closed || socketState.roomIds.has(room.roomId)) return

  if (socketState.helloSentRoomIds.has(room.roomId)) {
    socketState.handshakeSentAt.set(room.roomId, Date.now())
    log(`retrying room handshake to ${socketState.remotePublicKeyHex.slice(0, 12)}`, room.roomId)
  } else {
    socketState.helloSentRoomIds.add(room.roomId)
    log(`announcing room handshake to ${socketState.remotePublicKeyHex.slice(0, 12)}`, room.roomId)
  }

  socketState.handshakeSentAt.set(room.roomId, Date.now())
  sendHello(room, socketState.socket)
  sendRoster(room, socketState.socket)
}

function announceRoomToKnownSockets(room) {
  for (const socketState of transport.sockets.values()) {
    announceRoomOnSocket(room, socketState)
  }
}

function updateRoomConnectionState(room, detailWhenEmpty = 'awaiting peers') {
  roomState(
    room,
    room.connectedPeerKeys.size > 0 ? 'connected' : 'reconnecting',
    room.connectedPeerKeys.size > 0 ? 'peer set updated' : detailWhenEmpty
  )
}

function dropSocket(socketState, reason = 'closed') {
  if (socketState.closed) return
  socketState.closed = true

  if (transport.sockets.get(socketState.remotePublicKeyHex) === socketState) {
    transport.sockets.delete(socketState.remotePublicKeyHex)
  }

  for (const roomId of socketState.roomIds) {
    const room = rooms.get(roomId)
    if (!room) continue
    room.connectedPeerKeys.delete(socketState.remotePublicKeyHex)
    emit({
      type: 'peer.presence',
      roomId,
      transportPublicKeyHex: socketState.remotePublicKeyHex,
      presence: 'left'
    })
    log(
      `peer disconnected ${socketState.remotePublicKeyHex.slice(0, 12)}${reason === 'superseded' ? ' (superseded)' : ''}`,
      room.roomId
    )
    updateRoomConnectionState(room)
  }

  const trackedRoomIds = transport.explicitPeerRooms.get(socketState.remotePublicKeyHex)
  if (trackedRoomIds && trackedRoomIds.size > 0 && reason !== 'shutdown' && reason !== 'superseded') {
    dialExplicitPeer(socketState.remotePublicKeyHex, [...trackedRoomIds][0], 'reconnect')
  }
}

function createRoomState(command) {
  return {
    roomId: command.roomId,
    label: command.label,
    topicHex: command.topicHex,
    isCreator: command.type === 'create_room',
    localPeerCard: command.localPeerCard,
    relayPublicKeys: [],
    discovery: null,
    lastDiscoveryRefreshAt: 0,
    refreshInFlight: false,
    pendingAcks: new Map(),
    knownPeers: new Map(),
    explicitPeerTransportKeys: new Set(),
    connectedPeerKeys: new Set(),
    transportReady: false,
    lastStateKey: null
  }
}

async function ensureTransport(command) {
  if (transport.swarm) {
    if (transport.transportSeedHex !== command.transportSeedHex) {
      log('shared transport already initialized with an existing seed')
    }
    return
  }

  if (transport.initPromise) {
    await transport.initPromise
    if (transport.swarm && transport.transportSeedHex !== command.transportSeedHex) {
      log('shared transport already initialized with an existing seed')
    }
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
    relayThrough: () => {
      return relayBuffers()
    }
  })
  await transport.swarm.listen()

  transport.swarm.on('connection', (socket, info) => {
    const roomIds = roomIdsForTopics(info)
    const topicHint = roomIds && roomIds.length > 0 ? ` topics=${roomIds.length}` : ''
    log(`swarm discovery connected ${peerLabel(info)}${topicHint}`)
    attachConnectionWithHints(
      socket,
      info,
      roomIds,
      info && info.client ? 'swarm-client' : 'swarm-server'
    )

    socket.on('error', (err) => {
      const targetRoomIds = roomIds && roomIds.length > 0 ? roomIds : [...rooms.keys()]
      for (const roomId of targetRoomIds) {
        log(`swarm discovery error ${peerLabel(info)}: ${err.message}`, roomId)
      }
    })
  })
  transport.swarm.on('update', () => {
    if (!transport.swarm || transport.swarm.connecting === 0) return

    for (const room of rooms.values()) {
      if (room.connectedPeerKeys.size > 0) continue
      roomState(
        room,
        'reconnecting',
        `connecting to peers (${transport.swarm.connecting} pending)`
      )
    }
  })
  transport.swarm.on('ban', (info, err) => {
    log(`peer banned ${peerLabel(info)}: ${err ? err.message : 'unknown error'}`)
  })

  startHeartbeat()

  if (transport.bootstrap && transport.bootstrap.length > 0) {
    log(`bootstrap nodes ${transport.bootstrap.join(', ')}`)
  }
  if (transport.bootstrapRelayPublicKeys.length > 0) {
    log(`bootstrap relays ${transport.bootstrapRelayPublicKeys.join(', ')}`)
  }
  log(`dht server ready ${transport.swarm.keyPair.publicKey.toString('hex').slice(0, 16)}...`)
  log(`shared transport ready ${localTransportPublicKeyHex().slice(0, 16)}...`)
}

function startHeartbeat() {
  if (transport.heartbeat) return

  transport.heartbeat = setInterval(() => {
    const now = Date.now()

    for (const socketState of transport.sockets.values()) {
      if (!socketState.closed) {
        sendJson(socketState.socket, { type: 'ping', ts: now })
      }
    }

    for (const room of rooms.values()) {
      if (room.connectedPeerKeys.size === 0 && now - room.lastDiscoveryRefreshAt >= DISCOVERY_REFRESH_INTERVAL) {
        refreshDiscovery(room, 'awaiting peers')
      }

      for (const socketState of transport.sockets.values()) {
        if (socketState.closed || socketState.roomIds.has(room.roomId)) continue
        const lastSentAt = socketState.handshakeSentAt.get(room.roomId) || 0
        if (!socketState.helloSentRoomIds.has(room.roomId)) continue
        if (now - lastSentAt < HANDSHAKE_RETRY_INTERVAL) continue
        announceRoomOnSocket(room, socketState)
      }

      for (const socketState of transport.sockets.values()) {
        if (socketState.closed || socketState.probeReceived) continue
        if (socketState.probeAttempts >= TRANSPORT_PROBE_MAX_ATTEMPTS) continue
        if (now - socketState.probeLastSentAt < TRANSPORT_PROBE_INTERVAL) continue
        sendTransportProbe(socketState, 'heartbeat')
      }

      for (const pending of room.pendingAcks.values()) {
        if (pending.nextRetryAt > now) continue

        if (pending.attempts >= DELIVERY_MAX_ATTEMPTS) {
          room.pendingAcks.delete(pending.messageId)
          log(`delivery expired ${pending.messageId}`, room.roomId)
          continue
        }

        const sockets = roomSockets(room)
        if (sockets.length === 0) continue

        pending.attempts += 1
        pending.nextRetryAt = now + DELIVERY_RETRY_INTERVAL
        for (const socketState of sockets) {
          sendSocketFrame(socketState.socket, pending.frame)
        }
        log(`delivery retry ${pending.messageId} attempt ${pending.attempts}`, room.roomId)
      }
    }
  }, HEARTBEAT_INTERVAL)
}

async function refreshDiscovery(room, reason) {
  if (!room.discovery || room.refreshInFlight) return
  room.refreshInFlight = true
  room.lastDiscoveryRefreshAt = Date.now()

  try {
    await room.discovery.refresh({ client: true, server: true })
    log(`discovery refresh ${reason}`, room.roomId)
  } catch (err) {
    log(`discovery refresh failed: ${err.message}`, room.roomId)
  } finally {
    room.refreshInFlight = false
  }
}

async function announceRoomTopic(room) {
  if (!room.discovery) return
  await room.discovery.flushed()
}

function attachConnection(socket, info) {
  return attachConnectionWithHints(socket, info, roomIdsForTopics(info), 'swarm')
}

function attachConnectionWithHints(socket, info, roomIdsHint, source) {
  const remotePublicKeyHex = info.publicKey.toString('hex')
  const existing = transport.sockets.get(remotePublicKeyHex)
  if (existing && existing.socket !== socket) {
    const preferredSource = preferredSocketSource(remotePublicKeyHex)
    const keepExisting = existing.source === preferredSource || source !== preferredSource

    if (keepExisting) {
      log(
        `dropping duplicate ${source} socket for ${peerLabel(info)} (keeping ${existing.source}, preferred ${preferredSource})`,
        roomIdsHint && roomIdsHint.length > 0 ? roomIdsHint[0] : null
      )
      socket.destroy()
      return
    }

    log(
      `replacing duplicate ${existing.source} socket for ${peerLabel(info)} (preferred ${preferredSource})`,
      roomIdsHint && roomIdsHint.length > 0 ? roomIdsHint[0] : null
    )
    dropSocket(existing, 'superseded')
    existing.socket.destroy()
  }

  const socketState = {
    socket,
    info,
    remotePublicKeyHex,
    source,
    buffer: '',
    roomIds: new Set(),
    helloSentRoomIds: new Set(),
    handshakeSentAt: new Map(),
    probeAttempts: 0,
    probeLastSentAt: 0,
    probeReceived: false,
    closed: false
  }

  transport.sockets.set(remotePublicKeyHex, socketState)

  const matchedRoomIds = roomIdsHint && roomIdsHint.length > 0 ? roomIdsHint : roomIdsForTopics(info)
  const roomHint = matchedRoomIds && matchedRoomIds.length > 0
    ? ` topics=${matchedRoomIds.length}`
    : ''
  const sourceHint = source === 'swarm-client'
    ? ' via discovery-client'
    : source === 'swarm-server'
      ? ' via discovery-server'
      : ''
  log(`peer transport connected ${peerLabel(info)}${roomHint}${sourceHint}`)

  socket.on('data', (data) => {
    const chunkLength =
      typeof data === 'string'
        ? Buffer.byteLength(data, 'utf8')
        : typeof data?.byteLength === 'number'
          ? data.byteLength
          : typeof data?.length === 'number'
            ? data.length
            : 0
    if (chunkLength > 0) {
      log(`rx chunk ${chunkLength} byte(s) from ${socketState.remotePublicKeyHex.slice(0, 12)}`)
    }
    socketState.buffer += decodeSocketChunk(data)
    let newlineIndex = socketState.buffer.indexOf('\n')

    while (newlineIndex !== -1) {
      const line = socketState.buffer.slice(0, newlineIndex).trim()
      socketState.buffer = socketState.buffer.slice(newlineIndex + 1)

      if (line.length > 0) {
        if (line.startsWith('__epher_probe_stream__')) {
          socketState.probeReceived = true
          log(`received raw stream probe from ${socketState.remotePublicKeyHex.slice(0, 12)}`)
          newlineIndex = socketState.buffer.indexOf('\n')
          continue
        }
        handleFrame(socketState, line)
      }

      newlineIndex = socketState.buffer.indexOf('\n')
    }
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
    if (chunkLength > 0) {
      log(`rx datagram ${chunkLength} byte(s) from ${socketState.remotePublicKeyHex.slice(0, 12)}`)
    }
    const line = decodeSocketChunk(data).trim()
    if (line.length > 0) {
      if (line.startsWith('__epher_probe_datagram__')) {
        socketState.probeReceived = true
        log(`received raw datagram probe from ${socketState.remotePublicKeyHex.slice(0, 12)}`)
        return
      }
      handleFrame(socketState, line)
    }
  })

  socket.on('error', (err) => {
    const roomIds = socketState.roomIds.size > 0 ? [...socketState.roomIds] : matchedRoomIds || []
    if (roomIds.length === 0) {
      log(`peer error ${peerLabel(info)}: ${err.message}`)
      return
    }
    for (const roomId of roomIds) {
      log(`peer error ${peerLabel(info)}: ${err.message}`, roomId)
      const room = rooms.get(roomId)
      if (!room) continue
      roomState(room, 'reconnecting', 'peer transport error, refreshing discovery')
      Promise.resolve(refreshDiscovery(room, 'peer transport error')).catch((refreshErr) => {
        log(`discovery refresh failed: ${refreshErr.message}`, room.roomId)
      })
    }
  })

  socket.on('close', () => {
    dropSocket(socketState)
  })

  if (typeof socket.resume === 'function') {
    socket.resume()
  }

  const targetRoomIds = matchedRoomIds && matchedRoomIds.length > 0
    ? matchedRoomIds
    : [...rooms.keys()]
  const announceTargetRooms = () => {
    log(`preparing handshake for ${targetRoomIds.length} room(s)`, targetRoomIds[0] || null)
    for (const roomId of targetRoomIds) {
      const room = rooms.get(roomId)
      if (room) announceRoomOnSocket(room, socketState)
    }
  }

  let readyHandled = false
  const handleReady = (eventName) => {
    if (readyHandled || socketState.closed) return
    readyHandled = true
    log(`peer transport ready ${peerLabel(info)} via ${eventName}`, targetRoomIds[0] || null)
    sendTransportProbe(socketState, eventName)
    announceTargetRooms()
  }

  socket.once('connect', () => handleReady('connect'))
  socket.once('open', () => handleReady('open'))

  if (socket.connected) {
    handleReady('already-connected')
  }
}

function handleFrame(socketState, line) {
  const frame = safeJsonParse(line)
  if (!frame) {
    log('frame parse failed')
    return
  }

  switch (frame.type) {
    case 'hello': {
      const room = rooms.get(frame.roomId)
      if (!room) break
      log(`received hello from ${socketState.remotePublicKeyHex.slice(0, 12)}`, room.roomId)
      markRoomMembership(room, socketState)
      const wasNew = registerKnownPeer(room, frame.encodedPeerCard, frame.transportPublicKeyHex)
      if (wasNew) sendRoster(room, socketState.socket)
      break
    }

    case 'roster': {
      const room = rooms.get(frame.roomId)
      if (!room) break
      log(`received roster (${(frame.peers || []).length} peer card(s))`, room.roomId)
      markRoomMembership(room, socketState)
      for (const entry of frame.peers || []) {
        registerKnownPeer(room, entry.encodedPeerCard, entry.transportPublicKeyHex)
      }
      break
    }

    case 'ping':
      sendJson(socketState.socket, { type: 'pong', ts: Date.now() })
      break

    case 'pong':
      break

    case 'envelope': {
      const room = rooms.get(frame.roomId)
      if (!room) break
      markRoomMembership(room, socketState)
      const parsedEnvelope = safeJsonParse(frame.encodedEnvelope)
      if (parsedEnvelope && parsedEnvelope.messageId) {
        emit({
          type: 'room.envelope',
          roomId: room.roomId,
          encodedEnvelope: frame.encodedEnvelope
        })
        sendJson(socketState.socket, {
          type: 'ack',
          roomId: room.roomId,
          messageId: parsedEnvelope.messageId
        })
      }
      break
    }

    case 'ack': {
      const room = rooms.get(frame.roomId)
      if (room && room.pendingAcks.delete(frame.messageId)) {
        log(`delivery ack ${frame.messageId}`, room.roomId)
      }
      break
    }

    case 'delivery_ack': {
      const room = rooms.get(frame.roomId)
      if (!room || typeof frame.encodedAck !== 'string') break
      markRoomMembership(room, socketState)
      emit({
        type: 'room.delivery_ack',
        roomId: room.roomId,
        encodedAck: frame.encodedAck
      })
      break
    }

    default:
      log(`unknown frame type ${frame.type}`)
  }
}

function queueEnvelope(room, encodedEnvelope) {
  const parsedEnvelope = safeJsonParse(encodedEnvelope)
  if (!parsedEnvelope || !parsedEnvelope.messageId) {
    log('invalid encoded envelope', room.roomId)
    return
  }

  const frame = encodeSocketFrame({
    type: 'envelope',
    roomId: room.roomId,
    encodedEnvelope
  })

  room.pendingAcks.set(parsedEnvelope.messageId, {
    messageId: parsedEnvelope.messageId,
    frame,
    attempts: 1,
    nextRetryAt: Date.now() + DELIVERY_RETRY_INTERVAL
  })

  const sockets = roomSockets(room)
  for (const socketState of sockets) {
    sendSocketFrame(socketState.socket, frame)
  }

  if (sockets.length === 0) {
    roomState(
      room,
      'reconnecting',
      'message staged locally, no verified room peers yet'
    )
    log(`message queued without active peers ${parsedEnvelope.messageId}`, room.roomId)
  } else {
    log(`pairwise envelope broadcast ${parsedEnvelope.messageId}`, room.roomId)
  }
}

function broadcastDeliveryAck(room, encodedAck) {
  const parsedAck = safeJsonParse(encodedAck)
  if (!parsedAck || typeof parsedAck.messageId !== 'string') {
    log('invalid delivery ack', room.roomId)
    return
  }

  const frame = encodeSocketFrame({
    type: 'delivery_ack',
    roomId: room.roomId,
    encodedAck
  })

  const sockets = roomSockets(room)
  for (const socketState of sockets) {
    sendSocketFrame(socketState.socket, frame)
  }

  if (sockets.length === 0) {
    log(`delivery ack staged without active peers ${parsedAck.messageId}`, room.roomId)
  } else {
    log(`delivery ack broadcast ${parsedAck.messageId}`, room.roomId)
  }
}

async function destroyRoom(room) {
  room.pendingAcks.clear()
  room.connectedPeerKeys.clear()
  untrackExplicitPeersForRoom(room)

  for (const socketState of transport.sockets.values()) {
    socketState.roomIds.delete(room.roomId)
    socketState.helloSentRoomIds.delete(room.roomId)
  }

  if (room.discovery) {
    await room.discovery.destroy()
  }
}

async function maybeDestroyTransport() {
  if (rooms.size > 0 || !transport.swarm) return

  if (transport.heartbeat) {
    clearInterval(transport.heartbeat)
    transport.heartbeat = null
  }

  for (const socketState of [...transport.sockets.values()]) {
    dropSocket(socketState, 'shutdown')
    socketState.socket.destroy()
  }

  await transport.swarm.destroy()
  if (transport.dht) {
    await transport.dht.destroy()
  }

  transport.swarm = null
  transport.dht = null
  transport.transportSeedHex = null
  transport.bootstrap = undefined
  transport.bootstrapRelayPublicKeys = []
  transport.initPromise = null
  transport.explicitPeerRooms.clear()
  for (const timer of transport.explicitPeerDialTimers.values()) {
    clearTimeout(timer)
  }
  transport.explicitPeerDialTimers.clear()
  transport.sockets.clear()
}

function runAsyncCommand(command, task) {
  Promise.resolve()
    .then(task)
    .catch((err) => {
      const message = err && err.stack ? err.stack : String(err)
      log(`runtime error: ${message}`, command.roomId || null)
    })
}

function handleCommand(command) {
  switch (command.type) {
    case 'drain_events':
      return {
        ok: true,
        events: pendingEvents.splice(0, pendingEvents.length)
      }

    case 'create_room':
    case 'join_room': {
      let room = rooms.get(command.roomId)
      if (!room) {
        room = createRoomState(command)
        rooms.set(command.roomId, room)
      }

      runAsyncCommand(command, async () => {
        await ensureTransport(command)

        if (!room.discovery) {
          // Avoid creator/member duplicate sockets on the first hop. The room
          // creator advertises the topic; joiners dial it and also advertise so
          // later members can still discover the full room mesh.
          room.discovery = transport.swarm.join(Buffer.from(room.topicHex, 'hex'), {
            server: true,
            client: !room.isCreator
          })

          Promise.resolve()
            .then(() => {
              registerKnownPeer(room, room.localPeerCard, localTransportPublicKeyHex())
              return announceRoomTopic(room)
            })
            .then(() => {
              log(`topic announced ${room.topicHex.slice(0, 16)}...`, room.roomId)
              room.transportReady = true
              roomState(room, 'reconnecting', 'topic announced on hyperdht, awaiting room peer')
              announceRoomToKnownSockets(room)
              refreshDiscovery(room, 'post-announce')
            })
            .catch((err) => {
              log(`announce failed: ${err.message}`, room.roomId)
              room.transportReady = false
              roomState(room, 'reconnecting', 'announce failed')
            })
        }

        roomState(room, 'reconnecting', 'hyperswarm discovery started')
      })
      return { ok: true }
    }

    case 'set_known_peer_transport_keys': {
      const room = rooms.get(command.roomId)
      if (!room) {
        log(`ignoring transport key update for unknown room ${command.roomId}`)
        return { ok: true }
      }
      const nextKeys = new Set((command.peerTransportPublicKeys || []).filter(Boolean))
      for (const publicKeyHex of room.explicitPeerTransportKeys) {
        if (!nextKeys.has(publicKeyHex)) {
          untrackExplicitPeer(room.roomId, publicKeyHex)
        }
      }
      room.explicitPeerTransportKeys = nextKeys
      for (const publicKeyHex of nextKeys) {
        trackExplicitPeer(room.roomId, publicKeyHex)
      }
      log(`known peer transport keys updated: ${room.explicitPeerTransportKeys.size}`, room.roomId)
      return { ok: true }
    }

    case 'send_room_message': {
      const room = rooms.get(command.roomId)
      if (!room) {
        log(`dropping outbound envelope for unknown room ${command.roomId}`)
        return { ok: true }
      }
      queueEnvelope(room, command.encodedEnvelope)
      return { ok: true }
    }

    case 'send_delivery_ack': {
      const room = rooms.get(command.roomId)
      if (!room) {
        log(`dropping delivery ack for unknown room ${command.roomId}`)
        return { ok: true }
      }
      broadcastDeliveryAck(room, command.encodedAck)
      return { ok: true }
    }

    case 'leave_room': {
      runAsyncCommand(command, async () => {
        const room = rooms.get(command.roomId)
        if (!room) return
        rooms.delete(command.roomId)
        await destroyRoom(room)
        roomState(room, 'expired', 'runtime detached from room')
        await maybeDestroyTransport()
      })
      return { ok: true }
    }

    case 'suspend_networking': {
      runAsyncCommand(command, async () => {
        if (!transport.swarm) return
        await transport.swarm.suspend({ log })
        for (const room of rooms.values()) {
          roomState(room, 'backgrounded', 'runtime suspended')
        }
      })
      return { ok: true }
    }

    case 'resume_networking': {
      runAsyncCommand(command, async () => {
        if (!transport.swarm) return
        await transport.swarm.resume({ log })
        for (const room of rooms.values()) {
          roomState(
            room,
            room.connectedPeerKeys.size > 0 ? 'connected' : 'reconnecting',
            'runtime resumed'
          )
          for (const socketState of roomSockets(room)) {
            flushPending(room, socketState)
          }
        }
      })
      return { ok: true }
    }

    default:
      return { ok: false, error: `unknown command: ${command.type}` }
  }
}

emit({
  type: 'runtime.ready',
  runtime: 'Bare Worklet + Hyperswarm',
  detail: 'Shared Hyperswarm transport ready with room multiplexing and retry queue',
  realTransportActive: true
})

log('runtime boot complete')

if (!IPC) {
  throw new Error('BareKit IPC is not available in the mobile worklet runtime')
}

IPC.setEncoding('utf8')

let pendingInput = ''
let pendingOutput = []
let flushingOutput = false

function queueReply (payload) {
  pendingOutput.push(JSON.stringify(payload) + '\n')
  flushReplies()
}

function flushReplies () {
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

function handleIncomingCommandLine (line) {
  let command

  try {
    command = JSON.parse(line)
  } catch (err) {
    queueReply({ ok: false, error: `invalid json: ${err.message}` })
    return
  }

  try {
    queueReply(handleCommand(command))
  } catch (err) {
    const message = err && err.stack ? err.stack : String(err)
    log(`runtime error: ${message}`, command.roomId || null)
    queueReply({ ok: false, error: message })
  }
}

IPC.on('data', (chunk) => {
  pendingInput += chunk

  while (true) {
    const newlineIndex = pendingInput.indexOf('\n')
    if (newlineIndex === -1) break

    const line = pendingInput.slice(0, newlineIndex)
    pendingInput = pendingInput.slice(newlineIndex + 1)

    if (line.length === 0) continue
    handleIncomingCommandLine(line)
  }
})

IPC.on('drain', flushReplies)

IPC.on('error', (err) => {
  log(`ipc error: ${err.message || err}`)
})

IPC.on('close', () => {
  log('ipc closed')
})
