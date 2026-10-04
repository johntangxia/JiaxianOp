<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { BarChart, LineChart } from "echarts/charts";
import { GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsOption } from "echarts";

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

const props = withDefaults(defineProps<{ option: EChartsOption; height?: string }>(), { height: "360px" });

const container = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;
let observer: ResizeObserver | null = null;

onMounted(() => {
  if (!container.value) {
    return;
  }
  chart = echarts.init(container.value);
  chart.setOption(props.option as echarts.EChartsCoreOption);
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(container.value);
});

// 配置整体替换（不合并），避免切换数据后残留旧系列
watch(
  () => props.option,
  (option) => chart?.setOption(option as echarts.EChartsCoreOption, true),
);

onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <div ref="container" :style="{ width: '100%', height }"></div>
</template>
