<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchTasks } from "../../api/growth";
import type { GrowthTaskStatus, GrowthTaskView } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import {
  ENTITY_TYPE_LABELS,
  formatMetricValue,
  isOverdue,
  METRIC_LABELS,
  progressBar,
  progressText,
  TASK_STATUS_LABELS,
  TASK_TYPE_LABELS,
} from "../../utils/growth";
import TaskDrawer from "./TaskDrawer.vue";

defineProps<{ refreshKey: number }>();
const emit = defineEmits<{ create: [] }>();

const PAGE_SIZE = 20;
const ALL = "ALL";   // el-radio 不能用空字符串当值，"全部"用哨兵值
const status = ref<GrowthTaskStatus | typeof ALL>(ALL);
const page = ref(1);
const openId = ref<number | null>(null);
const list = useAsync((s: GrowthTaskStatus | undefined, offset: number) => fetchTasks(s, PAGE_SIZE, offset));

function load(): void {
  void list.run(status.value === ALL ? undefined : status.value, (page.value - 1) * PAGE_SIZE);
}
watch(status, () => {
  page.value = 1;
  load();
});
watch(page, load);
load();

const data = computed(() => list.data.value);
// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): GrowthTaskView => row as GrowthTaskView;
const statusType = { TODO: "info", DOING: "primary", DONE: "success", CANCELLED: "info" } as const;

defineExpose({ reload: load });
</script>

<template>
  <div class="panel">
    <div class="head">
      <el-radio-group v-model="status" size="small">
        <el-radio-button :value="ALL">全部</el-radio-button>
        <el-radio-button v-for="(label, key) in TASK_STATUS_LABELS" :key="key" :value="key">{{ label }}</el-radio-button>
      </el-radio-group>
      <el-button type="primary" size="small" @click="emit('create')">新建任务</el-button>
    </div>
    <p class="muted">基线 = 开始日前 30 天的指标值；当前 = 最近 30 天；进度 = (当前 − 基线) / (目标 − 基线)。点一行看效果趋势并修改状态。</p>
    <AsyncBlock :loading="list.loading.value" :error="list.error.value" :empty="!!data && data.total === 0" empty-text="还没有任务；可以从告警、热点内容或部门渗透创建，也可以点“新建任务”">
      <div v-if="data && data.total > 0">
        <el-table :data="data.tasks" size="small" row-class-name="clickable-row" @row-click="(row: unknown) => (openId = r(row).task.id)">
          <el-table-column label="标题" min-width="200"><template #default="{ row }">{{ r(row).task.title }}</template></el-table-column>
          <el-table-column label="类型" min-width="110"><template #default="{ row }">{{ TASK_TYPE_LABELS[r(row).task.taskType] }}</template></el-table-column>
          <el-table-column label="对象" min-width="130">
            <template #default="{ row }"><span class="muted">{{ ENTITY_TYPE_LABELS[r(row).task.entityType] }} · </span>{{ r(row).task.entityName ?? "—" }}</template>
          </el-table-column>
          <el-table-column label="指标" min-width="110"><template #default="{ row }">{{ METRIC_LABELS[r(row).task.metric] }}</template></el-table-column>
          <el-table-column label="基线 → 当前 → 目标" min-width="210">
            <template #default="{ row }">
              {{ formatMetricValue(r(row).task.metric, r(row).task.baselineValue) }} → <strong>{{ formatMetricValue(r(row).task.metric, r(row).currentValue) }}</strong> → {{ formatMetricValue(r(row).task.metric, r(row).task.targetValue) }}
            </template>
          </el-table-column>
          <el-table-column label="进度" min-width="150">
            <template #default="{ row }">
              <div class="bar"><el-progress :percentage="progressBar(r(row).progress)" :stroke-width="8" :show-text="false" /><span>{{ progressText(r(row).progress) }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="负责人" min-width="90"><template #default="{ row }">{{ r(row).task.ownerName ?? "—" }}</template></el-table-column>
          <el-table-column label="到期" min-width="110">
            <template #default="{ row }">
              <span :class="{ overdue: isOverdue(r(row).task, data.asOf) }">{{ r(row).task.dueDate ? r(row).task.dueDate!.slice(0, 10) : "—" }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="80">
            <template #default="{ row }"><el-tag :type="statusType[r(row).task.status]" size="small">{{ TASK_STATUS_LABELS[r(row).task.status] }}</el-tag></template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" class="pager" layout="total, prev, pager, next" :page-size="PAGE_SIZE" :total="data.total" />
      </div>
    </AsyncBlock>
    <TaskDrawer :task-id="openId" @close="openId = null" @changed="load" />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bar :deep(.el-progress) {
  flex: 1;
}
.bar span {
  min-width: 44px;
  text-align: right;
}
.overdue {
  color: #d9363e;
  font-weight: 600;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
