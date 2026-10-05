<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchRetention } from "../../api/readers";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import type { Range } from "../../utils/drill";
import { cohortLabel, heatCell, isSmallCohort, MIN_COHORT_SIZE, periodHeader } from "../../utils/readers";

const props = defineProps<{ range: Range }>();

// 月同期群的人数更稳定，作为默认
const granularity = ref<"WEEK" | "MONTH">("MONTH");
const report = useAsync((g: "WEEK" | "MONTH", from: string, to: string) => fetchRetention(g, from, to));
watch([() => props.range, granularity], () => void report.run(granularity.value, props.range.from, props.range.to), { deep: true, immediate: true });

const data = computed(() => report.data.value);
const headers = computed(() => Array.from({ length: data.value?.periods ?? 0 }, (_, i) => periodHeader(i, granularity.value)));
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">新成员留存同期群 <span class="muted">（按加入{{ granularity === "MONTH" ? "月" : "周" }}分组；格子 = 加入后第 k 个周期里至少活跃一天的人占比）</span></h3>
      <el-radio-group v-model="granularity" size="small">
        <el-radio-button value="MONTH">按月</el-radio-button>
        <el-radio-button value="WEEK">按周</el-radio-button>
      </el-radio-group>
    </div>
    <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.cohorts.length === 0" empty-text="所选区间内没有新加入的成员">
      <div v-if="data && data.cohorts.length > 0" class="scroll">
        <table class="heat">
          <thead>
            <tr>
              <th>加入{{ granularity === "MONTH" ? "月" : "周" }}</th>
              <th>人数</th>
              <th v-for="header in headers" :key="header">{{ header }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="cohort in data.cohorts" :key="cohort.cohortStart" :class="{ small: isSmallCohort(cohort.size) }" :title="isSmallCohort(cohort.size) ? `人数不足 ${MIN_COHORT_SIZE} 人，比例会剧烈波动` : undefined">
              <td class="label">{{ cohortLabel(cohort.cohortStart, granularity) }}</td>
              <td>{{ cohort.size }}</td>
              <td v-for="(value, i) in cohort.retention" :key="i" :style="{ background: heatCell(value).background, color: heatCell(value).color }">
                {{ heatCell(value).text }}
              </td>
            </tr>
          </tbody>
        </table>
        <p class="muted">"—"表示这个周期还没有被数据完整覆盖（数据最后一天是 {{ data.dataEnd }}）。历史开始前就加入的老成员不在任何同期群里；人数少于 5 的同期群（整行置灰），留存率会因为一两个人而大幅波动，别据此下结论。</p>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.scroll {
  overflow-x: auto;
}
.heat {
  border-collapse: separate;
  border-spacing: 3px;
  width: 100%;
}
.heat th {
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-muted);
  text-align: center;
  white-space: nowrap;
}
.heat td {
  padding: 8px 10px;
  text-align: center;
  border-radius: 4px;
  min-width: 64px;
}
.heat td.label {
  text-align: left;
  white-space: nowrap;
}
.heat tr.small {
  opacity: 0.45;
}
</style>
