<script setup lang="ts">
import { computed, watch } from "vue";
import { fetchCreatorLadder } from "../../api/people";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildLadderOption } from "../../charts/peopleOption";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import type { Range } from "../../utils/drill";
import { formatDays } from "../../utils/people";

// 阶梯按"截至统计日的累计行为"判定，统计日取所选区间的结束日
const props = defineProps<{ range: Range }>();

const report = useAsync((asOf: string) => fetchCreatorLadder(asOf));
watch(() => props.range.to, () => void report.run(props.range.to), { immediate: true });

const data = computed(() => report.data.value);
const option = computed(() => (data.value ? buildLadderOption(data.value.stages) : {}));
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.members === 0" empty-text="统计日还没有成员">
    <div v-if="data" class="page">
      <div class="panel">
        <h3 class="panel-title">成长阶梯 <span class="muted">（截至 {{ data.asOf }}，{{ data.members }} 名成员，每人只算最高的一个阶段）</span></h3>
        <BaseChart :option="option" height="280px" />
        <el-table :data="data.stages" size="small">
          <el-table-column label="阶段" prop="label" min-width="110" />
          <el-table-column label="人数" prop="count" min-width="80" />
          <el-table-column label="占成员" min-width="90"><template #default="{ row }">{{ formatPercent((row as typeof data.stages[number]).share) }}</template></el-table-column>
        </el-table>
        <p class="muted">
          未激活：从未有过行为；读者：有行为但从未互动或发帖；互动者：互动过但没发过帖；创作者：发过帖但近 90 天少于 3 篇；活跃创作者：近 90 天发帖 ≥ 3 篇；
          KOL：{{ data.kolDate ? `${data.kolDate} 那次重算里的明星 KOL` : "还没有 KOL 重算记录，暂无" }}。
        </p>
      </div>

      <div class="panel">
        <h3 class="panel-title">读者走向创作者：到达率与耗时 <span class="muted">（各步骤独立统计；耗时 = 从加入社区到那一步的中位天数，只统计走到的人）</span></h3>
        <el-table :data="data.reach" size="small">
          <el-table-column label="步骤" prop="label" min-width="120" />
          <el-table-column label="到达人数" prop="reached" min-width="100" />
          <el-table-column label="占成员" min-width="100"><template #default="{ row }">{{ formatPercent((row as typeof data.reach[number]).share) }}</template></el-table-column>
          <el-table-column label="中位耗时" min-width="110"><template #default="{ row }">{{ formatDays((row as typeof data.reach[number]).medianDays) }}</template></el-table-column>
        </el-table>
      </div>
    </div>
  </AsyncBlock>
</template>
