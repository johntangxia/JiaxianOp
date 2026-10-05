<script setup lang="ts">
import { computed } from "vue";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { useRoute } from "vue-router";
import HealthBadge from "./components/HealthBadge.vue";

const route = useRoute();
// 详情页高亮对应的一级菜单
const activeMenu = computed(() => {
  if (route.path.startsWith("/kol")) return "/kol";
  if (route.path.startsWith("/channels") || route.path.startsWith("/accounts") || route.path.startsWith("/contents")) return "/channels";
  if (route.path.startsWith("/bloggers")) return "/channels";
  if (route.path.startsWith("/users")) return "/users";
  return route.path;
});
</script>

<template>
  <el-config-provider :locale="zhCn">
    <el-container class="app-shell">
      <el-aside width="200px" class="app-aside">
        <div class="brand">稼先社区<br /><small>运营分析看板</small></div>
        <el-menu :default-active="activeMenu" :default-openeds="['admin']" router class="app-menu">
          <el-menu-item index="/">总览看板</el-menu-item>
          <el-menu-item index="/channels">频道驾驶舱</el-menu-item>
          <el-menu-item index="/contest">频道竞赛榜</el-menu-item>
          <el-menu-item index="/compare">对比分析</el-menu-item>
          <el-menu-item index="/readers">读者分析</el-menu-item>
          <el-menu-item index="/creators">创作者分析</el-menu-item>
          <el-menu-item index="/users">用户分析</el-menu-item>
          <el-menu-item index="/growth">增长闭环</el-menu-item>
          <el-menu-item index="/leaderboard">积分排行榜</el-menu-item>
          <el-menu-item index="/kol">KOL 管理</el-menu-item>
          <el-sub-menu index="admin">
            <template #title>系统管理</template>
            <el-menu-item index="/admin/simulator">数据模拟器</el-menu-item>
            <el-menu-item index="/admin/points-config">积分权重</el-menu-item>
            <el-menu-item index="/admin/playbook">建议文案库</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="app-header">
          <span class="page-title">{{ route.meta.title }}</span>
          <HealthBadge />
        </el-header>
        <el-main>
          <router-view />
        </el-main>
      </el-container>
    </el-container>
  </el-config-provider>
</template>
