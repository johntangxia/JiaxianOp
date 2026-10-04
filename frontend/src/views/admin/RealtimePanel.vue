<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { fetchRealtimeStatus, startRealtime, stopRealtime } from "../../api/admin";
import type { RealtimeStatus } from "../../api/types";
import { buildRealtimeBody, defaultRealtimeForm } from "../../utils/simulatorForms";

const form = reactive(defaultRealtimeForm());
const status = ref<RealtimeStatus | null>(null);
const error = ref<string | null>(null);
const busy = ref(false);
let timer: number | undefined;

async function refresh(): Promise<void> {
  try {
    status.value = await fetchRealtimeStatus();
    error.value = null;
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}

async function start(): Promise<void> {
  busy.value = true;
  try {
    status.value = await startRealtime(buildRealtimeBody(form));
    error.value = null;
  } catch (e) {
    // 已经在运行时后端返回 409，响应体是一句说明
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

async function stop(): Promise<void> {
  busy.value = true;
  try {
    await stopRealtime();
    await refresh();
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    busy.value = false;
  }
}

// 页面存在期间每 2 秒轮询一次状态，离开页面即停止
onMounted(() => {
  void refresh();
  timer = window.setInterval(() => void refresh(), 2000);
});
onBeforeUnmount(() => window.clearInterval(timer));
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">实时模拟</h3>
    <div class="status">
      <el-tag :type="status?.running ? 'success' : 'info'" effect="plain">{{ status?.running ? "运行中" : "已停止" }}</el-tag>
      <template v-if="status?.running">
        <span>批次：{{ status.batchId }}</span>
        <span>已发送事件：{{ status.sentCount }}</span>
        <span class="muted">启动于 {{ status.startedAt?.replace("T", " ").slice(0, 19) }}</span>
      </template>
      <span v-else-if="status" class="muted">上次批次：{{ status.batchId ?? "—" }}，已发送 {{ status.sentCount }}</span>
    </div>

    <el-form inline class="form">
      <el-form-item label="批次名"><el-input v-model="form.batchId" placeholder="留空自动生成" style="width: 170px" /></el-form-item>
      <el-form-item label="间隔（毫秒）"><el-input-number v-model="form.intervalMillis" :min="100" :step="100" /></el-form-item>
      <el-form-item label="每批事件数"><el-input-number v-model="form.eventsPerBatch" :min="1" /></el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="busy" :disabled="status?.running" @click="start">启动</el-button>
        <el-button :loading="busy" :disabled="!status?.running" @click="stop">停止</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
  </div>
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
}
</style>
