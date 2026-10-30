<script setup lang="ts">
import { guidanceKey } from "../../guidance/context";
import { inject, ref, watch } from "vue";
import type { TaskDraft } from "../../utils/growth";
import AlertsTab from "./AlertsTab.vue";
import DepartmentsTab from "./DepartmentsTab.vue";
import HotContentTab from "./HotContentTab.vue";
import TaskDialog from "./TaskDialog.vue";
import TasksTab from "./TasksTab.vue";

const tab = ref("alerts");
const guidance = inject(guidanceKey);
watch(tab, (value) => guidance?.setTab("growth", value), { immediate: true, flush: "sync" });
const dialogVisible = ref(false);
const draft = ref<TaskDraft | null>(null);
// 创建任务后让"运营任务"标签页重新加载：用 key 重建组件
const tasksKey = ref(0);

function createTask(next: TaskDraft | null): void {
  draft.value = next;
  dialogVisible.value = true;
}

function onCreated(): void {
  tasksKey.value++;
  tab.value = "tasks";
}
</script>

<template>
  <div class="page">
    <el-tabs v-model="tab">
      <el-tab-pane label="告警中心" name="alerts" lazy><AlertsTab @create-task="createTask" /></el-tab-pane>
      <el-tab-pane label="运营任务" name="tasks" lazy><TasksTab :key="tasksKey" :refresh-key="tasksKey" @create="createTask(null)" /></el-tab-pane>
      <el-tab-pane label="热点内容" name="hot" lazy><HotContentTab @create-task="createTask" /></el-tab-pane>
      <el-tab-pane label="部门渗透" name="departments" lazy><DepartmentsTab @create-task="createTask" /></el-tab-pane>
    </el-tabs>
    <TaskDialog v-model:visible="dialogVisible" :draft="draft" @created="onCreated" />
  </div>
</template>
