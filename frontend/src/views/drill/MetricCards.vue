<script setup lang="ts">
import type { SubjectMetrics } from "../../api/types";
import { formatPercent, formatSeconds } from "../../utils/channelMetrics";
import { formatNumber } from "../../utils/format";
import DeltaText from "../channels/DeltaText.vue";

defineProps<{ current: SubjectMetrics; previous: SubjectMetrics; showSupply?: boolean }>();
</script>

<template>
  <div class="kpis">
    <div class="panel kpi"><div class="label">PV</div><div class="value">{{ formatNumber(current.content.pv) }}<DeltaText :current="current.content.pv" :previous="previous.content.pv" /></div></div>
    <div class="panel kpi"><div class="label">UV</div><div class="value">{{ formatNumber(current.content.uv) }}<DeltaText :current="current.content.uv" :previous="previous.content.uv" /></div></div>
    <div class="panel kpi"><div class="label">互动率</div><div class="value">{{ formatPercent(current.content.interactionRate) }}</div><div class="sub">互动 {{ formatNumber(current.content.interactions) }}　上期 {{ formatPercent(previous.content.interactionRate) }}</div></div>
    <div class="panel kpi own"><div class="label">自主分发占比</div><div class="value">{{ formatPercent(current.content.ownEntryShare) }}</div><div class="sub">上期 {{ formatPercent(previous.content.ownEntryShare) }}　综合依赖 {{ formatPercent(current.content.compositeDependency) }}</div></div>
    <div v-if="showSupply" class="panel kpi"><div class="label">发帖 / 作者</div><div class="value">{{ current.supply.contents }} / {{ current.supply.authors }}</div><div class="sub">上期发帖 {{ previous.supply.contents }}</div></div>
    <div class="panel kpi"><div class="label">内容曝光 / 点击率</div><div class="value small">{{ formatNumber(current.exposure.impressions) }} / {{ formatPercent(current.exposure.ctr) }}</div><div class="sub">点击 {{ formatNumber(current.exposure.clicks) }}</div></div>
    <div class="panel kpi"><div class="label">平均停留 / 读完率</div><div class="value small">{{ formatSeconds(current.exposure.avgDwellSeconds) }} / {{ formatPercent(current.exposure.completionRate) }}</div></div>
    <div v-if="current.followers" class="panel kpi"><div class="label">关注者</div><div class="value">{{ formatNumber(current.followers.followers) }}</div><div class="sub">新增 {{ current.followers.newFollowers }}　取关 {{ current.followers.lostFollowers }}</div></div>
  </div>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}
.kpi.own {
  border-color: var(--color-primary);
}
.label {
  color: var(--color-text-muted);
  font-size: 13px;
}
.value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 600;
}
.value.small {
  font-size: 18px;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
