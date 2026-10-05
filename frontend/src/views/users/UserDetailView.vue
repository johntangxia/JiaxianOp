<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fetchUserProfile } from "../../api/people";
import AsyncBlock from "../../components/AsyncBlock.vue";
import BaseChart from "../../components/BaseChart.vue";
import { buildSourcePieOption, buildTimelineOption } from "../../charts/peopleOption";
import { useAsync } from "../../composables/useAsync";
import { eventLabel, actionLabel } from "../../utils/actionTypes";
import { formatPercent, formatSeconds } from "../../utils/channelMetrics";
import { bloggerLink } from "../../utils/drill";
import { formatNumber } from "../../utils/format";
import { dateOnly } from "../../utils/readers";
import { lifecycleTagType, shortTime, valueTagType } from "../../utils/people";

const route = useRoute();
const router = useRouter();
const employeeId = computed(() => Number(route.params.id));
const validId = computed(() => Number.isInteger(employeeId.value) && employeeId.value > 0);
const asOf = computed(() => (typeof route.query.asOf === "string" && /^\d{4}-\d{2}-\d{2}$/.test(route.query.asOf) ? route.query.asOf : undefined));

const profile = useAsync((id: number, date: string | undefined) => fetchUserProfile(id, date));
watch([employeeId, asOf], () => validId.value && void profile.run(employeeId.value, asOf.value), { immediate: true });

const data = computed(() => profile.data.value);
const timelineOption = computed(() => (data.value ? buildTimelineOption(data.value.timeline) : {}));
const sourceOption = computed(() => (data.value ? buildSourcePieOption(data.value.reading.sources) : {}));

function eventText(type: string): string {
  const base = eventLabel(type);
  return base === type ? actionLabel(type) : base;
}

// 博主页要带日期范围：以统计日为终点往前 180 天
function openBlogger(): void {
  if (!data.value) {
    return;
  }
  const end = new Date(`${data.value.asOf}T00:00:00`);
  const start = new Date(end);
  start.setDate(start.getDate() - 179);
  const iso = (d: Date): string => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  void router.push(bloggerLink(employeeId.value, { from: iso(start), to: data.value.asOf }));
}
</script>

<template>
  <div class="page">
    <router-link to="/users" class="back">← 返回用户分析</router-link>

    <el-alert v-if="!validId" type="error" title="无效的用户 id" :closable="false" show-icon />

    <AsyncBlock v-else :loading="profile.loading.value" :error="profile.error.value" :empty="false">
      <div v-if="data" class="page">
        <div class="panel header">
          <div class="who">
            <span class="name">{{ data.basic.name }}</span>
            <span class="muted">部门：{{ data.basic.dept ?? "—" }}</span>
            <span class="muted">加入社区：{{ dateOnly(data.basic.joinDate) }}<template v-if="data.basic.daysInCommunity !== null">（{{ data.basic.daysInCommunity }} 天）</template></span>
            <span class="muted">统计日：{{ data.asOf }}</span>
          </div>
          <div class="tags">
            <el-tag :type="lifecycleTagType(data.basic.stage)">生命周期：{{ data.basic.stageLabel ?? "尚未加入" }}</el-tag>
            <span v-if="data.basic.previousStageLabel" class="muted">（30 天前：{{ data.basic.previousStageLabel }}）</span>
            <el-tag :type="valueTagType(data.basic.valueSegment)">价值：{{ data.basic.valueLabel ?? "—" }}</el-tag>
            <el-tag type="info">成长阶段：{{ data.basic.creatorStageLabel }}</el-tag>
            <el-tag v-if="data.kol" type="success">KOL：{{ data.kol.segmentName }}（第 {{ data.kol.rank }} 名）</el-tag>
          </div>
          <div class="links">
            <el-button v-if="data.hasPosts" size="small" @click="openBlogger">博主页（作者视角）</el-button>
            <el-button v-if="data.kol" size="small" @click="router.push({ name: 'kol-detail', params: { id: employeeId } })">KOL 详情</el-button>
          </div>
        </div>

        <div class="kpis">
          <div class="panel kpi"><div class="label">近 30 天活跃天数</div><div class="value">{{ data.recent.activeDays }}</div><div class="sub muted">最近活跃 {{ dateOnly(data.recent.lastActive) }}</div></div>
          <div class="panel kpi"><div class="label">浏览</div><div class="value">{{ formatNumber(data.recent.views) }}</div></div>
          <div class="panel kpi"><div class="label">互动</div><div class="value">{{ formatNumber(data.recent.interactions) }}</div></div>
          <div class="panel kpi"><div class="label">发帖</div><div class="value">{{ formatNumber(data.recent.posts) }}</div></div>
          <div class="panel kpi"><div class="label">近 30 天角色</div><div class="value small">{{ data.recent.roleLabel }}</div></div>
          <div class="panel kpi"><div class="label">近 12 个月积分</div><div class="value">{{ formatNumber(data.points.total) }}</div><div class="sub muted">主动 {{ formatNumber(data.points.active) }} · 被动 {{ formatNumber(data.points.passive) }}</div></div>
        </div>

        <div class="panel">
          <h3 class="panel-title">活跃时间轴 <span class="muted">（最近 12 周，每周的登录、浏览、互动、发帖次数）</span></h3>
          <BaseChart :option="timelineOption" height="280px" />
        </div>

        <div class="panel">
          <h3 class="panel-title">阅读偏好 <span class="muted">（近 90 天，{{ formatNumber(data.reading.views) }} 次浏览）</span></h3>
          <el-empty v-if="data.reading.views === 0" description="近 90 天没有浏览记录" :image-size="60" />
          <div v-else class="reading">
            <div>
              <h4>常看的领域</h4>
              <el-table :data="data.reading.channels" size="small">
                <el-table-column label="领域" prop="label" min-width="130" />
                <el-table-column label="次数" prop="count" min-width="70" />
                <el-table-column label="占比" min-width="90"><template #default="{ row }">{{ formatPercent((row as typeof data.reading.channels[number]).share) }}</template></el-table-column>
              </el-table>
              <h4>常看的标签</h4>
              <div class="chips">
                <el-tag v-for="tag in data.reading.tags" :key="tag.label" type="info" effect="plain">{{ tag.label }} · {{ tag.count }}</el-tag>
                <span v-if="data.reading.tags.length === 0" class="muted">—</span>
              </div>
            </div>
            <div>
              <h4>来源构成</h4>
              <BaseChart :option="sourceOption" height="200px" />
              <p class="muted">
                视频占比 {{ formatPercent(data.reading.videoShare) }} · 平均停留 {{ formatSeconds(data.reading.avgDwellSeconds) }} · 读完/播完率 {{ formatPercent(data.reading.completionRate) }}
              </p>
            </div>
          </div>
        </div>

        <div class="two">
          <div class="panel">
            <h3 class="panel-title">关注的账号 <span class="muted">（含已取关）</span></h3>
            <el-empty v-if="data.follows.length === 0" description="没有关注任何账号" :image-size="60" />
            <el-table v-else :data="data.follows" size="small" max-height="320">
              <el-table-column label="账号" prop="accountName" min-width="140" />
              <el-table-column label="关注时间" min-width="110"><template #default="{ row }">{{ dateOnly((row as typeof data.follows[number]).followTime) }}</template></el-table-column>
              <el-table-column label="状态" min-width="110">
                <template #default="{ row }">
                  <span v-if="(row as typeof data.follows[number]).unfollowTime" class="muted">已取关 {{ dateOnly((row as typeof data.follows[number]).unfollowTime) }}</span>
                  <span v-else>关注中</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="panel">
            <h3 class="panel-title">最近 20 条行为</h3>
            <el-empty v-if="data.recentEvents.length === 0" description="没有行为记录" :image-size="60" />
            <el-table v-else :data="data.recentEvents" size="small" max-height="320">
              <el-table-column label="时间" min-width="120"><template #default="{ row }">{{ shortTime((row as typeof data.recentEvents[number]).time) }}</template></el-table-column>
              <el-table-column label="行为" min-width="100"><template #default="{ row }">{{ eventText((row as typeof data.recentEvents[number]).type) }}</template></el-table-column>
              <el-table-column label="对象" min-width="100"><template #default="{ row }">{{ (row as typeof data.recentEvents[number]).refId ?? "—" }}</template></el-table-column>
            </el-table>
          </div>
        </div>
      </div>
    </AsyncBlock>
  </div>
</template>

<style scoped>
.back {
  color: var(--color-primary);
  text-decoration: none;
  font-size: 14px;
}
.header {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.who {
  display: flex;
  align-items: baseline;
  gap: 16px;
  flex-wrap: wrap;
}
.name {
  font-size: 20px;
  font-weight: 600;
}
.tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.links {
  display: flex;
  gap: 8px;
}
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
}
.label {
  color: var(--color-text-muted);
  font-size: 13px;
}
.value {
  margin-top: 8px;
  font-size: 26px;
  font-weight: 600;
}
.value.small {
  font-size: 20px;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
}
.reading {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 24px;
}
.reading h4 {
  margin: 12px 0 8px;
  font-size: 14px;
}
.chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  gap: 16px;
}
</style>
