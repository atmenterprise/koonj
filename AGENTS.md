# Working on Koonj

Koonj is a project-scoped data catalog and storage archival platform. It scans local and SMB/NAS mounts and S3-compatible storage, catalogs files and their lineage, and runs archive and recall jobs. PostgreSQL stores catalog and job state, RabbitMQ dispatches transfer and notification work, and Keycloak provides authentication using OIDC/Basic Auth. FUSE can recall archived files on demand, with optional Samba access. FUSE is work in development codebase, do not give priority on it.

## Where to work

| Path | Responsibility |
| --- | --- |
| `koonj-server/` | Java 21 and Spring Boot API, catalog scans, storage connectors and credentials, transfer admission and workers, and project-scoped MCP tools. Java packages under `uk.appsthatmatter.koonj.server` include `controller`, `dto`, `service`, `entity`, `repository`, and `security`. Runtime defaults live in `src/main/resources/application.yml`. |
| `koonj-ui/` | React, TypeScript, and Vite web console. Active features live in `src/modules/`, application routing and providers in `src/app/`, Keycloak integration in `src/auth/`, and API contracts, services, and query hooks in `src/api/`. Current routes use the live modules. |
| `koonj-notification-worker/` and `koonj-notification-model/` | RabbitMQ notification delivery and shared notification types. The server owns the notification outbox. |
| `koonj-fuse/` | Linux FUSE gateway for on-demand recall. Samba configuration and mount scripts live under `deploy/`. Work in development codebase. Low on priority.|
| `deploy/` and `koonj-assembly/` | Compose files, Dockerfiles, Nginx and Keycloak configuration, support scripts, and Maven deployment ZIP packaging. The main Compose source is `deploy/docker/docker-compose.yml`. |

## Working across modules

- Preserve project isolation when changing catalog queries, storage lookups, transfers, or MCP tools. Check server-side authorization for the relevant `KOONJ_ADMIN`, `KOONJ_OPERATOR`, and `KOONJ_VIEWER` roles.
- When changing an API contract, check the server controller and DTOs alongside the UI types, services, and contract tests. Check MCP callers if they use the changed operation.
- When changing archive or recall behavior, review persisted job state, path reservations, checksums, and retry and resume behavior. Use disposable storage and services for integration checks.
- When changing a runtime setting or packaged file, check module application defaults, `deploy/docker/.env.example`, the Compose configuration, and the `koonj-assembly/assembly/` descriptors as applicable. Update the relevant user guide and keep real credentials out of tracked files.
- When Changing the UI code (`koonj-ui`) for adding new features or updating features, ensure that the design of the website is maintained consistent across the change.

## Setup and verification

Use JDK 21, Maven 3.9+, Node.js 22.12+ with npm, and Docker with Compose v2 for local services. Follow the [local development setup](docs/guide/DEPLOYMENT-GUIDE.md#option-c-local-development-stack) for PostgreSQL, LocalStack, Keycloak, and the server. From `koonj-ui/`, run `npm ci` and `npm run dev` to start the console.

Run the checks relevant to the change:

| Scope | Command and working directory |
| --- | --- |
| Server and notification worker tests | `mvn -pl koonj-server,koonj-notification-worker -am test` from the repository root |
| UI tests and type-checked build | `npm ci`, `npm test`, and `npm run build` from `koonj-ui/` |
| Deployment ZIP | `mvn -pl koonj-assembly -am clean package` from the repository root. The ZIP is written to `koonj-assembly/target/`. |

The normal server tests use H2. The transfer integration test requires disposable PostgreSQL and RabbitMQ. The notification worker broker test runs when `KOONJ_TEST_RABBITMQ=true` and a configured broker is available. See the [deployment guide](docs/guide/DEPLOYMENT-GUIDE.md) and [CI workflow](.github/workflows/ci.yml) for the integration settings and packaging checks.

See the [UI guide](koonj-ui/README.md) for browser setup and an archive/recall smoke test, the [FUSE guide](docs/guide/FUSE-GUIDE.md) for mount validation, and the [contribution guide](CONTRIBUTING.md) for coding conventions and Conventional Commit PR titles.
