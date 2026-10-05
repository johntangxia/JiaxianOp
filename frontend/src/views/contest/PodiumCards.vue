<script setup lang="ts">
import type { ContestEntry } from "../../api/types";
import { formatScore, medalClass, rankMove, scoreChangeText } from "../../utils/contest";

defineProps<{ entries: ContestEntry[] }>();
const emit = defineEmits<{ open: [entry: ContestEntry] }>();
</script>

<template>
  <div class="podium">
    <div
      v-for="entry in entries.slice(0, 3)"
      :key="entry.channelId"
      class="panel card"
      :class="medalClass(entry.rank)"
      @click="emit('open', entry)"
    >
      <div class="rank">第 {{ entry.rank }} 名</div>
      <div class="name">{{ entry.name }}</div>
      <div class="score">{{ formatScore(entry.score) }}<small>分</small></div>
      <div class="sub">
        <span :class="{ 'rate-up': rankMove(entry.rankChange).kind === 'up', 'rate-down': rankMove(entry.rankChange).kind === 'down' }">
          {{ rankMove(entry.rankChange).text }}
        </span>
        <span class="muted">　分数 {{ scoreChangeText(entry.scoreChange) }}</span>
      </div>
      <div class="muted">最强：{{ entry.strongest ?? "—" }}　最弱：{{ entry.weakest ?? "—" }}</div>
    </div>
  </div>
</template>

<style scoped>
.podium {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}
.card {
  cursor: pointer;
  border-top-width: 4px;
}
.medal-gold {
  border-top-color: #f5b301;
}
.medal-silver {
  border-top-color: #aab4c4;
}
.medal-bronze {
  border-top-color: #c97b3a;
}
.rank {
  color: var(--color-text-muted);
  font-size: 13px;
}
.name {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
}
.score {
  margin-top: 8px;
  font-size: 32px;
  font-weight: 700;
  color: var(--color-primary);
}
.score small {
  margin-left: 2px;
  font-size: 14px;
  font-weight: 400;
}
.sub {
  margin: 6px 0;
}
</style>
