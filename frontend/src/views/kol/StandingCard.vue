<script setup lang="ts">
import type { CurrentStanding } from "../../api/types";
import { formatNumber, formatRate } from "../../utils/format";
import { segmentTagType } from "../../utils/segment";

defineProps<{ current: CurrentStanding | null }>();

const metricText = { ACTIVITY: "活跃次数", POINTS: "积分" } as const;

function scoreClass(score: number): string {
  return score >= 4 ? "score-high" : score <= 2 ? "score-low" : "score-mid";
}
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">当前状态</h3>

    <el-empty v-if="!current" description="不在最近一次名单内" :image-size="60" />

    <template v-else>
      <div class="row">
        <el-tag :type="segmentTagType(current.segment)" effect="plain">{{ current.segmentName }}</el-tag>
        <el-tag v-if="current.churnAlert" type="danger" effect="dark">预警</el-tag>
        <span class="muted">统计日 {{ current.asOf }}　名单名次 {{ current.rank }}　近 12 个月积分 {{ formatNumber(current.rollingPoints12m) }}</span>
      </div>

      <div class="grid">
        <div>
          <div class="muted">新近度 R</div>
          <div><span class="score-chip" :class="scoreClass(current.rScore)">{{ current.rScore }}</span>
            距今 {{ current.recencyDays ?? "—" }} 天</div>
        </div>
        <div>
          <div class="muted">频次 F</div>
          <div><span class="score-chip" :class="scoreClass(current.fScore)">{{ current.fScore }}</span>
            近 90 天 {{ current.frequency }} 次</div>
        </div>
        <div>
          <div class="muted">产出 M</div>
          <div><span class="score-chip" :class="scoreClass(current.mScore)">{{ current.mScore }}</span>
            近 90 天 {{ formatNumber(current.monetary) }} 分</div>
        </div>
      </div>

      <el-alert
        v-if="current.alert"
        type="error"
        :closable="false"
        show-icon
        :title="`流失预警：${metricText[current.alert.metric]}下降 ${formatRate(current.alert.dropRate).replace('+', '')}`"
        :description="`近期日均 ${current.alert.recentDaily.toFixed(2)}，基线日均 ${current.alert.baselineDaily.toFixed(2)}`"
        style="margin-top: 12px"
      />

      <div class="advice">
        <div class="muted">建议</div>
        <div>{{ current.recommendation ?? "暂无匹配的建议文案" }}</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 14px;
}
.advice {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f7f9fc;
  line-height: 1.6;
}
</style>
