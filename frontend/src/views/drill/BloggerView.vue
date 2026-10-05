<script setup lang="ts">
import { computed, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchBlogger } from "../../api/drill";
import type { BreakdownRow, Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { useDrillRoute } from "../../composables/useDrillRoute";
import { accountLink, channelLink } from "../../utils/drill";
import BreakdownTable from "./BreakdownTable.vue";
import DrillBreadcrumb from "./DrillBreadcrumb.vue";
import MetricCards from "./MetricCards.vue";
import TopContentsTable from "./TopContentsTable.vue";
import TrendAndMix from "./TrendAndMix.vue";

const router = useRouter();
const { id, range, valid, granularity } = useDrillRoute();
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
];

const detail = useAsync((employeeId: number, from: string, to: string, g: Granularity) => fetchBlogger(employeeId, from, to, g));
watch(
  [id, range, granularity],
  () => valid.value && range.value && void detail.run(id.value, range.value.from, range.value.to, granularity.value),
  { immediate: true },
);

const data = computed(() => detail.data.value);
const crumbs = computed(() => [{ label: "频道驾驶舱", to: { name: "channels" } }, { label: data.value ? `博主：${data.value.blogger.name}` : "博主" }]);

function openAccount(row: BreakdownRow): void {
  if (range.value) void router.push(accountLink(row.id, range.value));
}
function openChannel(row: BreakdownRow): void {
  if (range.value) void router.push(channelLink(row.id, range.value));
}
function openKol(): void {
  void router.push({ name: "kol-detail", params: { id: id.value } });
}
</script>

<template>
  <div class="page">
    <el-alert v-if="!valid" title="链接缺少博主或日期范围，请从账号详情进入" type="warning" show-icon :closable="false" />
    <template v-else-if="range">
      <DrillBreadcrumb :items="crumbs" />
      <div class="panel head">
        <h2 v-if="data" class="name">{{ data.blogger.name }}</h2>
        <el-tag v-if="data?.blogger.dept" effect="plain">{{ data.blogger.dept }}</el-tag>
        <span v-if="data?.blogger.communityJoinDate" class="muted">{{ data.blogger.communityJoinDate.slice(0, 10) }} 加入社区</span>
        <span class="muted">{{ range.from }} ~ {{ range.to }}</span>
        <el-button v-if="data?.blogger.kolProfile" size="small" type="primary" plain @click="openKol">KOL 详情</el-button>
        <el-radio-group v-model="granularity" size="small" class="gran">
          <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <p class="muted note">这里只看他"作为作者"的表现：合著内容每位作者都记完整的 PV 和互动。</p>

      <AsyncBlock :loading="detail.loading.value" :error="detail.error.value" :empty="false">
        <div v-if="data" class="page">
          <MetricCards :current="data.current" :previous="data.previous" show-supply />
          <TrendAndMix :trend="data.trend" :granularity="data.granularity" :composition="data.composition" />
          <div class="panel">
            <h3 class="panel-title">所属账号 <span class="muted">（点一行看账号）</span></h3>
            <BreakdownTable :rows="data.accounts" name-label="账号" extra-label="类型" show-followers @open="openAccount" />
          </div>
          <div class="panel">
            <h3 class="panel-title">各频道分布 <span class="muted">（点一行看频道）</span></h3>
            <BreakdownTable :rows="data.channels" name-label="频道" extra-label="类别" @open="openChannel" />
          </div>
          <div class="panel">
            <h3 class="panel-title">PV 最高的内容 Top {{ data.topContents.length }}</h3>
            <TopContentsTable :contents="data.topContents" :range="range" />
          </div>
        </div>
      </AsyncBlock>
    </template>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.name {
  margin: 0;
  font-size: 18px;
}
.gran {
  margin-left: auto;
}
.note {
  margin: 0;
}
</style>
