// SPDX-License-Identifier: MIT
//
// dsh-su local environment briefing: DSH plugin that registers the local Root
// runtime facts as a system-prompt section.
//
// The app writes this directory into the guest (/opt/dsh-app/suenv/) at every
// engine start of a Root build and mounts it with an extra `--patch` overlay
// that inserts this file by absolute path. Safe mode and custom profiles never
// receive the overlay; remote (ACP) sessions never run this local plugin, so
// they never see the section.
//
// Facts are read from the running guest when the plugin loads — never
// hardcoded: the execution identity comes from the process, the distribution
// from /etc/os-release, the architecture and working directory from the
// process itself. The namespace layout is a launch-design fact: this overlay
// only ships in Root builds, whose runc launcher isolates the mount namespace
// only. What this section deliberately does not claim: that networking works,
// that apt works, or that any sandbox backend is available — none of that is
// measured here. AGENTS.md keeps the operating rules; the engine's own
// diagnostics snapshot ($DSH_HOME/dsh-app/diagnostics.md) carries the dynamic
// state.

import { readFileSync } from 'node:fs'

/** Unique section name; a duplicate registration would throw, and this plugin loads once per engine start. */
export const SECTION_NAME = 'dsh-su:local-root-environment'

/** After the deployment persona, before the first-party policy sections. */
export const SECTION_ORDER = 100

function osReleaseField(content, field) {
  const line = content.split('\n').find((candidate) => candidate.startsWith(`${field}=`))
  if (line === undefined) return null
  return line.slice(field.length + 1).replace(/^"|"$/g, '')
}

/** `PRETTY_NAME` from /etc/os-release, falling back to `ID VERSION_ID`; null when unreadable here. */
export function ubuntuLine() {
  let content
  try {
    content = readFileSync('/etc/os-release', 'utf8')
  } catch {
    return null
  }
  const pretty = osReleaseField(content, 'PRETTY_NAME')
  if (pretty) return pretty
  const id = osReleaseField(content, 'ID')
  const version = osReleaseField(content, 'VERSION_ID')
  return [id, version].filter(Boolean).join(' ') || null
}

/**
 * The section text, assembled from the environment the plugin observes.
 *
 * @param env - process-like object (defaults to `process`); split out so tests
 *   can drive real formatting against fake identities.
 * @param readOsRelease - direct os-release content override for tests.
 */
export function buildSectionText(env = process, readOsRelease) {
  const uid = env.getuid()
  const euid = env.geteuid()
  const identity = uid === 0 && euid === 0
    ? '真实 root（UID 0）'
    : `UID ${uid}（euid ${euid}），不是 root`
  const platform = typeof env.platform === 'function' ? env.platform() : env.platform
  let distribution = readOsRelease !== undefined ? readOsRelease : ubuntuLine()
  const lines = [
    '## 本地运行环境（DSH SU）',
    '',
    `- 执行身份：${identity}。本机环境由 runc 启动的 chroot 提供，仅挂载（mount）命名空间隔离，PID 与用户命名空间未隔离。`,
    `- 客户系统：${distribution ?? '未读取到 /etc/os-release'}；平台 ${platform}，架构 ${env.arch}。`,
    `- 默认工作目录：${env.cwd()}（引擎进程启动时的实际 cwd）。`,
    '- 本节只陈述上述启动时实测/随构建写入的事实；网络连通性、apt 可用性、沙箱后端均未经本节测量，不要据此假定。操作细则见 AGENTS.md，动态状态（引擎状态、设备、最近错误）见 $DSH_HOME/dsh-app/diagnostics.md。',
  ]
  return lines.join('\n')
}

export default class SuEnv {
  static inject = ['systemPrompt']

  /** @param ctx - plugin context. */
  constructor(ctx) {
    const text = buildSectionText()
    ctx.effect(
      () => ctx.systemPrompt.section({ name: SECTION_NAME, order: SECTION_ORDER, text, interpolate: false }),
      'dsh-su-env: local root environment section',
    )
  }
}
