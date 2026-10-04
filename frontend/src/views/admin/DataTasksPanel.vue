<script setup lang="ts">
import { reactive, ref } from "vue";
import { fetchPvRange } from "../../api/analytics";
import { rebuildPv, snapshotAllLeaderboards } from "../../api/admin";
import { rebuildKol } from "../../api/kol";

type Status = "pending" | "running" | "done" | "error";
interface Step {
  name: string;
  status: Status;
  detail: string;
}

const message = (e: unknown): string => (e instanceof Error ? e.message : String(e));

// ---- 单个任务 ----
const pvRange = ref<[string, string] | null>(null);
const pvResult = ref<string | null>(null);
const pvError = ref<string | null>(null);
const pvBusy = ref(false);

async function runPv(): Promise<string> {
  const result = await rebuildPv(pvRange.value?.[0], pvRange.value?.[1]);
  return `写入 ${result.rowsWritten} 行汇总`;
}

async function onPv(): Promise<void> {
  pvBusy.value = true;
  pvResult.value = null;
  pvError.value = null;
  try {
    pvResult.value = await runPv();
  } catch (e) {
    pvError.value = message(e);
  } finally {
    pvBusy.value = false;
  }
}

const snapResult = ref<string | null>(null);
const snapError = ref<string | null>(null);
const snapBusy = ref(false);

async function runSnapshot(): Promise<string> {
  const result = await snapshotAllLeaderboards();
  return `重算 ${result.months} 个月、${result.years} 个年，写入 ${result.rowsWritten} 行`;
}

async function onSnapshot(): Promise<void> {
  snapBusy.value = true;
  snapResult.value = null;
  snapError.value = null;
  try {
    snapResult.value = await runSnapshot();
  } catch (e) {
    snapError.value = message(e);
  } finally {
    snapBusy.value = false;
  }
}

const kol = reactive<{ asOf: string | undefined; weeks: number }>({ asOf: undefined, weeks: 12 });
const kolResult = ref<string | null>(null);
const kolError = ref<string | null>(null);
const kolBusy = ref(false);

async function runKol(asOf: string | undefined, weeks: number): Promise<string> {
  const results = await rebuildKol(asOf, weeks);
  const last = results[results.length - 1];
  return last
    ? `重算 ${results.length} 周，最后统计日 ${last.asOf}：名单 ${last.population} 人，预警 ${last.alerts} 人`
    : "没有结果";
}

async function onKol(): Promise<void> {
  kolBusy.value = true;
  kolResult.value = null;
  kolError.value = null;
  try {
    kolResult.value = await runKol(kol.asOf, kol.weeks);
  } catch (e) {
    kolError.value = message(e);
  } finally {
    kolBusy.value = false;
  }
}

// ---- 一键重算全部 ----
const steps = ref<Step[]>([]);
const allBusy = ref(false);

async function runStep(index: number, task: () => Promise<string>): Promise<void> {
  steps.value[index].status = "running";
  try {
    steps.value[index].detail = await task();
    steps.value[index].status = "done";
  } catch (e) {
    steps.value[index].status = "error";
    steps.value[index].detail = message(e);
    throw e;
  }
}

async function runAll(): Promise<void> {
  allBusy.value = true;
  steps.value = [
    { name: "PV 汇总重算", status: "pending", detail: "" },
    { name: "榜单快照重算", status: "pending", detail: "" },
    { name: "KOL 重算", status: "pending", detail: "" },
  ];
  try {
    await runStep(0, async () => {
      const result = await rebuildPv();
      return `写入 ${result.rowsWritten} 行汇总`;
    });
    await runStep(1, runSnapshot);
    // 模拟数据是历史的：以今天为统计日所有人都会变成沉寂，所以统计日取 PV 数据的最后一天
    const range = await fetchPvRange().catch(() => null);
    await runStep(2, () => runKol(range?.to ?? undefined, 12));
  } catch {
    // 失败的那一步已经记录了原因，后面的步骤不再执行
  } finally {
    allBusy.value = false;
  }
}

const stepType = { pending: "info", running: "warning", done: "success", error: "danger" } as const;
const stepText = { pending: "等待", running: "进行中", done: "完成", error: "失败" } as const;
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">数据任务（重算派生数据）</h3>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="历史回填会清空汇总、快照和 KOL 结果，回填之后请运行下面的数据任务。"
      style="margin-bottom: 12px"
    />

    <div class="all">
      <el-button type="primary" :loading="allBusy" @click="runAll">一键重算全部</el-button>
      <span class="muted">按"PV 汇总 → 榜单快照 → KOL 重算（统计日取 PV 数据的最后一天，12 周）"顺序执行</span>
    </div>
    <ul v-if="steps.length > 0" class="steps">
      <li v-for="step in steps" :key="step.name">
        <el-tag :type="stepType[step.status]" size="small" effect="plain">{{ stepText[step.status] }}</el-tag>
        {{ step.name }}<span v-if="step.detail" class="muted">：{{ step.detail }}</span>
      </li>
    </ul>

    <el-divider />

    <div class="task">
      <strong>PV 汇总</strong>
      <el-date-picker v-model="pvRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至"
        start-placeholder="全部历史" end-placeholder="（可选）" style="width: 260px" />
      <el-button :loading="pvBusy" @click="onPv">重算</el-button>
      <span v-if="pvResult" class="ok">{{ pvResult }}</span>
      <span v-if="pvError" class="bad">{{ pvError }}</span>
    </div>

    <div class="task">
      <strong>榜单快照</strong>
      <span class="muted">重算所有已结束且有流水的月和年</span>
      <el-button :loading="snapBusy" @click="onSnapshot">重算</el-button>
      <span v-if="snapResult" class="ok">{{ snapResult }}</span>
      <span v-if="snapError" class="bad">{{ snapError }}</span>
    </div>

    <div class="task">
      <strong>KOL 分层</strong>
      <el-date-picker v-model="kol.asOf" type="date" value-format="YYYY-MM-DD" placeholder="统计日（默认今天）" style="width: 180px" />
      <el-input-number v-model="kol.weeks" :min="1" :max="52" />
      <span class="muted">周</span>
      <el-button :loading="kolBusy" @click="onKol">重算</el-button>
      <span v-if="kolResult" class="ok">{{ kolResult }}</span>
      <span v-if="kolError" class="bad">{{ kolError }}</span>
    </div>
  </div>
</template>

<style scoped>
.all {
  display: flex;
  align-items: center;
  gap: 12px;
}
.steps {
  margin: 12px 0 0;
  padding-left: 18px;
  line-height: 2;
  font-size: 13px;
}
.task {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.task strong {
  width: 80px;
}
.ok {
  color: var(--color-down);
  font-size: 13px;
}
.bad {
  color: var(--color-up);
  font-size: 13px;
}
</style>
