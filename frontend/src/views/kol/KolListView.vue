<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { fetchBloggers, fetchRuns, type BloggerQuery } from "../../api/kol";
import type { BloggerItem, Segment } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { formatNumber } from "../../utils/format";
import { segmentTagType } from "../../utils/segment";
import SegmentCards from "./SegmentCards.vue";

const router = useRouter();

const asOf = ref<string | undefined>();
const segment = ref<Segment | undefined>();
const alertOnly = ref(false);
const page = ref(1);
const pageSize = ref(20);

const runs = useAsync(() => fetchRuns());
const list = useAsync((query: BloggerQuery) => fetchBloggers(query));

function reload(): void {
  void list.run({
    asOf: asOf.value,
    segment: segment.value,
    alert: alertOnly.value ? true : undefined,
    limit: pageSize.value,
    offset: (page.value - 1) * pageSize.value,
  });
}

// 任何筛选变化回到第 1 页；翻页只重新取当前页
function onFilterChange(): void {
  page.value = 1;
  reload();
}

function onPage(value: number): void {
  page.value = value;
  reload();
}

function onPageSize(value: number): void {
  pageSize.value = value;
  onFilterChange();
}

function onSegment(value: Segment | undefined): void {
  segment.value = value;
  onFilterChange();
}

onMounted(async () => {
  await runs.run();
  asOf.value = runs.data.value?.[0];
  reload();
});

const items = computed<BloggerItem[]>(() => list.data.value?.items ?? []);
const noRuns = computed(() => list.data.value !== null && list.data.value.asOf === null);

function open(row: BloggerItem): void {
  void router.push(`/kol/${row.employeeId}`);
}

function scoreClass(score: number): string {
  return score >= 4 ? "score-high" : score <= 2 ? "score-low" : "score-mid";
}
</script>

<template>
  <div class="page">
    <AsyncBlock :loading="runs.loading.value" :error="runs.error.value" :empty="false">
      <div class="page">
        <div class="panel controls">
          <span class="muted">统计日</span>
          <el-select v-model="asOf" style="width: 150px" placeholder="选择统计日" @change="onFilterChange">
            <el-option v-for="date in runs.data.value ?? []" :key="date" :label="date" :value="date" />
          </el-select>
          <el-switch v-model="alertOnly" active-text="仅看预警" @change="onFilterChange" />
        </div>

        <AsyncBlock
          :loading="list.loading.value"
          :error="list.error.value"
          :empty="noRuns"
          empty-text="还没有分层结果，请先在系统管理里运行 KOL 重算"
        >
          <div v-if="list.data.value && !noRuns" class="page">
            <SegmentCards :counts="list.data.value.segmentCounts" :selected="segment" @select="onSegment" />

            <div class="panel">
              <el-table
                :data="items"
                size="small"
                row-key="employeeId"
                row-class-name="clickable-row"
                empty-text="没有符合条件的博主"
                @row-click="open"
              >
                <el-table-column prop="rank" label="名次" width="70" align="center" />
                <el-table-column prop="name" label="姓名" min-width="120" />
                <el-table-column label="分层" min-width="170">
                  <template #default="{ row }">
                    <el-tag :type="segmentTagType(row.segment)" size="small" effect="plain">{{ row.segmentName }}</el-tag>
                    <el-tag v-if="row.churnAlert" type="danger" size="small" effect="dark" style="margin-left: 6px">预警</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="R / F / M" min-width="150">
                  <template #default="{ row }">
                    <span class="score-chip" :class="scoreClass(row.rScore)">{{ row.rScore }}</span>
                    <span class="score-chip" :class="scoreClass(row.fScore)">{{ row.fScore }}</span>
                    <span class="score-chip" :class="scoreClass(row.mScore)">{{ row.mScore }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="近 12 个月积分" align="right" min-width="120">
                  <template #default="{ row }">{{ formatNumber(row.rollingPoints12m) }}</template>
                </el-table-column>
                <el-table-column label="距今（天）" align="right" min-width="90">
                  <template #default="{ row }">{{ row.recencyDays ?? "—" }}</template>
                </el-table-column>
                <el-table-column label="建议文案" min-width="260" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.recommendation ?? "—" }}</template>
                </el-table-column>
              </el-table>

              <el-pagination
                class="pager"
                background
                layout="total, sizes, prev, pager, next"
                :total="list.data.value.total"
                :current-page="page"
                :page-size="pageSize"
                :page-sizes="[20, 50, 100]"
                @current-change="onPage"
                @size-change="onPageSize"
              />
            </div>
          </div>
          <div v-else-if="noRuns" class="muted center">
            <router-link to="/admin/simulator">去运行 KOL 重算</router-link>
          </div>
        </AsyncBlock>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.center {
  text-align: center;
}
</style>
