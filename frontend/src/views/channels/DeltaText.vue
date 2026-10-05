<script setup lang="ts">
import { computed } from "vue";
import { changeRate } from "../../utils/channelMetrics";
import { formatRate, rateClass } from "../../utils/format";

const props = defineProps<{ current: number | null | undefined; previous: number | null | undefined }>();
const rate = computed(() => changeRate(props.current, props.previous));
</script>

<template>
  <span class="delta" :class="rateClass(rate)" title="相比上一等长周期">{{ formatRate(rate) }}</span>
</template>

<style scoped>
.delta {
  font-size: 12px;
  margin-left: 4px;
}
</style>
