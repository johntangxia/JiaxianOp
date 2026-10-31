# Cloudflare Static Demo Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement each task, with focused verification.

**Goal:** Publish an honest, backend-free static demo of the existing analytics UI.

**Architecture:** A separate Vite build mode uses all non-admin routes and a browser-local API adapter backed by JSON snapshots. Existing views remain the UI; the normal build remains unchanged.

**Tech Stack:** Vue 3, TypeScript, Vite, Element Plus, ECharts, Vitest, Python standard library for snapshot extraction.

**Spec:** `docs/superpowers/specs/2026-10-31-cloudflare-static-demo-design.md`

## Global Constraints

- Do not run simulator backfill, seeding, restore, or database reset commands.
- Do not modify or delete pre-existing untracked files.
- Static build must not make `/api` calls at runtime and must have an SPA route fallback.
- Keep existing metric definitions and clearly mark fixed simulated data.

## Review Focus

- Deep links on Pages must load the correct static route.
- KOL examples, user 360 and top lists must not expose local simulated employee names or original IDs.
- Controls must only change published snapshot selections; unsupported date combinations must fail explicitly.
- The static build must not display operational health or admin actions.
- Local growth task writes must persist only in the current browser and survive reloads.
- The original full-stack build and its tests must continue to work.

## Tasks

### Task 1: Snapshot schema and extraction

- Add a focused test for safe, bounded demo data, then create an allowlisted read-only extraction script and committed JSON snapshot.
- Verify JSON is valid, bounded and contains no unredacted people or account names.

### Task 2: Static routes and views

- Add tests for static API dispatch, local filters/pagination, and writes; implement static mode switch, clear demo banner, all non-admin routes, anonymous user 360 and local growth tasks.
- Verify direct channel, account, blogger, content, user, and KOL links resolve without network requests to `/api`.

### Task 3: Pages packaging and docs

- Add `build:demo`, Pages SPA fallback, and deploy instructions with limitations.
- Run frontend tests, normal production build, static production build, and inspect static output for backend URLs and deep-route behavior.
