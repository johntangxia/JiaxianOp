<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { fetchChannels, fetchPvRange, fetchTrend } from "../api/analytics";
import type { ChannelOption, PvRange } from "../api/types";
import AsyncBlock from "../components/AsyncBlock.vue";
import { useAsync } from "../composables/useAsync";
import { defaultRange } from "../utils/dateRange";
import { deriveKpis } from "../utils/kpi";
import KpiCards from "./overview/KpiCards.vue";
import PvFilterBar from "./overview/PvFilterBar.vue";
import TopPanel from "./overview/TopPanel.vue";
import TrendPanel from "./overview/TrendPanel.vue";
import { filterOf, type OverviewQuery } from "./overview/types";

const pvRange = ref<PvRange | null>(null);
const channels = ref<ChannelOption[]>([]);
const query = ref<OverviewQuery | null>(null);
const initializing = ref(true);
const initError = ref<string | null>(null);
const noData = ref(false);

const trend = useAsync((q: OverviewQuery) =>
  fetchTrend({
    granularity: q.granularity,
    from: q.range.from,
    to: q.range.to,
    forecastPeriods: q.forecastPeriods,
    ...filterOf(q),
  }),
);

onMounted(async () => {
  try {
    const [range, channelList] = await Promise.all([fetchPvRange(), fetchChannels()]);
    channels.value = channelList;
    if (!range.from || !range.to) {
      noData.value = true;
    } else {
      pvRange.value = range;
      query.value = { granularity: "MONTH", range: defaultRange("MONTH", range), forecastPeriods: 3 };
    }
  } catch (e) {
    initError.value = e instanceof Error ? e.message : String(e);
  } finally {
    initializing.value = false;
  }
});

// 查询条件任何变化（包括初始化时第一次赋值）都重新取趋势
watch(
  query,
  (q) => {
    if (q) {
      void trend.run(q);
    }
  },
  { deep: true },
);

const kpis = computed(() => (trend.data.value ? deriveKpis(trend.data.value) : null));
</script>

<template>
  <div class="page">
    <AsyncBlock
      :loading="initializing"
      :error="initError"
      :empty="noData"
      empty-text="暂无 PV 数据，请先在后台回填并重算汇总"
    >
      <div v-if="query" class="page">
        <PvFilterBar v-model="query" :pv-range="pvRange" :channels="channels" />

        <AsyncBlock :loading="trend.loading.value" :error="trend.error.value" :empty="false">
          <div v-if="trend.data.value && kpis" class="page">
            <KpiCards :kpis="kpis" :granularity="trend.data.value.granularity" />
            <TrendPanel :trend="trend.data.value" />
          </div>
        </AsyncBlock>

        <TopPanel :query="query" />
      </div>
    </AsyncBlock>
  </div>
</template>
