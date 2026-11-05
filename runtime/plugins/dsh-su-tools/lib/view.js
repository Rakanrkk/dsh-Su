const CATALOG = [
  ['curl', 'curl'],
  ['wget', 'wget'],
  ['file', 'file'],
  ['strings', 'strings'],
  ['jq', 'jq'],
  ['rg', 'ripgrep'],
  ['tree', 'tree'],
  ['git', 'git'],
]

const words = (locale) => (typeof locale === 'string' && locale.toLowerCase().startsWith('en')
  ? { refreshed: 'Checked', available: 'Available', missing: 'Missing', action: 'Check again' }
  : { refreshed: '检查时间', available: '可用', missing: '缺失', action: '重新检查' })

export function toolsView(scope, caps) {
  const label = words(caps?.locale)
  return {
    type: 'stack',
    children: [
      { type: 'text', text: { $: 'data.checkedAt' }, tone: 'muted' },
      { type: 'metric', label: label.available, value: { $: 'data.availableCount' }, caption: { $: 'data.caption' } },
      { type: 'list', items: { $: 'data.items' } },
      { type: 'actions', items: [{ label: label.action, icon: 'refresh', action: 'refresh' }] },
    ],
  }
}

export function toolsData(snapshot) {
  const { result } = snapshot
  const label = words(snapshot.locale)
  return {
    checkedAt: result.checkedAt,
    availableCount: result.availableCount,
    caption: `${result.availableCount} / ${result.totalCount}`,
    totalCount: result.totalCount,
    items: result.tools.map((entry) => ({
      title: CATALOG.find(([name]) => name === entry.name)?.[1] ?? entry.name,
      subtitle: entry.available ? entry.version ?? label.missing : label.missing,
      trailing: entry.available ? label.available : label.missing,
      tone: entry.available ? 'good' : 'bad',
      icon: entry.available ? 'check' : 'error',
    })),
  }
}

export function toolsResultCard(result) {
  return {
    type: 'list',
    items: toolsData({ result, locale: undefined }).items,
  }
}
