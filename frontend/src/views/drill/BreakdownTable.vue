<script setup lang="ts">
import type { BreakdownRow } from "../../api/types";
import { compareNullable, formatPercent } from "../../utils/channelMetrics";
import { formatNumber } from "../../utils/format";
import DeltaText from "../channels/DeltaText.vue";

defineProps<{ rows: BreakdownRow[]; nameLabel: string; extraLabel?: string; showFollowers?: boolean }>();
const emit = defineEmits<{ open: [row: BreakdownRow] }>();

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): BreakdownRow => row as BreakdownRow;
const by = (pick: (row: BreakdownRow) => number | null | undefined) => (a: unknown, b: unknown) =>
  compareNullable(pick(r(a)), pick(r(b)));
const extraText = (extra: string | null): string => (extra === "PERSONAL" ? "个人" : extra === "TEAM" ? "团队" : (extra ?? ""));
</script>

<template>
  <el-table :data="rows" row-key="id" row-class-name="clickable-row" size="small" @row-click="(row: unknown) => emit('open', r(row))">
    <el-table-column :label="nameLabel" prop="name" min-width="140" />
    <el-table-column v-if="extraLabel" :label="extraLabel" min-width="80"><template #default="{ row }">{{ extraText(r(row).extra) }}</template></el-table-column>
    <el-table-column label="PV" min-width="120" sortable :sort-method="by((x) => x.current.content.pv)">
      <template #default="{ row }">{{ formatNumber(r(row).current.content.pv) }}<DeltaText :current="r(row).current.content.pv" :previous="r(row).previous.content.pv" /></template>
    </el-table-column>
    <el-table-column label="UV" min-width="70" sortable :sort-method="by((x) => x.current.content.uv)"><template #default="{ row }">{{ formatNumber(r(row).current.content.uv) }}</template></el-table-column>
    <el-table-column label="互动率" min-width="80" sortable :sort-method="by((x) => x.current.content.interactionRate)"><template #default="{ row }">{{ formatPercent(r(row).current.content.interactionRate) }}</template></el-table-column>
    <el-table-column label="自主分发占比" min-width="110" sortable :sort-method="by((x) => x.current.content.ownEntryShare)"><template #default="{ row }">{{ formatPercent(r(row).current.content.ownEntryShare) }}</template></el-table-column>
    <el-table-column label="发帖数" min-width="80" sortable :sort-method="by((x) => x.current.supply.contents)"><template #default="{ row }">{{ r(row).current.supply.contents }}</template></el-table-column>
    <el-table-column label="内容点击率" min-width="100" sortable :sort-method="by((x) => x.current.exposure.ctr)"><template #default="{ row }">{{ formatPercent(r(row).current.exposure.ctr) }}</template></el-table-column>
    <el-table-column v-if="showFollowers" label="关注者" min-width="100" sortable :sort-method="by((x) => x.current.followers?.followers)">
      <template #default="{ row }">{{ r(row).current.followers?.followers ?? 0 }}<span class="muted">　+{{ r(row).current.followers?.newFollowers ?? 0 }}</span></template>
    </el-table-column>
  </el-table>
</template>
