# Operator Console

## Purpose

Give Koonj operators a responsive workspace for catalog navigation, transfer control, and execution audit.

## ADDED Requirements

### Requirement: Persistent console shell
The console SHALL provide Home at `/`, Catalog Explorer at `/explorer`, and Activity & Audit Log at `/activities` through persistent navigation, with a global search and command bar, collapsible live transfer pill, and persistent light/dark preference.

#### Scenario: Navigate with the keyboard
- **WHEN** an operator opens the command bar and selects an authorized result
- **THEN** the console navigates to the result, preserves the theme, and moves focus to the destination view

### Requirement: Home health overview
Home SHALL display Local Storage Reclaimed, S3 Archived Footprint by class, Cataloged Files, and Active Engine Throughput in MB/s with active worker count. It SHALL show the distribution of Local Only, Synced, and Stubbed/Purged data.

#### Scenario: Live sample becomes stale
- **WHEN** live updates are interrupted
- **THEN** Home labels the last known throughput as stale and refreshes authoritative values on reconnect

### Requirement: Transfer and storage controls
Home SHALL show active Archive and Recall progress, authorized pause and cancel controls, actionable notifications, and registered Local, SMB, NAS and S3 endpoints with status and Scan Now.

#### Scenario: A pause request is accepted
- **WHEN** a permitted operator requests pause on an active transfer
- **THEN** the UI displays the pending request until the backend confirms the new state

### Requirement: Hierarchical explorer navigation
The explorer SHALL provide a resizable lazy tree with Source Servers and Cloud Targets, child counts where available, interactive breadcrumbs, server-side name search, and quick filters for All, Local Only, Archived, Purged Stubs, and In Progress.

#### Scenario: Switch location during a load
- **WHEN** an operator opens a different server, bucket, or folder before the previous request completes
- **THEN** the table displays the new scope without showing stale results from the previous one

### Requirement: Large-directory interaction
The explorer SHALL use server cursors and bounded virtualization for directories containing at least 50,000,000 files. It SHALL target smooth 60 Hz scrolling under the documented loaded-page benchmark and show incremental loading at page boundaries.

#### Scenario: Long bidirectional scroll
- **WHEN** an operator scrolls through many pages from a directory with over 50 million files
- **THEN** rendered rows and cached pages stay within measured bounds, and backwards navigation resumes from the correct anchor

### Requirement: Metadata and contextual commands
The table SHALL show selection, name and type, origin, size, storage state, last modified time, and a SHA-256 preview. Selecting files or folders SHALL reveal Archive, Recall, and Verify Checksum or Re-scan actions. Opening a row SHALL show detailed path, S3 URI, ETag, SHA-256, class, archival timestamp, tags, and eligible Archive or Recall actions.

#### Scenario: Archive with optional purge
- **WHEN** an authorized operator confirms an eligible selection with a bucket, class, and optional local purge
- **THEN** the UI submits only selection IDs and options, shows the resulting activity, and presents purge as conditional on verified upload and backend safety checks

#### Scenario: Recall with retrieval options
- **WHEN** an operator recalls archived or stubbed items
- **THEN** the UI offers authorized Local or NAS destinations and eligible Expedited, Standard, or Bulk retrieval choices, with backend errors shown for ineligible files

### Requirement: Activity and audit drill-down
The audit view SHALL list newest activities first with ID, action, target, actor, file and byte progress, duration, status, and timestamp. Expanding an activity SHALL expose paginated per-file outcomes with transfer speeds, S3 versions and ETags, and errors.

#### Scenario: Retry failed files
- **WHEN** an authorized operator retries eligible failed files from a settled activity
- **THEN** the UI links to the accepted retry activity and preserves the original audit record
