<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchPvRange } from "../../api/analytics";
import type { PvRange } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { defaultCockpitRange } from "../../utils/channelMetrics";
import { parseIsoDate, toIsoDate, type DateRange } from "../../utils/dateRange";
import ActivityTab from "./ActivityTab.vue";
import FunnelTab from "./FunnelTab.vue";
import RetentionTab from "./RetentionTab.vue";
import SegmentsTab from "./SegmentsTab.vue";

const initializing = ref(true);
const initError = ref<string | null>(null);
const noData = ref(false);
const range = ref<DateRange | null>(null);
const pvRange = ref<PvRange | null>(null);
const tab = ref("activity");

onMounted(async () => {
  try {
    pvRange.value = await fetchPvRange();
    const initial = defaultCockpitRange(pvRange.value);
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

const picker = computed<[Date, Date] | null>(() =>
  range.value ? [parseIsoDate(range.value.from), parseIsoDate(range.value.to)] : null,
);
function onPicker(value: [Date, Date] | null): void {
  if (value) {
    range.value = { from: toIsoDate(value[0]), to: toIsoDate(value[1]) };
  }
}

// 快捷项：以 PV 数据最后一天为终点
const shortcuts = computed(() =>
  [30, 90, 180, 365].map((days) => ({
    text: days === 365 ? "近一年" : days === 180 ? "近半年" : `近 ${days} 天`,
    value: (): [Date, Date] => {
      const r = defaultCockpitRange(pvRange.value, days);
      return r ? [parseIsoDate(r.from), parseIsoDate(r.to)] : [new Date(), new Date()];
    },
  })),
);
</script>

<template>
  <div class="page">
    <AsyncBlock :loading="initializing" :error="initError" :empty="noData" empty-text="暂无数据，请先在后台回填并重算汇总">
      <div v-if="range" class="page">
        <div class="panel bar">
          <el-date-picker
            :model-value="picker"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            :shortcuts="shortcuts"
            @update:model-value="onPicker"
          />
          <span class="muted">"活跃"指当天有任何行为（登录、浏览、点赞、评论、转发、收藏、发帖）</span>
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="活跃与增长" name="activity" lazy><ActivityTab :range="range" /></el-tab-pane>
          <el-tab-pane label="留存同期群" name="retention" lazy><RetentionTab :range="range" /></el-tab-pane>
          <el-tab-pane label="激活漏斗" name="funnel" lazy><FunnelTab :range="range" /></el-tab-pane>
          <el-tab-pane label="读者分层与沉默读者" name="segments" lazy><SegmentsTab :range="range" /></el-tab-pane>
        </el-tabs>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
</style>
