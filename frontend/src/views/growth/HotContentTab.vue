<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { fetchHotContents } from "../../api/growth";
import type { GrowthHotRow } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { formatPercent } from "../../utils/channelMetrics";
import { contentLink } from "../../utils/drill";
import { draftFromContent, type TaskDraft } from "../../utils/growth";

const emit = defineEmits<{ createTask: [draft: TaskDraft] }>();
const router = useRouter();

const report = useAsync(() => fetchHotContents(20));
onMounted(() => void report.run());
const data = computed(() => report.data.value);

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): GrowthHotRow => row as GrowthHotRow;
const times = (value: number | null): string => (value === null ? "—" : `${value.toFixed(1)}×`);

function view(row: GrowthHotRow): void {
  if (data.value) {
    const to = data.value.asOf;
    const from = new Date(new Date(to).getTime() - 29 * 86_400_000).toISOString().slice(0, 10);
    void router.push(contentLink(row.contentId, { from, to }));
  }
}
</script>

<template>
  <AsyncBlock :loading="report.loading.value" :error="report.error.value" :empty="false">
    <div v-if="data" class="page">
      <div class="panel">
        <h3 class="panel-title">热点内容 <span class="muted">（近 3 天 PV ≥ 10 且 ≥ 同频道基线的 3 倍；截至 {{ data.asOf }}）</span></h3>
        <el-table :data="data.hot" size="small" empty-text="近 3 天没有达到热点标准的内容">
          <el-table-column label="内容" min-width="90"><template #default="{ row }">#{{ r(row).contentId }}（{{ r(row).type === "VIDEO" ? "视频" : "文章" }}）</template></el-table-column>
          <el-table-column label="频道" prop="channelName" min-width="100" />
          <el-table-column label="账号" prop="accountName" min-width="120" />
          <el-table-column label="近 3 天 PV" prop="pv" min-width="90" />
          <el-table-column label="前 3 天 PV" min-width="90"><template #default="{ row }">{{ r(row).previousPv ?? "—" }}</template></el-table-column>
          <el-table-column label="热度倍数" min-width="90"><template #default="{ row }">{{ times(r(row).hotMultiple) }}</template></el-table-column>
          <el-table-column label="增长" min-width="80"><template #default="{ row }">{{ times(r(row).growth) }}</template></el-table-column>
          <el-table-column label="互动率" min-width="80"><template #default="{ row }">{{ formatPercent(r(row).interactionRate) }}</template></el-table-column>
          <el-table-column label="读完率" min-width="80"><template #default="{ row }">{{ formatPercent(r(row).completionRate) }}</template></el-table-column>
          <el-table-column label="操作" min-width="150">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="view(r(row))">看内容</el-button>
              <el-button size="small" link type="primary" @click="emit('createTask', draftFromContent(r(row), false))">创建任务</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <h3 class="panel-title">潜力内容 <span class="muted">（近 30 天 PV 不高，但互动率或读完率达到同频道中位数的 1.5 倍以上，适合再推一把）</span></h3>
        <el-table :data="data.potential" size="small" empty-text="没有符合条件的潜力内容">
          <el-table-column label="内容" min-width="90"><template #default="{ row }">#{{ r(row).contentId }}（{{ r(row).type === "VIDEO" ? "视频" : "文章" }}）</template></el-table-column>
          <el-table-column label="频道" prop="channelName" min-width="100" />
          <el-table-column label="账号" prop="accountName" min-width="120" />
          <el-table-column label="近 30 天 PV" prop="pv" min-width="100" />
          <el-table-column label="互动率" min-width="80"><template #default="{ row }">{{ formatPercent(r(row).interactionRate) }}</template></el-table-column>
          <el-table-column label="读完率" min-width="80"><template #default="{ row }">{{ formatPercent(r(row).completionRate) }}</template></el-table-column>
          <el-table-column label="质量倍数" min-width="90"><template #default="{ row }">{{ times(r(row).qualityMultiple) }}</template></el-table-column>
          <el-table-column label="操作" min-width="150">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="view(r(row))">看内容</el-button>
              <el-button size="small" link type="primary" @click="emit('createTask', draftFromContent(r(row), true))">创建任务</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </AsyncBlock>
</template>
