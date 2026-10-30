import { reactive, type InjectionKey } from "vue";
import { getGuidance, getPageGuidance } from "./catalog";
import type { GuidanceContext, GuidanceId } from "./types";

export function createGuidanceController() {
  const state = reactive<{ id: GuidanceId | null; context: GuidanceContext | null; tab: string | null; tabRoute: string | null }>({ id: null, context: null, tab: null, tabRoute: null });
  let origin: HTMLElement | null = null;
  let pageOpen = false;
  function open(id: GuidanceId, context?: GuidanceContext, trigger?: HTMLElement, isPage = id.startsWith("page.")) {
    getGuidance(id);
    if (trigger) origin = trigger;
    else if (!state.id) origin = typeof document === "undefined" ? null : document.activeElement as HTMLElement | null;
    pageOpen = isPage;
    state.id = id;
    state.context = context ?? null;
  }
  function close() { state.id = null; state.context = null; }
  function restoreFocus() { if (origin?.isConnected) origin.focus(); origin = null; }
  function routeChanged() { close(); state.tab = null; state.tabRoute = null; }
  function setTab(route: string, tab: string) {
    const changed = state.tabRoute === route && state.tab !== null && state.tab !== tab;
    if (changed && state.id) {
      if (pageOpen) { state.id = getPageGuidance(route, tab); state.context = null; }
      else close();
    }
    state.tabRoute = route; state.tab = tab;
  }
  return { state, open, close, restoreFocus, routeChanged, setTab };
}
export type GuidanceController = ReturnType<typeof createGuidanceController>;
export const guidanceKey: InjectionKey<GuidanceController> = Symbol("guidance");
