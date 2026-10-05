<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchChannels, fetchPvRange, searchAccounts, searchEmployees } from "../../api/analytics";
import { fetchComparison } from "../../api/drill";
import type { ChannelOption, CompareType, Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import RemoteSelect from "../../components/RemoteSelect.vue";
import { buildCompareMixOption, buildCompareTrendOption } from "../../charts/compareOption";
import { useAsync } from "../../composables/useAsync";
import { changeRate, defaultCockpitRange } from "../../utils/channelMetrics";
import { domainChannels } from "../../utils/channels";
import { parseIsoDate, toIsoDate, type DateRange } from "../../utils/dateRange";
import { accountLink, bloggerLink, channelLink, compareRows, rangeFromQuery } from "../../utils/drill";
import { formatRate, rateClass } from "../../utils/format";
import DrillBreadcrumb from "./DrillBreadcrumb.vue";

const MAX = 5;
const route = useRoute();
const router = useRouter();

const types: Array<{ value: CompareType; label: string }> = [
  { value: "CHANNEL", label: "领域频道" },
  { value: "ACCOUNT", label: "账号" },
  { value: "BLOGGER", label: "博主（作者）" },
];
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
];

function initialType(): CompareType {
  const t = String(route.query.type ?? "");
  return t === "ACCOUNT" || t === "BLOGGER" ? t : "CHANNEL";
}
function initialIds(): number[] {
  return String(route.query.ids ?? "")
    .split(",")
    .map(Number)
    .filter((n) => Number.isInteger(n) && n > 0)
    .slice(0, MAX);
}

const type = ref<CompareType>(initialType());
const ids = ref<number[]>(initialIds());
const range = ref<DateRange | null>(rangeFromQuery(route.query));
const granularity = ref<Granularity>("WEEK");
const channels = ref<ChannelOption[]>([]);
const names = reactive(new Map<number, string>());
const comparison = useAsync((t: CompareType, list: number[], r: DateRange, g: Granularity) =>
  fetchComparison(t, list, r.from, r.to, g),
);

onMounted(async () => {
  try {
    channels.value = domainChannels(await fetchChannels());
    channels.value.forEach((c) => names.set(c.id, c.name));
    if (!range.value) {
      range.value = defaultCockpitRange(await fetchPvRange());
    }
  } catch {
    // 选择器拿不到数据时页面仍可用，只是下拉为空
  }
});

watch(
  [type, ids, range, granularity],
  () => {
    if (range.value && ids.value.length >= 2 && ids.value.length <= MAX) {
      void comparison.run(type.value, ids.value, range.value, granularity.value);
    }
    void router.replace({
      query: { type: type.value, ids: ids.value.join(","), ...(range.value ?? {}) },
    });
  },
  { deep: true, immediate: true },
);

watch(type, (_, previous) => previous !== undefined && (ids.value = []));

watch(
  () => comparison.data.value,
  (data) => data?.subjects.forEach((s) => names.set(s.id, s.name)),
);

const picker = computed<[Date, Date] | null>(() =>
  range.value ? [parseIsoDate(range.value.from), parseIsoDate(range.value.to)] : null,
);
function onPicker(value: [Date, Date] | null): void {
  if (value) {
    range.value = { from: toIsoDate(value[0]), to: toIsoDate(value[1]) };
  }
}

function add(id: number | undefined): void {
  if (id !== undefined && !ids.value.includes(id) && ids.value.length < MAX) {
    ids.value = [...ids.value, id];
  }
}
function remove(id: number): void {
  ids.value = ids.value.filter((x) => x !== id);
}

// 远程搜索的结果顺便记下名字，已选对象的标签才显示得出名字
const remember = (search: (keyword: string) => Promise<Array<{ id: number; name: string }>>) => async (keyword: string) => {
  const list = await search(keyword);
  list.forEach((o) => names.set(o.id, o.name));
  return list;
};
const accountFetcher = remember(searchAccounts);
const bloggerFetcher = remember(searchEmployees);
const channelChoices = computed(() => channels.value.filter((c) => !ids.value.includes(c.id)));

const data = computed(() => comparison.data.value);
const rows = computed(() => (data.value ? compareRows(data.value) : []));
const trendOption = computed(() => (data.value ? buildCompareTrendOption(data.value.subjects, data.value.granularity) : {}));
const mixOption = computed(() => (data.value ? buildCompareMixOption(data.value.subjects) : {}));
const mixHeight = computed(() => `${Math.max(data.value?.subjects.length ?? 2, 2) * 56 + 60}px`);

function subjectLink(id: number) {
  const r = range.value ?? { from: "", to: "" };
  return type.value === "CHANNEL" ? channelLink(id, r) : type.value === "ACCOUNT" ? accountLink(id, r) : bloggerLink(id, r);
}
</script>

<template>
  <div class="page">
    <DrillBreadcrumb :items="[{ label: '频道驾驶舱', to: { name: 'channels' } }, { label: '对比分析' }]" />

    <div class="panel controls">
      <el-radio-group v-model="type">
        <el-radio-button v-for="t in types" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>

      <el-select v-if="type === 'CHANNEL'" :model-value="undefined" filterable placeholder="添加领域频道" style="width: 200px" @update:model-value="add">
        <el-option v-for="c in channelChoices" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <RemoteSelect v-else-if="type === 'ACCOUNT'" :model-value="undefined" :fetcher="accountFetcher" placeholder="搜索并添加账号" style="width: 200px" @update:model-value="add" />
      <RemoteSelect v-else :model-value="undefined" :fetcher="bloggerFetcher" placeholder="搜索并添加博主" style="width: 200px" @update:model-value="add" />

      <el-date-picker :model-value="picker" type="daterange" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" @update:model-value="onPicker" />
      <el-radio-group v-model="granularity" size="small">
        <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <div class="panel chips">
      <span class="muted">已选（2–{{ MAX }} 个同类对象）：</span>
      <el-tag v-for="id in ids" :key="id" closable effect="plain" @close="remove(id)">{{ names.get(id) ?? `#${id}` }}</el-tag>
      <span v-if="ids.length === 0" class="muted">还没有选择对象</span>
    </div>

    <el-alert v-if="ids.length === 1" title="再选至少一个同类对象才能对比" type="info" show-icon :closable="false" />

    <AsyncBlock :loading="comparison.loading.value" :error="comparison.error.value" :empty="false">
      <div v-if="data && ids.length >= 2" class="page">
        <div class="panel">
          <h3 class="panel-title">指标对照 <span class="muted">（绿色标出每行最优；环比对比 {{ data.previousFrom }} ~ {{ data.previousTo }}；博主按作者归属统计，合著内容每位作者都记完整的 PV）</span></h3>
          <table class="compare">
            <thead>
              <tr>
                <th>指标</th>
                <th v-for="subject in data.subjects" :key="subject.id">
                  <a class="link" @click="router.push(subjectLink(subject.id))">{{ subject.name }}</a>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.key">
                <td class="metric">{{ row.label }}</td>
                <td v-for="(cell, i) in row.cells" :key="i" :class="{ best: cell.best }">
                  {{ cell.text }}
                  <span v-if="cell.previous !== null && cell.previous !== undefined" class="delta" :class="rateClass(changeRate(cell.current, cell.previous))">
                    {{ formatRate(changeRate(cell.current, cell.previous)) }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="grid">
          <div class="panel">
            <h3 class="panel-title">PV 趋势</h3>
            <BaseChart :option="trendOption" height="320px" />
          </div>
          <div class="panel">
            <h3 class="panel-title">PV 来源构成（占各自 PV 的比例）</h3>
            <BaseChart :option="mixOption" :height="mixHeight" />
          </div>
        </div>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.chips {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.compare {
  width: 100%;
  border-collapse: collapse;
}
.compare th,
.compare td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border);
  text-align: right;
}
.compare th:first-child,
.compare td.metric {
  text-align: left;
  color: var(--color-text-muted);
}
.compare td.best {
  background: #e6f6ec;
  color: #1a7f46;
  font-weight: 600;
}
.delta {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
}
.link {
  color: var(--color-primary);
  cursor: pointer;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}
</style>
