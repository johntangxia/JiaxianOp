<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchPvRange } from "../../api/analytics";
import { fetchCockpit } from "../../api/channels";
import type { ChannelRow, PvRange } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { defaultCockpitRange } from "../../utils/channelMetrics";
import { parseIsoDate, toIsoDate, type DateRange } from "../../utils/dateRange";
import CompositeTable from "./CompositeTable.vue";
import DistributionCompare from "./DistributionCompare.vue";
import DomainTable from "./DomainTable.vue";

const router = useRouter();

const initializing = ref(true);
const initError = ref<string | null>(null);
const noData = ref(false);
const range = ref<DateRange | null>(null);
const cockpit = useAsync((r: DateRange) => fetchCockpit(r.from, r.to));

onMounted(async () => {
  try {
    const pvRange: PvRange = await fetchPvRange();
    const initial = defaultCockpitRange(pvRange);
    if (initial) {
      range.value = initial;
    } else {
      noData.value = true;
    }
  } catch (e) {
    initError.value = e instanceof Error ? e.message : String(e);
  } finally {
    initializing.value = false;
  }
});

watch(range, (value) => value && void cockpit.run(value), { deep: true });

const picker = computed<[Date, Date] | null>(() =>
  range.value ? [parseIsoDate(range.value.from), parseIsoDate(range.value.to)] : null,
);

function onPicker(value: [Date, Date] | null): void {
  if (value) {
    range.value = { from: toIsoDate(value[0]), to: toIsoDate(value[1]) };
  }
}

function open(row: ChannelRow): void {
  if (range.value) {
    void router.push({ name: "channel-detail", params: { id: row.id }, query: { ...range.value } });
  }
}

const data = computed(() => cockpit.data.value);
</script>

<template>
  <div class="page">
    <AsyncBlock :loading="initializing" :error="initError" :empty="noData" empty-text="暂无 PV 数据，请先在后台回填并重算汇总">
      <div v-if="range" class="page">
        <div class="panel bar">
          <el-date-picker
            :model-value="picker"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            @update:model-value="onPicker"
          />
          <span v-if="data" class="muted">环比对比 {{ data.previousFrom }} ~ {{ data.previousTo }}（上一个等长周期）</span>
        </div>

        <AsyncBlock :loading="cockpit.loading.value" :error="cockpit.error.value" :empty="false">
          <div v-if="data" class="page">
            <DistributionCompare :composite="data.composite" :domain="data.domain" />

            <div class="panel">
              <h3 class="panel-title">综合频道 · 分发</h3>
              <CompositeTable :rows="data.composite" @open="open" />
            </div>

            <div class="panel">
              <h3 class="panel-title">领域频道 · 自主分发与内容表现 <span class="muted">（蓝底列是领域频道自己入口的分发；点击一行看详情）</span></h3>
              <DomainTable :rows="data.domain" @open="open" />
            </div>
          </div>
        </AsyncBlock>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 16px;
}
</style>
