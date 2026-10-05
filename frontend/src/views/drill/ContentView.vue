<script setup lang="ts">
import { computed, watch } from "vue";
import { useRouter } from "vue-router";
import { fetchContent } from "../../api/drill";
import type { Granularity } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { useDrillRoute } from "../../composables/useDrillRoute";
import { formatPercent } from "../../utils/channelMetrics";
import { accountLink, bloggerLink, channelLink } from "../../utils/drill";
import { formatNumber } from "../../utils/format";
import DrillBreadcrumb from "./DrillBreadcrumb.vue";
import MetricCards from "./MetricCards.vue";
import TrendAndMix from "./TrendAndMix.vue";

const router = useRouter();
const { id, range, valid, granularity } = useDrillRoute();
const granularities: Array<{ value: Granularity; label: string }> = [
  { value: "DAY", label: "日" },
  { value: "WEEK", label: "周" },
  { value: "MONTH", label: "月" },
];

const detail = useAsync((contentId: number, from: string, to: string, g: Granularity) => fetchContent(contentId, from, to, g));
watch(
  [id, range, granularity],
  () => valid.value && range.value && void detail.run(id.value, range.value.from, range.value.to, granularity.value),
  { immediate: true },
);

const data = computed(() => detail.data.value);
const crumbs = computed(() => {
  const content = data.value?.content;
  const r = range.value;
  if (!content || !r) {
    return [{ label: "频道驾驶舱", to: { name: "channels" } }, { label: "内容" }];
  }
  return [
    { label: "频道驾驶舱", to: { name: "channels" } },
    { label: content.channelName, to: channelLink(content.channelId, r) },
    { label: content.accountName, to: accountLink(content.accountId, r) },
    { label: `内容 #${content.id}` },
  ];
});

function formatTime(time: string): string {
  return time.replace("T", " ").slice(0, 16);
}
</script>

<template>
  <div class="page">
    <el-alert v-if="!valid" title="链接缺少内容或日期范围，请从账号详情进入" type="warning" show-icon :closable="false" />
    <template v-else-if="range">
      <DrillBreadcrumb :items="crumbs" />
      <div v-if="data" class="panel head">
        <h2 class="name">内容 #{{ data.content.id }}</h2>
        <el-tag effect="plain">{{ data.content.type === "VIDEO" ? "视频" : "文章" }}</el-tag>
        <span class="muted">发布于 {{ formatTime(data.content.publishTime) }}</span>
        <el-tag v-for="tag in data.tags" :key="tag" size="small" type="info">{{ tag }}</el-tag>
        <el-radio-group v-model="granularity" size="small" class="gran">
          <el-radio-button v-for="g in granularities" :key="g.value" :value="g.value">{{ g.label }}</el-radio-button>
        </el-radio-group>
      </div>

      <AsyncBlock :loading="detail.loading.value" :error="detail.error.value" :empty="false">
        <div v-if="data" class="page">
          <div class="panel">
            <span class="muted">频道：</span><a class="link" @click="router.push(channelLink(data.content.channelId, range))">{{ data.content.channelName }}</a>
            <span class="muted">　账号：</span><a class="link" @click="router.push(accountLink(data.content.accountId, range))">{{ data.content.accountName }}</a>
            <span class="muted">　作者：</span>
            <a v-for="author in data.authors" :key="author.id" class="link author" @click="router.push(bloggerLink(author.id, range))">{{ author.name }}</a>
          </div>
          <MetricCards :current="data.current" :previous="data.previous" />
          <TrendAndMix :trend="data.trend" :granularity="data.granularity" :composition="data.composition" />
          <div class="panel">
            <h3 class="panel-title">各入口的曝光与点击 <span class="muted">（入口是用户点击进入的频道入口；含自主入口和综合入口）</span></h3>
            <el-table :data="data.entries" size="small">
              <el-table-column label="入口频道" prop="channelName" min-width="120" />
              <el-table-column label="类别" prop="category" min-width="100" />
              <el-table-column label="曝光" min-width="80"><template #default="{ row }">{{ formatNumber((row as typeof data.entries[number]).impressions) }}</template></el-table-column>
              <el-table-column label="点击" min-width="80"><template #default="{ row }">{{ formatNumber((row as typeof data.entries[number]).clicks) }}</template></el-table-column>
              <el-table-column label="点击率" min-width="80"><template #default="{ row }">{{ formatPercent((row as typeof data.entries[number]).impressions === 0 ? null : (row as typeof data.entries[number]).clicks / (row as typeof data.entries[number]).impressions) }}</template></el-table-column>
            </el-table>
            <p v-if="data.entries.length === 0" class="muted">这段时间没有入口曝光（搜索、分享、其他来源没有曝光）。</p>
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
  gap: 10px;
  flex-wrap: wrap;
}
.name {
  margin: 0;
  font-size: 18px;
}
.gran {
  margin-left: auto;
}
.link {
  color: var(--color-primary);
  cursor: pointer;
}
.author {
  margin-right: 8px;
}
</style>
