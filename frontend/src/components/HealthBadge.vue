<script setup lang="ts">
import { onMounted, ref } from "vue";
import { fetchHealth } from "../api/health";

const status = ref<"checking" | "ok" | "down">("checking");

onMounted(async () => {
  try {
    await fetchHealth();
    status.value = "ok";
  } catch {
    status.value = "down";
  }
});
</script>

<template>
  <el-tag :type="status === 'ok' ? 'success' : status === 'down' ? 'danger' : 'info'" size="small">
    后端{{ status === "ok" ? "正常" : status === "down" ? "不可用" : "检查中" }}
  </el-tag>
</template>
