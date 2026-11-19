# Koonj UI specification

The [operator-console delta](specs/operator-console/spec.md) contains the normative requirements. This document supplies presentation, interaction, and acceptance detail for the Apps That Matter console.

## Shell and visual language

Use a light-first slate/zinc palette, near-black text, crisp one-pixel borders, and restrained indigo and teal accents. Use dark surfaces with equivalent contrast in dark mode. Use compact typography and tabular numerals for file sizes, counters, and rates. Finder informs the hierarchy and column layout, while Linear and Cloudflare R2 inform density and storage vocabulary. Use distinct Koonj branding. Status badges pair text with color and include Local Only, Archived, Purged · S3 Stub, Recalling, and Failed, plus pending states. Keep global search and commands keyboard accessible. The header pill expands to show active Archive/Recall transfers and links to their activities.

## Home

Place four KPI cards above the tier distribution bar. Show reclaimed bytes, object and byte totals by S3 class (group visually into Standard and Glacier), cataloged file count, and current MB/s with active worker executions. Label zero, unavailable, and stale samples separately. The tier bar represents bytes by mutually exclusive presence categories, with the denominator shown. Use a textual legend and values alongside the bar. Below it, pair active transfers and pause/cancel controls with notifications linking to their relevant activity or location. Show status and Scan Now for all four location types.

## Explorer

Use a resizable two-section source/target tree. Load children on expansion, paginate wide nodes, and display server-provided counts with unknown counts omitted. The current folder, search, filters, and inspector target deep-link. Breadcrumbs navigate scopes. The table uses aligned checkbox, name/icon, origin, size, state/class, modified, and truncated SHA-256 columns. Row activation opens the inspector and a separate folder affordance navigates into a folder. Support keyboard selection and shift-range within loaded rows. Clear selection when scope or filters change and keep it stable across page eviction within the same query.

An anchored bottom bar shows the selected count and actions. The archive confirmation selects bucket and class, and offers an unchecked verified-purge toggle where authorized. Recall chooses authorized destination and relative path and shows restore-tier restrictions for the selected classes. Verify Checksum and Re-scan use server commands. Show accepted activity links; allow recovery from validation and conflict errors without losing the selection. The inspector presents original path, S3 URI, ETag, SHA-256, storage class, archived time and formatted JSON tags, with accessible copy controls and Archive/Recall shortcuts.

## Activity and audit

Show ID, operation (including Purge Local), target summary, actor, completed/total files and bytes, duration, status, and timestamp. Sort newest first. Expand a row into a paginated job sub-table with file path, result, speed, S3 version and ETag, and sanitized errors or attempt history. Retry Failed Files acts on eligible failures server-side and links to the new activity. Preserve the original row. Keep pending, running, paused, completed, partial failure, failed, and cancelled distinguishable.

## Component hierarchy

```text
App
  ThemeProvider
  ApiClientProvider / QueryClientProvider / LiveEventsProvider
  AppShell
    Sidebar / ThemeToggle
    Header / CommandPalette / TransferStatusPill
    HomeRoute
      KpiGrid / TierDistributionBar
      ActiveTransferList / NotificationsFeed / StorageEndpointCards
    ExplorerRoute
      ResizableCatalogTree
      Breadcrumbs / QuickFilters / FolderSearch
      VirtualMetadataTable
      BulkActionBar / ArchiveDialog / RecallDialog / VerificationDialog
      MetadataInspector
    ActivitiesRoute
      ActivityTable / ActivityProgress
        JobResultsTable / AttemptHistory / RetryFailedDialog
```

## Performance acceptance

On a documented desktop reference machine with a 60 Hz display, test the production build in Chromium with a synthetic directory of 50,000,001 files and repeat against a seeded live API. Record machine, browser, viewport, throttling and traces. Start with 100 rows per page, six retained pages per active query and ten overscan rows per edge. Confirm the row DOM remains proportional to viewport plus overscan and total retained rows stay bounded across many folder switches. Run a 30-second bidirectional loaded-page scroll trial with live activity events and target at least 95% of frame intervals at or below 17 ms. Test cursor boundaries, delayed responses, anchor preservation and memory after repeated page eviction separately. Record measured results during implementation.
