<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchTop } from "../../api/analytics";
import type { PvDimension } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildTopBarOption } from "../../charts/topBarOption";
import { useAsync } from "../../composables/useAsync";
import { filterOf, type OverviewQuery } from "./types";

const props = defineProps<{ query: OverviewQuery }>();

const dimension = ref<PvDimension>("CHANNEL");
const limit = ref(10);

const dimensions: Array<{ value: PvDimension; label: string }> = [
  { value: "CHANNEL", label: "频道" },
  { value: "ACCOUNT", label: "账号" },
  { value: "EMPLOYEE", label: "博主" },
  { value: "CONTENT_TYPE", label: "内容类型" },
];

const top = useAsync((query: OverviewQuery, dim: PvDimension, count: number) =>
  fetchTop({ dimension: dim, from: query.range.from, to: query.range.to, limit: count, ...filterOf(query) }),
);

// Top N 按实际日期范围统计（不做周期扩展），筛选条件和趋势图一致
watch(
  [() => props.query, dimension, limit],
  () => void top.run(props.query, dimension.value, limit.value),
  { deep: true, immediate: true },
);

const entries = computed(() => top.data.value ?? []);
const option = computed(() => buildTopBarOption(entries.value));
const chartHeight = computed(() => `${Math.max(entries.value.length, 3) * 34 + 24}px`);
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">PV Top {{ limit }}</h3>
      <div class="controls">
        <el-radio-group v-model="dimension" size="small">
          <el-radio-button v-for="d in dimensions" :key="d.value" :value="d.value">{{ d.label }}</el-radio-button>
        </el-radio-group>
        <el-select v-model="limit" size="small" style="width: 90px">
          <el-option v-for="n in [5, 10, 20]" :key="n" :label="`前 ${n}`" :value="n" />
        </el-select>
      </div>
    </div>

    <AsyncBlock :loading="top.loading.value" :error="top.error.value" :empty="top.data.value !== null && entries.length === 0">
      <BaseChart :option="option" :height="chartHeight" />
    </AsyncBlock>

    <p v-if="dimension === 'EMPLOYEE'" class="muted">
      合著作品每位作者都记完整 PV，所以各博主的 PV 之和会大于全站总数。
    </p>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.controls {
  display: flex;
  gap: 8px;
}
</style>
