<script setup lang="ts">
import GuidanceButton from "../../components/GuidanceButton.vue";
import { computed, watch } from "vue";
import { fetchFunnel } from "../../api/readers";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildFunnelOption } from "../../charts/readerOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import type { Range } from "../../utils/drill";

const props = defineProps<{ range: Range }>();

const report = useAsync((from: string, to: string) => fetchFunnel(from, to));
watch(() => props.range, () => void report.run(props.range.from, props.range.to), { deep: true, immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildFunnelOption(data.value.steps) : {}));
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">新成员激活漏斗 <GuidanceButton id="page.readers.funnel" label="新成员激活漏斗" /> <span class="muted">（对象：{{ range.from }} ~ {{ range.to }} 加入社区的成员）</span></h3>
    <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.joined === 0" empty-text="所选区间内没有新加入的成员">
      <div v-if="data && data.joined > 0">
        <BaseChart :option="option" height="260px" />
        <el-table :data="data.steps" size="small">
          <el-table-column label="步骤" prop="label" min-width="160" />
          <el-table-column label="人数" prop="count" min-width="80" />
          <el-table-column label="占加入人数" min-width="100"><template #default="{ row }">{{ formatPercent((row as typeof data.steps[number]).rateOfJoined) }}</template></el-table-column>
        </el-table>
        <p class="muted">各步骤独立统计（一个人可以没浏览就发帖），所以只给占加入人数的比例；窗口都从加入当天 0 点算起。</p>
      </div>
    </AsyncBlock>
  </div>
</template>
