<script setup lang="ts">
import { guidanceKey } from "../../guidance/context";
import { computed, inject, onMounted, ref, watch } from "vue";
import { fetchReaderRange } from "../../api/readers";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { parseIsoDate, toIsoDate } from "../../utils/dateRange";
import InterestsTab from "./InterestsTab.vue";
import LifecycleTab from "./LifecycleTab.vue";
import UserListTab, { type ListPreset } from "./UserListTab.vue";
import ValueTab from "./ValueTab.vue";

const initializing = ref(true);
const initError = ref<string | null>(null);
const noData = ref(false);
const asOf = ref<string | null>(null);
const dataEnd = ref<string | null>(null);
const tab = ref("lifecycle");
const guidance = inject(guidanceKey);
watch(tab, (value) => guidance?.setTab("users", value), { immediate: true, flush: "sync" });
// 生命周期、价值分层里点一行 → 切到用户列表并带上筛选；nonce 让同一个筛选能重复触发
const preset = ref<ListPreset>({ stage: null, value: null, nonce: 0 });

onMounted(async () => {
  try {
    const range = await fetchReaderRange();
    if (range.to) {
      asOf.value = range.to;
      dataEnd.value = range.to;
    } else {
      noData.value = true;
    }
  } catch (e) {
    initError.value = e instanceof Error ? e.message : String(e);
  } finally {
    initializing.value = false;
  }
});

const picker = computed<Date | null>(() => (asOf.value ? parseIsoDate(asOf.value) : null));
function onPicker(value: Date | null): void {
  if (value) {
    asOf.value = toIsoDate(value);
  }
}
function disabledDate(date: Date): boolean {
  return dataEnd.value !== null && date > parseIsoDate(dataEnd.value);
}

function openList(next: { stage?: string; value?: string }): void {
  preset.value = { stage: next.stage ?? null, value: next.value ?? null, nonce: preset.value.nonce + 1 };
  tab.value = "list";
}
</script>

<template>
  <div class="page">
    <AsyncBlock :loading="initializing" :error="initError" :empty="noData" empty-text="暂无数据，请先在后台回填并重算汇总">
      <div v-if="asOf" class="page">
        <div class="panel bar">
          <span>统计日</span>
          <el-date-picker :model-value="picker" type="date" :clearable="false" :disabled-date="disabledDate" @update:model-value="onPicker" />
          <span class="muted">"活跃"指当天有任何行为；生命周期以统计日前 30 天为窗口，价值分层和阅读偏好看近 90 天</span>
        </div>

        <el-tabs v-model="tab">
          <el-tab-pane label="生命周期" name="lifecycle" lazy><LifecycleTab :as-of="asOf" @open-list="openList" /></el-tab-pane>
          <el-tab-pane label="价值分层" name="value" lazy><ValueTab :as-of="asOf" @open-list="openList" /></el-tab-pane>
          <el-tab-pane label="兴趣与跨领域" name="interests" lazy><InterestsTab :as-of="asOf" /></el-tab-pane>
          <el-tab-pane label="用户列表" name="list" lazy><UserListTab :as-of="asOf" :preset="preset" /></el-tab-pane>
        </el-tabs>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
