<script setup lang="ts">
import { computed, inject } from "vue";
import { guidanceKey } from "../guidance/context";
import { ratioExample } from "../guidance/examples";
import { getGuidance } from "../guidance/catalog";
import { getGuidanceDetail } from "../guidance/details";
const controller = inject(guidanceKey)!;
const example = computed(() => {
  const value = controller.state.context?.example;
  const input = typeof value === "function" ? value() : value;
  return input ? ratioExample(input, input) : null;
});
const content = computed(() => controller.state.id ? getGuidance(controller.state.id) : null);
const detail = computed(() => controller.state.id ? getGuidanceDetail(controller.state.id) : null);
</script>
<template>
  <el-drawer :model-value="!!content" :size="'min(520px, 100vw)'" :modal="false" :lock-scroll="false" :close-on-click-modal="false" :title="content?.title ?? '说明'" @close="controller.close" @closed="controller.restoreFocus">
    <article v-if="content && detail" class="guidance-content">
      <section><h3>含义与范围</h3><p>{{ content.meaning }}</p></section>
      <section><h3>怎么算</h3><p>{{ content.calculation }}</p></section>
      <section v-if="example"><h3>当前数据示例</h3><p>{{ example }}</p></section>
      <section v-else-if="content.illustration"><h3>示意例子</h3><p>{{ content.illustration }}</p></section>
      <section><h3>怎么看</h3><ul><li v-for="item in detail.reading" :key="item">{{ item }}</li></ul></section>
      <section><h3>怎么分析</h3><ol><li v-for="item in detail.analysis" :key="item">{{ item }}</li></ol></section>
      <section><h3>价值分析</h3><ul><li v-for="item in detail.value" :key="item">{{ item }}</li></ul></section>
      <section><h3>注意事项</h3><p>{{ content.caution }}</p></section>
      <section v-if="detail.related?.length"><h3>相关说明</h3><div class="related"><button v-for="id in detail.related" :key="id" type="button" @click="controller.open(id)">{{ getGuidance(id).title }} →</button></div></section>
      <p class="guidance-footnote">本项目使用模拟数据验证分析方案；请结合实际业务和数据质量判断。</p>
    </article>
  </el-drawer>
</template>
<style scoped>
.guidance-footnote { color: #718096 !important; font-size: 12px; border-top: 1px solid #e5eaf2; padding-top: 16px; }
.guidance-content section { margin: 0 0 24px; }
.guidance-content h3 { color: #1d2c49; font-size: 15px; margin: 0 0 8px; }
.guidance-content p { color: #46546a; line-height: 1.75; white-space: pre-line; margin: 0; }
.guidance-content ul, .guidance-content ol { color: #46546a; line-height: 1.75; margin: 0; padding-left: 1.4em; }
.guidance-content li + li { margin-top: 8px; }
.related { display: flex; flex-wrap: wrap; gap: 8px; }
.related button { color: #2467cf; background: #f1f6ff; border: 1px solid #d6e5ff; border-radius: 4px; padding: 5px 9px; cursor: pointer; font: inherit; }
.related button:hover { background: #e5efff; }
.related button:focus-visible { outline: 2px solid #2467cf; outline-offset: 2px; }
</style>
