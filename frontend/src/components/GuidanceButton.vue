<script setup lang="ts">
import { inject } from "vue";
import { guidanceKey } from "../guidance/context";
import type { GuidanceContext, GuidanceId } from "../guidance/types";
const props = defineProps<{ id: GuidanceId; label: string; page?: boolean; context?: GuidanceContext }>();
const controller = inject(guidanceKey);
function open(event: MouseEvent) { controller?.open(props.id, props.context, event.currentTarget as HTMLElement, !!props.page); }
</script>
<template>
  <button type="button" class="guidance-button" :class="{ page }" :aria-label="`查看${label}说明`" :title="`查看${label}说明`" @click.stop.prevent="open">{{ page ? "看板说明" : "ⓘ" }}</button>
</template>
<style scoped>
.guidance-button { border: 0; background: transparent; color: #346bdc; cursor: pointer; font-size: 15px; line-height: 1.3; min-width: 28px; min-height: 28px; padding: 3px 5px; border-radius: 4px; vertical-align: middle; }
.guidance-button:hover { background: #edf3ff; }
.guidance-button:focus-visible { outline: 2px solid #2463eb; outline-offset: 2px; }
.guidance-button.page { border: 1px solid #c9d8f5; font-size: 13px; padding: 5px 10px; white-space: nowrap; }
</style>
