# Tasks

## 1. Foundation and preview

- [ ] 1.1 Build on the React 19, TypeScript, Vite, Tailwind and TanStack foundation. Implement route layout, nav, keyboard command bar and persistent theme. Verify all deep links, theme persistence, focus and contrast.
- [ ] 1.2 Implement the typed client and fixture adapter using [api-client.ts](api-client.ts) and [mock-fixtures.ts](mock-fixtures.ts). Document and verify `VITE_KOONJ_API_MODE=mock npm run dev` from `koonj-ui` once implemented. Check cursor paging, command acknowledgements, failures, SSE event replay and disconnect with no live network calls in mock mode.
- [ ] 1.3 Confirm extension field names, routes and authorization with the backend change before wiring live features. Add contract tests for baseline and extended responses, capability gating, idempotent submissions, and action error handling.

## 2. Shell and Home

- [ ] 2.1 Wire global search and command results with debounce, cancellation, keyboard navigation and deep links. Test stale results and route focus.
- [ ] 2.2 Implement one shared SSE subscription and collapsible transfer pill. Verify revision ordering, reconnect refresh, stale throughput, and subscription cleanup.
- [ ] 2.3 Implement the four KPIs, accessible tier breakdown, and actionable notification feed. Verify per-class formatting and zero, stale, and unavailable states.
- [ ] 2.4 Implement active transfer cards with pause/resume/cancel and storage endpoint cards with Scan Now. Verify confirmation, pending acknowledgement, permission gating and accepted activity links.

## 3. Catalog Explorer

- [ ] 3.1 Build the resizable two-root lazy tree, breadcrumb navigation, folder search, and quick filters. Verify pagination for wide nodes, route restoration and rapid scope changes.
- [ ] 3.2 Wire TanStack Table and Virtual to cursor windows. Verify stable row keys, keyboard navigation, reverse fetch, eviction, anchor restoration, and stale-request cancellation.
- [ ] 3.3 Add checkbox and loaded-range selection, selection limits, and the floating bulk actions. Verify persistence through eviction and clearing on query scope change.
- [ ] 3.4 Implement archive, recall, verify and rescan dialogs with authorized targets, class/tier constraints and safe purge confirmation. Verify the submitted options, validation recovery, and activity links.
- [ ] 3.5 Implement the metadata inspector with accessible focus, copy controls, JSON tags, and shared single-item action dialogs. Verify missing fields, file/folder views and deep links.

## 4. Activities and audit

- [ ] 4.1 Implement newest-first activity history, counters, durations, and status badges. Verify live and historical views, cursor continuity, partial failures, and settled activities.
- [ ] 4.2 Implement inline job and attempt pagination with speeds, versions, ETags, safe error text, and Retry Failed Files. Verify authorization, linked retry activity and unchanged source audit history.

## 5. Integration and acceptance

- [ ] 5.1 Exercise dashboard, explorer, mutations, and audit flows in fixture mode and on the same-origin live stack. Verify SPA route fallback, SSE proxy behavior, and metadata-only requests. Require the backend extensions for full live acceptance.
- [ ] 5.2 Run the [50,000,001-file benchmark](ui-spec.md#performance-acceptance) against synthetic and seeded live data. Record frame timings, DOM size, retained pages, memory, and cursor-boundary behavior during bidirectional scroll and SSE traffic.
