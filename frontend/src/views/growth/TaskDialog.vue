<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { fetchChannels, searchAccounts, searchEmployees } from "../../api/analytics";
import { createTask, fetchDepartments } from "../../api/growth";
import type { ChannelOption, GrowthEntityType, GrowthMetric, GrowthTaskType } from "../../api/types";
import RemoteSelect from "../../components/RemoteSelect.vue";
import { domainChannels } from "../../utils/channels";
import {
  ENTITY_TYPE_LABELS,
  isPercentMetric,
  METRIC_LABELS,
  METRICS_BY_ENTITY,
  TASK_TYPE_LABELS,
  targetFromInput,
  type TaskDraft,
} from "../../utils/growth";

const props = defineProps<{ visible: boolean; draft: TaskDraft | null }>();
const emit = defineEmits<{ "update:visible": [value: boolean]; created: [] }>();

interface Form {
  title: string;
  taskType: GrowthTaskType;
  entityType: GrowthEntityType;
  entityId: number | undefined;
  entityName: string | undefined;
  metric: GrowthMetric;
  target: number | undefined;
  owner: string;
  startDate: string;
  dueDate: string;
  note: string;
  alertId: number | undefined;
}

const emptyForm = (): Form => ({
  title: "", taskType: "OTHER", entityType: "OVERALL", entityId: undefined, entityName: undefined, metric: "ACTIVE_RATE",
  target: undefined, owner: "", startDate: "", dueDate: "", note: "", alertId: undefined,
});

const form = reactive<Form>(emptyForm());
const saving = ref(false);
const error = ref<string | null>(null);
const channels = ref<ChannelOption[]>([]);
const depts = ref<string[]>([]);
// 远程搜索选中的账号/博主名字，用来显示已选对象
const pickedName = ref<string | undefined>(undefined);

watch(
  () => props.visible,
  async (open) => {
    if (!open) {
      return;
    }
    Object.assign(form, emptyForm(), props.draft ?? {}, { target: undefined, owner: "", startDate: "", dueDate: "" });
    form.note = props.draft?.note ?? "";
    pickedName.value = props.draft?.entityName;
    error.value = null;
    try {
      if (channels.value.length === 0) {
        channels.value = domainChannels(await fetchChannels());
      }
      if (depts.value.length === 0) {
        depts.value = (await fetchDepartments(30)).departments.map((d) => d.dept);
      }
    } catch {
      // 选择器拿不到数据时只是下拉为空
    }
  },
);

const metricOptions = computed(() => METRICS_BY_ENTITY[form.entityType]);
const entityTypes = Object.keys(ENTITY_TYPE_LABELS) as GrowthEntityType[];
const taskTypes = Object.keys(TASK_TYPE_LABELS) as GrowthTaskType[];

function onEntityType(): void {
  form.entityId = undefined;
  form.entityName = undefined;
  pickedName.value = undefined;
  if (!METRICS_BY_ENTITY[form.entityType].includes(form.metric)) {
    form.metric = METRICS_BY_ENTITY[form.entityType][0];
  }
}

const rememberName = (search: (keyword: string) => Promise<Array<{ id: number; name: string }>>) =>
  async (keyword: string) => {
    const list = await search(keyword);
    names.value = new Map([...names.value, ...list.map((o): [number, string] => [o.id, o.name])]);
    return list;
  };
const names = ref(new Map<number, string>());
const accountFetcher = rememberName(searchAccounts);
const bloggerFetcher = rememberName(searchEmployees);

function onRemote(id: number | undefined): void {
  form.entityId = id;
  pickedName.value = id === undefined ? undefined : (names.value.get(id) ?? pickedName.value);
}

async function submit(): Promise<void> {
  error.value = null;
  if (form.title.trim() === "") {
    error.value = "请填写标题";
    return;
  }
  if (form.target === undefined || Number.isNaN(form.target)) {
    error.value = "请填写目标值";
    return;
  }
  saving.value = true;
  try {
    await createTask({
      title: form.title.trim(),
      taskType: form.taskType,
      entityType: form.entityType,
      entityId: form.entityId,
      entityName: form.entityType === "DEPARTMENT" ? form.entityName : undefined,
      metric: form.metric,
      targetValue: targetFromInput(form.metric, form.target),
      owner: form.owner.trim() || undefined,
      startDate: form.startDate || undefined,
      dueDate: form.dueDate || undefined,
      note: form.note.trim() || undefined,
      alertId: form.alertId,
    });
    emit("update:visible", false);
    emit("created");
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog :model-value="visible" title="新建运营任务" width="560px" @update:model-value="emit('update:visible', $event)">
    <el-form label-width="96px" @submit.prevent>
      <el-form-item label="标题"><el-input v-model="form.title" maxlength="200" /></el-form-item>
      <el-form-item label="任务类型">
        <el-select v-model="form.taskType" style="width: 200px"><el-option v-for="t in taskTypes" :key="t" :label="TASK_TYPE_LABELS[t]" :value="t" /></el-select>
      </el-form-item>
      <el-form-item label="对象类型">
        <el-select v-model="form.entityType" style="width: 200px" @change="onEntityType"><el-option v-for="t in entityTypes" :key="t" :label="ENTITY_TYPE_LABELS[t]" :value="t" /></el-select>
      </el-form-item>
      <el-form-item v-if="form.entityType === 'CHANNEL'" label="频道">
        <el-select v-model="form.entityId" filterable placeholder="选择领域频道" style="width: 240px">
          <el-option v-for="c in channels" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-else-if="form.entityType === 'DEPARTMENT'" label="部门">
        <el-select v-model="form.entityName" filterable placeholder="选择部门" style="width: 240px">
          <el-option v-for="d in depts" :key="d" :label="d" :value="d" />
        </el-select>
      </el-form-item>
      <el-form-item v-else-if="form.entityType === 'ACCOUNT' || form.entityType === 'BLOGGER'" :label="ENTITY_TYPE_LABELS[form.entityType]">
        <el-tag v-if="form.entityId !== undefined" closable effect="plain" style="margin-right: 8px" @close="onRemote(undefined)">{{ pickedName ?? `#${form.entityId}` }}</el-tag>
        <RemoteSelect :model-value="undefined" :fetcher="form.entityType === 'ACCOUNT' ? accountFetcher : bloggerFetcher" :placeholder="form.entityType === 'ACCOUNT' ? '搜索账号' : '搜索博主'" style="width: 200px" @update:model-value="onRemote" />
      </el-form-item>
      <el-form-item label="跟踪指标">
        <el-select v-model="form.metric" style="width: 200px"><el-option v-for="m in metricOptions" :key="m" :label="METRIC_LABELS[m]" :value="m" /></el-select>
      </el-form-item>
      <el-form-item label="目标值">
        <el-input-number v-model="form.target" :precision="isPercentMetric(form.metric) ? 2 : 0" :min="0" :controls="false" style="width: 160px" />
        <span class="unit">{{ isPercentMetric(form.metric) ? "%（输入 5 表示 5%）" : "" }}</span>
      </el-form-item>
      <el-form-item label="负责人"><el-input v-model="form.owner" maxlength="50" style="width: 200px" /></el-form-item>
      <el-form-item label="开始日期">
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="默认统计日" style="width: 200px" />
        <span class="unit">基线 = 开始日前 30 天的指标值</span>
      </el-form-item>
      <el-form-item label="到期日"><el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.note" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.unit {
  margin-left: 10px;
  color: var(--color-text-muted);
  font-size: 12px;
}
</style>
