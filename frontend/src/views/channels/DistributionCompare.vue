<script setup lang="ts">
import { computed } from "vue";
import type { ChannelRow } from "../../api/types";
import BaseChart from "../../components/BaseChart.vue";
import { buildMixPieOption } from "../../charts/mixPieOption";
import { formatPercent, sumDistribution, sumSourceMix } from "../../utils/channelMetrics";
import { formatNumber } from "../../utils/format";
import DeltaText from "./DeltaText.vue";

const props = defineProps<{ composite: ChannelRow[]; domain: ChannelRow[] }>();

const compositeTotals = computed(() => sumDistribution(props.composite, "current"));
const compositePrev = computed(() => sumDistribution(props.composite, "previous"));
const ownTotals = computed(() => sumDistribution(props.domain, "current"));
const ownPrev = computed(() => sumDistribution(props.domain, "previous"));
const mix = computed(() => sumSourceMix(props.domain, "current"));

const slices = computed(() => [
  { key: "OWN_ENTRY", label: "自主入口", value: mix.value.ownEntry },
  { key: "COMPOSITE_ENTRY", label: "综合入口", value: mix.value.compositeEntry },
  { key: "SEARCH", label: "搜索", value: mix.value.search },
  { key: "SHARE", label: "分享", value: mix.value.share },
  { key: "OTHER", label: "其他", value: mix.value.other },
]);
const option = computed(() => buildMixPieOption(slices.value));
const ownShare = computed(() => (mix.value.total === 0 ? null : mix.value.ownEntry / mix.value.total));
</script>

<template>
  <div class="compare">
    <div class="panel group">
      <h3 class="panel-title">综合频道 · 入口分发</h3>
      <div class="nums">
        <div><div class="label">曝光</div><div class="value">{{ formatNumber(compositeTotals.impressions) }}<DeltaText :current="compositeTotals.impressions" :previous="compositePrev.impressions" /></div></div>
        <div><div class="label">点击</div><div class="value">{{ formatNumber(compositeTotals.clicks) }}<DeltaText :current="compositeTotals.clicks" :previous="compositePrev.clicks" /></div></div>
        <div><div class="label">点击率</div><div class="value">{{ formatPercent(compositeTotals.ctr) }}</div></div>
      </div>
      <p class="muted">关注、推荐、热点、视频、直播五个入口合计</p>
    </div>

    <div class="panel group own">
      <h3 class="panel-title">领域频道 · 自主入口分发</h3>
      <div class="nums">
        <div><div class="label">曝光</div><div class="value">{{ formatNumber(ownTotals.impressions) }}<DeltaText :current="ownTotals.impressions" :previous="ownPrev.impressions" /></div></div>
        <div><div class="label">点击</div><div class="value">{{ formatNumber(ownTotals.clicks) }}<DeltaText :current="ownTotals.clicks" :previous="ownPrev.clicks" /></div></div>
        <div><div class="label">点击率</div><div class="value">{{ formatPercent(ownTotals.ctr) }}</div></div>
      </div>
      <p class="muted">23 个领域频道在自己入口做的分发；占领域内容 PV 的 {{ formatPercent(ownShare) }}</p>
    </div>

    <div class="panel group">
      <h3 class="panel-title">领域内容的来源构成</h3>
      <BaseChart :option="option" height="180px" />
    </div>
  </div>
</template>

<style scoped>
.compare {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
}
.group.own {
  border-color: var(--color-primary);
}
.nums {
  display: flex;
  gap: 24px;
}
.label {
  color: var(--color-text-muted);
  font-size: 13px;
}
.value {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
}
</style>
