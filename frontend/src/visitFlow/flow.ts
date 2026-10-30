export type Visit = { path: string[]; count: number };
export type Branch = { id: string; count: number; percent: number };

export const sources = [
  { id: 'welink', label: 'WeLink 群', count: 337 },
  { id: 'w3', label: 'W3 网站', count: 215 },
  { id: 'app', label: '稼先应用号', count: 179 },
  { id: 'w3search', label: 'W3 搜索', count: 73 },
  { id: 'otherSite', label: '其他网站', count: 59 },
  { id: 'direct', label: '主动打开', count: 56 },
  { id: 'voice', label: '心声社区', count: 24 },
  { id: 'public', label: '稼先公众号', count: 23 },
  { id: 'pending', label: '待确认', count: 34 },
];
export const labels: Record<string, string> = {
  ...Object.fromEntries(sources.map(s => [s.id, s.label])),
  content: '内容详情', channel: '综合频道', domain: '领域频道', personal: '个人中心', expert: '专家主页',
  browse: '继续浏览', interact: '互动操作', recommended: '推荐', follow: '关注', search: '一站式搜索',
  like: '点赞', comment: '评论', favorite: '收藏', share: '转发', other: '其他', end: '结束访问',
};

// Synthetic paths solely for demonstrating interaction: no attribution is inferred from existing logs.
const templates: { path: string[]; weight: number }[] = [
  { path: ['content', 'browse', 'channel', 'recommended', 'content', 'like', 'end'], weight: 25 },
  { path: ['content', 'browse', 'channel', 'follow', 'content', 'end'], weight: 10 },
  { path: ['content', 'browse', 'content', 'interact', 'comment', 'end'], weight: 10 },
  { path: ['content', 'interact', 'like', 'browse', 'domain', 'end'], weight: 8 },
  { path: ['content', 'end'], weight: 12 },
  { path: ['channel', 'recommended', 'content', 'browse', 'expert', 'end'], weight: 9 },
  { path: ['channel', 'follow', 'content', 'interact', 'favorite', 'end'], weight: 6 },
  { path: ['domain', 'content', 'browse', 'content', 'end'], weight: 5 },
  { path: ['personal', 'browse', 'content', 'end'], weight: 6 },
  { path: ['expert', 'content', 'interact', 'share', 'end'], weight: 5 },
  { path: ['search', 'content', 'end'], weight: 4 },
];
export const demoVisits: Visit[] = sources.flatMap(source => {
  let assigned = 0;
  const weights = source.id === 'direct' ? [10, 5, 5, 5, 5, 25, 15, 10, 10, 5, 5] : templates.map(template => template.weight);
  return templates.map((template, i) => {
    const count = i === templates.length - 1 ? source.count - assigned : Math.floor(source.count * weights[i] / 100);
    assigned += count;
    return { path: [source.id, ...template.path], count };
  });
});

export function branches(visits: Visit[], prefix: string[]): Branch[] {
  if (prefix[prefix.length - 1] === 'end') return [];
  const matches = visits.filter(v => prefix.every((id, i) => v.path[i] === id));
  const total = matches.reduce((sum, v) => sum + v.count, 0);
  const counts = new Map<string, number>();
  for (const v of matches) {
    const id = v.path[prefix.length];
    if (id && v.count > 0) counts.set(id, (counts.get(id) ?? 0) + v.count);
  }
  return [...counts].map(([id, count]) => ({ id, count, percent: total ? count * 100 / total : 0 })).sort((a, b) => b.count - a.count);
}
export function nextPath(path: string[], id: string): string[] {
  return path[path.length - 1] === 'end' ? path : [...path, id];
}
export function collapsePath(path: string[], index: number, id: string): string[] {
  return [...path.slice(0, index), id];
}
