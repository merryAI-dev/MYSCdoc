import { Hocuspocus } from '@hocuspocus/server'
import { Database } from '@hocuspocus/extension-database'
import { Redis } from '@hocuspocus/extension-redis'
import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { prosemirrorJSONToYDoc, yDocToProsemirrorJSON } from '@tiptap/y-tiptap'
import jwt from 'jsonwebtoken'
import pg from 'pg'
import * as Y from 'yjs'
import { startCompaction } from './compaction.js'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const PORT = Number(process.env.PORT || 8081)
const DATABASE_URL = process.env.DATABASE_URL || 'postgres://mydoc:changeme@localhost:5432/mydoc'
const REDIS_URL = process.env.REDIS_URL || 'redis://localhost:6379'
const COLLAB_JWT_SECRET = process.env.COLLAB_JWT_SECRET || ''
const INTERNAL_SERVICE_TOKEN = process.env.INTERNAL_SERVICE_TOKEN || ''
const JAVA_BASE_URL = process.env.JAVA_BASE_URL || 'http://localhost:8080'
const DEBOUNCE_MS = Number(process.env.DEBOUNCE_MS || 5000)
const DEBOUNCE_MAX_MS = Number(process.env.DEBOUNCE_MAX_MS || 30000)

const { Pool } = pg
const pool = new Pool({ connectionString: DATABASE_URL })
const schema = getSchema([StarterKit])
const redisUrl = new URL(REDIS_URL)
const lastEditors = new Map()
const snapshotTimers = new Map()
const connections = new Map()
const REDIS_HOOKS = [
  'afterLoadDocument',
  'onStoreDocument',
  'afterStoreDocument',
  'onAwarenessUpdate',
  'onChange',
  'beforeBroadcastStateless',
]

async function fetchDocument({ documentName }) {
  assertDocumentName(documentName)
  const loaded = new Y.Doc()
  const snapshot = await pool.query('SELECT state_data, last_update_id FROM yjs_snapshot WHERE doc_id = $1', [documentName])
  let lastUpdateId = 0
  let hadStoredState = false

  if (snapshot.rowCount > 0) {
    Y.applyUpdate(loaded, snapshot.rows[0].state_data)
    lastUpdateId = Number(snapshot.rows[0].last_update_id)
    hadStoredState = true
  }

  const updates = await pool.query(
    'SELECT id, update_data FROM yjs_update WHERE doc_id = $1 AND id > $2 ORDER BY id',
    [documentName, lastUpdateId],
  )
  for (const row of updates.rows) {
    Y.applyUpdate(loaded, row.update_data)
    hadStoredState = true
  }

  if (!hadStoredState) {
    const blocks = await fetchJavaBlocks(documentName)
    if (blocks.length > 0) {
      return Y.encodeStateAsUpdate(prosemirrorJSONToYDoc(schema, blocksToTiptapDoc(blocks), 'prosemirror'))
    }
  }
  return Y.encodeStateAsUpdate(loaded)
}

async function storeUpdate({ documentName, update, context, document }) {
  assertDocumentName(documentName)
  await pool.query(
    'INSERT INTO yjs_update (doc_id, update_data) VALUES ($1, $2)',
    [documentName, Buffer.from(update)],
  )
  if (context?.memberId) {
    lastEditors.set(documentName, context.memberId)
  }
  scheduleSnapshot(documentName, document)
}

function scheduleSnapshot(documentName, document) {
  let timer = snapshotTimers.get(documentName)
  if (timer?.debounce) {
    clearTimeout(timer.debounce)
  }
  if (!timer) {
    timer = {}
    snapshotTimers.set(documentName, timer)
  }
  timer.debounce = setTimeout(() => flushSnapshot(documentName, document), DEBOUNCE_MS)
  if (!timer.max) {
    timer.max = setTimeout(() => flushSnapshot(documentName, document), DEBOUNCE_MAX_MS)
  }
}

async function flushSnapshot(documentName, document, retry = true) {
  const timer = snapshotTimers.get(documentName)
  if (timer?.debounce) {
    clearTimeout(timer.debounce)
  }
  if (timer?.max) {
    clearTimeout(timer.max)
  }
  snapshotTimers.delete(documentName)

  const editorId = lastEditors.get(documentName)
  if (!editorId) {
    return
  }

  try {
    const json = yDocToProsemirrorJSON(document, 'prosemirror')
    const response = await fetch(`${JAVA_BASE_URL}/api/internal/snapshots`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${INTERNAL_SERVICE_TOKEN}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        documentId: documentName,
        editorId,
        blocks: tiptapDocToBlocks(json),
      }),
    })
    if (response.status >= 500 && retry) {
      setTimeout(() => flushSnapshot(documentName, document, false), 30000)
      return
    }
    if (!response.ok) {
      console.error(`snapshot commit failed: ${response.status}`)
    }
  } catch (error) {
    if (retry) {
      setTimeout(() => flushSnapshot(documentName, document, false), 30000)
    } else {
      console.error('snapshot commit failed', error)
    }
  }
}

async function fetchJavaBlocks(documentId) {
  const response = await fetch(`${JAVA_BASE_URL}/api/internal/documents/${documentId}/blocks`, {
    headers: { Authorization: `Bearer ${INTERNAL_SERVICE_TOKEN}` },
  })
  if (!response.ok) {
    throw new Error(`failed to fetch initial blocks: ${response.status}`)
  }
  return response.json()
}

function blocksToTiptapDoc(blocks) {
  return { type: 'doc', content: blocks.map(block => block.content) }
}

function tiptapDocToBlocks(doc) {
  return (doc.content || []).map(node => ({
    type: blockType(node),
    content: node,
    sourceType: 'MANUAL',
    sourceUrl: null,
    sourceRef: null,
  }))
}

function blockType(node) {
  if (node.type === 'heading') {
    const level = Number(node.attrs?.level || 1)
    if (level === 2) {
      return 'HEADING2'
    }
    if (level === 3) {
      return 'HEADING3'
    }
    return 'HEADING1'
  }
  if (node.type === 'paragraph') {
    return 'PARAGRAPH'
  }
  if (node.type === 'bulletList') {
    return 'BULLET_LIST'
  }
  if (node.type === 'orderedList') {
    return 'ORDERED_LIST'
  }
  if (node.type === 'codeBlock') {
    return 'CODE'
  }
  if (node.type === 'table') {
    return 'TABLE'
  }
  if (node.type === 'blockquote') {
    return 'QUOTE'
  }
  if (node.type === 'image') {
    return 'IMAGE'
  }
  return 'PARAGRAPH'
}

function authenticate({ token, documentName, connection }) {
  assertDocumentName(documentName)
  const payload = jwt.verify(token, COLLAB_JWT_SECRET, { algorithms: ['HS256'] })
  if (payload.doc !== documentName) {
    throw new Error('token document mismatch')
  }
  if (payload.perm === 'read') {
    connection.readOnly = true
  }
  return { memberId: payload.sub }
}

function trackConnection({ documentName, context, connectionInstance, socketId }) {
  if (!context?.memberId) {
    return
  }
  let byMember = connections.get(documentName)
  if (!byMember) {
    byMember = new Map()
    connections.set(documentName, byMember)
  }
  let memberConnections = byMember.get(context.memberId)
  if (!memberConnections) {
    memberConnections = new Map()
    byMember.set(context.memberId, memberConnections)
  }
  memberConnections.set(socketId, connectionInstance)
}

function untrackConnection({ documentName, context, socketId }) {
  const memberConnections = connections.get(documentName)?.get(context?.memberId)
  if (!memberConnections) {
    return
  }
  memberConnections.delete(socketId)
}

function kick(documentId, memberId) {
  const memberConnections = connections.get(documentId)?.get(memberId)
  if (!memberConnections) {
    return
  }
  for (const connection of memberConnections.values()) {
    connection.close()
  }
  memberConnections.clear()
}

async function handleRequest({ request, response }) {
  const url = new URL(request.url, `http://${request.headers.host}`)
  if (url.pathname !== '/internal/kick') {
    return
  }
  if (request.method !== 'POST') {
    response.writeHead(405)
    response.end()
    throw null
  }
  if (request.headers.authorization !== `Bearer ${INTERNAL_SERVICE_TOKEN}`) {
    response.writeHead(401)
    response.end()
    throw null
  }
  let body
  try {
    body = JSON.parse(await readBody(request))
    assertUuid(body.documentId, 'documentId')
    assertUuid(body.memberId, 'memberId')
  } catch {
    response.writeHead(400)
    response.end()
    throw null
  }
  kick(body.documentId, body.memberId)
  response.writeHead(204)
  response.end()
  throw null
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', chunk => chunks.push(chunk))
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    request.on('error', reject)
  })
}

function assertDocumentName(value) {
  assertUuid(value, 'document name')
}

function assertUuid(value, name) {
  if (!UUID_PATTERN.test(value || '')) {
    throw new Error(`invalid ${name}`)
  }
}

function redisExtension() {
  let available = false
  const extension = new Redis({
    host: redisUrl.hostname,
    port: Number(redisUrl.port || 6379),
    options: {
      connectTimeout: 500,
      enableOfflineQueue: false,
      maxRetriesPerRequest: 1,
      retryStrategy: attempt => Math.min(attempt * 50, 500),
    },
  })
  for (const client of [extension.pub, extension.sub]) {
    client.on('ready', () => {
      available = true
    })
    client.on('error', error => {
      available = false
      console.error('Redis unavailable', error?.message || error)
    })
  }
  return bestEffort(extension, REDIS_HOOKS, () => available)
}

function bestEffort(extension, hooks, isAvailable) {
  for (const hook of hooks) {
    const original = extension[hook]?.bind(extension)
    if (!original) {
      continue
    }
    extension[hook] = async (...args) => {
      if (!isAvailable()) {
        return undefined
      }
      try {
        return await original(...args)
      } catch (error) {
        console.error(`Redis ${hook} failed`, error?.message || error)
        return undefined
      }
    }
  }
  return extension
}

const server = new Hocuspocus({
  port: PORT,
  debounce: DEBOUNCE_MS,
  maxDebounce: DEBOUNCE_MAX_MS,
  extensions: [
    redisExtension(),
    new Database({
      fetch: fetchDocument,
      store: async () => {},
    }),
  ],
  onAuthenticate: authenticate,
  connected: trackConnection,
  onChange: storeUpdate,
  onDisconnect: async payload => {
    untrackConnection(payload)
    if (payload.clientsCount === 0) {
      await flushSnapshot(payload.documentName, payload.document)
    }
  },
  onRequest: handleRequest,
})

await server.listen(PORT)
startCompaction(pool)
