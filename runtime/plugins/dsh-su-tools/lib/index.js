import { inspectTools } from './tools.js'
import { toolsData, toolsResultCard, toolsView } from './view.js'
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'dsh-su-tools'

export function apply(ctx, options = {}) {
  const tools = options.tools
  const empty = () => ({
    checkedAt: '…',
    tools: [],
    availableCount: 0,
    totalCount: tools?.length ?? 0,
  })
  const refresh = (signal) => inspectTools(tools, { signal })

  const toolCard = (_args, result) => {
    try {
      const text = JSON.stringify({ 'dsh-go/view': toolsResultCard(result) })
      if (text === undefined || Buffer.byteLength(text, 'utf8') > 8192) return {}
      return JSON.parse(text)
    } catch {
      return {}
    }
  }

  const toolText = (result) => {
    try {
      const rows = result.tools.map((entry) => `${entry.name}: ${entry.available ? entry.version ?? '可用' : '缺失'}`)
      return [`${result.checkedAt}：${result.availableCount}/${result.totalCount} 可用`, ...rows].join('\n')
    } catch {
      return '工具环境结果不可用'
    }
  }

  ctx.inject(['tools'], (host) => {
    host.tools.register(defineTool({
      name: 'tool_environment',
      description: '检查当前运行时 curl、wget、file、strings、jq、rg、tree 和 git 是否可用及其版本。需要确认命令行工具环境时使用；结果只读。',
      parameters: {},
      output: {
        schema: {
          type: 'object',
          additionalProperties: false,
          properties: {
            checkedAt: { type: 'string', required: true },
            availableCount: { type: 'integer', required: true },
            totalCount: { type: 'integer', required: true },
            tools: {
              type: 'array',
              required: true,
              items: {
                type: 'object',
                additionalProperties: true,
                properties: {
                  name: { type: 'string', required: true },
                  available: { type: 'boolean', required: true },
                  version: { oneOf: [{ type: 'string' }, { type: 'null' }] },
                },
              },
            },
          },
        },
        render: (_args, result) => [{ type: 'text', text: toolText(result) }],
        presentationMeta: toolCard,
      },
      timeoutMs: 5000,
      isConcurrencySafe: () => true,
      execute: (_args, exec) => inspectTools(tools, { signal: exec?.signal }),
    }))
  })

  ctx.inject(['dshGo'], (host) => {
    host.dshGo.panel('tools', {
      view: toolsView,
      data: (_scope, emit, caps) => {
        const controller = new AbortController()
        emit(toolsData({ result: empty(), locale: caps?.locale }))
        refresh(controller.signal).then(
          (next) => {
            emit(toolsData({ result: next, locale: caps?.locale }))
          },
          () => emit(toolsData({ result: empty(), locale: caps?.locale })),
        )
        return () => controller.abort()
      },
      actions: {
        refresh: async (_scope, event) => {
          try {
            const next = await refresh(event?.signal)
            const locale = event?.locale
            return [
              { type: 'patch', id: 'tools', data: toolsData({ result: next, locale }) },
              { type: 'toast', text: locale?.toLowerCase().startsWith('en') ? 'Checked' : '已检查' },
            ]
          } catch {
            return { type: 'toast', text: event?.locale?.toLowerCase().startsWith('en') ? 'Check failed' : '检查失败' }
          }
        },
      },
    })
  })

  return { inspect: refresh }
}
