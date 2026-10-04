<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { createPlaybook, deletePlaybook, fetchPlaybook, updatePlaybook } from "../../api/playbook";
import type { PlaybookAlertStatus, PlaybookEntry, PlaybookRequest, Segment } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { alertStatusLabel } from "../../utils/actionTypes";
import { SEGMENTS, segmentName, segmentTagType } from "../../utils/segment";

const MAX_TEXT = 1000;

const entries = useAsync(() => fetchPlaybook());
onMounted(() => void entries.run());

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const saving = ref(false);
const form = reactive<{ segmentLabel: Segment; alertStatus: PlaybookAlertStatus; recommendationText: string }>({
  segmentLabel: "STAR_KOL",
  alertStatus: "NO_ALERT",
  recommendationText: "",
});

function openCreate(): void {
  editingId.value = null;
  form.segmentLabel = "STAR_KOL";
  form.alertStatus = "NO_ALERT";
  form.recommendationText = "";
  dialogVisible.value = true;
}

function openEdit(entry: PlaybookEntry): void {
  editingId.value = entry.id;
  form.segmentLabel = entry.segmentLabel;
  form.alertStatus = entry.alertStatus;
  form.recommendationText = entry.recommendationText;
  dialogVisible.value = true;
}

async function save(): Promise<void> {
  if (!form.recommendationText.trim()) {
    ElMessage.warning("请填写建议文案");
    return;
  }
  const request: PlaybookRequest = { ...form };
  saving.value = true;
  try {
    if (editingId.value === null) {
      await createPlaybook(request);
    } else {
      await updatePlaybook(editingId.value, request);
    }
    dialogVisible.value = false;
    ElMessage.success("已保存");
    await entries.run();
  } catch (e) {
    // 后端的 400/409 响应体就是一句说明原因的文字（比如该分层和预警状态已有文案）
    ElMessage.error(e instanceof Error ? e.message : String(e));
  } finally {
    saving.value = false;
  }
}

async function remove(entry: PlaybookEntry): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${segmentName(entry.segmentLabel)} / ${alertStatusLabel(entry.alertStatus)}」的建议文案吗？`,
      "确认删除",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  try {
    await deletePlaybook(entry.id);
    ElMessage.success("已删除");
    await entries.run();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}
</script>

<template>
  <div class="page">
    <div class="panel head">
      <span class="muted">KOL 名单会按"分层 + 是否预警"匹配这里的文案；没有精确匹配时回退到该分层的"无预警"文案。</span>
      <el-button type="primary" @click="openCreate">新增文案</el-button>
    </div>

    <div class="panel">
      <AsyncBlock :loading="entries.loading.value" :error="entries.error.value" :empty="false">
        <el-table :data="entries.data.value ?? []" size="small" row-key="id" empty-text="还没有建议文案">
          <el-table-column label="分层" width="150">
            <template #default="{ row }">
              <el-tag :type="segmentTagType(row.segmentLabel)" size="small" effect="plain">{{ segmentName(row.segmentLabel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="预警状态" width="110">
            <template #default="{ row }">{{ alertStatusLabel(row.alertStatus) }}</template>
          </el-table-column>
          <el-table-column prop="recommendationText" label="建议文案" min-width="360" />
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row as PlaybookEntry)">编辑</el-button>
              <el-button link type="danger" @click="remove(row as PlaybookEntry)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </AsyncBlock>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增文案' : '编辑文案'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="分层">
          <el-select v-model="form.segmentLabel" style="width: 100%">
            <el-option v-for="s in SEGMENTS" :key="s.value" :label="s.name" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="预警状态">
          <el-radio-group v-model="form.alertStatus">
            <el-radio value="NO_ALERT">无预警</el-radio>
            <el-radio value="ALERT">触发预警</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="建议文案">
          <el-input v-model="form.recommendationText" type="textarea" :rows="5" :maxlength="MAX_TEXT" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
</style>
