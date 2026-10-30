<script setup lang="ts">
import GuidanceButton from "../components/GuidanceButton.vue";
import { computed, ref, watch } from "vue";
import { fetchLeaderboard, fetchPeriods } from "../api/leaderboard";
import type { PeriodType } from "../api/types";
import AsyncBlock from "../components/AsyncBlock.vue";
import BaseChart from "../components/BaseChart.vue";
import { buildLeaderboardOption } from "../charts/leaderboardOption";
import { useAsync } from "../composables/useAsync";
import { formatNumber } from "../utils/format";

const type = ref<PeriodType>("month");
const period = ref("");
const limit = ref(20);

const periods = useAsync((t: PeriodType) => fetchPeriods(t));
const board = useAsync((t: PeriodType, p: string, n: number) => fetchLeaderboard(t, p, n));

// 切换月榜/年榜时重新取周期列表，并默认选第一项（当前周期）
watch(
  type,
  async () => {
    await periods.run(type.value);
    period.value = periods.data.value?.[0]?.periodValue ?? "";
  },
  { immediate: true },
);

// 周期或人数变化时取榜单；切换类型时 period 会在周期列表返回后才变，所以不会拿旧周期去查新类型
watch([period, limit], () => {
  if (period.value) {
    void board.run(type.value, period.value, limit.value);
  }
});

const entries = computed(() => board.data.value?.entries ?? []);
const chartOption = computed(() => buildLeaderboardOption(entries.value.slice(0, 10)));
const chartHeight = computed(() => `${Math.min(entries.value.length, 10) * 34 + 56}px`);

function periodLabel(value: string, status: string): string {
  return status === "IN_PROGRESS" ? `${value}（进行中）` : value;
}

function rankClass(rank: number): string {
  return rank === 1 ? "gold" : rank === 2 ? "silver" : rank === 3 ? "bronze" : "";
}

function share(part: number, total: number): string {
  return total > 0 ? `${((part / total) * 100).toFixed(1)}%` : "0%";
}
</script>

<template>
  <div class="page">
    <div class="panel controls">
      <el-radio-group v-model="type">
        <el-radio-button value="month">月榜</el-radio-button>
        <el-radio-button value="year">年榜</el-radio-button>
      </el-radio-group>

      <el-select v-model="period" style="width: 190px" :loading="periods.loading.value" placeholder="选择周期">
        <el-option
          v-for="p in periods.data.value ?? []"
          :key="p.periodValue"
          :label="periodLabel(p.periodValue, p.status)"
          :value="p.periodValue"
        />
      </el-select>

      <el-select v-model="limit" style="width: 110px">
        <el-option v-for="n in [10, 20, 50]" :key="n" :label="`前 ${n} 名`" :value="n" />
      </el-select>

      <template v-if="board.data.value">
        <el-tag :type="board.data.value.status === 'IN_PROGRESS' ? 'warning' : 'info'" effect="plain">
          {{ board.data.value.status === "IN_PROGRESS" ? "进行中" : "已归档" }}
        </el-tag>
        <el-tag type="info" effect="plain">
          {{ board.data.value.source === "LIVE" ? "实时计算" : "归档快照" }}
        </el-tag>
      </template>
    </div>

    <AsyncBlock :loading="periods.loading.value" :error="periods.error.value" :empty="false">
      <AsyncBlock
        :loading="board.loading.value"
        :error="board.error.value"
        :empty="board.data.value !== null && entries.length === 0"
        empty-text="该周期还没有积分数据"
      >
        <div v-if="entries.length > 0" class="page">
          <div class="panel">
            <h3 class="panel-title">前 {{ Math.min(entries.length, 10) }} 名积分构成 <GuidanceButton id="metric.points" label="主动和被动积分" /></h3>
            <BaseChart :option="chartOption" :height="chartHeight" />
          </div>

          <div class="panel">
            <h3 class="panel-title">榜单</h3>
            <el-table :data="entries" size="small" row-key="employeeId">
              <el-table-column label="名次" width="80" align="center">
                <template #default="{ row }">
                  <span class="rank-badge" :class="rankClass(row.rank)">{{ row.rank }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="姓名" min-width="140" />
              <el-table-column label="总分" align="right" min-width="100">
                <template #default="{ row }"><strong>{{ formatNumber(row.points) }}</strong></template>
              </el-table-column>
              <el-table-column label="主动分" align="right" min-width="100">
                <template #default="{ row }">{{ formatNumber(row.activePoints) }}</template>
              </el-table-column>
              <el-table-column label="被动分" align="right" min-width="100">
                <template #default="{ row }">{{ formatNumber(row.passivePoints) }}</template>
              </el-table-column>
              <el-table-column label="主动 / 被动占比" min-width="220">
                <template #default="{ row }">
                  <div class="split-bar" :title="`主动 ${share(row.activePoints, row.points)}　被动 ${share(row.passivePoints, row.points)}`">
                    <span class="active" :style="{ width: share(row.activePoints, row.points) }"></span>
                    <span class="passive" :style="{ width: share(row.passivePoints, row.points) }"></span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </AsyncBlock>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.rank-badge {
  display: inline-block;
  min-width: 24px;
  padding: 2px 6px;
  border-radius: 12px;
  background: #eef1f6;
  font-weight: 600;
  line-height: 1.4;
}
.rank-badge.gold {
  background: #f6c344;
  color: #5a3d00;
}
.rank-badge.silver {
  background: #c7cdd6;
  color: #3b4350;
}
.rank-badge.bronze {
  background: #d9a066;
  color: #4a2c0a;
}
.split-bar {
  display: flex;
  height: 10px;
  overflow: hidden;
  border-radius: 5px;
  background: #eef1f6;
}
.split-bar .active {
  background: var(--color-primary);
}
.split-bar .passive {
  background: var(--color-passive);
}
</style>
