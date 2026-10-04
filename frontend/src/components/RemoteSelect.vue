<script setup lang="ts">
import { computed, ref } from "vue";

interface Option {
  id: number;
  name: string;
}

const props = defineProps<{
  modelValue: number | undefined;
  fetcher: (keyword: string) => Promise<Option[]>;
  placeholder: string;
}>();
const emit = defineEmits<{ "update:modelValue": [value: number | undefined] }>();

const options = ref<Option[]>([]);
const selected = ref<Option | null>(null);
const loading = ref(false);

// 已选中的项即使不在最新的搜索结果里也要保留，否则下拉框会把 id 当成文字显示
const visibleOptions = computed(() => {
  const chosen = selected.value;
  return chosen && !options.value.some((option) => option.id === chosen.id)
    ? [chosen, ...options.value]
    : options.value;
});

async function search(keyword: string): Promise<void> {
  loading.value = true;
  try {
    options.value = await props.fetcher(keyword);
  } catch {
    options.value = [];
  } finally {
    loading.value = false;
  }
}

function onVisibleChange(open: boolean): void {
  if (open) {
    void search("");
  }
}

function onChange(value: number | string | undefined): void {
  const id = value === "" || value === undefined ? undefined : Number(value);
  selected.value = id === undefined ? null : (visibleOptions.value.find((option) => option.id === id) ?? null);
  emit("update:modelValue", id);
}
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    clearable
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder"
    @visible-change="onVisibleChange"
    @update:model-value="onChange"
  >
    <el-option v-for="option in visibleOptions" :key="option.id" :label="option.name" :value="option.id" />
  </el-select>
</template>
