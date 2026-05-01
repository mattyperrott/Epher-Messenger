#!/usr/bin/env node

const { createHash, randomUUID } = require('crypto')
const { WebSocket, WebSocketServer } = require('ws')
const http = require('http')
const https = require('https')

const host = process.env.EPHER_MIXNET_RELAY_BIND || '127.0.0.1'
const port = Number(process.env.EPHER_MIXNET_RELAY_PORT || '9797')
const path = process.env.EPHER_MIXNET_RELAY_PATH || '/mix'
const providerBaseUrl = process.env.EPHER_MIXNET_PROVIDER_URL || 'http://127.0.0.1:9798/provider'

const rooms = new Map()
const connections = new Map()
const connectionsByClientId = new Map()

const mixNodeUrls = {
  'mix-a': process.env.EPHER_MIXNET_NODE_A_URL || 'http://127.0.0.1:9801/forward',
  'mix-b': process.env.EPHER_MIXNET_NODE_B_URL || 'http://127.0.0.1:9802/forward',
  'mix-c': process.env.EPHER_MIXNET_NODE_C_URL || 'http://127.0.0.1:9803/forward',
  'mix-d': process.env.EPHER_MIXNET_NODE_D_URL || 'http://127.0.0.1:9801/forward',
  'mix-e': process.env.EPHER_MIXNET_NODE_E_URL || 'http://127.0.0.1:9802/forward',
  'mix-f': process.env.EPHER_MIXNET_NODE_F_URL || 'http://127.0.0.1:9803/forward',
  'mix-g': process.env.EPHER_MIXNET_NODE_G_URL || 'http://127.0.0.1:9801/forward',
  'mix-h': process.env.EPHER_MIXNET_NODE_H_URL || 'http://127.0.0.1:9802/forward',
  'mix-i': process.env.EPHER_MIXNET_NODE_I_URL || 'http://127.0.0.1:9803/forward'
}

function providerStoreUrl() {
  return `${providerBaseUrl.replace(/\/+$/, '')}/store`
}

function encodeFixedPacket(payload) {
  return Buffer.from(JSON.stringify(payload), 'utf8')
    .toString('base64url')
    .padEnd(6144, '~')
}

function createRoom(roomId) {
  let room = rooms.get(roomId)
  if (!room) {
    room = {
      roomId,
      roomAccessProof: '',
      participants: new Map()
    }
    rooms.set(roomId, room)
  }
  return room
}

function sendJson(ws, payload) {
  if (ws.readyState !== WebSocket.OPEN) return
  ws.send(JSON.stringify(payload))
}

function sendLog(connection, roomId, line) {
  sendJson(connection.ws, {
    type: 'log',
    roomId,
    line
  })
}

function postJson(urlString, payload) {
  return new Promise((resolve, reject) => {
    const target = new URL(urlString)
    const client = target.protocol === 'https:' ? https : http
    const body = Buffer.from(JSON.stringify(payload), 'utf8')
    const request = client.request(
      target,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': String(body.length)
        }
      },
      (response) => {
        const chunks = []
        response.on('data', (chunk) => chunks.push(chunk))
        response.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(new Error(`HTTP ${response.statusCode}: ${text || 'request failed'}`))
            return
          }
          resolve(text)
        })
      }
    )

    request.on('error', reject)
    request.write(body)
    request.end()
  })
}

function broadcastRoomState(room) {
  const detail = `Mix route ready with ${room.participants.size} participant(s)`
  for (const participant of room.participants.values()) {
    const connection = connections.get(participant.connectionId)
    if (!connection) continue
    sendJson(connection.ws, {
      type: 'room_state',
      roomId: room.roomId,
      state: 'connected',
      participantCount: room.participants.size,
      detail
    })
  }
  if (room.participants.size === 0) {
    rooms.delete(room.roomId)
  }
}

async function dispatchToMailbox(sourceRoute, participant, packet) {
  const routeUrls = (sourceRoute.mixHopIds || [])
    .map((hopId) => mixNodeUrls[hopId])
    .filter(Boolean)
  console.log(
    `[dev-mixnet-gateway] dispatch packet type=${packet.type} room=${sourceRoute.roomId || sourceRoute.roomAlias} to provider=${participant.providerId} mailbox=${participant.mailboxId} via=${routeUrls.length > 0 ? routeUrls.join(' -> ') : 'provider-direct'}`
  )
  const payload = {
    trace: [
      sourceRoute.ingressGatewayId,
      ...(sourceRoute.mixHopIds || []),
      participant.providerId
    ],
    delivery: {
      roomId: sourceRoute.roomId || sourceRoute.roomAlias,
      roomAccessProof: sourceRoute.roomAccessProof,
      providerId: participant.providerId,
      mailboxId: participant.mailboxId,
      packet: encodeFixedPacket(packet)
    }
  }

  if (routeUrls.length === 0) {
    await postJson(providerStoreUrl(), payload.delivery)
    return
  }

  await postJson(routeUrls[0], {
    ...payload,
    remainingUrls: [...routeUrls.slice(1), providerStoreUrl()]
  })
}

async function broadcastPeerPresence(room, sourceParticipant, presence) {
  for (const participant of room.participants.values()) {
    if (participant.participantKey === sourceParticipant.participantKey) continue
    await dispatchToMailbox(
      sourceParticipant,
      participant,
      {
        type: 'peer_presence',
        roomId: room.roomId,
        transportPublicKeyHex: sourceParticipant.transportPublicKeyHex,
        presence,
        packetId: randomUUID()
      }
    )
  }
}

async function removeConnectionFromRooms(connection) {
  for (const [roomId, participantKey] of connection.rooms.entries()) {
    const room = rooms.get(roomId)
    if (!room) continue
    const participant = room.participants.get(participantKey)
    if (participant && participant.connectionId === connection.id) {
      room.participants.delete(participantKey)
      await broadcastPeerPresence(room, participant, 'left')
      broadcastRoomState(room)
    }
  }
  connection.rooms.clear()
  if (connectionsByClientId.get(connection.clientId) === connection.id) {
    connectionsByClientId.delete(connection.clientId)
  }
}

async function handleJoinRoom(connection, message) {
  const roomId = String(message.roomId || '').trim()
  const roomAccessProof = String(message.roomAccessProof || '').trim()
  if (!roomId) {
    sendJson(connection.ws, { type: 'error', detail: 'roomId missing' })
    return
  }
  if (!roomAccessProof) {
    sendJson(connection.ws, { type: 'error', detail: 'roomAccessProof missing' })
    return
  }

  const transportPublicKeyHex = String(message.transportPublicKeyHex || '').trim()
  const providerId = String(message.providerId || '').trim() || 'provider-alpha'
  const ingressGatewayId = String(message.ingressGatewayId || '').trim() || 'entry-gateway-a'
  const routeId = String(message.routeId || '').trim() || createHash('sha256').update(`${roomId}:${transportPublicKeyHex || connection.id}`).digest('hex').slice(0, 20)
  const mixHopIds = Array.isArray(message.mixHopIds) ? message.mixHopIds.map(String).filter(Boolean) : []
  const mailboxId = String(message.mailboxId || '').trim() || createHash('sha256')
    .update(`${roomId}:${providerId}:${transportPublicKeyHex || connection.id}`)
    .digest('hex')
  const participantKey = transportPublicKeyHex || `${connection.id}:${roomId}`
  const room = createRoom(roomId)
  if (room.roomAccessProof && room.roomAccessProof !== roomAccessProof) {
    sendJson(connection.ws, { type: 'error', roomId, detail: 'roomAccessProof invalid' })
    return
  }
  room.roomAccessProof = roomAccessProof

  const previousParticipant = room.participants.get(participantKey)
  if (previousParticipant && previousParticipant.connectionId !== connection.id) {
    console.log(
      `[dev-mixnet-gateway] replacing prior participant room=${roomId} participantKey=${participantKey.slice(0, 16)} oldConnection=${previousParticipant.connectionId} newConnection=${connection.id}`
    )
    const previousConnection = connections.get(previousParticipant.connectionId)
    previousConnection?.rooms.delete(roomId)
  }

  let fingerprint = ''
  try {
    fingerprint = JSON.parse(String(message.encodedPeerCard || '{}')).fingerprint || ''
  } catch (error) {
    fingerprint = ''
  }

  const participant = {
    participantKey,
    connectionId: connection.id,
    clientId: connection.clientId,
    mailboxId,
    providerId,
    ingressGatewayId,
    routeId,
    mixHopIds,
    fingerprint,
    transportPublicKeyHex,
    roomAccessProof,
    encodedPeerCard: String(message.encodedPeerCard || ''),
    role: String(message.role || 'member'),
    joinedAt: Date.now()
  }

  room.participants.set(participantKey, participant)
  connection.rooms.set(roomId, participantKey)
  console.log(
    `[dev-mixnet-gateway] join room=${roomId} client=${connection.clientId} participantKey=${participantKey.slice(0, 16)} provider=${providerId} mailbox=${mailboxId} route=${ingressGatewayId} -> ${mixHopIds.join(' -> ')} role=${participant.role} participants=${room.participants.size}`
  )

  for (const existing of room.participants.values()) {
    if (existing.participantKey === participantKey) continue
    await dispatchToMailbox(existing, participant, {
      type: 'peer_card',
      roomId,
      encodedPeerCard: existing.encodedPeerCard,
      transportPublicKeyHex: existing.transportPublicKeyHex,
      packetId: randomUUID()
    })
  }

  for (const existing of room.participants.values()) {
    if (existing.participantKey === participantKey) continue
    await dispatchToMailbox(participant, existing, {
      type: 'peer_card',
      roomId,
      encodedPeerCard: participant.encodedPeerCard,
      transportPublicKeyHex: participant.transportPublicKeyHex,
      packetId: randomUUID()
    })
  }

  sendLog(connection, roomId, `joined mix gateway route ${ingressGatewayId} -> ${(mixHopIds || []).join(' -> ')} -> ${providerId}`)
  await broadcastPeerPresence(room, participant, 'joined')
  broadcastRoomState(room)
}

async function handleLeaveRoom(connection, message) {
  const roomId = String(message.roomId || '').trim()
  const roomAccessProof = String(message.roomAccessProof || '').trim()
  const participantKey = connection.rooms.get(roomId)
  const room = rooms.get(roomId)
  if (!participantKey || !room) return
  if (room.roomAccessProof && roomAccessProof != room.roomAccessProof) return
  const participant = room.participants.get(participantKey)
  if (participant && participant.connectionId === connection.id) {
    room.participants.delete(participantKey)
    console.log(`[dev-mixnet-gateway] leave room=${roomId} client=${connection.clientId} mailbox=${participant.mailboxId}`)
    await broadcastPeerPresence(room, participant, 'left')
  }
  connection.rooms.delete(roomId)
  broadcastRoomState(room)
}

async function handlePushPacket(connection, message) {
  const roomId = String(message.roomId || '').trim()
  const roomAccessProof = String(message.roomAccessProof || '').trim()
  const encodedPacket = String(message.packet || '')
  const participantKey = connection.rooms.get(roomId)
  const room = rooms.get(roomId)
  if (!room || !participantKey) {
    sendJson(connection.ws, {
      type: 'error',
      roomId,
      detail: 'room membership missing for mix packet'
    })
    return
  }
  if (!roomAccessProof || room.roomAccessProof !== roomAccessProof) {
    sendJson(connection.ws, {
      type: 'error',
      roomId,
      detail: 'roomAccessProof invalid for mix packet'
    })
    return
  }

  let packet
  try {
    packet = JSON.parse(Buffer.from(String(encodedPacket).replace(/~+$/g, ''), 'base64url').toString('utf8'))
  } catch (error) {
    sendJson(connection.ws, {
      type: 'error',
      roomId,
      detail: 'mix packet decode failed'
    })
    return
  }

  if (!packet || packet.type === 'cover') return
  if (packet.type !== 'envelope' && packet.type !== 'delivery_ack') {
    sendJson(connection.ws, {
      type: 'error',
      roomId,
      detail: `unsupported mix packet type ${packet.type || 'missing'}`
    })
    return
  }

  let recipientFingerprint = ''
  try {
    if (packet.type === 'envelope') {
      recipientFingerprint = JSON.parse(String(packet.encodedEnvelope || '{}')).recipientFingerprint || ''
    } else if (packet.type === 'delivery_ack') {
      recipientFingerprint = JSON.parse(String(packet.encodedAck || '{}')).recipientFingerprint || ''
    }
  } catch (error) {
    recipientFingerprint = ''
  }

  const sourceParticipant = room.participants.get(participantKey)
  if (!sourceParticipant) return
  console.log(
    `[dev-mixnet-gateway] push packet room=${roomId} type=${packet.type} from=${sourceParticipant.clientId} recipients=${Math.max(0, room.participants.size - 1)}`
  )

  for (const participant of room.participants.values()) {
    if (participant.participantKey === participantKey) continue
    if (recipientFingerprint && participant.fingerprint && participant.fingerprint !== recipientFingerprint) continue
    await dispatchToMailbox(sourceParticipant, participant, {
      type: packet.type,
      roomId,
      ...(packet.type === 'envelope'
        ? { encodedEnvelope: packet.encodedEnvelope }
        : { encodedAck: packet.encodedAck }),
      packetId: randomUUID()
    })
  }
}

const server = new WebSocketServer({ host, port, path })

server.on('listening', () => {
  console.log(`[dev-mixnet-gateway] listening on ws://${host}:${port}${path}`)
  console.log(`[dev-mixnet-gateway] provider API ${providerBaseUrl}`)
})

server.on('connection', (ws, request) => {
  const connection = {
    id: randomUUID(),
    ws,
    clientId: `anon-${randomUUID().slice(0, 8)}`,
    rooms: new Map()
  }
  connections.set(connection.id, connection)
  console.log(`[dev-mixnet-gateway] connection ${connection.id} from ${request.socket.remoteAddress || 'unknown'}`)

  sendJson(ws, {
    type: 'welcome',
    detail: `Mix gateway connected (${request.socket.remoteAddress || 'unknown remote'})`
  })

  ws.on('message', async (raw) => {
    let message
    try {
      message = JSON.parse(raw.toString())
    } catch (error) {
      sendJson(ws, { type: 'error', detail: 'invalid json' })
      return
    }

    try {
      switch (message.type) {
        case 'hello': {
          const nextClientId = String(message.clientId || connection.clientId)
          const existingConnectionId = connectionsByClientId.get(nextClientId)
          if (existingConnectionId && existingConnectionId !== connection.id) {
            const existingConnection = connections.get(existingConnectionId)
            if (existingConnection) {
              await removeConnectionFromRooms(existingConnection)
              existingConnection.ws.close(4001, 'superseded by reconnect')
              connections.delete(existingConnectionId)
            }
          }
          connection.clientId = nextClientId
          connectionsByClientId.set(connection.clientId, connection.id)
          sendLog(connection, null, `mix gateway session ready for ${connection.clientId}`)
          break
        }
        case 'join_room':
          await handleJoinRoom(connection, message)
          break
        case 'leave_room':
          await handleLeaveRoom(connection, message)
          break
        case 'push_packet':
          await handlePushPacket(connection, message)
          break
        default:
          sendJson(ws, { type: 'error', detail: `unknown message type: ${message.type || 'missing'}` })
          break
      }
    } catch (error) {
      sendJson(ws, { type: 'error', detail: error.message || 'gateway failure' })
    }
  })

  ws.on('close', async () => {
    await removeConnectionFromRooms(connection)
    connections.delete(connection.id)
  })

  ws.on('error', async () => {
    await removeConnectionFromRooms(connection)
    connections.delete(connection.id)
  })
})

process.on('SIGINT', () => {
  server.close(() => process.exit(0))
})

process.on('SIGTERM', () => {
  server.close(() => process.exit(0))
})
