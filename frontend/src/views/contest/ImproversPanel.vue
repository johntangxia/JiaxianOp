<script setup lang="ts">
import type { ContestEntry } from "../../api/types";
import { formatScore, scoreChangeText } from "../../utils/contest";

defineProps<{ improvers: ContestEntry[]; previousHasData: boolean }>();
const emit = defineEmits<{ open: [entry: ContestEntry] }>();
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">进步榜 <span class="muted">（健康分提升最多）</span></h3>
    <p v-if="!previousHasData" class="muted">上一周期没有数据，暂时没法比较。</p>
    <p v-else-if="improvers.length === 0" class="muted">本期没有频道的健康分比上期提升。</p>
    <ol v-else class="list">
      <li v-for="entry in improvers" :key="entry.channelId" @click="emit('open', entry)">
        <span class="name">{{ entry.name }}</span>
        <span class="gain rate-up">{{ scoreChangeText(entry.scoreChange) }}</span>
        <span class="muted">现 {{ formatScore(entry.score) }}（第 {{ entry.rank }} 名）</span>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.list {
  margin: 0;
  padding-left: 20px;
  line-height: 2.2;
}
li {
  cursor: pointer;
}
.name {
  font-weight: 600;
}
.gain {
  margin: 0 8px;
}
</style>
