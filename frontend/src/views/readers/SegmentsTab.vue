<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchLurkers, fetchSegments, lurkersExportUrl } from "../../api/readers";
import type { ReaderLurker } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildSegmentPieOption } from "../../charts/readerOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import type { Range } from "../../utils/drill";
import { downloadFile } from "../../utils/download";
import { userLink } from "../../utils/people";
import { dateOnly } from "../../utils/readers";

const props = defineProps<{ range: Range }>();
const router = useRouter();

const PAGE_SIZE = 20;
const ALL_DEPTS = "__ALL__";   // el-select 会把空字符串当"未选择"，所以"全部部门"用哨兵值
const dept = ref(ALL_DEPTS);
const page = ref(1);

const segments = useAsync((from: string, to: string) => fetchSegments(from, to));
const lurkers = useAsync((from: string, to: string, d: string | undefined, offset: number) =>
  fetchLurkers(from, to, d, PAGE_SIZE, offset),
);

function loadLurkers(): void {
  void lurkers.run(props.range.from, props.range.to, dept.value === ALL_DEPTS ? undefined : dept.value, (page.value - 1) * PAGE_SIZE);
}

watch(
  () => props.range,
  () => {
    page.value = 1;
    void segments.run(props.range.from, props.range.to);
    loadLurkers();
  },
  { deep: true, immediate: true },
);
watch(dept, () => {
  page.value = 1;
  loadLurkers();
});
watch(page, loadLurkers);

const pieOption = computed(() => (segments.data.value ? buildSegmentPieOption(segments.data.value.segments) : {}));
const lurkerData = computed(() => lurkers.data.value);
function exportCsv(): void {
  downloadFile(lurkersExportUrl(props.range.from, props.range.to, dept.value === ALL_DEPTS ? undefined : dept.value));
}

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): ReaderLurker => row as ReaderLurker;
function open(row: unknown): void {
  void router.push(userLink(r(row).employeeId, props.range.to));
}
</script>

<template>
  <div class="page">
    <div class="panel">
      <h3 class="panel-title">读者分层 <span class="muted">（区间结束日已是成员的员工；判定顺序：创作者 &gt; 互动者 &gt; 沉默读者 &gt; 仅登录 &gt; 不活跃）</span></h3>
      <AsyncBlock :loading="segments.loading.value" :error="segments.error.value" :empty="false">
        <div v-if="segments.data.value" class="split">
          <BaseChart :option="pieOption" height="240px" />
          <el-table :data="segments.data.value.segments" size="small">
            <el-table-column label="角色" prop="label" min-width="100" />
            <el-table-column label="人数" prop="count" min-width="80" />
            <el-table-column label="占成员" min-width="90"><template #default="{ row }">{{ formatPercent((row as typeof segments.data.value.segments[number]).share) }}</template></el-table-column>
          </el-table>
        </div>
      </AsyncBlock>
    </div>

    <div class="panel">
      <div class="head">
        <h3 class="panel-title">沉默读者名单 <span class="muted">（区间内有浏览，但没发帖也没点赞、评论、转发、收藏；按浏览次数从高到低，点一行看他的用户 360）</span></h3>
        <div class="filters">
          <el-select v-model="dept" size="small" style="width: 180px">
            <el-option label="全部部门" :value="ALL_DEPTS" />
            <el-option v-for="d in lurkerData?.depts ?? []" :key="d" :label="d" :value="d" />
          </el-select>
          <el-button size="small" :disabled="!lurkerData || lurkerData.total === 0" @click="exportCsv">导出 CSV</el-button>
        </div>
      </div>
      <AsyncBlock :loading="lurkers.loading.value" :error="lurkers.error.value" :empty="!!lurkerData && lurkerData.total === 0" empty-text="没有沉默读者">
        <div v-if="lurkerData && lurkerData.total > 0">
          <el-table :data="lurkerData.lurkers" size="small" row-class-name="clickable-row" @row-click="open">
            <el-table-column label="姓名" prop="name" min-width="110" />
            <el-table-column label="部门" min-width="130"><template #default="{ row }">{{ r(row).dept ?? "—" }}</template></el-table-column>
            <el-table-column label="加入社区" min-width="110"><template #default="{ row }">{{ dateOnly(r(row).joinDate) }}</template></el-table-column>
            <el-table-column label="浏览次数" prop="views" min-width="90" />
            <el-table-column label="活跃天数" prop="activeDays" min-width="90" />
            <el-table-column label="最近活跃" min-width="110"><template #default="{ row }">{{ dateOnly(r(row).lastActive) }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" class="pager" layout="total, prev, pager, next" :page-size="PAGE_SIZE" :total="lurkerData.total" />
        </div>
      </AsyncBlock>
    </div>
  </div>
</template>

<style scoped>
.split {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
  align-items: center;
}
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
