<script setup lang="ts">
import { computed } from "vue";
import type { PvTrend } from "../../api/types";
import BaseChart from "../../components/BaseChart.vue";
import { buildTrendOption } from "../../charts/trendOption";
import { formatNumber, formatRate, rateClass } from "../../utils/format";
import { buildTrendRows, type TrendRow } from "../../utils/trendTable";

const props = defineProps<{ trend: PvTrend }>();

const option = computed(() => buildTrendOption(props.trend));
const rows = computed(() => buildTrendRows(props.trend));

const kindText: Record<TrendRow["kind"], string> = {
  forecast: "预测",
  incomplete: "进行中",
  complete: "已结束",
};
const kindType: Record<TrendRow["kind"], "warning" | "primary" | "info"> = {
  forecast: "warning",
  incomplete: "primary",
  complete: "info",
};
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">PV 趋势</h3>
    <BaseChart :option="option" height="360px" />
    <p class="muted">实线为实际值，空心点表示进行中的周期（数据还不完整）；虚线和浅色带为预测值及置信区间。</p>

    <el-table :data="rows" size="small" max-height="360" row-key="key" style="margin-top: 12px">
      <el-table-column prop="label" label="周期" min-width="130" />
      <el-table-column label="PV" align="right" min-width="110">
        <template #default="{ row }">{{ formatNumber(row.pv) }}</template>
      </el-table-column>
      <el-table-column label="环比" align="right" min-width="90">
        <template #default="{ row }">
          <span :class="rateClass(row.momRate)">{{ formatRate(row.momRate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="同比" align="right" min-width="90">
        <template #default="{ row }">
          <span :class="rateClass(row.yoyRate)">{{ formatRate(row.yoyRate) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="置信区间" align="right" min-width="130">
        <template #default="{ row }">{{ row.range ?? "—" }}</template>
      </el-table-column>
      <el-table-column label="状态" align="center" min-width="90">
        <template #default="{ row }">
          <el-tag :type="kindType[row.kind as TrendRow['kind']]" size="small" effect="plain">
            {{ kindText[row.kind as TrendRow["kind"]] }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
