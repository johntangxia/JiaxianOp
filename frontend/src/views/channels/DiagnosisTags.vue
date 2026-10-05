<script setup lang="ts">
import type { Diagnosis } from "../../api/types";
import { severityTagType } from "../../utils/channelMetrics";

defineProps<{ diagnoses: Diagnosis[] }>();

const labels: Record<string, string> = {
  NO_OWN_ENTRY_TRAFFIC: "无自主流量",
  LOW_OWN_ENTRY_SHARE: "自主分发偏低",
  HIGH_COMPOSITE_DEPENDENCY: "依赖综合频道",
  LOW_OWN_CTR: "自主点击率低",
  LOW_INTERACTION: "互动偏低",
  HIGH_CONCENTRATION: "流量集中",
  LOW_SUPPLY: "供给不足",
  PV_DROP: "PV 下滑",
  LOW_CTR: "点击率低",
  CLICK_DROP: "点击下滑",
  LOW_COVERAGE: "覆盖领域少",
};
</script>

<template>
  <span v-if="diagnoses.length === 0" class="muted">正常</span>
  <span v-else class="tags">
    <el-tooltip v-for="d in diagnoses" :key="d.code" :content="d.message" placement="top">
      <el-tag :type="severityTagType(d.severity)" size="small" effect="light">{{ labels[d.code] ?? d.code }}</el-tag>
    </el-tooltip>
  </span>
</template>

<style scoped>
.tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
