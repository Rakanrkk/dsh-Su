import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import './dsh-tools.js'
import { createTestHost } from '../../../../sdk/plugin-kit/lib/testing.js'
import { TOOLS, inspectTools } from '../lib/tools.js'
import { toolsData, toolsView } from '../lib/view.js'

const { apply } = await import('../lib/index.js')

test('inspect reports actual availability and tolerates missing executables', async () => {
  const result = await inspectTools(['curl', 'dsh-su-missing-tool'])
  assert.equal(result.totalCount, 2)
  assert.match(result.checkedAt, /^\d{4}-\d{2}-\d{2}T/)
  assert.equal(result.tools[0].name, 'curl')
  assert.equal(result.tools[1].available, false)
  assert.equal(result.tools[1].version, null)
  if (result.tools[0].available) assert.equal(typeof result.tools[0].version, 'string')
})

test('inspectTools result survives JSON roundtrip with deep equality', async () => {
  const result = await inspectTools(['curl', 'dsh-su-missing-tool'])
  assert.deepEqual(JSON.parse(JSON.stringify(result)), result)
})

test('view and result data stay lossless and avoid root claims', () => {
  const result = {
    checkedAt: '2026-11-05T00:00:00.000Z',
    tools: TOOLS.map((name, index) => ({ name, available: index % 2 === 0, version: index % 2 === 0 ? '1.0.0' : undefined })),
    availableCount: 4,
    totalCount: 8,
  }
  const view = toolsView({}, { locale: 'zh-CN' })
  const data = toolsData({ result, locale: 'zh-CN' })
  assert.equal(JSON.stringify(JSON.parse(JSON.stringify(view))), JSON.stringify(view))
  assert.equal(JSON.stringify(JSON.parse(JSON.stringify(data))), JSON.stringify(data))
  assert.equal(data.items.length, 8)
  assert.equal(data.availableCount, 4)
  assert.equal(data.totalCount, 8)
})

test('createTestHost loads the panel and refreshes real probes', async () => {
  const host = createTestHost({ dir: fileURLToPath(new URL('..', import.meta.url)) })
  apply(host.ctx)
  assert.equal(host.contributions().state, 'ok')
  const feed = host.subscribe('tools')
  for (let elapsed = 0; feed.data?.items?.length !== TOOLS.length && elapsed < 5000; elapsed += 50) {
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  assert.equal(feed.data.totalCount, TOOLS.length)
  assert.equal(feed.data.items.length, TOOLS.length)
  host.dispose()
})

test('panel data uses feed locale for the first and refreshed values', async () => {
  const english = createTestHost({ dir: fileURLToPath(new URL('..', import.meta.url)), caps: { locale: 'en-US' } })
  apply(english.ctx, { tools: ['dsh-su-missing-tool'] })
  const enFeed = english.subscribe('tools')
  for (let elapsed = 0; enFeed.data?.items?.length !== 1 && elapsed < 5000; elapsed += 50) {
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  assert.equal(enFeed.data.caption, '0 / 1')
  assert.equal(enFeed.data.items[0].trailing, 'Missing')
  const invocation = await english.invoke('tools', { action: 'refresh', locale: 'en-US' })
  assert.deepEqual(invocation.effects.map((effect) => effect.type), ['patch', 'toast'])
  assert.equal(invocation.effects[0].data.items[0].trailing, 'Missing')
  const chinese = createTestHost({ dir: fileURLToPath(new URL('..', import.meta.url)) })
  apply(chinese.ctx, { tools: ['dsh-su-missing-tool'] })
  const zhFeed = chinese.subscribe('tools')
  for (let elapsed = 0; zhFeed.data?.items?.length !== 1 && elapsed < 5000; elapsed += 50) {
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  assert.equal(zhFeed.data.caption, '0 / 1')
  assert.equal(zhFeed.data.items[0].trailing, '缺失')
  english.dispose()
  chinese.dispose()
})

test('subscriptions stay independent and manual refresh does not cancel scopes', async () => {
  const host = createTestHost({ dir: fileURLToPath(new URL('..', import.meta.url)) })
  apply(host.ctx, { tools: ['curl'] })
  const feedA = host.subscribe('tools')
  const feedB = host.subscribe('tools')
  feedA.stop()
  for (let elapsed = 0; feedB.data?.items?.length !== 1 && elapsed < 5000; elapsed += 50) {
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  assert.equal(feedB.data.items.length, 1)
  const before = JSON.stringify(feedB.data)
  const invocation = await host.invoke('tools', { action: 'refresh' })
  await new Promise((resolve) => setTimeout(resolve, 50))
  assert.deepEqual(invocation.effects.map((effect) => effect.type), ['patch', 'toast'])
  assert.equal(JSON.stringify(feedB.data), before)
  host.dispose()
})

test('registers a read-only agent tool using the existing tools service', async () => {
  const registered = new Map()
  const inject = (names, callback) => {
    if (names.includes('tools')) callback({ tools: { register: (definition) => registered.set(definition.name, definition) } })
  }
  apply({ tools: { register: (definition) => registered.set(definition.name, definition) }, inject }, { tools: ['curl'] })
  const tool = registered.get('tool_environment')
  assert.ok(tool)
  const result = await tool.execute({}, { signal: new AbortController().signal })
  assert.equal(result.totalCount, 1)
  assert.equal(result.tools[0].name, 'curl')
  const text = tool.output.render({}, result)
  assert.equal(text[0].type, 'text')
  const meta = tool.output.presentationMeta({}, result)
  assert.deepEqual(Object.keys(meta), ['dsh-go/view'])
  assert.ok(Buffer.byteLength(JSON.stringify(meta)) <= 8192)
})

test('two concurrent refreshes each return their own patch', async () => {
  const host = createTestHost({ dir: fileURLToPath(new URL('..', import.meta.url)) })
  apply(host.ctx, { tools: ['curl'] })
  const [invocationA, invocationB] = await Promise.all([
    host.invoke('tools', { action: 'refresh' }),
    host.invoke('tools', { action: 'refresh' }),
  ])
  assert.deepEqual(invocationA.effects.map((effect) => effect.type), ['patch', 'toast'])
  assert.deepEqual(invocationB.effects.map((effect) => effect.type), ['patch', 'toast'])
  assert.equal(invocationA.issues.length, 0)
  assert.equal(invocationB.issues.length, 0)
  host.dispose()
})
