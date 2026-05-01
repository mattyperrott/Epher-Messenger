#!/usr/bin/env node

const { randomUUID } = require('crypto')
const { WebSocket, WebSocketServer } = require('ws')

const host = process.env.EPHER_ROOM_RELAY_BIND || '127.0.0.1'
const port = Number(process.env.EPHER_ROOM_RELAY_PORT || '8787')
const path = process.env.EPHER_ROOM_RELAY_PATH || '/ws'

const rooms = new Map()
const connections = new Map()
const connectionsByClientId = new Map()

function createRoom(roomId, label) {
  let room = rooms.get(roomId)
  if (!room) {
    room = {
      roomId,
      label: label || 'Private Room',
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

function broadcastRoomState(room) {
  const detail = `Debug relay active with ${room.participants.size} participant(s)`
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

function removeConnectionFromRooms(connection) {
  for (const [roomId, participantKey] of connection.rooms.entries()) {
    const room = rooms.get(roomId)
    if (!room) continue
    const participant = room.participants.get(participantKey)
    if (participant && participant.connectionId === connection.id) {
      room.participants.delete(participantKey)
      broadcastRoomState(room)
    }
  }
  connection.rooms.clear()
  if (connectionsByClientId.get(connection.clientId) === connection.id) {
    connectionsByClientId.delete(connection.clientId)
  }
}

function handleJoinRoom(connection, message) {
  const roomId = String(message.roomId || '').trim()
  if (!roomId) {
    sendJson(connection.ws, { type: 'error', detail: 'roomId missing' })
    return
  }

  const transportPublicKeyHex = String(message.transportPublicKeyHex || '').trim()
  const participantKey = transportPublicKeyHex || `${connection.id}:${roomId}`
  const room = createRoom(roomId, message.label)

  const previousParticipant = room.participants.get(participantKey)
  if (previousParticipant && previousParticipant.connectionId !== connection.id) {
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
    fingerprint,
    transportPublicKeyHex,
    encodedPeerCard: String(message.encodedPeerCard || ''),
    role: String(message.role || 'member'),
    joinedAt: Date.now()
  }

  room.participants.set(participantKey, participant)
  connection.rooms.set(roomId, participantKey)

  for (const existing of room.participants.values()) {
    if (existing.participantKey === participantKey) continue
    sendJson(connection.ws, {
      type: 'peer_card',
      roomId,
      encodedPeerCard: existing.encodedPeerCard,
      transportPublicKeyHex: existing.transportPublicKeyHex
    })
  }

  for (const existing of room.participants.values()) {
    if (existing.participantKey === participantKey) continue
    const existingConnection = connections.get(existing.connectionId)
    if (!existingConnection) continue
    sendJson(existingConnection.ws, {
      type: 'peer_card',
      roomId,
      encodedPeerCard: participant.encodedPeerCard,
      transportPublicKeyHex: participant.transportPublicKeyHex
    })
  }

  sendLog(connection, roomId, `joined debug relay room as ${participant.role}`)
  broadcastRoomState(room)
}

function handleLeaveRoom(connection, message) {
  const roomId = String(message.roomId || '').trim()
  const participantKey = connection.rooms.get(roomId)
  const room = rooms.get(roomId)
  if (!participantKey || !room) return
  const participant = room.participants.get(participantKey)
  if (participant && participant.connectionId === connection.id) {
    room.participants.delete(participantKey)
  }
  connection.rooms.delete(roomId)
  broadcastRoomState(room)
}

function handleEnvelope(connection, message) {
  const roomId = String(message.roomId || '').trim()
  const encodedEnvelope = String(message.encodedEnvelope || '')
  const participantKey = connection.rooms.get(roomId)
  const room = rooms.get(roomId)
  if (!room || !participantKey) {
    sendJson(connection.ws, {
      type: 'error',
      roomId,
      detail: 'room membership missing for envelope'
    })
    return
  }

  let recipientFingerprint = ''
  try {
    recipientFingerprint = JSON.parse(encodedEnvelope).recipientFingerprint || ''
  } catch (error) {
    recipientFingerprint = ''
  }

  for (const participant of room.participants.values()) {
    if (participant.participantKey === participantKey) continue
    if (recipientFingerprint && participant.fingerprint && participant.fingerprint !== recipientFingerprint) continue
    const peerConnection = connections.get(participant.connectionId)
    if (!peerConnection) continue
    sendJson(peerConnection.ws, {
      type: 'envelope',
      roomId,
      encodedEnvelope
    })
  }
}

const server = new WebSocketServer({ host, port, path })

server.on('listening', () => {
  console.log(`[dev-room-relay] listening on ws://${host}:${port}${path}`)
})

server.on('connection', (ws, request) => {
  const connection = {
    id: randomUUID(),
    ws,
    clientId: `anon-${randomUUID().slice(0, 8)}`,
    rooms: new Map()
  }
  connections.set(connection.id, connection)
  console.log(`[dev-room-relay] connection ${connection.id} from ${request.socket.remoteAddress || 'unknown'}`)

  sendJson(ws, {
    type: 'welcome',
    detail: `Debug room relay connected (${request.socket.remoteAddress || 'unknown remote'})`
  })

  ws.on('message', (raw) => {
    let message
    try {
      message = JSON.parse(raw.toString())
    } catch (error) {
      sendJson(ws, { type: 'error', detail: 'invalid json' })
      return
    }

    switch (message.type) {
      case 'hello':
        {
          const nextClientId = String(message.clientId || connection.clientId)
          const existingConnectionId = connectionsByClientId.get(nextClientId)
          if (existingConnectionId && existingConnectionId !== connection.id) {
            const existingConnection = connections.get(existingConnectionId)
            if (existingConnection) {
              console.log(`[dev-room-relay] replacing stale connection for ${nextClientId}`)
              removeConnectionFromRooms(existingConnection)
              existingConnection.ws.close(4001, 'superseded by reconnect')
              connections.delete(existingConnectionId)
            }
          }
          connection.clientId = nextClientId
          connectionsByClientId.set(connection.clientId, connection.id)
        }
        console.log(`[dev-room-relay] hello ${connection.clientId}`)
        sendLog(connection, null, `relay session ready for ${connection.clientId}`)
        break
      case 'join_room':
        console.log(`[dev-room-relay] join ${message.roomId} by ${connection.clientId}`)
        handleJoinRoom(connection, message)
        break
      case 'leave_room':
        console.log(`[dev-room-relay] leave ${message.roomId} by ${connection.clientId}`)
        handleLeaveRoom(connection, message)
        break
      case 'envelope':
        console.log(`[dev-room-relay] envelope ${message.roomId} from ${connection.clientId}`)
        handleEnvelope(connection, message)
        break
      default:
        sendJson(ws, { type: 'error', detail: `unknown message type: ${message.type || 'missing'}` })
        break
    }
  })

  ws.on('close', () => {
    console.log(`[dev-room-relay] close ${connection.clientId}`)
    removeConnectionFromRooms(connection)
    connections.delete(connection.id)
  })

  ws.on('error', () => {
    console.log(`[dev-room-relay] error ${connection.clientId}`)
    removeConnectionFromRooms(connection)
    connections.delete(connection.id)
  })
})

process.on('SIGINT', () => {
  server.close(() => process.exit(0))
})

process.on('SIGTERM', () => {
  server.close(() => process.exit(0))
})
