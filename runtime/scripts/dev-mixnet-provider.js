#!/usr/bin/env node

const http = require('http')
const { randomUUID } = require('crypto')

const host = process.env.EPHER_MIXNET_PROVIDER_BIND || '127.0.0.1'
const port = Number(process.env.EPHER_MIXNET_PROVIDER_PORT || '9798')
const basePath = process.env.EPHER_MIXNET_PROVIDER_PATH || '/provider'
const mailboxBatchSize = Number(process.env.EPHER_MIXNET_MAILBOX_BATCH_SIZE || '12')
const fixedPacketChars = Number(process.env.EPHER_MIXNET_FIXED_PACKET_CHARS || '6144')

const mailboxes = new Map()

function mailboxKey(providerId, mailboxId) {
  return `${providerId}:${mailboxId}`
}

function mailboxFor(providerId, mailboxId) {
  const key = mailboxKey(providerId, mailboxId)
  let mailbox = mailboxes.get(key)
  if (!mailbox) {
    mailbox = {
      roomId: '',
      roomAccessProof: '',
      frames: []
    }
    mailboxes.set(key, mailbox)
  }
  return mailbox
}

function shuffle(array) {
  for (let index = array.length - 1; index > 0; index -= 1) {
    const swapIndex = Math.floor(Math.random() * (index + 1))
    const current = array[index]
    array[index] = array[swapIndex]
    array[swapIndex] = current
  }
  return array
}

function encodeFixedPacket(payload) {
  const encoded = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url')
  return encoded.padEnd(fixedPacketChars, '~')
}

function coverPacket(roomId) {
  return encodeFixedPacket({
    type: 'cover',
    roomId,
    packetId: randomUUID()
  })
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8')
        resolve(text ? JSON.parse(text) : {})
      } catch (error) {
        reject(error)
      }
    })
    req.on('error', reject)
  })
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body)
  })
  res.end(body)
}

const server = http.createServer(async (req, res) => {
  if (req.method !== 'POST') {
    sendJson(res, 405, { ok: false, error: 'method not allowed' })
    return
  }

  try {
    const body = await readJson(req)
    if (req.url === `${basePath}/store`) {
      const providerId = String(body.providerId || '').trim() || 'provider-alpha'
      const mailboxId = String(body.mailboxId || '').trim()
      const roomId = String(body.roomId || '').trim()
      const roomAccessProof = String(body.roomAccessProof || '').trim()
      const packet = String(body.packet || '').trim()
      if (!mailboxId || !packet || !roomAccessProof) {
        sendJson(res, 400, { ok: false, error: 'mailboxId, packet and roomAccessProof required' })
        return
      }
      const mailbox = mailboxFor(providerId, mailboxId)
      if (mailbox.roomAccessProof && mailbox.roomAccessProof !== roomAccessProof) {
        sendJson(res, 403, { ok: false, error: 'roomAccessProof invalid' })
        return
      }
      if (!mailbox.roomAccessProof) {
        mailbox.roomAccessProof = roomAccessProof
      }
      if (!mailbox.roomId && roomId) {
        mailbox.roomId = roomId
      }
      mailbox.frames.push(packet)
      while (mailbox.frames.length > 256) {
        mailbox.frames.shift()
      }
      console.log(`[dev-mixnet-provider] store provider=${providerId} mailbox=${mailboxId} queued=${mailbox.frames.length}`)
      sendJson(res, 200, { ok: true, queued: mailbox.frames.length })
      return
    }

    if (req.url === `${basePath}/pull`) {
      const roomId = String(body.roomId || '').trim()
      const providerId = String(body.providerId || '').trim() || 'provider-alpha'
      const mailboxId = String(body.mailboxId || '').trim()
      const roomAccessProof = String(body.roomAccessProof || '').trim()
      const batchSize = Math.max(1, Math.min(mailboxBatchSize, Number(body.batchSize || mailboxBatchSize)))
      if (!mailboxId || !roomAccessProof) {
        sendJson(res, 400, { ok: false, error: 'mailboxId and roomAccessProof required' })
        return
      }

      const mailbox = mailboxFor(providerId, mailboxId)
      if (mailbox.roomAccessProof && mailbox.roomAccessProof !== roomAccessProof) {
        sendJson(res, 403, { ok: false, error: 'roomAccessProof invalid' })
        return
      }
      if (!mailbox.roomAccessProof) {
        mailbox.roomAccessProof = roomAccessProof
      }
      if (!mailbox.roomId && roomId) {
        mailbox.roomId = roomId
      }
      const frames = []
      while (mailbox.frames.length > 0 && frames.length < batchSize) {
        frames.push(mailbox.frames.shift())
      }
      const realCount = frames.length
      while (frames.length < batchSize) {
        frames.push(coverPacket(roomId))
      }
      shuffle(frames)
      console.log(`[dev-mixnet-provider] pull provider=${providerId} mailbox=${mailboxId} delivered=${frames.length} real=${realCount}`)
      sendJson(res, 200, {
        ok: true,
        roomId,
        providerId,
        mailboxId,
        frames
      })
      return
    }

    sendJson(res, 404, { ok: false, error: 'unknown endpoint' })
  } catch (error) {
    sendJson(res, 500, { ok: false, error: error.message || 'provider error' })
  }
})

server.listen(port, host, () => {
  console.log(`[dev-mixnet-provider] listening on http://${host}:${port}${basePath}`)
})

process.on('SIGINT', () => server.close(() => process.exit(0)))
process.on('SIGTERM', () => server.close(() => process.exit(0)))
