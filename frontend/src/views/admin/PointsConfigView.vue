<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { deleteWeightVersion, fetchPointsConfig, updateWeight } from "../../api/pointsConfig";
import type { PointsConfigItem } from "../../api/types";
import AsyncBlock from "../../components/AsyncBlock.vue";
import { useAsync } from "../../composables/useAsync";
import { actionLabel, categoryLabel } from "../../utils/actionTypes";

const config = useAsync(() => fetchPointsConfig());
onMounted(() => void config.run());

const groups = computed(() => {
  const items = config.data.value ?? [];
  return [
    { category: "ACTIVE" as const, items: items.filter((i) => i.category === "ACTIVE") },
    { category: "PASSIVE" as const, items: items.filter((i) => i.category === "PASSIVE") },
  ];
});

const dialogVisible = ref(false);
const editing = ref<PointsConfigItem | null>(null);
const weight = ref<number>(0);
const saving = ref(false);

function openEdit(item: PointsConfigItem): void {
  editing.value = item;
  weight.value = item.current.weight;
  dialogVisible.value = true;
}

async function save(): Promise<void> {
  if (!editing.value) {
    return;
  }
  saving.value = true;
  try {
    await updateWeight(editing.value.actionType, weight.value);
    dialogVisible.value = false;
    ElMessage.success("已新增今天生效的版本");
    await config.run();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  } finally {
    saving.value = false;
  }
}

async function removeVersion(item: PointsConfigItem, id: number, weightValue: number, date: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除「${actionLabel(item.actionType)}」${date} 生效的版本（权重 ${weightValue}）吗？`,
      "确认删除",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  try {
    await deleteWeightVersion(id);
    ElMessage.success("已删除");
    await config.run();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : String(e));
  }
}
</script>

<template>
  <div class="page">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="修改会新增一个今天生效的版本（今天已有版本则覆盖）；积分引擎对之后新产生的事件使用最新版本，已有积分流水不会重算，需要重新回填才会按新权重重新生成。"
    />

    <AsyncBlock :loading="config.loading.value" :error="config.error.value" :empty="false">
      <div v-for="group in groups" :key="group.category" class="panel">
        <h3 class="panel-title">{{ categoryLabel(group.category) }}</h3>
        <el-table :data="group.items" size="small" row-key="actionType">
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.versions" size="small" class="versions">
                <el-table-column prop="effectiveDate" label="生效日期" min-width="130" />
                <el-table-column prop="weight" label="权重" min-width="90" />
                <el-table-column label="操作" width="110" align="center">
                  <template #default="{ row: version }">
                    <el-tooltip :disabled="row.versions.length > 1" content="每个动作至少保留一个版本">
                      <span>
                        <el-button
                          link
                          type="danger"
                          :disabled="row.versions.length <= 1"
                          @click="removeVersion(row as PointsConfigItem, version.id, version.weight, version.effectiveDate)"
                        >删除</el-button>
                      </span>
                    </el-tooltip>
                  </template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column label="动作" min-width="200">
            <template #default="{ row }">
              {{ actionLabel(row.actionType) }}
              <span class="muted">（{{ row.actionType }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="当前权重" min-width="100">
            <template #default="{ row }"><strong>{{ row.current.weight }}</strong></template>
          </el-table-column>
          <el-table-column label="生效日期" min-width="130">
            <template #default="{ row }">{{ row.current.effectiveDate }}</template>
          </el-table-column>
          <el-table-column label="版本数" width="90" align="center">
            <template #default="{ row }">{{ row.versions.length }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row as PointsConfigItem)">修改</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </AsyncBlock>

    <el-dialog v-model="dialogVisible" :title="`修改权重：${editing ? actionLabel(editing.actionType) : ''}`" width="420px">
      <el-form label-width="90px">
        <el-form-item label="新权重">
          <el-input-number v-model="weight" :min="0" :max="10000" :step="1" />
        </el-form-item>
        <p class="muted">将新增一个今天生效的版本，范围 0 ~ 10000 的整数。</p>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.versions {
  margin: 4px 24px 8px;
  width: calc(100% - 48px);
}
</style>
