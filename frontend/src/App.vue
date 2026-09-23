<script setup lang="ts">
import { ref, onMounted } from "vue";
import { fetchHealth } from "./api/health";

const status = ref("检查中...");

onMounted(async () => {
  try {
    status.value = await fetchHealth();
  } catch (e) {
    status.value = "后端不可用";
  }
});
</script>

<template>
  <main>
    <h1>稼先社区运营分析看板</h1>
    <p>后端状态：{{ status }}</p>
  </main>
</template>
