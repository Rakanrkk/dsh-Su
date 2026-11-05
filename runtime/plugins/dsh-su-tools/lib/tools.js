import { spawn } from 'node:child_process'

export const TOOLS = Object.freeze(['curl', 'wget', 'file', 'strings', 'jq', 'rg', 'tree', 'git'])

const TIMEOUT_MS = 3000
const VERSION_LIMIT = 160

function plainText(value) {
  return value.replaceAll(/[\u0000-\u001f\u007f]+/g, ' ').trim()
}

function shortVersion(value) {
  const line = plainText(value).split(/\s{2,}/)[0]
  return line === '' ? undefined : line.slice(0, VERSION_LIMIT)
}

function run(args, signal) {
  return new Promise((resolve) => {
    const child = spawn(args[0], args.slice(1), {
      stdio: ['ignore', 'pipe', 'pipe'],
      signal,
      windowsHide: true,
    })
    let output = ''
    let settled = false
    const finish = (result) => {
      if (settled) return
      settled = true
      resolve(result)
    }
    child.stdout.on('data', (chunk) => {
      output += chunk
      if (output.length > 8192) child.kill('SIGKILL')
    })
    child.stderr.on('data', () => {})
    child.on('error', () => finish({ status: null, stdout: '' }))
    child.on('close', (code, exitSignal) => finish({
      status: exitSignal === null ? code : null,
      stdout: output,
    }))
  })
}

const VERSION_ARGS = {
  file: ['file', '--version'],
  strings: ['strings', '--version'],
  tree: ['tree', '--version'],
}

export async function inspectTools(tools = TOOLS, options = {}) {
  const timeout = options.timeoutMs ?? TIMEOUT_MS
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new Error('timeout')), timeout)
  timer.unref?.()
  const ownSignal = options.signal
  const signal = ownSignal
    ? AbortSignal.any?.([ownSignal, controller.signal]) ?? ownSignal
    : controller.signal
  try {
    const entries = await Promise.all(tools.map(async (name) => {
      const args = VERSION_ARGS[name] ?? [name, '--version']
      const probe = await run(args, signal)
      const available = probe.status === 0
      return {
        name,
        available,
        version: available ? shortVersion(probe.stdout) : undefined,
      }
    }))
    return {
      checkedAt: new Date().toISOString(),
      tools: entries,
      availableCount: entries.filter((entry) => entry.available).length,
      totalCount: entries.length,
    }
  } finally {
    clearTimeout(timer)
  }
}
