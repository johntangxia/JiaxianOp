<script setup lang="ts">
import GuidanceButton from "../../components/GuidanceButton.vue";
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { creatorRankingExportUrl, fetchCreatorRanking } from "../../api/people";
import type { CreatorRow, CreatorSortKey } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import { downloadFile } from "../../utils/download";
import { bloggerLink, type Range } from "../../utils/drill";
import { formatNumber } from "../../utils/format";
import { CREATOR_SORTS } from "../../utils/people";

const props = defineProps<{ range: Range }>();
const router = useRouter();

const PAGE_SIZE = 20;
const ALL_DEPTS = "__ALL__";   // el-select 会把空字符串当"未选择"，所以"全部部门"用哨兵值
const sort = ref<CreatorSortKey>("PV");
const dept = ref(ALL_DEPTS);
const page = ref(1);

const report = useAsync((from: string, to: string, s: CreatorSortKey, d: string | undefined, offset: number) =>
  fetchCreatorRanking(from, to, s, d, PAGE_SIZE, offset),
);

function load(): void {
  void report.run(props.range.from, props.range.to, sort.value, dept.value === ALL_DEPTS ? undefined : dept.value, (page.value - 1) * PAGE_SIZE);
}

watch(
  () => props.range,
  () => {
    page.value = 1;
    load();
  },
  { deep: true, immediate: true },
);
watch([sort, dept], () => {
  page.value = 1;
  load();
});
watch(page, load);

const data = computed(() => report.data.value);
function exportCsv(): void {
  downloadFile(creatorRankingExportUrl(props.range.from, props.range.to, sort.value, dept.value === ALL_DEPTS ? undefined : dept.value));
}

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): CreatorRow => row as CreatorRow;
function open(row: unknown): void {
  void router.push(bloggerLink(r(row).employeeId, props.range));
}
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">创作者排行 <span class="muted">（点姓名看他的博主页；PV、互动按"作者归属"，合著内容每位作者都记完整数）</span></h3>
      <div class="filters">
        <el-select v-model="sort" size="small" style="width: 130px">
          <el-option v-for="s in CREATOR_SORTS" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="dept" size="small" style="width: 160px">
          <el-option label="全部部门" :value="ALL_DEPTS" />
          <el-option v-for="d in data?.depts ?? []" :key="d" :label="d" :value="d" />
        </el-select>
        <el-button size="small" :disabled="!data || data.total === 0" @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>
    <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.total === 0" empty-text="所选区间内没有创作者">
      <div v-if="data && data.total > 0">
        <el-table :data="data.creators" size="small" row-class-name="clickable-row" @row-click="open">
          <el-table-column label="#" min-width="50"><template #default="{ $index }">{{ data.offset + $index + 1 }}</template></el-table-column>
          <el-table-column label="姓名" prop="name" min-width="110" />
          <el-table-column label="部门" min-width="130"><template #default="{ row }">{{ r(row).dept ?? "—" }}</template></el-table-column>
          <el-table-column label="发帖数" prop="posts" min-width="80" />
          <el-table-column label="PV" min-width="80"><template #default="{ row }">{{ formatNumber(r(row).pv) }}</template></el-table-column>
          <el-table-column label="UV" min-width="70"><template #default="{ row }">{{ formatNumber(r(row).uv) }}</template></el-table-column>
          <el-table-column label="互动数" min-width="80"><template #default="{ row }">{{ formatNumber(r(row).interactions) }}</template></el-table-column>
          <el-table-column label="互动率" min-width="80"><template #default="{ row }">{{ formatPercent(r(row).interactionRate) }}</template></el-table-column>
          <el-table-column label="读完率" min-width="80"><template #header>读完率 <GuidanceButton id="metric.completion" label="读完率" /></template><template #default="{ row }">{{ formatPercent(r(row).completionRate) }}</template></el-table-column>
          <el-table-column label="新增关注" min-width="90"><template #default="{ row }">{{ formatNumber(r(row).newFollowers) }}</template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" class="pager" layout="total, prev, pager, next" :page-size="PAGE_SIZE" :total="data.total" />
        <p class="muted">新增关注 = 名下账号在区间内新增的关注者；团队账号由多人共享，每位成员都计完整数，所以合计会重复。缺失值（没有 PV、没有停留记录）排在最后。</p>
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
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
