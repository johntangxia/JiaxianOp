<script setup lang="ts">
import type { RouteLocationRaw } from "vue-router";

defineProps<{ items: Array<{ label: string; to?: RouteLocationRaw }> }>();
</script>

<template>
  <el-breadcrumb separator="›" class="crumbs">
    <el-breadcrumb-item v-for="(item, index) in items" :key="index" :to="item.to">{{ item.label }}</el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>
.crumbs {
  padding: 4px 2px;
}
</style>
