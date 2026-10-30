<script setup lang="ts">
import GuidanceButton from "../../components/GuidanceButton.vue";
import { computed, watch } from "vue";
import { fetchCreatorConcentration } from "../../api/people";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildConcentrationOption } from "../../charts/peopleOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import type { Range } from "../../utils/drill";
import { formatNumber } from "../../utils/format";
import { giniLabel } from "../../utils/people";

const props = defineProps<{ range: Range }>();

const report = useAsync((from: string, to: string) => fetchCreatorConcentration(from, to));
watch(() => props.range, () => void report.run(props.range.from, props.range.to), { deep: true, immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildConcentrationOption(data.value.shares) : {}));
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.creators === 0" empty-text="所选区间内没有创作者">
    <div v-if="data" class="page">
      <div class="panel">
        <h3 class="panel-title">头部创作者的贡献 <GuidanceButton id="metric.gini" label="基尼系数" /> <span class="muted">（{{ data.creators }} 名创作者，按 PV 从高到低；基尼系数 {{ giniLabel(data.gini) }}）</span></h3>
        <BaseChart :option="option" height="300px" />
        <p class="muted">基尼系数：0 = 所有创作者 PV 完全均匀，1 = 全部集中在一个人。头部占比过高说明产出依赖少数人，要警惕他们流失。</p>
      </div>

      <div class="panel">
        <h3 class="panel-title">个人账号 vs 团队账号 <span class="muted">（发帖按区间内发布的内容，PV 与互动按区间内发生的浏览与互动）</span></h3>
        <el-table :data="data.accountTypes" size="small">
          <el-table-column label="账号类型" prop="label" min-width="110" />
          <el-table-column label="创作者数" prop="creators" min-width="90" />
          <el-table-column label="发帖数" prop="posts" min-width="90" />
          <el-table-column label="PV" min-width="90"><template #default="{ row }">{{ formatNumber((row as typeof data.accountTypes[number]).pv) }}</template></el-table-column>
          <el-table-column label="互动数" min-width="90"><template #default="{ row }">{{ formatNumber((row as typeof data.accountTypes[number]).interactions) }}</template></el-table-column>
          <el-table-column label="互动率" min-width="90"><template #default="{ row }">{{ formatPercent((row as typeof data.accountTypes[number]).interactionRate) }}</template></el-table-column>
        </el-table>
      </div>
    </div>
  </AsyncBlock>
</template>
