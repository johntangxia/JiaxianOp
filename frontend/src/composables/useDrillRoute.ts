import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import type { Granularity } from "../api/types";
import { defaultGranularity, rangeFromQuery } from "../utils/drill";

/** 下钻页共用：路由里的对象 id、日期范围（query）和趋势粒度（默认按范围长短选）。 */
export function useDrillRoute() {
  const route = useRoute();
  const id = computed(() => Number(route.params.id));
  const range = computed(() => rangeFromQuery(route.query));
  const valid = computed(() => Number.isInteger(id.value) && id.value > 0 && range.value !== null);
  const granularity = ref<Granularity>(range.value ? defaultGranularity(range.value) : "DAY");
  return { id, range, valid, granularity };
}
