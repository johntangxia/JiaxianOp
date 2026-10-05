<script setup lang="ts">
import { computed, watch } from "vue";
import { fetchUserValue } from "../../api/people";
import type { UserValueSegmentRow } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildValuePieOption } from "../../charts/peopleOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";

const props = defineProps<{ asOf: string }>();
const emit = defineEmits<{ (e: "open-list", value: { value: string }): void }>();

const report = useAsync((asOf: string) => fetchUserValue(asOf));
watch(() => props.asOf, () => void report.run(props.asOf), { immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildValuePieOption(data.value.segments) : {}));

const r = (row: unknown): UserValueSegmentRow => row as UserValueSegmentRow;
function fixed(value: number | null): string {
  return value === null ? "—" : value.toFixed(1);
}
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.members === 0" empty-text="统计日还没有成员">
    <div v-if="data" class="panel">
      <h3 class="panel-title">价值分层 <span class="muted">（{{ data.members }} 名成员；近 90 天 RFM，点一行看该层的用户）</span></h3>
      <div class="split">
        <BaseChart :option="option" height="260px" />
        <el-table :data="data.segments" size="small" row-class-name="clickable-row" @row-click="(row: unknown) => emit('open-list', { value: r(row).segment })">
          <el-table-column label="分层" prop="label" min-width="90" />
          <el-table-column label="人数" prop="count" min-width="70" />
          <el-table-column label="占成员" min-width="80"><template #default="{ row }">{{ formatPercent(r(row).share) }}</template></el-table-column>
          <el-table-column label="人均参与分" min-width="100"><template #default="{ row }">{{ fixed(r(row).avgEngagement) }}</template></el-table-column>
          <el-table-column label="人均活跃天数" min-width="110"><template #default="{ row }">{{ fixed(r(row).avgActiveDays) }}</template></el-table-column>
        </el-table>
      </div>
      <p class="muted">
        打分：R = 距最近一次行为的天数（越近越高）、F = 活跃天数、M = 参与分（浏览 + 3 × 互动 + 10 × 发帖），都按全员的百分位给 1–5 分，没有行为得 1 分。
        核心：M ≥ 4 且 R ≥ 4；活跃：M ≥ 3 且 R ≥ 3；待唤醒：R ≤ 2 且（F ≥ 3 或 M ≥ 3），曾经有价值、现在沉下去了，优先唤回；低活跃：R ≥ 3 且 M ≤ 2；其余沉睡。
        这是对 KOL 管理的补充：KOL 只看积分前 1000 名，这里覆盖全员。
      </p>
    </div>
  </AsyncBlock>
</template>

<style scoped>
.split {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
  align-items: center;
}
</style>
