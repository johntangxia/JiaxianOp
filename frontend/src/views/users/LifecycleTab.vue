<script setup lang="ts">
import { computed, watch } from "vue";
import { fetchUserLifecycle } from "../../api/people";
import type { UserLifecycleStageRow, UserTransitionRow } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildLifecycleOption } from "../../charts/peopleOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import { formatNumber } from "../../utils/format";
import { lifecycleColor, matrixCell, stayed } from "../../utils/people";

const props = defineProps<{ asOf: string }>();
const emit = defineEmits<{ (e: "open-list", value: { stage: string }): void }>();

const report = useAsync((asOf: string) => fetchUserLifecycle(asOf));
watch(() => props.asOf, () => void report.run(props.asOf), { immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildLifecycleOption(data.value.stages) : {}));

const s = (row: unknown): UserLifecycleStageRow => row as UserLifecycleStageRow;
const t = (row: unknown): UserTransitionRow => row as UserTransitionRow;
function change(row: UserLifecycleStageRow): string {
  const delta = row.count - row.previousCount;
  return delta > 0 ? `+${delta}` : String(delta);
}
function deltaClass(row: UserLifecycleStageRow): string {
  const delta = row.count - row.previousCount;
  return delta === 0 ? "rate-none" : delta > 0 ? "rate-up" : "rate-down";
}
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.members === 0" empty-text="统计日还没有成员">
    <div v-if="data" class="page">
      <div class="kpis">
        <div v-for="stage in data.stages" :key="stage.stage" class="panel kpi clickable" @click="emit('open-list', { stage: stage.stage })">
          <div class="label"><span class="dot" :style="{ background: lifecycleColor(stage.stage) }" />{{ stage.label }}</div>
          <div class="value">{{ formatNumber(stage.count) }}</div>
          <div class="sub muted">{{ formatPercent(stage.share) }} · 较 30 天前 <span :class="deltaClass(stage)">{{ change(stage) }}</span></div>
        </div>
      </div>

      <div class="panel">
        <h3 class="panel-title">各阶段人数：{{ data.asOf }} vs {{ data.previousAsOf }} <span class="muted">（{{ data.members }} 名成员；上一时点 {{ data.previousMembers }} 名；点上面的卡片可看该阶段的用户）</span></h3>
        <BaseChart :option="option" height="300px" />
        <p class="muted">
          新成员：最近 30 天加入；活跃：近 30 天和再前 30 天都有行为；回流：近 30 天有行为、再前 30 天没有（含加入后首次被激活）；
          沉默：近 30 天没行为但再前 30 天有；流失：连续 60 天没行为但更早有过；未激活：加入已超过 30 天却从没有过任何行为。
        </p>
      </div>

      <div class="panel">
        <h3 class="panel-title">流转矩阵 <span class="muted">（行 = 30 天前所处的阶段，列 = 现在；格子 = 人数和占该行的比例，对角线是留在原阶段的人）</span></h3>
        <div class="scroll">
          <table class="heat">
            <thead>
              <tr>
                <th>30 天前 \ 现在</th>
                <th v-for="stage in data.stages" :key="stage.stage">{{ stage.label }}</th>
                <th>合计</th>
                <th>留在原阶段</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in data.transitions" :key="row.from ?? 'NONE'">
                <td class="row-label">{{ row.fromLabel }}</td>
                <td v-for="cell in row.to" :key="cell.stage" :style="{ background: matrixCell(cell.count, row.total).background, color: matrixCell(cell.count, row.total).color }">
                  {{ matrixCell(cell.count, row.total).text }}
                </td>
                <td>{{ row.total }}</td>
                <td>{{ stayed(t(row)) === null || row.total === 0 ? "—" : formatPercent(stayed(t(row))! / row.total, 0) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="muted">"此前非成员"一行是最近 30 天新加入社区的人；每行合计 = 30 天前该阶段的人数。关注"沉默 → 活跃/回流"（唤回）和"活跃 → 沉默"（流失风险）两格。</p>
        <el-table :data="data.stages" size="small">
          <el-table-column label="阶段" min-width="100"><template #default="{ row }">{{ s(row).label }}</template></el-table-column>
          <el-table-column label="现在" prop="count" min-width="80" />
          <el-table-column label="30 天前" prop="previousCount" min-width="80" />
          <el-table-column label="变化" min-width="80"><template #default="{ row }"><span :class="deltaClass(s(row))">{{ change(s(row)) }}</span></template></el-table-column>
          <el-table-column label="占成员" min-width="90"><template #default="{ row }">{{ formatPercent(s(row).share) }}</template></el-table-column>
        </el-table>
      </div>
    </div>
  </AsyncBlock>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
}
.clickable {
  cursor: pointer;
}
.label {
  color: var(--color-text-muted);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.value {
  margin-top: 8px;
  font-size: 26px;
  font-weight: 600;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
}
.scroll {
  overflow-x: auto;
}
.heat {
  border-collapse: separate;
  border-spacing: 3px;
  width: 100%;
}
.heat th {
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-muted);
  text-align: center;
  white-space: nowrap;
}
.heat td {
  padding: 8px 10px;
  text-align: center;
  border-radius: 4px;
  min-width: 70px;
  font-size: 13px;
}
.heat td.row-label {
  text-align: left;
  white-space: nowrap;
  background: transparent;
  color: inherit;
}
</style>
