<script setup lang="ts">
import { useRouter } from "vue-router";
import type { TopContent } from "../../api/types";
import { formatNumber } from "../../utils/format";
import { contentLink, type Range } from "../../utils/drill";

const props = defineProps<{ contents: TopContent[]; range: Range }>();
const router = useRouter();

// el-table 插槽里的 row 是 any，统一在这里断言
const r = (row: unknown): TopContent => row as TopContent;
const formatTime = (time: string): string => time.replace("T", " ").slice(0, 16);

function open(row: unknown): void {
  void router.push(contentLink(r(row).contentId, props.range));
}
</script>

<template>
  <el-table :data="contents" size="small" row-class-name="clickable-row" @row-click="open">
    <el-table-column label="内容" min-width="100"><template #default="{ row }">内容 #{{ r(row).contentId }}</template></el-table-column>
    <el-table-column label="类型" min-width="70"><template #default="{ row }">{{ r(row).type === "VIDEO" ? "视频" : "文章" }}</template></el-table-column>
    <el-table-column label="账号" min-width="140"><template #default="{ row }">{{ r(row).accountName }}</template></el-table-column>
    <el-table-column label="发布时间" min-width="140"><template #default="{ row }">{{ formatTime(r(row).publishTime) }}</template></el-table-column>
    <el-table-column label="PV" min-width="80"><template #default="{ row }">{{ formatNumber(r(row).pv) }}</template></el-table-column>
    <el-table-column label="互动数" min-width="80"><template #default="{ row }">{{ formatNumber(r(row).interactions) }}</template></el-table-column>
  </el-table>
</template>
