<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchActivity, fetchPointsTrend, fetchProfile } from "../../api/kol";
import type { Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildActivityOption } from "../../charts/activityOption";
import { buildPointsTrendOption } from "../../charts/pointsTrendOption";
import { buildRfmHistoryOption } from "../../charts/rfmHistoryOption";
import { useAsync } from "../../composables/useAsync";
import { eventLabel } from "../../utils/actionTypes";
import { segmentName } from "../../utils/segment";
import StandingCard from "./StandingCard.vue";

const route = useRoute();
const employeeId = computed(() => Number(route.params.id));
const validId = computed(() => Number.isInteger(employeeId.value) && employeeId.value > 0);

const granularity = ref<Granularity>("WEEK");
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
  { value: "YEAR", label: "年" },
];

const profile = useAsync((id: number) => fetchProfile(id));
const activity = useAsync((id: number, g: Granularity) => fetchActivity(id, g));
const trend = useAsync((id: number, g: Granularity) => fetchPointsTrend(id, g));

function loadSeries(): void {
  if (validId.value) {
    void activity.run(employeeId.value, granularity.value);
    void trend.run(employeeId.value, granularity.value);
  }
}

watch(
  employeeId,
  () => {
    if (validId.value) {
      void profile.run(employeeId.value);
      loadSeries();
    }
  },
  { immediate: true },
);
watch(granularity, loadSeries);

const history = computed(() => profile.data.value?.history ?? []);
const historyOption = computed(() => buildRfmHistoryOption(history.value));
const activityOption = computed(() => (activity.data.value ? buildActivityOption(activity.data.value) : {}));
const trendOption = computed(() => (trend.data.value ? buildPointsTrendOption(trend.data.value) : {}));
const events = computed(() => profile.data.value?.recentEvents ?? []);

function formatTime(time: string): string {
  return time.replace("T", " ").slice(0, 16);
}
</script>

<template>
  <div class="page">
    <router-link to="/kol" class="back">← 返回 KOL 名单</router-link>

    <el-alert v-if="!validId" type="error" title="无效的博主 id" :closable="false" show-icon />

    <AsyncBlock v-else :loading="profile.loading.value" :error="profile.error.value" :empty="false">
      <div v-if="profile.data.value" class="page">
        <div class="panel header">
          <span class="name">{{ profile.data.value.employee.name }}</span>
          <span class="muted">部门：{{ profile.data.value.employee.dept ?? "—" }}</span>
          <span class="muted">入职：{{ profile.data.value.employee.joinDate ?? "—" }}</span>
        </div>

        <StandingCard :current="profile.data.value.current" />

        <div class="panel">
          <h3 class="panel-title">RFM 分数历史</h3>
          <el-empty v-if="history.length === 0" description="还没有每周重算记录" :image-size="60" />
          <div v-else class="history">
            <div class="chart"><BaseChart :option="historyOption" height="280px" /></div>
            <div class="timeline">
              <h4 class="muted">分层标签变化</h4>
              <el-timeline v-if="profile.data.value.segmentChanges.length > 0">
                <el-timeline-item v-for="c in profile.data.value.segmentChanges" :key="c.asOf" :timestamp="c.asOf">
                  {{ segmentName(c.from) }} → {{ segmentName(c.to) }}
                </el-timeline-item>
              </el-timeline>
              <p v-else class="muted">分层标签没有变化</p>
            </div>
          </div>
        </div>

        <div class="panel granularity">
          <span class="muted">时间粒度</span>
          <el-radio-group v-model="granularity" size="small">
            <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
          </el-radio-group>
        </div>

        <div class="panel">
          <h3 class="panel-title">活跃时间轴</h3>
          <AsyncBlock :loading="activity.loading.value" :error="activity.error.value" :empty="false">
            <BaseChart v-if="activity.data.value" :option="activityOption" height="300px" />
          </AsyncBlock>
        </div>

        <div class="panel">
          <h3 class="panel-title">积分构成趋势</h3>
          <AsyncBlock :loading="trend.loading.value" :error="trend.error.value" :empty="false">
            <BaseChart v-if="trend.data.value" :option="trendOption" height="300px" />
          </AsyncBlock>
        </div>

        <div class="panel">
          <h3 class="panel-title">最近事件</h3>
          <el-table :data="events" size="small" empty-text="没有事件记录" max-height="360">
            <el-table-column label="时间" min-width="160">
              <template #default="{ row }">{{ formatTime(row.time) }}</template>
            </el-table-column>
            <el-table-column label="类型" min-width="100">
              <template #default="{ row }">{{ eventLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column label="关联 id" min-width="100">
              <template #default="{ row }">{{ row.refId ?? "—" }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.back {
  color: var(--color-primary);
  text-decoration: none;
  font-size: 13px;
}
.header {
  display: flex;
  align-items: baseline;
  gap: 16px;
}
.name {
  font-size: 20px;
  font-weight: 600;
}
.history {
  display: grid;
  grid-template-columns: 1fr 240px;
  gap: 16px;
}
.timeline h4 {
  margin: 0 0 8px;
  font-weight: 500;
}
.granularity {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
