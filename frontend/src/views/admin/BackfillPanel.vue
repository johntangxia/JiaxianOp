<script setup lang="ts">
import { reactive, ref } from "vue";
import { backfill } from "../../api/admin";
import { buildBackfillBody, defaultBackfillForm, validateBackfillForm } from "../../utils/simulatorForms";

const form = reactive(defaultBackfillForm());
const running = ref(false);
const result = ref<string | null>(null);
const error = ref<string | null>(null);

async function submit(): Promise<void> {
  const problem = validateBackfillForm(form);
  if (problem) {
    ElMessage.warning(problem);
    return;
  }
  try {
    await ElMessageBox.confirm(
      "回填会清空所有模拟数据（员工、内容、行为、积分流水），以及依赖它们的汇总、快照和 KOL 结果。确定继续吗？",
      "确认回填",
      { type: "warning", confirmButtonText: "清空并回填", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  running.value = true;
  result.value = null;
  error.value = null;
  try {
    result.value = await backfill(buildBackfillBody(form));
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    running.value = false;
  }
}
</script>

<template>
  <div class="panel">
    <h3 class="panel-title">历史回填</h3>
    <el-form label-width="120px" class="form">
      <el-form-item label="批次名"><el-input v-model="form.batchId" style="width: 220px" /></el-form-item>
      <el-form-item label="员工数"><el-input-number v-model="form.employeeCount" :min="1" /></el-form-item>
      <el-form-item label="账号数"><el-input-number v-model="form.accountCount" :min="1" /></el-form-item>
      <el-form-item label="团队账号比例">
        <el-input-number v-model="form.teamAccountRatio" :min="0" :max="1" :step="0.05" :precision="2" />
      </el-form-item>
      <el-form-item label="内容数"><el-input-number v-model="form.contentCount" :min="1" /></el-form-item>
      <el-form-item label="历史起止日期">
        <el-date-picker v-model="form.historyStart" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
        <span class="sep">至</span>
        <el-date-picker v-model="form.historyEnd" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
      </el-form-item>
      <el-form-item label="随机种子"><el-input-number v-model="form.randomSeed" /></el-form-item>
      <el-form-item label="博主画像"><el-switch v-model="form.personas" active-text="开启（明星/新人/流失/沉寂）" /></el-form-item>
      <p class="muted hint">结束日期建议早于今天至少 30 天：回填的浏览行为会延续到内容发布后 30 天。</p>
      <el-form-item>
        <el-button type="danger" :loading="running" @click="submit">{{ running ? "回填中…" : "清空并回填" }}</el-button>
      </el-form-item>
    </el-form>
    <el-alert v-if="result" type="success" :title="result" :closable="false" show-icon />
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
  </div>
</template>

<style scoped>
.sep {
  margin: 0 8px;
  color: var(--color-text-muted);
}
.hint {
  margin: 0 0 12px 120px;
}
</style>
