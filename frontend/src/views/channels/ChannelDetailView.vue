<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchChannelDetail } from "../../api/channels";
import { fetchChannelAccounts } from "../../api/drill";
import type { BreakdownRow, Granularity, TopContent } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildChannelTrendOption } from "../../charts/channelTrendOption";
import { buildMixPieOption } from "../../charts/mixPieOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent, formatSeconds, severityTagType } from "../../utils/channelMetrics";
import { parseIsoDate } from "../../utils/dateRange";
import { formatNumber } from "../../utils/format";
import { accountLink, channelLink } from "../../utils/drill";
import BreakdownTable from "../drill/BreakdownTable.vue";
import DeltaText from "./DeltaText.vue";

const route = useRoute();
const router = useRouter();

const channelId = computed(() => Number(route.params.id));
const from = computed(() => String(route.query.from ?? ""));
const to = computed(() => String(route.query.to ?? ""));
const valid = computed(() => Number.isInteger(channelId.value) && channelId.value > 0 && !!from.value && !!to.value);

// 区间超过 45 天时默认按周看，按天太碎
const rangeDays = (parseIsoDate(to.value).getTime() - parseIsoDate(from.value).getTime()) / 86_400_000 + 1;
const granularity = ref<Granularity>(rangeDays > 45 ? "WEEK" : "DAY");
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
];

const detail = useAsync((id: number, f: string, t: string, g: Granularity) => fetchChannelDetail(id, f, t, g));

watch(
  [channelId, from, to, granularity],
  () => {
    if (valid.value) {
      void detail.run(channelId.value, from.value, to.value, granularity.value);
    }
  },
  { immediate: true },
);

// 领域频道下的账号（下钻的下一级）；综合频道没有内容，不请求
const accounts = useAsync((id: number, f: string, t: string) => fetchChannelAccounts(id, f, t));
watch(
  [channelId, from, to, () => detail.data.value?.row.category],
  () => {
    if (valid.value && detail.data.value && detail.data.value.row.category !== "综合频道") {
      void accounts.run(channelId.value, from.value, to.value);
    }
  },
);

const row = computed(() => detail.data.value?.row ?? null);
const composite = computed(() => row.value?.category === "综合频道");
const current = computed(() => row.value?.current ?? null);
const previous = computed(() => row.value?.previous ?? null);

const trendOption = computed(() =>
  detail.data.value ? buildChannelTrendOption(detail.data.value.trend, detail.data.value.granularity, composite.value) : {},
);
const pieOption = computed(() => buildMixPieOption(detail.data.value?.composition ?? []));
const topContents = computed<TopContent[]>(() => detail.data.value?.topContents ?? []);

function formatTime(time: string): string {
  return time.replace("T", " ").slice(0, 16);
}

function openAccount(account: BreakdownRow): void {
  void router.push(accountLink(account.id, { from: from.value, to: to.value }));
}

// 综合频道的构成图：点一块进入对应的领域频道
function onPieClick(params: { name: string }): void {
  const slice = detail.data.value?.composition.find((s) => s.label === params.name);
  const domainId = slice ? Number(slice.key) : NaN;
  if (composite.value && Number.isInteger(domainId)) {
    void router.push(channelLink(domainId, { from: from.value, to: to.value }));
  }
}

function back(): void {
  void router.push({ name: "channels" });
}
</script>

<template>
  <div class="page">
    <el-alert v-if="!valid" title="链接缺少频道或日期范围，请从频道驾驶舱进入" type="warning" show-icon :closable="false" />
    <template v-else>
      <div class="panel head">
        <el-button size="small" @click="back">← 返回驾驶舱</el-button>
        <h2 v-if="row" class="name">{{ row.name }}</h2>
        <el-tag v-if="row" :type="composite ? 'warning' : 'primary'" effect="plain">{{ row.category }}</el-tag>
        <span class="muted">{{ from }} ~ {{ to }}</span>
        <el-radio-group v-model="granularity" size="small" class="gran">
          <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
        </el-radio-group>
      </div>

      <AsyncBlock :loading="detail.loading.value" :error="detail.error.value" :empty="false">
        <div v-if="row && current && previous" class="page">
          <div class="kpis">
            <template v-if="composite">
              <div class="panel kpi"><div class="label">曝光</div><div class="value">{{ formatNumber(current.distribution.impressions) }}<DeltaText :current="current.distribution.impressions" :previous="previous.distribution.impressions" /></div></div>
              <div class="panel kpi"><div class="label">点击</div><div class="value">{{ formatNumber(current.distribution.clicks) }}<DeltaText :current="current.distribution.clicks" :previous="previous.distribution.clicks" /></div></div>
              <div class="panel kpi"><div class="label">点击率</div><div class="value">{{ formatPercent(current.distribution.ctr) }}</div></div>
              <div class="panel kpi"><div class="label">触达 / 点击 UV</div><div class="value">{{ formatNumber(current.distribution.reachUv) }} / {{ formatNumber(current.distribution.clickUv) }}</div></div>
              <div class="panel kpi"><div class="label">覆盖领域</div><div class="value">{{ current.coverage?.domainsCovered ?? 0 }}</div></div>
            </template>
            <template v-else-if="current.content && current.supply && previous.content">
              <div class="panel kpi"><div class="label">PV</div><div class="value">{{ formatNumber(current.content.pv) }}<DeltaText :current="current.content.pv" :previous="previous.content.pv" /></div></div>
              <div class="panel kpi own"><div class="label">自主分发占比</div><div class="value">{{ formatPercent(current.content.ownEntryShare) }}</div><div class="sub">上期 {{ formatPercent(previous.content.ownEntryShare) }}</div></div>
              <div class="panel kpi own"><div class="label">自主入口 曝光 / 点击 / 点击率</div><div class="value small">{{ formatNumber(current.distribution.impressions) }} / {{ formatNumber(current.distribution.clicks) }} / {{ formatPercent(current.distribution.ctr) }}</div></div>
              <div class="panel kpi"><div class="label">综合依赖度</div><div class="value">{{ formatPercent(current.content.compositeDependency) }}</div><div class="sub">上期 {{ formatPercent(previous.content.compositeDependency) }}</div></div>
              <div class="panel kpi"><div class="label">UV</div><div class="value">{{ formatNumber(current.content.uv) }}</div></div>
              <div class="panel kpi"><div class="label">互动率</div><div class="value">{{ formatPercent(current.content.interactionRate) }}</div><div class="sub">互动 {{ formatNumber(current.content.interactions) }}</div></div>
              <div class="panel kpi"><div class="label">发帖 / 作者</div><div class="value">{{ current.supply.contents }} / {{ current.supply.authors }}</div></div>
              <div class="panel kpi"><div class="label">入口平均停留</div><div class="value">{{ formatSeconds(current.distribution.avgDwellSeconds) }}</div></div>
            </template>
          </div>

          <div class="panel">
            <h3 class="panel-title">诊断</h3>
            <p v-if="row.diagnoses.length === 0" class="muted">没有发现明显问题。</p>
            <ul v-else class="diagnoses">
              <li v-for="d in row.diagnoses" :key="d.code">
                <el-tag :type="severityTagType(d.severity)" size="small" effect="light">{{ d.severity === "CRITICAL" ? "严重" : d.severity === "WARN" ? "警告" : "提示" }}</el-tag>
                {{ d.message }}
              </li>
            </ul>
          </div>

          <div class="grid">
            <div class="panel">
              <h3 class="panel-title">{{ composite ? "入口曝光与点击趋势" : "PV 来源趋势与自主入口点击率" }}</h3>
              <BaseChart :option="trendOption" height="320px" />
            </div>
            <div class="panel">
              <h3 class="panel-title">{{ composite ? "点击内容所属领域" : "PV 来源构成" }}</h3>
              <BaseChart :option="pieOption" height="320px" @chart-click="onPieClick" />
              <p v-if="composite" class="muted">点一块进入对应领域频道</p>
            </div>
          </div>

          <div v-if="!composite" class="panel">
            <h3 class="panel-title">该频道下的账号 <span class="muted">（指标只统计该频道里的内容；点一行进入账号）</span></h3>
            <AsyncBlock :loading="accounts.loading.value" :error="accounts.error.value" :empty="false">
              <BreakdownTable v-if="accounts.data.value" :rows="accounts.data.value.accounts" name-label="账号" extra-label="类型" show-followers @open="openAccount" />
            </AsyncBlock>
          </div>

          <div class="panel">
            <h3 class="panel-title">{{ composite ? "点击最多的内容" : "PV 最高的内容" }} Top {{ topContents.length }}</h3>
            <el-table :data="topContents" size="small">
              <el-table-column label="内容" min-width="100"><template #default="{ row: item }">内容 #{{ (item as TopContent).contentId }}</template></el-table-column>
              <el-table-column label="类型" min-width="80"><template #default="{ row: item }">{{ (item as TopContent).type === "VIDEO" ? "视频" : "文章" }}</template></el-table-column>
              <el-table-column label="账号" min-width="140"><template #default="{ row: item }">{{ (item as TopContent).accountName }}</template></el-table-column>
              <el-table-column label="发布时间" min-width="140"><template #default="{ row: item }">{{ formatTime((item as TopContent).publishTime) }}</template></el-table-column>
              <el-table-column :label="composite ? '点击' : 'PV'" min-width="80"><template #default="{ row: item }">{{ formatNumber((item as TopContent).pv) }}</template></el-table-column>
              <el-table-column label="互动数" min-width="80"><template #default="{ row: item }">{{ formatNumber((item as TopContent).interactions) }}</template></el-table-column>
            </el-table>
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
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}
.kpi.own {
  border-color: var(--color-primary);
}
.label {
  color: var(--color-text-muted);
  font-size: 13px;
}
.value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 600;
}
.value.small {
  font-size: 18px;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.diagnoses {
  margin: 0;
  padding-left: 18px;
  line-height: 2;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}
</style>
