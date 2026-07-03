import { getSchema } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import { prosemirrorJSONToYDoc, yDocToProsemirrorJSON } from '@tiptap/y-tiptap'
import WebSocket from 'ws'
import * as Y from 'yjs'

globalThis.WebSocket = WebSocket
const { HocuspocusProvider } = await import('@hocuspocus/provider')

const WS_URL = process.env.HOCUSPOCUS_URL || 'ws://localhost:8081'
const JAVA_BASE_URL = process.env.JAVA_BASE_URL || 'http://localhost:8080'
const DOC_ID = required('DOC_ID')
const MEMBER_ID = required('MEMBER_ID')
const TOKEN = required('TOKEN')
const schema = getSchema([StarterKit])

const doc1 = new Y.Doc()
const doc2 = new Y.Doc()
const provider1 = provider(doc1, TOKEN)
const provider2 = provider(doc2, TOKEN)

await Promise.all([waitSynced(provider1), waitSynced(provider2)])

const edited = prosemirrorJSONToYDoc(schema, {
  type: 'doc',
  content: [
    { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '동시 편집' }] },
    { type: 'paragraph', content: [{ type: 'text', text: 'provider 동기화 본문' }] },
  ],
}, 'prosemirror')
Y.applyUpdate(doc1, Y.encodeStateAsUpdate(edited))

await waitUntil(() => JSON.stringify(yDocToProsemirrorJSON(doc2, 'prosemirror')).includes('provider 동기화 본문'), 5000)

await expectAuthFailure()
await waitUntil(async () => {
  const response = await fetch(`${JAVA_BASE_URL}/api/documents/${DOC_ID}/revisions`, {
    headers: { 'X-Member-Id': MEMBER_ID },
  })
  const body = await response.json()
  return body.content?.some(revision => revision.cause === 'SNAPSHOT_COMMIT')
}, 8000)

const closed = new Promise(resolve => provider1.on('close', resolve))
await fetch(`${JAVA_BASE_URL}/api/documents/${DOC_ID}/archive`, {
  method: 'POST',
  headers: { 'X-Member-Id': MEMBER_ID },
})
await timeout(closed, 5000, 'archive did not close provider connection')

provider1.destroy()
provider2.destroy()
console.log('M3 editing-plane acceptance passed')
process.exit(0)

function provider(document, token) {
  return new HocuspocusProvider({
    url: WS_URL,
    name: DOC_ID,
    token,
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

async function expectAuthFailure() {
  const bad = new HocuspocusProvider({
    url: WS_URL,
    name: DOC_ID,
    token: `${TOKEN}.forged`,
    document: new Y.Doc(),
    quiet: true,
  })
  await timeout(new Promise(resolve => {
    bad.on('authenticationFailed', resolve)
  }), 5000, 'forged token was not rejected')
  bad.destroy()
}

async function waitUntil(check, timeoutMs) {
  const startedAt = Date.now()
  while (Date.now() - startedAt < timeoutMs) {
    if (await check()) {
      return
    }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('condition was not met')
}

function timeout(promise, timeoutMs, message) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(message)), timeoutMs)),
  ])
}

function required(name) {
  const value = process.env[name]
  if (!value) {
    throw new Error(`${name} is required`)
  }
  return value
}
