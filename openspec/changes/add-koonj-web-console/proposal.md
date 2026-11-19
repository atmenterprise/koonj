# Proposal

## Why

Koonj operators need one interface to navigate on-premise and cloud catalogs, submit transfers, and audit their outcomes. The console must stay responsive even when a directory contains more than 50 million entries.

## What Changes

- Add an Apps That Matter console with Home, Catalog Explorer, and Activity & Audit Log, persistent navigation, global search and commands, dark mode, and a live transfer pill.
- Build lazy directory navigation, cursor-backed virtualized rows, selection workflows, and a metadata inspector.
- Show durable operation progress, actionable alerts, and paginated per-file execution history.
- Define the TypeScript client contract and development preview fixtures. Identify backend extensions required for the full UI.

## Capabilities

### New Capabilities

- `operator-console` adds detailed navigation, dashboard, catalog, and audit requirements to the capability introduced by the pending monorepo change. Both changes use the same capability path.

### Modified Capabilities

## Impact

Targets React 19, TypeScript, Vite, Tailwind CSS, TanStack Query, Table, and Virtual in `koonj-ui`. Depends on `initialize-koonj-monorepo` and `add-metadata-catalog-and-transfer-apis`. Preview work can proceed against fixtures. Full live acceptance also requires the API extensions in [design.md](design.md).
