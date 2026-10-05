<script setup lang="ts">
import { computed } from "vue";
import type { ContestEntry } from "../../api/types";
import { formatScore, medalClass, rankMove, scoreChangeText, scoreColor } from "../../utils/contest";

const props = defineProps<{ entries: ContestEntry[] }>();
const emit = defineEmits<{ open: [entry: ContestEntry] }>();

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): ContestEntry => row as ContestEntry;

// 维度列取自第一行（同一个榜里所有行的维度相同）
const dimensionLabels = computed(() => props.entries[0]?.dimensions.map((d) => d.label) ?? []);
const dimensionCount = (label: string) => label;
function score(entry: ContestEntry, label: string): number {
  return entry.dimensions.find((d) => d.label === label)?.score ?? 0;
}
function weight(entry: ContestEntry, label: string): number {
  return entry.dimensions.find((d) => d.label === label)?.weight ?? 0;
}
function moveClass(entry: ContestEntry): string {
  const kind = rankMove(entry.rankChange).kind;
  return kind === "up" ? "rate-up" : kind === "down" ? "rate-down" : "rate-none";
}
</script>

<template>
  <el-table :data="entries" row-key="channelId" row-class-name="clickable-row" @row-click="(row: unknown) => emit('open', r(row))">
    <el-table-column label="名次" width="76">
      <template #default="{ row }"><span class="rank" :class="medalClass(r(row).rank)">{{ r(row).rank }}</span></template>
    </el-table-column>
    <el-table-column label="变化" width="76">
      <template #default="{ row }"><span :class="moveClass(r(row))">{{ rankMove(r(row).rankChange).text }}</span></template>
    </el-table-column>
    <el-table-column label="频道" prop="name" min-width="120" />
    <el-table-column label="健康分" min-width="120">
      <template #default="{ row }">
        <strong>{{ formatScore(r(row).score) }}</strong>
        <span class="muted">　{{ scoreChangeText(r(row).scoreChange) }}</span>
      </template>
    </el-table-column>
    <el-table-column v-for="label in dimensionLabels" :key="dimensionCount(label)" min-width="110">
      <template #header>{{ label }}<span class="muted"> {{ Math.round(weight(entries[0], label) * 100) }}%</span></template>
      <template #default="{ row }">
        <el-tooltip :content="`${label}：${formatScore(score(r(row), label))} 分（权重 ${Math.round(weight(r(row), label) * 100)}%）`" placement="top">
          <el-progress :percentage="score(r(row), label)" :stroke-width="8" :show-text="false" :color="scoreColor(score(r(row), label))" />
        </el-tooltip>
      </template>
    </el-table-column>
    <el-table-column label="最强项" min-width="90">
      <template #default="{ row }">{{ r(row).strongest ?? "—" }}</template>
    </el-table-column>
    <el-table-column label="最弱项" min-width="90">
      <template #default="{ row }">{{ r(row).weakest ?? "—" }}</template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.rank {
  display: inline-block;
  min-width: 26px;
  line-height: 26px;
  text-align: center;
  border-radius: 13px;
  font-weight: 600;
}
.medal-gold {
  background: #fff4cc;
  color: #a67c00;
}
.medal-silver {
  background: #eef1f6;
  color: #5b6573;
}
.medal-bronze {
  background: #fbe6d4;
  color: #9a5a22;
}
</style>
