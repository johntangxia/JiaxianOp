<script setup lang="ts">
import { computed } from "vue";
import type { DrillTrendPoint, Granularity, MixSlice } from "../../api/types";
import BaseChart from "../../components/BaseChart.vue";
import { buildChannelTrendOption } from "../../charts/channelTrendOption";
import { buildMixPieOption } from "../../charts/mixPieOption";

const props = defineProps<{ trend: DrillTrendPoint[]; granularity: Granularity; composition: MixSlice[] }>();

const trendOption = computed(() => buildChannelTrendOption(props.trend, props.granularity, false));
const pieOption = computed(() => buildMixPieOption(props.composition));
</script>

<template>
  <div class="grid">
    <div class="panel">
      <h3 class="panel-title">PV 来源趋势与内容点击率</h3>
      <BaseChart :option="trendOption" height="320px" />
    </div>
    <div class="panel">
      <h3 class="panel-title">PV 来源构成</h3>
      <BaseChart :option="pieOption" height="320px" />
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}
</style>
