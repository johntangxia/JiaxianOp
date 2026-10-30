import { describe, expect, it } from 'vitest';
import { demoVisits, sources, branches, nextPath, collapsePath } from './flow';

describe('汇总访问路径', () => {
  it('入口分组统计访问次数，给定入口占比与演示数据匹配', () => {
    const count = demoVisits.reduce((sum, v) => sum + v.count, 0);
    expect(count).toBe(1000);
    expect(branches(demoVisits, []).find(x => x.id === 'welink')?.count).toBe(337);
    expect(sources.find(x => x.id === 'pending')?.label).toContain('待确认');
  });
  it('每一层只计算之前完整路径匹配的访问，百分比以当前路径为分母', () => {
    const visits = [
      { path: ['a', 'x', 'p'], count: 6 },
      { path: ['a', 'x', 'q'], count: 4 },
      { path: ['a', 'y', 'p'], count: 10 },
      { path: ['b', 'x', 'q'], count: 30 },
    ];
    expect(branches(visits, ['a', 'x'])).toEqual([{ id: 'p', count: 6, percent: 60 }, { id: 'q', count: 4, percent: 40 }]);
  });
  it('结束访问是终点，不能展开', () => {
    expect(branches(demoVisits, ['welink', 'content', 'end'])).toEqual([]);
    expect(nextPath(['welink', 'content', 'end'], 'channel')).toEqual(['welink', 'content', 'end']);
  });
  it('切换上游分支时移除旧的后续路径', () => {
    expect(collapsePath(['welink', 'content', 'browse'], 1, 'channel')).toEqual(['welink', 'channel']);
  });
  it('模拟不同入口的去向有区别，但各入口合计仍保持原样', () => {
    const welink = branches(demoVisits, ['welink']).find(b => b.id === 'content')!.percent;
    const direct = branches(demoVisits, ['direct']).find(b => b.id === 'content')!.percent;
    expect(welink - direct).toBeGreaterThan(10);
    for (const source of sources) {
      expect(branches(demoVisits, [source.id]).reduce((sum, b) => sum + b.count, 0)).toBe(source.count);
    }
  });
});
