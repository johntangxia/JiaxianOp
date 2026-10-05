<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { fetchAlerts, rebuildAlerts, updateAlertStatus } from "../../api/growth";
import type { GrowthAlert, GrowthAlertStatus } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { ALERT_STATUS_LABELS, alertKindLabel, draftFromAlert, ENTITY_TYPE_LABELS, type TaskDraft } from "../../utils/growth";

const emit = defineEmits<{ createTask: [draft: TaskDraft] }>();

const PAGE_SIZE = 20;
const ALL = "ALL";   // el-radio 不能用空字符串当值，"全部"用哨兵值
const status = ref<GrowthAlertStatus | typeof ALL>("OPEN");
const page = ref(1);
const busy = ref(false);
const list = useAsync((s: GrowthAlertStatus | undefined, offset: number) =>
  fetchAlerts({ status: s, limit: PAGE_SIZE, offset }),
);

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
const r = (row: unknown): GrowthAlert => row as GrowthAlert;

async function rebuild(): Promise<void> {
  busy.value = true;
  try {
    const result = await rebuildAlerts();
    ElMessage.success(`已重算（统计日 ${result.asOf}）：新增 ${result.created} 条，更新 ${result.updated} 条`);
    page.value = 1;
    load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  } finally {
    busy.value = false;
  }
}

async function setStatus(alert: GrowthAlert, next: GrowthAlertStatus): Promise<void> {
  try {
    await updateAlertStatus(alert.id, next);
    load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}
</script>

<template>
  <div class="panel">
    <div class="head">
      <el-radio-group v-model="status" size="small">
        <el-radio-button value="OPEN">待处理 {{ data?.counts.OPEN ?? "" }}</el-radio-button>
        <el-radio-button value="ACKED">已确认 {{ data?.counts.ACKED ?? "" }}</el-radio-button>
        <el-radio-button value="CLOSED">已关闭 {{ data?.counts.CLOSED ?? "" }}</el-radio-button>
        <el-radio-button :value="ALL">全部</el-radio-button>
      </el-radio-group>
      <el-button type="primary" size="small" :loading="busy" @click="rebuild">重算告警</el-button>
    </div>
    <p class="muted">告警来自频道诊断、DAU 下滑、新成员激活、部门渗透率和 KOL 流失预警；重算是幂等的，已有状态保留，已关闭的不会重开。</p>
    <AsyncBlock :loading="list.loading.value" :error="list.error.value" :empty="!!data && data.total === 0" empty-text="没有告警；点“重算告警”按当前数据检查一遍">
      <div v-if="data && data.total > 0">
        <el-table :data="data.alerts" size="small">
          <el-table-column label="级别" width="80">
            <template #default="{ row }"><el-tag :type="r(row).severity === 'CRITICAL' ? 'danger' : 'warning'" size="small">{{ r(row).severity === "CRITICAL" ? "严重" : "警告" }}</el-tag></template>
          </el-table-column>
          <el-table-column label="类型" min-width="150"><template #default="{ row }">{{ alertKindLabel(r(row).kind) }}</template></el-table-column>
          <el-table-column label="对象" min-width="130">
            <template #default="{ row }"><span class="muted">{{ ENTITY_TYPE_LABELS[r(row).entityType] }} · </span>{{ r(row).entityName }}</template>
          </el-table-column>
          <el-table-column label="说明" prop="message" min-width="280" />
          <el-table-column label="统计日" min-width="100"><template #default="{ row }">{{ r(row).periodEnd.slice(0, 10) }}</template></el-table-column>
          <el-table-column label="状态" min-width="80"><template #default="{ row }">{{ ALERT_STATUS_LABELS[r(row).status] }}</template></el-table-column>
          <el-table-column label="操作" min-width="210">
            <template #default="{ row }">
              <el-button v-if="r(row).status === 'OPEN'" size="small" link type="primary" @click="setStatus(r(row), 'ACKED')">确认</el-button>
              <el-button v-if="r(row).status !== 'CLOSED'" size="small" link @click="setStatus(r(row), 'CLOSED')">关闭</el-button>
              <el-button v-if="r(row).status === 'CLOSED'" size="small" link @click="setStatus(r(row), 'OPEN')">重新打开</el-button>
              <el-button size="small" link type="primary" @click="emit('createTask', draftFromAlert(r(row)))">创建任务</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" class="pager" layout="total, prev, pager, next" :page-size="PAGE_SIZE" :total="data.total" />
      </div>
    </AsyncBlock>
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
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
