#!/usr/bin/env node

const http = require('http')
const https = require('https')

const host = process.env.EPHER_MIXNET_NODE_BIND || '127.0.0.1'
const port = Number(process.env.EPHER_MIXNET_NODE_PORT || '9801')
const path = process.env.EPHER_MIXNET_NODE_PATH || '/forward'
const nodeId = process.env.EPHER_MIXNET_NODE_ID || `mix-node-${port}`
const minDelayMs = Number(process.env.EPHER_MIXNET_MIN_DELAY_MS || '350')
const maxDelayMs = Number(process.env.EPHER_MIXNET_MAX_DELAY_MS || '1400')
const maxRequestBytes = Number(process.env.EPHER_MIXNET_MAX_REQUEST_BYTES || '65536')
const allowedForwardUrls = new Set(
  [
    process.env.EPHER_MIXNET_NODE_A_URL,
    process.env.EPHER_MIXNET_NODE_B_URL,
    process.env.EPHER_MIXNET_NODE_C_URL,
    process.env.EPHER_MIXNET_NODE_D_URL,
    process.env.EPHER_MIXNET_NODE_E_URL,
    process.env.EPHER_MIXNET_NODE_F_URL,
    process.env.EPHER_MIXNET_NODE_G_URL,
    process.env.EPHER_MIXNET_NODE_H_URL,
    process.env.EPHER_MIXNET_NODE_I_URL,
    providerStoreUrl()
  ]
    .map((value) => normalizeUrl(value || ''))
    .filter(Boolean)
)

function providerStoreUrl() {
  const baseUrl = process.env.EPHER_MIXNET_PROVIDER_URL || ''
  if (!baseUrl) return ''
  try {
    const normalizedBase = new URL(baseUrl)
    normalizedBase.hash = ''
    normalizedBase.username = ''
    normalizedBase.password = ''
    normalizedBase.pathname = `${normalizedBase.pathname.replace(/\/+$/, '')}/store`
    return normalizedBase.toString()
  } catch {
    return ''
  }
}

function normalizeUrl(urlString) {
  if (!urlString) return ''
  try {
    const url = new URL(urlString)
    url.hash = ''
    url.username = ''
    url.password = ''
    return url.toString()
  } catch {
    return ''
  }
}

function isAllowedForwardUrl(urlString) {
  const normalized = normalizeUrl(urlString)
  if (!normalized) return false
  if (allowedForwardUrls.size > 0) {
    return allowedForwardUrls.has(normalized)
  }
  try {
    const target = new URL(normalized)
    return target.hostname === '127.0.0.1' || target.hostname === 'localhost'
  } catch {
    return false
  }
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let totalBytes = 0
    let aborted = false
    req.on('data', (chunk) => {
      if (aborted) return
      totalBytes += chunk.length
      if (totalBytes > maxRequestBytes) {
        aborted = true
        reject(new Error('request too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (aborted) return
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
            reject(new Error(`HTTP ${response.statusCode}: ${text || 'forward failed'}`))
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

function randomDelayMs() {
  if (maxDelayMs <= minDelayMs) return minDelayMs
  return minDelayMs + Math.floor(Math.random() * (maxDelayMs - minDelayMs + 1))
}

const server = http.createServer(async (req, res) => {
  if (req.method !== 'POST' || req.url !== path) {
    sendJson(res, 404, { ok: false, error: 'unknown endpoint' })
    return
  }

  try {
    const body = await readJson(req)
    const remainingUrls = Array.isArray(body.remainingUrls) ? body.remainingUrls.map(String).filter(Boolean) : []
    const nextUrl = remainingUrls.shift()
    if (!nextUrl) {
      sendJson(res, 400, { ok: false, error: 'next hop missing' })
      return
    }
    if (!isAllowedForwardUrl(nextUrl)) {
      sendJson(res, 400, { ok: false, error: 'next hop not allowed' })
      return
    }

    setTimeout(async () => {
      try {
        console.log(`[${nodeId}] forwarding to ${nextUrl}`)
        const payload =
          body.delivery && nextUrl.includes('/provider/store')
            ? body.delivery
            : {
                ...body,
                remainingUrls
              }
        await postJson(nextUrl, {
          ...payload
        })
      } catch (error) {
        console.error(`[${nodeId}] forward failure:`, error.message || error)
      }
    }, randomDelayMs())

    sendJson(res, 200, { ok: true, nodeId, forwardedTo: nextUrl })
  } catch (error) {
    sendJson(res, 500, { ok: false, error: error.message || 'mix node failure' })
  }
})

server.listen(port, host, () => {
  console.log(`[${nodeId}] listening on http://${host}:${port}${path}`)
})

process.on('SIGINT', () => server.close(() => process.exit(0)))
process.on('SIGTERM', () => server.close(() => process.exit(0)))
