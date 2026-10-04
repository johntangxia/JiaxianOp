<script setup lang="ts">
withDefaults(
  defineProps<{ loading: boolean; error: string | null; empty: boolean; emptyText?: string }>(),
  { emptyText: "暂无数据" },
);
</script>

<template>
  <div v-loading="loading" class="async-block">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-empty v-else-if="empty && !loading" :description="emptyText" />
    <slot v-else />
  </div>
</template>

<style scoped>
.async-block {
  min-height: 80px;
}
</style>
