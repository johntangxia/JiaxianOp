<script lang="ts">
export interface ListPreset {
  stage: string | null;
  value: string | null;
  nonce: number;
}
</script>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchUserList, userListExportUrl } from "../../api/people";
import type { UserListRow, UserListSort } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { downloadFile } from "../../utils/download";
import { dateOnly } from "../../utils/readers";
import { lifecycleTagType, USER_SORTS, userLink, valueTagType } from "../../utils/people";

const props = defineProps<{ asOf: string; preset: ListPreset }>();
const router = useRouter();

const PAGE_SIZE = 20;
const ALL = "__ALL__";   // el-select 会把空字符串当"未选择"，所以"全部"用哨兵值
const STAGES = [
  { value: "NEW", label: "新成员" },
  { value: "ACTIVE", label: "活跃" },
  { value: "RETURNING", label: "回流" },
  { value: "LAPSING", label: "沉默" },
  { value: "CHURNED", label: "流失" },
  { value: "DORMANT", label: "未激活" },
];
const VALUES = [
  { value: "CORE", label: "核心" },
  { value: "ACTIVE", label: "活跃" },
  { value: "TO_WAKE", label: "待唤醒" },
  { value: "LOW_ACTIVE", label: "低活跃" },
  { value: "SLEEPING", label: "沉睡" },
];

const stage = ref(props.preset.stage ?? ALL);
const value = ref(props.preset.value ?? ALL);
const dept = ref(ALL);
const keyword = ref("");
const sort = ref<UserListSort>("LAST_ACTIVE");
const page = ref(1);

const report = useAsync((offset: number) =>
  fetchUserList({
    asOf: props.asOf,
    stage: stage.value === ALL ? undefined : stage.value,
    value: value.value === ALL ? undefined : value.value,
    dept: dept.value === ALL ? undefined : dept.value,
    q: keyword.value.trim() || undefined,
    sort: sort.value,
    limit: PAGE_SIZE,
    offset,
  }),
);

function load(): void {
  void report.run((page.value - 1) * PAGE_SIZE);
}
function reload(): void {
  page.value = 1;
  load();
}

watch(() => props.asOf, reload, { immediate: true });
watch([stage, value, dept, sort], reload);
watch(page, load);
// 从生命周期/价值分层页跳进来时带上筛选
watch(
  () => props.preset.nonce,
  () => {
    stage.value = props.preset.stage ?? ALL;
    value.value = props.preset.value ?? ALL;
  },
);

const data = computed(() => report.data.value);
function exportCsv(): void {
  downloadFile(userListExportUrl({
    asOf: props.asOf,
    stage: stage.value === ALL ? undefined : stage.value,
    value: value.value === ALL ? undefined : value.value,
    dept: dept.value === ALL ? undefined : dept.value,
    q: keyword.value.trim() || undefined,
    sort: sort.value,
  }));
}
const r = (row: unknown): UserListRow => row as UserListRow;
function open(row: unknown): void {
  void router.push(userLink(r(row).employeeId, props.asOf));
}
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">用户列表 <span class="muted">（全体成员；阶段为统计日当天的生命周期，活跃天数和浏览/互动/发帖是近 90 天的；点一行看用户 360）</span></h3>
      <div class="filters">
        <el-select v-model="stage" size="small" style="width: 120px">
          <el-option label="全部阶段" :value="ALL" />
          <el-option v-for="s in STAGES" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="value" size="small" style="width: 120px">
          <el-option label="全部分层" :value="ALL" />
          <el-option v-for="v in VALUES" :key="v.value" :label="v.label" :value="v.value" />
        </el-select>
        <el-select v-model="dept" size="small" style="width: 150px">
          <el-option label="全部部门" :value="ALL" />
          <el-option v-for="d in data?.depts ?? []" :key="d" :label="d" :value="d" />
        </el-select>
        <el-select v-model="sort" size="small" style="width: 120px">
          <el-option v-for="s in USER_SORTS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-input v-model="keyword" size="small" placeholder="按姓名搜索" clearable style="width: 150px" @keyup.enter="reload" @clear="reload" />
        <el-button size="small" :disabled="!data || data.total === 0" @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>
    <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.total === 0" empty-text="没有符合条件的用户">
      <div v-if="data && data.total > 0">
        <el-table :data="data.users" size="small" row-class-name="clickable-row" @row-click="open">
          <el-table-column label="姓名" prop="name" min-width="110" />
          <el-table-column label="部门" min-width="130"><template #default="{ row }">{{ r(row).dept ?? "—" }}</template></el-table-column>
          <el-table-column label="加入社区" min-width="110"><template #default="{ row }">{{ dateOnly(r(row).joinDate) }}</template></el-table-column>
          <el-table-column label="生命周期" min-width="90"><template #default="{ row }"><el-tag size="small" :type="lifecycleTagType(r(row).stage)">{{ r(row).stageLabel }}</el-tag></template></el-table-column>
          <el-table-column label="价值分层" min-width="90"><template #default="{ row }"><el-tag size="small" :type="valueTagType(r(row).valueSegment)">{{ r(row).valueLabel }}</el-tag></template></el-table-column>
          <el-table-column label="最近活跃" min-width="110"><template #default="{ row }">{{ dateOnly(r(row).lastActive) }}</template></el-table-column>
          <el-table-column label="活跃天数" prop="activeDays" min-width="80" />
          <el-table-column label="浏览" prop="views" min-width="70" />
          <el-table-column label="互动" prop="interactions" min-width="70" />
          <el-table-column label="发帖" prop="posts" min-width="70" />
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
.filters {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
