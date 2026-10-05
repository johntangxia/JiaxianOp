<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { fetchUserInterests } from "../../api/people";
import type { UserDeptInterest } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import { formatNumber } from "../../utils/format";

const props = defineProps<{ asOf: string }>();

const days = ref(90);
const choices = [30, 90, 180];
const report = useAsync((asOf: string, d: number) => fetchUserInterests(asOf, d));
watch([() => props.asOf, days], () => void report.run(props.asOf, days.value), { immediate: true });

const data = computed(() => report.data.value);
const r = (row: unknown): UserDeptInterest => row as UserDeptInterest;
function others(row: UserDeptInterest): string {
  return row.topOthers.length === 0 ? "—" : row.topOthers.map((o) => `${o.domain} ${formatPercent(o.share, 0)}`).join("、");
}
</script>

<template>
  <div class="panel">
    <div class="head">
      <h3 class="panel-title">兴趣与跨领域 <span class="muted">（部门 = 领域；看各领域成员的阅读去向：本领域占比低说明爱看别的领域，其他领域里谁在吸引他们）</span></h3>
      <el-radio-group v-model="days" size="small">
        <el-radio-button v-for="d in choices" :key="d" :value="d">近 {{ d }} 天</el-radio-button>
      </el-radio-group>
    </div>
    <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="!!data && data.depts.length === 0" empty-text="没有部门数据">
      <div v-if="data">
        <el-table :data="data.depts" size="small">
          <el-table-column label="部门（领域）" prop="dept" min-width="140" />
          <el-table-column label="成员" prop="members" min-width="70" />
          <el-table-column label="读者" prop="readers" min-width="70" />
          <el-table-column label="浏览数" min-width="90"><template #default="{ row }">{{ formatNumber(r(row).views) }}</template></el-table-column>
          <el-table-column label="本领域阅读占比" min-width="170">
            <template #default="{ row }">
              <div v-if="r(row).ownShare !== null" class="own">
                <el-progress :percentage="Math.round(r(row).ownShare! * 100)" :stroke-width="10" :show-text="false" />
                <span>{{ formatPercent(r(row).ownShare) }}</span>
              </div>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="最爱看的其他领域（占其浏览比例）" min-width="280"><template #default="{ row }">{{ others(r(row)) }}</template></el-table-column>
        </el-table>
        <p class="muted">统计日 {{ data.asOf }} 往前 {{ data.days }} 天；浏览按内容所属频道归到领域；"读者"是区间内有过浏览的去重人数。</p>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.own {
  display: flex;
  align-items: center;
  gap: 8px;
}
.own :deep(.el-progress) {
  flex: 1;
}
</style>
