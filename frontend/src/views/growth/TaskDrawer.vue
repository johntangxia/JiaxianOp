<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { deleteTask, fetchTask, updateTask } from "../../api/growth";
import type { GrowthTaskStatus } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildTaskEffectOption } from "../../charts/taskEffectOption";
import { useAsync } from "../../composables/useAsync";
import {
  ENTITY_TYPE_LABELS,
  formatMetricValue,
  isOverdue,
  isPercentMetric,
  METRIC_LABELS,
  progressText,
  targetFromInput,
  targetToInput,
  TASK_STATUS_LABELS,
  TASK_TYPE_LABELS,
} from "../../utils/growth";

const props = defineProps<{ taskId: number | null }>();
const emit = defineEmits<{ close: []; changed: [] }>();

const detail = useAsync((id: number) => fetchTask(id));
const form = reactive<{ status: GrowthTaskStatus; owner: string; dueDate: string; target: number | undefined; note: string }>({
  status: "TODO", owner: "", dueDate: "", target: undefined, note: "",
});
const saving = ref(false);
const error = ref<string | null>(null);

watch(
  () => props.taskId,
  async (id) => {
    if (id === null) {
      return;
    }
    error.value = null;
    await detail.run(id);
    const task = detail.data.value?.view.task;
    if (task) {
      Object.assign(form, {
        status: task.status, owner: task.ownerName ?? "", dueDate: task.dueDate?.slice(0, 10) ?? "", note: task.note ?? "",
        target: targetToInput(task.metric, task.targetValue),
      });
    }
  },
  { immediate: true },
);

const data = computed(() => detail.data.value);
const option = computed(() => (data.value ? buildTaskEffectOption(data.value) : {}));
const statuses = Object.keys(TASK_STATUS_LABELS) as GrowthTaskStatus[];

async function save(): Promise<void> {
  const task = data.value?.view.task;
  if (!task || form.target === undefined) {
    return;
  }
  saving.value = true;
  error.value = null;
  try {
    await updateTask(task.id, {
      status: form.status, owner: form.owner, note: form.note, targetValue: targetFromInput(task.metric, form.target),
      dueDate: form.dueDate || undefined,
    });
    emit("changed");
    await detail.run(task.id);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}

async function remove(): Promise<void> {
  const task = data.value?.view.task;
  if (!task) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确定删除任务“${task.title}”？删除后不能恢复。`, "删除任务", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteTask(task.id);
    emit("changed");
    emit("close");
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  }
}
</script>

<template>
  <el-drawer :model-value="taskId !== null" size="640px" title="任务详情" @close="emit('close')">
    <AsyncBlock :loading="detail.loading.value" :error="detail.error.value" :empty="false">
      <div v-if="data" class="page">
        <div>
          <h3 class="title">{{ data.view.task.title }}</h3>
          <p class="muted">
            {{ TASK_TYPE_LABELS[data.view.task.taskType] }} · {{ ENTITY_TYPE_LABELS[data.view.task.entityType] }}{{ data.view.task.entityName ? `：${data.view.task.entityName}` : "" }}
            · 指标：{{ METRIC_LABELS[data.view.task.metric] }} · {{ data.view.task.startDate.slice(0, 10) }} 开始
            <el-tag v-if="isOverdue(data.view.task, data.asOf)" type="danger" size="small">已逾期</el-tag>
          </p>
        </div>
        <div class="nums">
          <div><div class="label">基线</div><div class="value">{{ formatMetricValue(data.view.task.metric, data.view.task.baselineValue) }}</div></div>
          <div><div class="label">当前（截至 {{ data.asOf }}）</div><div class="value">{{ formatMetricValue(data.view.task.metric, data.view.currentValue) }}</div></div>
          <div><div class="label">目标</div><div class="value">{{ formatMetricValue(data.view.task.metric, data.view.task.targetValue) }}</div></div>
          <div><div class="label">进度</div><div class="value">{{ progressText(data.view.progress) }}</div></div>
        </div>
        <div>
          <h4 class="sub">效果趋势 <span class="muted">（每 7 天一个窗口的指标值，虚线是基线和目标，绿线是开始日）</span></h4>
          <BaseChart :option="option" height="240px" />
        </div>
        <el-form label-width="80px" @submit.prevent>
          <el-form-item label="状态">
            <el-select v-model="form.status" style="width: 160px"><el-option v-for="s in statuses" :key="s" :label="TASK_STATUS_LABELS[s]" :value="s" /></el-select>
          </el-form-item>
          <el-form-item label="负责人"><el-input v-model="form.owner" maxlength="50" style="width: 200px" /></el-form-item>
          <el-form-item label="目标值">
            <el-input-number v-model="form.target" :precision="isPercentMetric(data.view.task.metric) ? 2 : 0" :min="0" :controls="false" style="width: 160px" />
            <span class="unit">{{ isPercentMetric(data.view.task.metric) ? "%" : "" }}</span>
          </el-form-item>
          <el-form-item label="到期日"><el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="3" maxlength="500" /></el-form-item>
          <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            <el-button type="danger" plain @click="remove">删除任务</el-button>
          </el-form-item>
        </el-form>
        <p class="muted">对象、指标和基线创建后不能修改；要换对象或指标，请新建任务。</p>
      </div>
    </AsyncBlock>
  </el-drawer>
</template>

<style scoped>
.title {
  margin: 0 0 4px;
  font-size: 17px;
}
.sub {
  margin: 0 0 8px;
}
.nums {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.label {
  color: var(--color-text-muted);
  font-size: 12px;
}
.value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
}
.unit {
  margin-left: 8px;
  color: var(--color-text-muted);
}
</style>
