<script setup lang="ts">
import GuidanceButton from "../../components/GuidanceButton.vue";
import type { ChannelRow } from "../../api/types";
import { compareNullable, formatPercent } from "../../utils/channelMetrics";
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
    <el-table-column label="频道" prop="name" min-width="120" fixed />
    <el-table-column label="自主曝光" min-width="100" sortable :sort-method="by((x) => x.current.distribution.impressions)" class-name="own-col">
      <template #header>自主曝光 <GuidanceButton id="metric.entryExposure" label="自主曝光" /></template>
      <template #default="{ row }">{{ formatNumber(r(row).current.distribution.impressions) }}</template>
    </el-table-column>
    <el-table-column label="自主点击" min-width="100" sortable :sort-method="by((x) => x.current.distribution.clicks)" class-name="own-col">
      <template #default="{ row }">{{ formatNumber(r(row).current.distribution.clicks) }}</template>
    </el-table-column>
    <el-table-column label="自主点击率" min-width="110" sortable :sort-method="by((x) => x.current.distribution.ctr)" class-name="own-col">
      <template #header>自主点击率 <GuidanceButton id="metric.entryCtr" label="自主点击率" /></template>
      <template #default="{ row }">{{ formatPercent(r(row).current.distribution.ctr) }}</template>
    </el-table-column>
    <el-table-column label="自主分发占比" min-width="120" sortable :sort-method="by((x) => x.current.content?.ownEntryShare)" class-name="own-col">
      <template #header>自主分发占比 <GuidanceButton id="metric.ownShare" label="自主分发占比" /></template>
      <template #default="{ row }">{{ formatPercent(r(row).current.content?.ownEntryShare) }}</template>
    </el-table-column>
    <el-table-column label="综合依赖度" min-width="110" sortable :sort-method="by((x) => x.current.content?.compositeDependency)">
      <template #header>综合依赖度 <GuidanceButton id="metric.compositeDependency" label="综合依赖度" /></template>
      <template #default="{ row }">{{ formatPercent(r(row).current.content?.compositeDependency) }}</template>
    </el-table-column>
    <el-table-column label="PV" min-width="120" sortable :sort-method="by((x) => x.current.content?.pv)">
      <template #default="{ row }">{{ formatNumber(r(row).current.content?.pv ?? 0) }}<DeltaText :current="r(row).current.content?.pv" :previous="r(row).previous.content?.pv" /></template>
    </el-table-column>
    <el-table-column label="UV" min-width="80" sortable :sort-method="by((x) => x.current.content?.uv)">
      <template #default="{ row }">{{ formatNumber(r(row).current.content?.uv ?? 0) }}</template>
    </el-table-column>
    <el-table-column label="互动率" min-width="90" sortable :sort-method="by((x) => x.current.content?.interactionRate)">
      <template #header>互动率 <GuidanceButton id="metric.interaction" label="互动率" /></template>
      <template #default="{ row }">{{ formatPercent(r(row).current.content?.interactionRate) }}</template>
    </el-table-column>
    <el-table-column label="发帖数" min-width="90" sortable :sort-method="by((x) => x.current.supply?.contents)">
      <template #default="{ row }">{{ r(row).current.supply?.contents ?? 0 }}</template>
    </el-table-column>
    <el-table-column label="作者数" min-width="80" sortable :sort-method="by((x) => x.current.supply?.authors)">
      <template #default="{ row }">{{ r(row).current.supply?.authors ?? 0 }}</template>
    </el-table-column>
    <el-table-column label="头部集中度" min-width="100" sortable :sort-method="by((x) => x.current.content?.top1Share)">
      <template #header>头部集中度 <GuidanceButton id="metric.concentration" label="头部集中度" /></template>
      <template #default="{ row }">{{ formatPercent(r(row).current.content?.top1Share) }}</template>
    </el-table-column>
    <el-table-column label="诊断" min-width="200">
      <template #default="{ row }"><DiagnosisTags :diagnoses="r(row).diagnoses" /></template>
    </el-table-column>
  </el-table>
</template>

<style>
/* 自主分发相关的列（自主曝光/点击/点击率/占比）用浅蓝底突出 */
.clickable-table .own-col {
  background: #f3f7ff;
}
</style>
