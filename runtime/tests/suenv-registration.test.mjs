// SPDX-License-Identifier: MIT
//
// Offline registration test for the dsh-su suenv plugin: drives
// app/assets/engine/suenv/index.mjs with a stub cordis context and asserts the
// native system-prompt contract, plus that the Kotlin overlay stays in sync
// with the packaged plugin. Run from the repository root:
//
//   node --test runtime/tests/suenv-registration.test.mjs

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const REPO = join(here, '..', '..')
const PLUGIN_PATH = join(REPO, 'app', 'assets', 'engine', 'suenv', 'index.mjs')
const GUEST_PATH = join(
  REPO,
  'app',
  'android',
  'app',
  'src',
  'main',
  'kotlin',
  'io',
  'github',
  'ccch1mneyyy',
  'dsh_app',
  'runtime',
  'SuEnvGuest.kt',
)

function stubContext() {
  const registered = []
  const effects = []
  const ctx = {
    systemPrompt: {
      section(section) {
        registered.push(section)
        return () => {
          const at = registered.indexOf(section)
          if (at >= 0) registered.splice(at, 1)
        }
      },
    },
    effect(factory, label) {
      effects.push({ label, dispose: factory() })
    },
  }
  return { ctx, registered, effects }
}

test('plugin registers exactly one local environment section with the measured identity', async () => {
  const { default: SuEnv, SECTION_NAME, SECTION_ORDER, buildSectionText } = await import(
    pathToFileURL(PLUGIN_PATH).href
  )
  const { ctx, registered, effects } = stubContext()

  new SuEnv(ctx)

  assert.equal(effects.length, 1, 'the section must be tied to the plugin lifetime via ctx.effect')
  assert.equal(registered.length, 1)
  const section = registered[0]
  assert.equal(section.name, SECTION_NAME)
  assert.equal(section.order, SECTION_ORDER)
  assert.equal(section.interpolate, false)
  assert.ok(section.text.length > 0)

  const text = buildSectionText()
  assert.ok(text.includes(`UID ${process.getuid()}`), 'the text must state the actual measured uid')
  assert.ok(text.includes(process.cwd()), 'the text must state the actual working directory')
  assert.ok(text.includes('AGENTS.md'), 'the text must point at the operating rules instead of duplicating them')

  effects[0].dispose()
  assert.equal(registered.length, 0, 'disposing the plugin effect must unregister the section')
})

test('section text never hardcodes an unmeasured identity', async () => {
  const { buildSectionText } = await import(pathToFileURL(PLUGIN_PATH).href)
  const rootEnv = {
    getuid: () => 0,
    geteuid: () => 0,
    arch: 'x64',
    platform: 'linux',
    cwd: () => '/root',
  }
  const rootText = buildSectionText(rootEnv, 'Ubuntu 24.04.3 LTS')
  assert.ok(rootText.includes('真实 root（UID 0）'))
  assert.ok(rootText.includes('Ubuntu 24.04.3 LTS'))
  assert.ok(rootText.includes('/root'))

  const userEnv = { ...rootEnv, getuid: () => 10042, geteuid: () => 10042 }
  const userText = buildSectionText(userEnv, null)
  assert.ok(userText.includes('UID 10042'), 'a non-root process must not be described as root')
  assert.ok(!userText.includes('真实 root'))
  assert.ok(userText.includes('未读取到 /etc/os-release'))
})

test('kotlin overlay stays in sync with the packaged plugin', async () => {
  const guest = readFileSync(GUEST_PATH, 'utf8')
  const guestDir = guest.match(/const val GUEST_DIR = "([^"]+)"/)?.[1]
  const pluginId = guest.match(/const val PLUGIN_ID = "([^"]+)"/)?.[1]
  const patchYaml = guest.match(/val PATCH_YAML = """([\s\S]*?)"""/)?.[1]
  const modules = guest.match(/val MODULES = listOf\(([^)]*)\)/)?.[1]
  // Kotlin constants interpolate $GUEST_DIR at runtime; resolve the same way here.
  const resolve = (raw) => (raw?.startsWith('$GUEST_DIR') ? guestDir + raw.slice('$GUEST_DIR'.length) : raw)
  const patch = resolve(guest.match(/const val PATCH = "([^"]+)"/)?.[1])
  const entry = resolve(guest.match(/const val ENTRY = "([^"]+)"/)?.[1])

  assert.ok(guestDir && patch && entry && patchYaml && modules && pluginId, 'overlay constants must be present')
  assert.equal(patch, `${guestDir}/suenv.patch.yml`)
  assert.equal(entry, `${guestDir}/index.mjs`)
  // The extracted yaml is Kotlin source: its $PLUGIN_ID/$ENTRY placeholders resolve at runtime.
  const rendered = patchYaml.replaceAll('$PLUGIN_ID', pluginId).replaceAll('$ENTRY', entry)
  assert.ok(rendered.includes(`- id: ${pluginId}`))
  assert.ok(rendered.includes(`name: ${entry}`))
  assert.equal(modules.replace(/["\s]/g, ''), 'index.mjs')
  for (const name of modules.split(',').map((raw) => raw.trim().replaceAll('"', '')).filter(Boolean)) {
    const asset = join(dirname(PLUGIN_PATH), name)
    assert.ok(readFileSync(asset, 'utf8').length > 0, `packaged module ${name} must exist on disk`)
  }
})
