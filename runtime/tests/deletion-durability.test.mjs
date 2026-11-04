// Public JSONL plugin/deletion seam, original codecs, real files and native writer locks.
import test from 'node:test';
import fs from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import { createHash } from 'node:crypto';
import { dirname } from 'node:path';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, readdir, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
if (!process.env.DSH_PREFIX) throw Error('Set DSH_PREFIX to locked patched rc4 with its native system package');
const base = join(resolve(process.env.DSH_PREFIX), 'node_modules/@deepseek-ai');
const load = name => import(pathToFileURL(join(base, name, 'lib/index.js')));
const { Context } = await load('cordis');
const { Session } = await load('dsh-session');
const { createUserMessage } = await load('dsh-llm');
const { releasedV3SessionFormatCodec } = await load('dsh-session-format-v2-to-v3');
const { apply } = await load('dsh-session-persistence-jsonl');
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'dsh-migration-'));
  const ctx = new Context();
  t.after(async () => { await ctx.fiber.dispose(); await rm(root, { recursive: true, force: true }); });
  const id = 'migration-fixture';
  const session = Session.create(id);
  session.append('user/message', createUserMessage({ content: [{ type: 'text', text: 'preserved historical input' }], source: { kind: 'user', rpcId: 'historical-request' } }), { surfaceOp: 'append' });
  const header = { ...session.header, version: 3, delegationDepth: 0 };
  // The actual released encoder validates this historical header, not a synthetic format adapter.
  const original = Buffer.from([releasedV3SessionFormatCodec.encodeHeader(header, 0), ...session.snapshotEvents().map(event => releasedV3SessionFormatCodec.encodeEvent(event))].map(value => JSON.stringify(value)).join('\n') + '\n');
  const directory = join(root, '_no-cwd', id);
  await mkdir(directory, { recursive: true });
  const source = join(directory, 'session.v3.jsonl');
  const current = join(directory, 'session.v4.jsonl');
  await writeFile(source, original);
  await apply(ctx, { root, compression: 'none', listConcurrency: 1 });
  return { root, ctx, id, source, current, original, directory, storage: ctx.sessionPersistence };
}


const hooks = () => ({ prepare: async () => {}, inspectTasks: async () => false, cleanup: async () => {} });
function interceptSync(t, f, failures) {
  const slot = join(f.root, '.dsh-go-lifecycle', createHash('sha256').update(f.id).digest('hex'));
  const required = [slot, join(f.root, '.dsh-go-lifecycle'), f.root, dirname(f.root)];
  const marker = join(slot, 'deleted.json');
  const originalOpen = fs.open, originalRm = fs.rm;
  const trace = [];
  fs.open = async function (path, ...args) {
    const handle = await originalOpen.call(this, path, ...args);
    const originalSync = handle.sync.bind(handle);
    handle.sync = async () => {
      if (required.includes(String(path))) {
        trace.push(['sync', String(path)]);
        if (failures[0]?.path === String(path)) {
          const fault = failures.shift().error;
          const record = JSON.parse(await readFile(marker, 'utf8'));
          assert.equal(record.version, 1, 'real hardlink publication already happened');
          assert.deepEqual(await readFile(f.source), f.original, 'no log removed before fault');
          throw fault;
        }
      }
      return originalSync();
    };
    return handle;
  };
  fs.rm = async function (path, ...args) {
    if (String(path) === f.source) trace.push(['remove-source', String(path)]);
    return originalRm.call(this, path, ...args);
  };
  syncBuiltinESMExports();
  t.after(() => { fs.open = originalOpen; fs.rm = originalRm; syncBuiltinESMExports(); });
  return { slot, required, marker, trace };
}
test('retry after linked-marker sync EIO reestablishes directory durability before deleting logs', async t => {
  const f = await fixture(t);
  const failures = [];
  const observation = interceptSync(t, f, failures);
  const fault = Object.assign(Error('controlled marker directory sync EIO'), { code: 'EIO' });
  failures.push({ path: observation.slot, error: fault });
  await assert.rejects(f.storage.dshGoDeletion.delete(f.id, hooks()), error => error === fault);
  assert.equal(f.storage.dshGoDeletion.pending.has(f.id), false);
  assert.equal(f.storage.dshGoDeletion.deleted(f.id), true);
  assert.deepEqual(await readFile(f.source), f.original);
  observation.trace.length = 0;
  await f.storage.dshGoDeletion.delete(f.id, hooks());
  const removeIndex = observation.trace.findIndex(([kind]) => kind === 'remove-source');
  assert(removeIndex >= 0, 'retry actually cleans the real source artifact');
  assert.deepEqual(observation.trace.slice(0, removeIndex), observation.required.map(path => ['sync', path]));
});
test('retry sync failure is propagated before log deletion and a later retry can complete', async t => {
  const f = await fixture(t);
  const failures = [];
  const observation = interceptSync(t, f, failures);
  const first = Object.assign(Error('controlled first sync EIO'), { code: 'EIO' });
  const second = Object.assign(Error('controlled retry ancestor sync EIO'), { code: 'EIO' });
  failures.push({ path: observation.slot, error: first });
  await assert.rejects(f.storage.dshGoDeletion.delete(f.id, hooks()), error => error === first);
  failures.push({ path: observation.required[1], error: second });
  observation.trace.length = 0;
  await assert.rejects(f.storage.dshGoDeletion.delete(f.id, hooks()), error => error === second);
  assert.equal(f.storage.dshGoDeletion.pending.has(f.id), false);
  assert.deepEqual(await readFile(f.source), f.original);
  assert(!observation.trace.some(([kind]) => kind === 'remove-source'));
  await f.storage.dshGoDeletion.delete(f.id, hooks());
  assert.equal(f.storage.dshGoDeletion.pending.has(f.id), false);
  assert.equal(f.storage.dshGoDeletion.deleted(f.id), true);
});
