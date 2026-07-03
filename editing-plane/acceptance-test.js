import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { prosemirrorJSONToYDoc, yDocToProsemirrorJSON } from '@tiptap/y-tiptap'
import { spawn } from 'node:child_process'
import net from 'node:net'
import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import pg from 'pg'
import WebSocket from 'ws'
import * as Y from 'yjs'
import { compactOnce } from './compaction.js'

globalThis.WebSocket = WebSocket
const { HocuspocusProvider } = await import('@hocuspocus/provider')

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(__dirname, '..')
const PORT = Number(process.env.PORT || 8081)
const WS_URL = process.env.HOCUSPOCUS_URL || `ws://localhost:${PORT}`
const JAVA_BASE_URL = process.env.JAVA_BASE_URL || 'http://localhost:8080'
const DATABASE_URL = process.env.DATABASE_URL || 'postgres://mydoc:changeme@localhost:5432/mydoc'
const INTERNAL_SERVICE_TOKEN = process.env.INTERNAL_SERVICE_TOKEN || 'internal-test-token-32-bytes-long'
const COLLAB_JWT_SECRET = process.env.COLLAB_JWT_SECRET || 'collab-secret-32-bytes-minimum-value'
const schema = getSchema([StarterKit])
const { Pool } = pg
const pool = new Pool({ connectionString: DATABASE_URL })

let serverProcess

try {
  await assertJavaCore()
  const fixture = await seedFixture()
  await startEditingPlane()
  await twoClientSyncAndSnapshot(fixture)
  await restartRestoresYjsState(fixture)
  await redisRestartKeepsSingleNodeEditing(fixture)
  await compactYjsUpdates(fixture.documentId)
  await archiveKicksConnection(fixture)
  console.log('M3 editing-plane acceptance passed')
} finally {
  await stopEditingPlane()
  await runCommand('docker', ['compose', 'start', 'redis'], { cwd: REPO_ROOT, allowFailure: true })
  await pool.end()
}
process.exit(0)

async function seedFixture() {
  const suffix = Date.now()
  const adminId = randomUUID()
  await pool.query(`
    INSERT INTO member (id, email, display_name, role, created_at)
    VALUES ($1, $2, $3, 'ADMIN', now())
  `, [adminId, `admin-m3-node-${suffix}@mysc.co.kr`, 'Node M3 Admin'])

  const space = await apiJson('/api/spaces', {
    method: 'POST',
    memberId: adminId,
    body: { slug: `m3-node-${suffix}`, name: 'M3 Node Acceptance' },
    expectedStatus: 201,
  })
  const member = await apiJson('/api/members', {
    method: 'POST',
    memberId: adminId,
    body: {
      email: `member-m3-node-${suffix}@mysc.co.kr`,
      displayName: 'Node M3 Member',
      role: 'MEMBER',
    },
    expectedStatus: 201,
  })
  const document = await apiJson('/api/documents', {
    method: 'POST',
    memberId: member.id,
    body: { spaceId: space.id, title: 'M3 Node 편집 문서' },
    expectedStatus: 201,
  })
  await apiJson(`/api/documents/${document.id}/blocks`, {
    method: 'PUT',
    memberId: member.id,
    body: {
      blocks: [
        block('HEADING1', { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '초기 제목' }] }),
        block('PARAGRAPH', paragraph('초기 본문')),
      ],
    },
    expectedStatus: 204,
  })
  const tokenResponse = await apiJson('/api/internal/collab-tokens', {
    method: 'POST',
    memberId: member.id,
    body: { documentId: document.id },
    expectedStatus: 200,
  })
  return { adminId, memberId: member.id, documentId: document.id, token: tokenResponse.token }
}

async function twoClientSyncAndSnapshot(fixture) {
  const doc1 = new Y.Doc()
  const doc2 = new Y.Doc()
  const provider1 = provider(doc1, fixture)
  const provider2 = provider(doc2, fixture)
  await Promise.all([waitSynced(provider1), waitSynced(provider2)])
  await waitUntil(() => prosemirrorText(doc1).includes('초기 본문'), 5000, 'initial Java blocks were not hydrated')

  applyProsemirror(doc1, [
    { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '동시 편집' }] },
    { type: 'paragraph', content: [{ type: 'text', text: 'provider 동기화 본문' }] },
  ])

  await waitUntil(() => prosemirrorText(doc2).includes('provider 동기화 본문'), 5000, 'provider update was not synchronized')
  await expectAuthFailure(fixture)
  await waitUntil(async () => {
    const body = await apiJson(`/api/documents/${fixture.documentId}/revisions`, {
      memberId: fixture.memberId,
      expectedStatus: 200,
    })
    return body.content?.some(revision => revision.cause === 'SNAPSHOT_COMMIT')
  }, 8000, 'snapshot commit revision was not created')

  provider1.destroy()
  provider2.destroy()
}

async function restartRestoresYjsState(fixture) {
  await stopEditingPlane()
  await startEditingPlane()
  const document = new Y.Doc()
  const instance = provider(document, fixture)
  await waitSynced(instance)
  await waitUntil(
    () => prosemirrorText(document).includes('provider 동기화 본문'),
    5000,
    'restart did not restore stored Yjs state',
  )
  instance.destroy()
}

async function redisRestartKeepsSingleNodeEditing(fixture) {
  await runCommand('docker', ['compose', 'stop', 'redis'], { cwd: REPO_ROOT })
  const doc1 = new Y.Doc()
  const doc2 = new Y.Doc()
  const provider1 = provider(doc1, fixture)
  const provider2 = provider(doc2, fixture)
  await Promise.all([waitSynced(provider1), waitSynced(provider2)])
  applyProsemirror(doc1, [
    { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: 'Redis 중단 편집' }] },
    { type: 'paragraph', content: [{ type: 'text', text: 'redis 없이도 단일 노드 편집 유지' }] },
  ])
  await waitUntil(
    () => prosemirrorText(doc2).includes('redis 없이도 단일 노드 편집 유지'),
    5000,
    'single-node editing did not continue while Redis was stopped',
  )
  provider1.destroy()
  provider2.destroy()
  await runCommand('docker', ['compose', 'start', 'redis'], { cwd: REPO_ROOT })
  await waitPort(6379)
}

async function compactYjsUpdates(documentId) {
  const source = new Y.Doc()
  const updates = []
  source.on('update', update => updates.push(Buffer.from(update)))
  const map = source.getMap('acceptance')
  for (let index = 0; index < 501; index += 1) {
    map.set(`k${index}`, index)
  }
  for (const update of updates) {
    await pool.query('INSERT INTO yjs_update (doc_id, update_data) VALUES ($1, $2)', [documentId, update])
  }
  const maxId = Number((await pool.query('SELECT MAX(id) AS id FROM yjs_update WHERE doc_id = $1', [documentId])).rows[0].id)
  await compactOnce(pool)
  const snapshotCount = Number((await pool.query('SELECT COUNT(*) AS count FROM yjs_snapshot WHERE doc_id = $1', [documentId])).rows[0].count)
  const compactedRows = Number((await pool.query('SELECT COUNT(*) AS count FROM yjs_update WHERE doc_id = $1 AND id <= $2', [documentId, maxId])).rows[0].count)
  if (snapshotCount !== 1 || compactedRows !== 0) {
    throw new Error(`compaction failed: snapshots=${snapshotCount}, compactedRows=${compactedRows}`)
  }
}

async function archiveKicksConnection(fixture) {
  await stopEditingPlane()
  await startEditingPlane()
  const doc = new Y.Doc()
  const instance = provider(doc, fixture)
  await waitSynced(instance)
  const closed = new Promise(resolve => instance.on('close', resolve))
  await apiJson(`/api/documents/${fixture.documentId}/archive`, {
    method: 'POST',
    memberId: fixture.memberId,
    expectedStatus: 204,
  })
  await timeout(closed, 5000, 'archive did not close provider connection')
  instance.destroy()
}

function provider(document, fixture) {
  return new HocuspocusProvider({
    url: WS_URL,
    name: fixture.documentId,
    token: fixture.token,
    document,
    quiet: true,
  })
}

function waitSynced(instance) {
  if (instance.synced) {
    return Promise.resolve()
  }
  return timeout(new Promise(resolve => {
    instance.on('synced', ({ state }) => {
      if (state) {
        resolve()
      }
    })
  }), 5000, 'provider did not sync')
}

async function expectAuthFailure(fixture) {
  const bad = new HocuspocusProvider({
    url: WS_URL,
    name: fixture.documentId,
    token: `${fixture.token}.forged`,
    document: new Y.Doc(),
    quiet: true,
  })
  await timeout(new Promise(resolve => {
    bad.on('authenticationFailed', resolve)
  }), 5000, 'forged token was not rejected')
  bad.destroy()
}

async function assertJavaCore() {
  const response = await fetch(`${JAVA_BASE_URL}/actuator/health`)
  if (!response.ok) {
    throw new Error(`Java core is not healthy at ${JAVA_BASE_URL}`)
  }
}

async function startEditingPlane() {
  if (serverProcess) {
    return
  }
  await ensurePortFree(PORT)
  serverProcess = spawn(process.execPath, ['server.js'], {
    cwd: __dirname,
    env: {
      ...process.env,
      PORT: String(PORT),
      DATABASE_URL,
      REDIS_URL: process.env.REDIS_URL || 'redis://localhost:6379',
      COLLAB_JWT_SECRET,
      INTERNAL_SERVICE_TOKEN,
      JAVA_BASE_URL,
      DEBOUNCE_MS: process.env.DEBOUNCE_MS || '1000',
      DEBOUNCE_MAX_MS: process.env.DEBOUNCE_MAX_MS || '3000',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  serverProcess.stdout.on('data', chunk => process.stdout.write(`[editing-plane] ${chunk}`))
  serverProcess.stderr.on('data', chunk => process.stderr.write(`[editing-plane] ${chunk}`))
  serverProcess.once('exit', code => {
    if (serverProcess) {
      console.error(`editing-plane exited with code ${code}`)
    }
  })
  await waitPort(PORT)
}

async function stopEditingPlane() {
  if (!serverProcess) {
    return
  }
  const child = serverProcess
  serverProcess = null
  child.kill('SIGTERM')
  await timeout(new Promise(resolve => child.once('exit', resolve)), 5000, 'editing-plane did not stop')
}

async function apiJson(pathname, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (options.memberId) {
    headers['X-Member-Id'] = options.memberId
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const response = await fetch(`${JAVA_BASE_URL}${pathname}`, {
    method: options.method || 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (response.status !== options.expectedStatus) {
    const text = await response.text()
    throw new Error(`${pathname} returned ${response.status}, expected ${options.expectedStatus}: ${text}`)
  }
  if (response.status === 204) {
    return null
  }
  return response.json()
}

function applyProsemirror(document, content) {
  const next = prosemirrorJSONToYDoc(schema, { type: 'doc', content }, 'prosemirror')
  Y.applyUpdate(document, Y.encodeStateAsUpdate(next))
}

function prosemirrorText(document) {
  return JSON.stringify(yDocToProsemirrorJSON(document, 'prosemirror'))
}

function block(type, content) {
  return {
    type,
    content,
    sourceType: 'MANUAL',
    sourceUrl: null,
    sourceRef: null,
  }
}

function paragraph(text) {
  return { type: 'paragraph', content: [{ type: 'text', text }] }
}

async function waitUntil(check, timeoutMs, message) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < timeoutMs) {
    if (await check()) {
      return
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error(message)
}

function timeout(promise, timeoutMs, message) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(message)), timeoutMs)),
  ])
}

function ensurePortFree(port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1')
    socket.once('connect', () => {
      socket.destroy()
      reject(new Error(`port ${port} is already in use`))
    })
    socket.once('error', () => resolve())
  })
}

function waitPort(port) {
  return waitUntil(() => new Promise(resolve => {
    const socket = net.connect(port, '127.0.0.1')
    socket.once('connect', () => {
      socket.destroy()
      resolve(true)
    })
    socket.once('error', () => resolve(false))
  }), 10000, `port ${port} did not open`)
}

function runCommand(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      stdio: options.allowFailure ? 'ignore' : 'inherit',
    })
    child.once('exit', code => {
      if (code === 0 || options.allowFailure) {
        resolve()
      } else {
        reject(new Error(`${command} ${args.join(' ')} failed with code ${code}`))
      }
    })
    child.once('error', reject)
  })
}

process.on('SIGINT', async () => {
  try {
    await stopEditingPlane()
    await pool.end()
  } finally {
    process.exit(130)
  }
})
