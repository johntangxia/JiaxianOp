# Visit Flow Implementation Plan

**Goal:** Provide a flow diagram matching the approved mockup with source-to-path drilldown and honest simulated-data labeling.

**Architecture:** A small pure aggregation module groups explicitly synthetic visit paths into branches and computes conditional visit counts. A Vue page displays these as selectable, expandable columns and SVG ribbons, with the selected path in a side panel. It does not infer external sources from existing PV logs or represent examples as real analytics.

**Tech Stack:** Vue 3, TypeScript, Vitest, SVG.

**Spec:** User-approved 2026-10-28 conversation and effect image; entry is step 0; initial three steps, expand further on same flow, 30-minute session rule is a future real-data integration requirement.

## Global Constraints

- Preserve existing uncommitted work and demo data.
- External source percentages are supplied reference values, with 3.4% pending confirmation; simulated paths are explicitly labeled and do not infer actual flow.
- Aggregate visits, never individual users; terminal branches have no children.
- A real period filter, real source attribution, and sessionization require future event data and must not be falsely represented as working.

## Tasks

1. Test and implement deterministic synthetic aggregate paths, prefix filtering, proportions, terminal branches, and source totals.
2. Implement route, menu, flow visualization, source selection, node selection, expand/collapse and current path panel.
3. Test full frontend and build, rebuild only web container, verify visual and interactive behavior against approved mockup in browser, document limitations.
