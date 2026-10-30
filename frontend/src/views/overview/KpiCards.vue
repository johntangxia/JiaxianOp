<script setup lang="ts">
import GuidanceButton from "../../components/GuidanceButton.vue";
import type { Granularity } from "../../api/types";
import { formatNumber, formatPeriod, formatRate, rateClass } from "../../utils/format";
import type { Kpis } from "../../utils/kpi";

defineProps<{ kpis: Kpis; granularity: Granularity }>();
</script>

<template>
  <div class="kpi-row">
    <div class="panel kpi">
      <div class="kpi-label">所选周期合计 PV <GuidanceButton id="metric.pv" label="浏览量 PV" /></div>
      <div class="kpi-value">{{ formatNumber(kpis.totalPv) }}</div>
    </div>

    <div class="panel kpi">
      <div class="kpi-label">
        最近已结束周期
        <span v-if="kpis.latest" class="muted">（{{ formatPeriod(kpis.latest.periodStart, granularity) }}）</span>
      </div>
      <template v-if="kpis.latest">
        <div class="kpi-value">{{ formatNumber(kpis.latest.pv) }}</div>
        <div class="kpi-sub">
          环比 <span :class="rateClass(kpis.latest.momRate)">{{ formatRate(kpis.latest.momRate) }}</span>
          　同比 <span :class="rateClass(kpis.latest.yoyRate)">{{ formatRate(kpis.latest.yoyRate) }}</span>
        </div>
      </template>
      <div v-else class="kpi-value rate-none">—</div>
    </div>

    <div class="panel kpi">
      <div class="kpi-label">
        下一期预测
        <span v-if="kpis.nextForecast" class="muted">（{{ formatPeriod(kpis.nextForecast.periodStart, granularity) }}）</span>
      </div>
      <template v-if="kpis.nextForecast">
        <div class="kpi-value forecast">{{ formatNumber(kpis.nextForecast.value) }}</div>
        <div class="kpi-sub">
          区间 {{ formatNumber(kpis.nextForecast.lower) }} ~ {{ formatNumber(kpis.nextForecast.upper) }}
        </div>
      </template>
      <div v-else class="kpi-value rate-none">—</div>
    </div>
  </div>
</template>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.kpi-label {
  color: var(--color-text-muted);
  font-size: 13px;
}
.kpi-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 600;
}
.kpi-value.forecast {
  color: var(--color-forecast);
}
.kpi-sub {
  margin-top: 6px;
  font-size: 13px;
}
</style>
