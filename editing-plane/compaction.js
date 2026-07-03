import * as Y from 'yjs'

const TEN_MINUTES_MS = 10 * 60 * 1000

export function startCompaction(pool) {
  setInterval(() => {
    compactOnce(pool).catch(error => console.error('compaction failed', error))
  }, TEN_MINUTES_MS)
}

export async function compactOnce(pool) {
  const documents = await pool.query(`
    SELECT doc_id, MAX(id) AS max_id
    FROM yjs_update
    GROUP BY doc_id
    HAVING COUNT(*) >= 500
  `)

  for (const row of documents.rows) {
    await compactDocument(pool, row.doc_id, Number(row.max_id))
  }
}

async function compactDocument(pool, docId, maxUpdateId) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const snapshot = await client.query(
      'SELECT state_data FROM yjs_snapshot WHERE doc_id = $1 FOR UPDATE',
      [docId],
    )
    const updates = await client.query(
      'SELECT update_data FROM yjs_update WHERE doc_id = $1 AND id <= $2 ORDER BY id',
      [docId, maxUpdateId],
    )

    const doc = new Y.Doc()
    if (snapshot.rowCount > 0) {
      Y.applyUpdate(doc, snapshot.rows[0].state_data)
    }
    for (const update of updates.rows) {
      Y.applyUpdate(doc, update.update_data)
    }

    await client.query(`
      INSERT INTO yjs_snapshot (doc_id, state_data, last_update_id, updated_at)
      VALUES ($1, $2, $3, now())
      ON CONFLICT (doc_id)
      DO UPDATE SET state_data = EXCLUDED.state_data,
                    last_update_id = EXCLUDED.last_update_id,
                    updated_at = now()
    `, [docId, Buffer.from(Y.encodeStateAsUpdate(doc)), maxUpdateId])
    await client.query('DELETE FROM yjs_update WHERE doc_id = $1 AND id <= $2', [docId, maxUpdateId])
    await client.query('COMMIT')
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  } finally {
    client.release()
  }
}
