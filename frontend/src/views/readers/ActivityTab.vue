<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchReaderActivity } from "../../api/readers";
import type { Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildReaderActivityOption } from "../../charts/readerOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import { defaultGranularity, type Range } from "../../utils/drill";
import { formatNumber } from "../../utils/format";

const props = defineProps<{ range: Range }>();

const granularity = ref<Granularity>(defaultGranularity(props.range) === "WEEK" ? "WEEK" : "DAY");
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日（DAU）" },
  { value: "WEEK", label: "周（WAU）" },
  { value: "MONTH", label: "月（MAU）" },
];

const report = useAsync((from: string, to: string, g: Granularity) => fetchReaderActivity(from, to, g));
watch([() => props.range, granularity], () => void report.run(props.range.from, props.range.to, granularity.value), { deep: true, immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildReaderActivityOption(data.value.points, data.value.granularity) : {}));
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="false">
    <div v-if="data" class="page">
      <div class="kpis">
        <div class="panel kpi"><div class="label">DAU（{{ range.to }}）</div><div class="value">{{ formatNumber(data.summary.dau) }}</div></div>
        <div class="panel kpi"><div class="label">WAU（近 7 天）</div><div class="value">{{ formatNumber(data.summary.wau) }}</div></div>
        <div class="panel kpi"><div class="label">MAU（近 30 天）</div><div class="value">{{ formatNumber(data.summary.mau) }}</div></div>
        <div class="panel kpi"><div class="label">黏性（日均 DAU / MAU）</div><div class="value">{{ formatPercent(data.summary.stickiness) }}</div></div>
        <div class="panel kpi"><div class="label">成员总数</div><div class="value">{{ formatNumber(data.summary.members) }}</div></div>
      </div>
      <div class="panel">
        <div class="head">
          <h3 class="panel-title">活跃人数、新增成员与活跃率 <span class="muted">（活跃 = 当天有任何行为；活跃率 = 活跃人数 / 期末成员数）</span></h3>
          <el-radio-group v-model="granularity" size="small">
            <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </div>
        <BaseChart :option="option" height="340px" />
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
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
