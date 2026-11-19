# Design

## Context

The workspace contains a UI archetype and two pending OpenSpec changes. The foundation provides React 19, Vite and Tailwind. The metadata change defines explorer, activity, archive/recall, summary and SSE endpoints. This plan builds on those contracts and records the additions needed for live parity.

## Goals / Non-Goals

Deliver a distinct Koonj interface for Apps That Matter with a fixture-driven development mode and a production REST/SSE adapter. Browser data flows consist of metadata and control commands. Workers own payloads, checksums, and destructive actions.

## Decisions

- Use React 19, TypeScript, Vite and Tailwind tokens; use TanStack Query for cursor navigation and TanStack Table with TanStack Virtual for presentation. Use the URL for explorer scope, filters and inspector deep links, and local preference storage for theme and panel sizes.
- Keep a bounded window of cursor pages with stable UUID row keys. Fetch ahead near the edge, abort outdated requests, evict opposite the scroll direction, and preserve a visible anchor on prepend or eviction. Retain bounded navigation checkpoints, replay from the nearest retained checkpoint where necessary, and allow explicit restart at the folder top. Bidirectional retrieval at arbitrary depth requires server-issued previous cursors. The scroll range describes the retained window to avoid browser pixel limits. Use explicit UUID selection with visible limits independent of mounted rows. Folder expansion runs server-side and remains asynchronous.
- Use one SSE subscription per app instance. Merge activity revisions, coalesce updates per visible view, and refresh durable resources on reconnect. Show timestamps for stale samples. Avoid invalidating explorer queries on each progress message. Mutations stay pending until acknowledged; the backend supplies permissions, eligibility, and final status.
- Keep navigation and text hierarchy compact. Define slate/zinc surfaces, subtle borders, indigo focus and selection, and teal accents derived from the crane palette. Use color plus text for status, an accessible splitter, keyboard-operable virtual rows, stable focus on redraw, and labelled dialogs that restore trigger focus. Use drawer layouts at compact widths.
- Provide an opt-in fixture adapter for development. Seed realistic states and synthetic large directories, use the same client surface, and simulate cursors, transitions, retries, and reconnect without touching live resources.

### Backend integration

| Capability | Baseline and proposed extension |
| --- | --- |
| Explorer navigation | Reuse tree and item endpoints. Add registered-location discovery, child counts, item detail with ETag, reverse cursors, and multi-state filtering for In Progress. Add scoped global search across authorized locations. |
| Dashboard / SSE | Extend summary with tier bytes and actionable alert targets; include active worker execution count and freshness in throughput samples. Extend activity feed with running filters. |
| Archive and recall | Extend archive with authorized target location and verified purge flag, and recall with authorized destination location and relative path. Baseline uses a configured bucket and original destination. Backend validates scope, destination conflicts, generation, tier and eligibility. |
| Transfer and storage actions | Add capability discovery, scan, checksum verification, activity pause/resume/cancel, and failed-file retry. Cancel stops eligible remaining work; verified completed effects remain in history. Pause/resume is cooperative at worker boundaries. Retry creates a linked activity and revalidates the failed generations. |
| History | Extend activity and job results with actor, target, duration, rate, object version/ETag and action eligibility. Add explicit paused/cancelled lifecycle states and control request acknowledgements. |

The [client contract](api-client.ts) distinguishes live backend prerequisites from interface methods used by fixtures. Short-term live integration may show controls disabled with a concise reason where capability discovery reports missing functionality. Complete live acceptance requires backend support for all requested flows. Expose permissions and supported options through the API; keep action endpoints idempotent and confirm purge/cancel. Archive retains the local copy by default.

## Risks / Trade-offs

- A cursor API cannot provide an arbitrary position across a 50-million-row logical scroll surface without additional seek support. The explorer uses relative scrolling, tree navigation, and server search.
- Backend latency affects page arrivals. Test loaded-page rendering separately from loading indicators and capture both in performance traces.
- Cold restore waits may last longer than a browser session. Use durable statuses and alerts and reconcile after reconnect.

## Migration Plan

Apply the foundation before the UI and coordinate production rollout with backend API support. Maintain a same-origin deployment for REST and SSE, including direct route loads, proxy streaming and SPA fallback. Use fixture mode during UI development.
