<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchBoards, fetchPeriods } from "../../api/contest";
import type { ContestEntry, ContestType, ContestPeriod } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { defaultPeriod, periodLabel } from "../../utils/contest";
import BoardTable from "./BoardTable.vue";
import ImproversPanel from "./ImproversPanel.vue";
import PodiumCards from "./PodiumCards.vue";

const router = useRouter();

const type = ref<ContestType>("MONTH");
const periods = ref<ContestPeriod[]>([]);
const periodStart = ref<string | null>(null);
const league = ref<"domain" | "composite">("domain");
const initializing = ref(true);
const initError = ref<string | null>(null);

const boards = useAsync((t: ContestType, start: string) => fetchBoards(t, start));

async function loadPeriods(): Promise<void> {
  try {
    periods.value = await fetchPeriods(type.value);
    periodStart.value = defaultPeriod(periods.value)?.periodStart ?? null;
    initError.value = null;
  } catch (e) {
    initError.value = e instanceof Error ? e.message : String(e);
  } finally {
    initializing.value = false;
  }
}

onMounted(loadPeriods);
watch(type, () => void loadPeriods());
watch(periodStart, (start) => start && void boards.run(type.value, start));

const periodOptions = computed(() =>
  periods.value.map((p) => ({ value: p.periodStart, label: periodLabel(type.value, p) })),
);
const entries = computed<ContestEntry[]>(() => {
  const data = boards.data.value;
  return data ? (league.value === "domain" ? data.domain : data.composite) : [];
});

function open(entry: ContestEntry): void {
  const data = boards.data.value;
  if (data) {
    void router.push({
      name: "channel-detail",
      params: { id: entry.channelId },
      query: { from: data.periodStart, to: data.periodEnd },
    });
  }
}
</script>

<template>
  <div class="page">
    <AsyncBlock :loading="initializing" :error="initError" :empty="!initializing && periods.length === 0" empty-text="暂无 PV 数据，请先在后台回填并重算汇总">
      <div class="page">
        <div class="panel bar">
          <el-radio-group v-model="type">
            <el-radio-button value="WEEK">周榜</el-radio-button>
            <el-radio-button value="MONTH">月榜</el-radio-button>
          </el-radio-group>
          <el-select v-model="periodStart" style="width: 240px" placeholder="选择周期">
            <el-option v-for="o in periodOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <span v-if="boards.data.value" class="muted">
            对比上一周期 {{ boards.data.value.previousStart }} ~ {{ boards.data.value.previousEnd }}
          </span>
        </div>

        <AsyncBlock :loading="boards.loading.value" :error="boards.error.value" :empty="false">
          <div v-if="boards.data.value" class="page">
            <el-tabs v-model="league">
              <el-tab-pane label="领域榜（23 个领域频道）" name="domain" />
              <el-tab-pane label="综合榜（5 个综合入口）" name="composite" />
            </el-tabs>

            <PodiumCards :entries="entries" @open="open" />

            <div class="main">
              <div class="panel table">
                <h3 class="panel-title">完整榜单 <span class="muted">（健康分 = 各维度榜内百分位得分的加权和；点一行看频道详情）</span></h3>
                <BoardTable :entries="entries" @open="open" />
              </div>
              <ImproversPanel
                v-if="league === 'domain'"
                class="side"
                :improvers="boards.data.value.improvers"
                :previous-has-data="boards.data.value.previousHasData"
                @open="open"
              />
            </div>
          </div>
        </AsyncBlock>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1100px) {
  .main {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
