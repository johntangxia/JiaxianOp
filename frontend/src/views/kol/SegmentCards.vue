<script setup lang="ts">
import type { Segment } from "../../api/types";
import { SEGMENTS } from "../../utils/segment";

defineProps<{ counts: Record<Segment, number>; selected: Segment | undefined }>();
const emit = defineEmits<{ select: [segment: Segment | undefined] }>();
</script>

<template>
  <div class="segment-cards">
    <button
      v-for="s in SEGMENTS"
      :key="s.value"
      type="button"
      class="segment-card"
      :class="{ active: selected === s.value }"
      @click="emit('select', selected === s.value ? undefined : s.value)"
    >
      <el-tag :type="s.tagType" size="small" effect="plain">{{ s.name }}</el-tag>
      <span class="count">{{ counts[s.value] ?? 0 }}</span>
    </button>
  </div>
</template>

<style scoped>
.segment-cards {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
.segment-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
  text-align: left;
}
.segment-card:hover {
  border-color: var(--color-primary);
}
.segment-card.active {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(47, 111, 237, 0.18);
}
.count {
  font-size: 24px;
  font-weight: 600;
}
</style>
