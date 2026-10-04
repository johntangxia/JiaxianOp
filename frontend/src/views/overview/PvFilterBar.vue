<script setup lang="ts">
import { computed } from "vue";
import { searchAccounts, searchEmployees } from "../../api/analytics";
import type { ChannelOption, ContentType, Granularity, PvRange } from "../../api/types";
import RemoteSelect from "../../components/RemoteSelect.vue";
import { defaultRange, pickerType, pickerValue, rangeFromPicker } from "../../utils/dateRange";
import type { OverviewQuery } from "./types";

const props = defineProps<{ modelValue: OverviewQuery; pvRange: PvRange | null; channels: ChannelOption[] }>();
const emit = defineEmits<{ "update:modelValue": [value: OverviewQuery] }>();

const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
  { value: "YEAR", label: "年" },
];

// el-select 会把空字符串当成"未选择"，所以"全部类型"用哨兵值 ALL
const contentTypes: Array<{ value: ContentType | "ALL"; label: string }> = [
  { value: "ALL", label: "全部类型" },
  { value: "ARTICLE", label: "文章" },
  { value: "VIDEO", label: "视频" },
];

function patch(changes: Partial<OverviewQuery>): void {
  emit("update:modelValue", { ...props.modelValue, ...changes });
}

// 切换粒度时把范围重置为该粒度的默认范围
function onGranularity(value: string | number | boolean | undefined): void {
  const granularity = value as Granularity;
  patch({ granularity, range: defaultRange(granularity, props.pvRange) });
}

const picker = computed(() => pickerValue(props.modelValue.granularity, props.modelValue.range));

function onPicker(value: [Date, Date] | null): void {
  if (value) {
    patch({ range: rangeFromPicker(props.modelValue.granularity, value) });
  }
}

function onChannel(value: number | undefined): void {
  patch({ channelId: value });
}

function onContentType(value: ContentType | "ALL"): void {
  patch({ contentType: value === "ALL" ? undefined : value });
}

function onForecast(value: number | undefined): void {
  patch({ forecastPeriods: value ?? 0 });
}
</script>

<template>
  <div class="panel filter-bar">
    <el-radio-group :model-value="modelValue.granularity" @update:model-value="onGranularity">
      <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
    </el-radio-group>

    <el-date-picker
      :model-value="picker"
      :type="pickerType(modelValue.granularity)"
      :clearable="false"
      range-separator="至"
      start-placeholder="开始"
      end-placeholder="结束"
      @update:model-value="onPicker"
    />

    <el-select
      :model-value="modelValue.channelId"
      clearable
      filterable
      placeholder="全部频道"
      style="width: 150px"
      @update:model-value="onChannel"
    >
      <el-option v-for="c in channels" :key="c.id" :label="c.name" :value="c.id" />
    </el-select>

    <RemoteSelect
      :model-value="modelValue.accountId"
      :fetcher="searchAccounts"
      placeholder="全部账号"
      style="width: 160px"
      @update:model-value="patch({ accountId: $event })"
    />

    <RemoteSelect
      :model-value="modelValue.employeeId"
      :fetcher="searchEmployees"
      placeholder="全部博主"
      style="width: 160px"
      @update:model-value="patch({ employeeId: $event })"
    />

    <el-select
      :model-value="modelValue.contentType ?? 'ALL'"
      style="width: 120px"
      @update:model-value="onContentType"
    >
      <el-option v-for="t in contentTypes" :key="t.value" :label="t.label" :value="t.value" />
    </el-select>

    <span class="forecast">
      预测
      <el-input-number
        :model-value="modelValue.forecastPeriods"
        :min="0"
        :max="12"
        size="small"
        @update:model-value="onForecast"
      />
      期
    </span>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.forecast {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-muted);
  font-size: 13px;
}
</style>
