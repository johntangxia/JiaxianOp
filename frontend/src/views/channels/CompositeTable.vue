<script setup lang="ts">
import type { ChannelRow } from "../../api/types";
import { compareNullable, formatPercent, formatSeconds } from "../../utils/channelMetrics";
import { formatNumber } from "../../utils/format";
import DeltaText from "./DeltaText.vue";
import DiagnosisTags from "./DiagnosisTags.vue";

defineProps<{ rows: ChannelRow[] }>();
const emit = defineEmits<{ open: [row: ChannelRow] }>();

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): ChannelRow => row as ChannelRow;
const by = (pick: (row: ChannelRow) => number | null | undefined) => (a: unknown, b: unknown) =>
  compareNullable(pick(r(a)), pick(r(b)));
</script>

<template>
  <el-table :data="rows" row-key="id" class="clickable-table" row-class-name="clickable-row" @row-click="(row: unknown) => emit('open', r(row))">
    <el-table-column label="频道" prop="name" min-width="90" />
    <el-table-column label="曝光" min-width="110" sortable :sort-method="by((x) => x.current.distribution.impressions)">
      <template #default="{ row }">{{ formatNumber(r(row).current.distribution.impressions) }}<DeltaText :current="r(row).current.distribution.impressions" :previous="r(row).previous.distribution.impressions" /></template>
    </el-table-column>
    <el-table-column label="点击" min-width="110" sortable :sort-method="by((x) => x.current.distribution.clicks)">
      <template #default="{ row }">{{ formatNumber(r(row).current.distribution.clicks) }}<DeltaText :current="r(row).current.distribution.clicks" :previous="r(row).previous.distribution.clicks" /></template>
    </el-table-column>
    <el-table-column label="点击率" min-width="90" sortable :sort-method="by((x) => x.current.distribution.ctr)">
      <template #default="{ row }">{{ formatPercent(r(row).current.distribution.ctr) }}</template>
    </el-table-column>
    <el-table-column label="触达 UV" min-width="90" sortable :sort-method="by((x) => x.current.distribution.reachUv)">
      <template #default="{ row }">{{ formatNumber(r(row).current.distribution.reachUv) }}</template>
    </el-table-column>
    <el-table-column label="平均停留" min-width="100" sortable :sort-method="by((x) => x.current.distribution.avgDwellSeconds)">
      <template #default="{ row }">{{ formatSeconds(r(row).current.distribution.avgDwellSeconds) }}</template>
    </el-table-column>
    <el-table-column label="覆盖领域" min-width="90" sortable :sort-method="by((x) => x.current.coverage?.domainsCovered)">
      <template #default="{ row }">{{ r(row).current.coverage?.domainsCovered ?? 0 }}</template>
    </el-table-column>
    <el-table-column label="头部领域占比" min-width="110">
      <template #default="{ row }">{{ formatPercent(r(row).current.coverage?.topDomainShare) }}</template>
    </el-table-column>
    <el-table-column label="诊断" min-width="160">
      <template #default="{ row }"><DiagnosisTags :diagnoses="r(row).diagnoses" /></template>
    </el-table-column>
  </el-table>
</template>
