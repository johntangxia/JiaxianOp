<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { branches, collapsePath, demoVisits, labels, sources, type Branch } from '../visitFlow/flow';

const path = ref(['welink', 'content', 'browse', 'channel']);
const depth = ref(3);
const canvas = ref<HTMLElement | null>(null);
const total = demoVisits.reduce((sum, visit) => sum + visit.count, 0);
const label = (id: string) => labels[id] ?? id;
const fmt = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
const columns = computed(() => {
  const result: Branch[][] = [sources.map(source => ({ id: source.id, count: source.count, percent: source.count / total * 100 }))];
  for (let i = 1; i <= depth.value; i++) {
    const prefix = path.value.slice(0, i);
    if (prefix.length !== i || prefix[prefix.length - 1] === 'end') break;
    const items = branches(demoVisits, prefix);
    if (!items.length) break;
    result.push(items);
  }
  return result;
});
const selectedCount = computed(() => {
  const matches = demoVisits.filter(v => path.value.every((id, index) => v.path[index] === id));
  return matches.reduce((sum, v) => sum + v.count, 0);
});
const canExpand = computed(() => path.value[path.value.length - 1] !== 'end' && branches(demoVisits, path.value).length > 0);
function fill(next: string[], limit: number) {
  while (next.length <= limit && next[next.length - 1] !== 'end') {
    const first = branches(demoVisits, next)[0];
    if (!first) break;
    next = [...next, first.id];
  }
  return next;
}
function select(index: number, id: string) {
  path.value = fill(collapsePath(path.value, index, id), depth.value);
}
async function expand() {
  if (!canExpand.value) return;
  depth.value += 1;
  path.value = fill(path.value, depth.value);
  await nextTick();
  canvas.value?.scrollTo({ left: canvas.value.scrollWidth, behavior: 'smooth' });
}
function collapse() {
  if (path.value.length <= 1) return;
  depth.value = Math.min(depth.value - 1, path.value.length - 2);
  path.value = path.value.slice(0, depth.value + 1);
  if (depth.value === 0) moveGraph('start');
}
function moveGraph(direction: 'start' | 'left' | 'right') {
  const element = canvas.value;
  if (!element) return;
  const distance = Math.max(element.clientWidth * 0.7, 218);
  const left = direction === 'start' ? 0 : element.scrollLeft + (direction === 'left' ? -distance : distance);
  element.scrollTo({ left, behavior: 'smooth' });
}
const columnWidth = 218;
const top = 95;
const row = 69;
const nodeWidth = 164;
const nodeHeight = 54;
const edges = computed(() => {
  const result: { d: string; width: number; active: boolean; key: string }[] = [];
  for (let col = 1; col < columns.value.length; col++) {
    const parentIndex = columns.value[col - 1].findIndex(item => item.id === path.value[col - 1]);
    if (parentIndex < 0) continue;
    columns.value[col].forEach((child, index) => {
      const x1 = (col - 1) * columnWidth + nodeWidth;
      const x2 = col * columnWidth;
      const y1 = top + parentIndex * row + nodeHeight / 2;
      const y2 = top + index * row + nodeHeight / 2;
      const mid = (x1 + x2) / 2;
      result.push({ d: `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`, width: child.count / Math.max(...sources.map(source => source.count)) * 22, active: child.id === path.value[col], key: `${col}-${child.id}` });
    });
  }
  return result.sort((a, b) => Number(a.active) - Number(b.active));
});
const graphWidth = computed(() => Math.max(4, columns.value.length) * columnWidth);
</script>

<template>
  <div class="flow-page">
    <div class="flow-heading"><div><h1>全站访问流向</h1><p>从外部入口出发，查看进入社区后的汇总流向与持续消费</p></div><span class="demo-badge">模拟演示</span></div>
    <div class="notice"><strong>演示数据</strong>：入口占比采用提供的参考数值，3.4% 的来源待确认；站内访问次数和路径为合成示例，不代表真实流量。现有数据无法可靠关联真实外部入口与访问会话。</div>
    <div class="flow-panel">
      <div class="panel-top"><div><h2>访问流向图</h2><p>按访问次数统计 · 外部入口为第 0 步 · 点击节点逐层展开</p></div><div class="graph-controls"><span class="legend"><i></i> 线条粗细表示访问次数</span><button type="button" @click="moveGraph('start')">回到外部入口</button><button type="button" @click="moveGraph('left')">← 向左查看</button><button type="button" @click="moveGraph('right')">向右查看 →</button></div></div>
      <div class="flow-body">
        <div ref="canvas" class="canvas-scroll" aria-label="访问流向图，可横向滚动">
          <div class="graph" :style="{ width: `${graphWidth}px` }">
            <div v-for="(_, col) in columns" :key="`background-${col}`" class="column-background" :style="{ left: `${col * columnWidth - 8}px` }" aria-hidden="true"></div>
            <svg class="edges" :width="graphWidth" height="740" aria-hidden="true"><path v-for="edge in edges" :key="edge.key" :d="edge.d" fill="none" :stroke="edge.active ? '#4788f7' : '#d9e0ea'" :stroke-width="edge.width" stroke-linecap="round" /></svg>
            <div v-for="(items, col) in columns" :key="col" class="column" :style="{ left: `${col * columnWidth}px` }">
              <div class="column-heading"><strong>{{ col === 0 ? '外部入口' : `第 ${col} 步` }}</strong><small>{{ col === 0 ? '访问来源' : col === 1 ? '进入站内的首个页面' : '下一步去向' }}</small></div>
              <div class="node-list">
                <button v-for="item in items" :key="item.id" type="button" class="node" :class="{ selected: path[col] === item.id, terminal: item.id === 'end' }" :aria-pressed="path[col] === item.id" @click="select(col, item.id)">
                  <span class="node-icon">{{ item.id === 'end' ? '×' : col === 0 ? '↗' : '›' }}</span>
                  <span class="node-text"><strong>{{ label(item.id) }}</strong><small>{{ col === 0 ? `${item.percent.toFixed(1)}%` : `${fmt(item.count)} 次 · ${item.percent.toFixed(1)}%` }}</small></span>
                  <span v-if="item.id !== 'end'" class="node-arrow">›</span>
                </button>
              </div>
            </div>
          </div>
        </div>
        <aside class="path-panel"><h3>当前选中路径</h3><div class="path-box"><div v-for="(id, index) in path" :key="`${index}-${id}`" class="path-item"><span class="path-dot">{{ index + 1 }}</span><span><small>{{ index === 0 ? '外部入口' : `第 ${index} 步` }}</small><strong>{{ label(id) }}</strong></span></div></div><div class="path-info"><span>当前路径访问次数</span><strong>{{ fmt(selectedCount) }} 次</strong><small>演示数据 · 按完整路径筛选</small></div><el-button type="primary" class="path-action" :disabled="!canExpand" @click="expand">继续展开 →</el-button><el-button class="path-action collapse" :disabled="path.length <= 1" @click="collapse">收起一步</el-button><p class="hint">“结束访问”表示该次访问没有记录到后续行为，是路径终点。</p></aside>
      </div>
    </div>
    <div class="flow-footnote">说明：每个节点的比例以其上一步的访问次数为分母。同一次访问可能产生多个行为；本图展示访问路径，非单个用户记录。真实数据需补充外部来源及访问会话记录，30 分钟无行为规则尚未应用于真实数据。</div>
  </div>
</template>

<style scoped>
.flow-page { color:#1f2c43; display:flex; flex-direction:column; gap:16px; }
.flow-heading { display:flex; justify-content:space-between; align-items:center; }
.flow-heading h1 { margin:0; font-size:23px; }.flow-heading p,.panel-top p { margin:7px 0 0; color:#77859b; font-size:13px; }
.demo-badge { border:1px solid #a9cdfb; color:#276be6; background:#eff6ff; padding:6px 12px; border-radius:5px; font-size:13px; }
.notice { padding:12px 16px; border-radius:6px; background:#eef5ff; border:1px solid #d6e8ff; color:#426488; font-size:13px; line-height:1.65; }
.flow-panel { background:#fff; border:1px solid #e4e8ef; border-radius:8px; overflow:hidden; }
.panel-top { min-height:70px; display:flex; justify-content:space-between; align-items:center; gap:12px; padding:16px 20px; border-bottom:1px solid #edf0f5; }.panel-top h2 { font-size:17px; margin:0; color:#286ce7; }.legend { white-space:nowrap; color:#79869b; font-size:12px; }.legend i { display:inline-block; width:9px; height:9px; border-radius:50%; background:#508af3; }.legend span { margin:0 8px; }
.graph-controls { display:flex; align-items:center; flex-wrap:wrap; gap:8px; justify-content:flex-end; }.graph-controls button { border:1px solid #d6e4f6; border-radius:5px; background:#fff; color:#276be6; font:inherit; font-size:12px; padding:7px 9px; cursor:pointer; white-space:nowrap; }.graph-controls button:hover { background:#eff6ff; }.graph-controls button:focus-visible { outline:2px solid #246fe5; outline-offset:2px; }
.flow-body { display:flex; min-width:0; }.canvas-scroll { flex:1; min-width:0; overflow-x:auto; overflow-y:hidden; padding:0 0 0 20px; }.graph { position:relative; height:740px; }.edges { position:absolute; top:0; left:0; pointer-events:none; }.column { position:absolute; top:0; width:164px; }.column-heading { height:95px; padding-top:23px; text-align:center; box-sizing:border-box; }.column-heading strong { display:block; font-size:15px; }.column-heading small { display:block; margin-top:5px; font-size:11px; color:#8793a5; white-space:nowrap; }.node-list { display:flex; flex-direction:column; gap:15px; }.node { width:164px; height:54px; display:flex; align-items:center; gap:8px; border:1px solid #e3eaf2; border-radius:7px; background:#f5f8fc; color:#293b56; padding:7px 9px; text-align:left; cursor:pointer; }.node:hover { border-color:#8db8ff; }.node.selected { border:1.5px solid #2876f1; background:#eef5ff; color:#1e61d2; box-shadow:0 2px 8px #246fe51a; }.node:focus-visible { outline:2px solid #246fe5; outline-offset:2px; }.node-icon { width:25px; height:25px; flex-shrink:0; display:grid; place-items:center; border-radius:6px; color:#7291b4; background:#e7eef7; font-size:18px; font-weight:bold; }.selected .node-icon { color:#fff; background:#2877ef; }.node-text { min-width:0; flex:1; }.node-text strong,.node-text small { display:block; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }.node-text strong { font-size:12px; }.node-text small { margin-top:3px; color:#74849b; font-size:10px; }.node-arrow { color:#99a8bb; font-size:18px; }.terminal .node-icon { color:#687c93; }
.path-panel { width:225px; flex-shrink:0; padding:20px 17px; background:#fbfcfe; border-left:1px solid #edf0f5; }.path-panel h3 { margin:0 0 15px; font-size:15px; }.path-box { background:#f1f6fd; border-radius:7px; padding:12px; max-height:375px; overflow:auto; }.path-item { display:flex; align-items:center; gap:10px; position:relative; padding:8px 0; }.path-item:not(:last-child):after { content:''; position:absolute; top:32px; left:12px; width:2px; height:20px; background:#97bdf9; }.path-dot { width:25px; height:25px; flex-shrink:0; display:grid; place-items:center; border-radius:50%; background:#2f77eb; color:#fff; font-size:11px; }.path-item small,.path-item strong { display:block; }.path-item small { color:#8292a8; font-size:10px; }.path-item strong { margin-top:3px; font-size:12px; }.path-info { margin:18px 0; display:flex; flex-direction:column; gap:5px; color:#77869a; font-size:12px; }.path-info strong { color:#243852; font-size:20px; }.path-info small { font-size:11px; }.path-action { width:100%; margin:0; }.collapse { margin:9px 0 0; }.hint { color:#95a2b2; font-size:11px; line-height:1.6; }.flow-footnote { color:#8390a3; font-size:12px; line-height:1.7; }
.column-background { position:absolute; top:90px; width:180px; height:635px; border-radius:7px; background:#f4f7fb; pointer-events:none; }.edges { z-index:1; }.column { z-index:2; }.node { background:#fff; }
@media(max-width:900px) { .flow-body { flex-direction:column; }.path-panel { width:auto; border-left:0; border-top:1px solid #edf0f5; }.legend { display:none; } }
</style>
