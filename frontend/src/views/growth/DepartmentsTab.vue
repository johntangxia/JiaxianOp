<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchChannels } from "../../api/analytics";
import { fetchDepartments } from "../../api/growth";
import type { GrowthDepartmentRow } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { compareNullable, formatPercent } from "../../utils/channelMetrics";
import { domainChannels } from "../../utils/channels";
import { channelLink } from "../../utils/drill";
import { draftFromDepartment, type TaskDraft } from "../../utils/growth";

const emit = defineEmits<{ createTask: [draft: TaskDraft] }>();
const router = useRouter();

const days = ref(30);
const report = useAsync((d: number) => fetchDepartments(d));
const channelIds = ref(new Map<string, number>());

onMounted(async () => {
  try {
    domainChannels(await fetchChannels()).forEach((c) => channelIds.value.set(c.name, c.id));
  } catch {
    // 拿不到频道列表时只是没法跳转
  }
});
watch(days, (d) => void report.run(d), { immediate: true });

const data = computed(() => report.data.value);

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): GrowthDepartmentRow => row as GrowthDepartmentRow;
const by = (pick: (row: GrowthDepartmentRow) => number | null) => (a: unknown, b: unknown) => compareNullable(pick(r(a)), pick(r(b)));
const barPercent = (value: number | null): number => (value === null ? 0 : Math.min(100, Math.round(value * 100)));

function open(row: unknown): void {
  const id = channelIds.value.get(r(row).dept);
  if (id !== undefined && data.value) {
    void router.push(channelLink(id, { from: data.value.from, to: data.value.to }));
  }
}
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">部门渗透率 <span class="muted">（渗透率 = 窗口内有过任何行为的成员 / 成员数；对比上一个等长窗口；点一行看对应领域频道）</span></h3>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
    </div>
    <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="false">
      <div v-if="data">
        <p class="muted">窗口 {{ data.from }} ~ {{ data.to }}；全社区：成员 {{ data.overall.current.members }}，活跃 {{ data.overall.current.active }}，渗透率 {{ formatPercent(data.overall.current.penetration) }}（上期 {{ formatPercent(data.overall.previous.penetration) }}）</p>
        <el-table :data="data.departments" size="small" row-class-name="clickable-row" @row-click="open">
          <el-table-column label="部门" prop="dept" min-width="120" />
          <el-table-column label="成员" min-width="70" sortable :sort-method="by((x) => x.current.members)"><template #default="{ row }">{{ r(row).current.members }}</template></el-table-column>
          <el-table-column label="活跃" min-width="70" sortable :sort-method="by((x) => x.current.active)"><template #default="{ row }">{{ r(row).current.active }}</template></el-table-column>
          <el-table-column label="渗透率" min-width="200" sortable :sort-method="by((x) => x.current.penetration)">
            <template #default="{ row }">
              <div class="bar"><el-progress :percentage="barPercent(r(row).current.penetration)" :stroke-width="8" :show-text="false" /><span>{{ formatPercent(r(row).current.penetration) }}</span></div>
            </template>
          </el-table-column>
          <el-table-column label="上期渗透率" min-width="100"><template #default="{ row }">{{ formatPercent(r(row).previous.penetration) }}</template></el-table-column>
          <el-table-column label="创作者" min-width="70"><template #default="{ row }">{{ r(row).current.creators }}</template></el-table-column>
          <el-table-column label="互动者" min-width="70"><template #default="{ row }">{{ r(row).current.interactors }}</template></el-table-column>
          <el-table-column label="沉默读者" min-width="80"><template #default="{ row }">{{ r(row).current.lurkers }}</template></el-table-column>
          <el-table-column label="新增成员" min-width="80"><template #default="{ row }">{{ r(row).current.newMembers }}</template></el-table-column>
          <el-table-column label="操作" min-width="110">
            <template #default="{ row }"><el-button size="small" link type="primary" @click.stop="emit('createTask', draftFromDepartment(r(row)))">创建任务</el-button></template>
          </el-table-column>
        </el-table>
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
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bar :deep(.el-progress) {
  flex: 1;
}
.bar span {
  min-width: 48px;
  text-align: right;
}
</style>
