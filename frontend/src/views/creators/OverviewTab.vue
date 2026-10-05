<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchCreatorOverview } from "../../api/people";
import type { Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildCreatorTrendOption } from "../../charts/peopleOption";
import { useAsync } from "../../composables/useAsync";
import { changeRate, formatPercent } from "../../utils/channelMetrics";
import type { Range } from "../../utils/drill";
import { formatNumber, formatRate, rateClass } from "../../utils/format";
import { cohortLabel, heatCell, periodHeader } from "../../utils/readers";

const props = defineProps<{ range: Range }>();

const granularity = ref<Granularity>("MONTH");
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
];

const report = useAsync((from: string, to: string, g: Granularity) => fetchCreatorOverview(from, to, g));
watch([() => props.range, granularity], () => void report.run(props.range.from, props.range.to, granularity.value), { deep: true, immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildCreatorTrendOption(data.value.series, data.value.granularity) : {}));
const headers = computed(() => Array.from({ length: data.value?.periods ?? 0 }, (_, i) => periodHeader(i, "MONTH")));
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="false">
    <div v-if="data" class="page">
      <div class="kpis">
        <div class="panel kpi">
          <div class="label">创作者数</div>
          <div class="value">{{ formatNumber(data.summary.creators) }}</div>
          <div class="sub" :class="rateClass(changeRate(data.summary.creators, data.summary.previousCreators))">较上期 {{ formatRate(changeRate(data.summary.creators, data.summary.previousCreators)) }}</div>
        </div>
        <div class="panel kpi">
          <div class="label">占成员比</div>
          <div class="value">{{ formatPercent(data.summary.creatorShare) }}</div>
        </div>
        <div class="panel kpi">
          <div class="label">新增创作者</div>
          <div class="value">{{ formatNumber(data.summary.newCreators) }}</div>
          <div class="sub muted">历史上第一次发帖落在本区间</div>
        </div>
        <div class="panel kpi">
          <div class="label">流失 / 回流</div>
          <div class="value">{{ formatNumber(data.summary.lostCreators) }} / {{ formatNumber(data.summary.returningCreators) }}</div>
          <div class="sub muted">上期发过本期没发 / 隔期回来</div>
        </div>
        <div class="panel kpi">
          <div class="label">发帖数</div>
          <div class="value">{{ formatNumber(data.summary.posts) }}</div>
          <div class="sub" :class="rateClass(changeRate(data.summary.posts, data.summary.previousPosts))">较上期 {{ formatRate(changeRate(data.summary.posts, data.summary.previousPosts)) }}</div>
        </div>
      </div>

      <div class="panel">
        <div class="head">
          <h3 class="panel-title">创作者趋势 <span class="muted">（活跃创作者 = 周期内发过帖的去重人数；人均发帖 = 发帖数 / 活跃创作者）</span></h3>
          <el-radio-group v-model="granularity" size="small">
            <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </div>
        <BaseChart :option="option" height="320px" />
      </div>

      <div class="panel">
        <h3 class="panel-title">创作者留存同期群 <span class="muted">（按首次发帖月分组；格子 = 之后第 k 个月里至少又发过一次帖的人占比）</span></h3>
        <el-empty v-if="data.cohorts.length === 0" description="所选区间内没有首次发帖的创作者" :image-size="60" />
        <div v-else class="scroll">
          <table class="heat">
            <thead>
              <tr>
                <th>首次发帖月</th>
                <th>人数</th>
                <th v-for="header in headers" :key="header">{{ header.replace("加入当月", "首发当月") }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="cohort in data.cohorts" :key="cohort.cohortStart">
                <td class="label-cell">{{ cohortLabel(cohort.cohortStart, "MONTH") }}</td>
                <td>{{ cohort.size }}</td>
                <td v-for="(value, i) in cohort.retention" :key="i" :style="{ background: heatCell(value).background, color: heatCell(value).color }">
                  {{ heatCell(value).text }}
                </td>
              </tr>
            </tbody>
          </table>
          <p class="muted">"—"表示这个月还没有被数据完整覆盖（数据最后一天是 {{ data.dataEnd }}）；历史开始前就发过帖的老创作者，首次发帖日取历史里最早的一次，不在区间内的不进同期群。</p>
        </div>
      </div>
    </div>
  </AsyncBlock>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}
.label {
  color: var(--color-text-muted);
  font-size: 13px;
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
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
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
  min-width: 64px;
}
.heat td.label-cell {
  text-align: left;
  white-space: nowrap;
}
</style>
