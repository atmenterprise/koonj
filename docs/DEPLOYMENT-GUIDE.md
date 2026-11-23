# Koonj — Deployment Guide

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Build the Deployment ZIP](#build-the-deployment-zip)
- [Prerequisites](#prerequisites)
- [Option A: Docker Deployment](#option-a-docker-deployment)
- [Optional Docker Observability with OpenObserve](#optional-docker-observability-with-openobserve)
- [Option B: On-Premise Deployment](#option-b-on-premise-deployment)
- [Scaling](#scaling)
- [Configuration Reference](#configuration-reference)
- [Keycloak Setup](#keycloak-setup)
- [Security Checklist](#security-checklist)
- [Troubleshooting](#troubleshooting)

---

## Overview

Koonj is a data catalog and explorer application consisting of two independently
deployable components:

| Component        | Technology             | Purpose                              |
| ---------------- | ---------------------- | ------------------------------------ |
| **koonj-server** | Spring Boot 3.5 / JDK 21 | REST API, catalog engine, job runner |
| **koonj-ui**     | React SPA / Nginx      | Web console served as static files   |

Both components can be **scaled independently** — run multiple API server
instances behind a load balancer and multiple Nginx instances for the UI.

### External dependencies

| Service        | Version | Purpose                    |
| -------------- | ------- | -------------------------- |
| PostgreSQL     | 15+     | Catalog metadata store     |
| Keycloak       | 24+     | Identity & access management |

---

## Architecture

```
                    ┌──────────────┐
                    │   Browser    │
                    └──────┬───────┘
                           │ HTTP(S)
                    ┌──────▼───────┐
                    │   Nginx /    │
                    │   koonj-ui   │◄── Static SPA assets
                    └──────┬───────┘
                           │ /api/*
              ┌────────────▼────────────┐
              │   Load Balancer (opt.)  │
              └────┬───────────────┬────┘
           ┌───────▼──────┐ ┌──────▼───────┐
           │ koonj-server │ │ koonj-server │  ← scale horizontally
           │  instance 1  │ │  instance N  │
           └───────┬──────┘ └──────┬───────┘
                   │               │
           ┌───────▼───────────────▼───────┐
           │          PostgreSQL            │
           └───────────────────────────────┘
                           │
           ┌───────────────▼───────────────┐
           │          Keycloak             │
           └───────────────────────────────┘
```

---

## Build the Deployment ZIP

From the repository root, with JDK 21, Maven 3.9+, and Node.js 22.12+ with npm:

```bash
mvn -pl koonj-assembly -am clean package
```

The build writes `koonj-assembly/target/koonj-<version>-deployment.zip`.
Use `-Ddeployment.version=1.0.0` to set the version in the ZIP name and its
top-level directory. The ZIP contains `docker/` and `on-premise/`, each
with the executable server and FUSE JARs and the compiled UI. The Docker
package includes the Linux host setup script, the FUSE gateway, and the
optional Samba sidecar configuration. Follow the
[transparent recall gateway guide](FUSE-RECALL.md) to provide SMB access to
[Windows](FUSE-RECALL-WINDOWS.md), [macOS](FUSE-RECALL-MACOS.md), and Linux clients.

## Prerequisites

### For Docker Deployment

- Docker Engine 24+ with Docker Compose v2
- 4 GB RAM minimum (8 GB recommended)
- 20 GB disk space

### For On-Premise Deployment

| Requirement  | Linux / Unix              | Windows                       |
| ------------ | ------------------------- | ----------------------------- |
| Java         | OpenJDK 21+ or Temurin 21 | Eclipse Temurin 21+           |
| Web server   | Nginx 1.24+               | Nginx for Windows or IIS 10+  |
| PostgreSQL   | 15+ (local or remote)     | 15+ (local or remote)         |
| Keycloak     | 24+ (local or remote)     | 24+ (local or remote)         |
| OS           | RHEL 8+, Ubuntu 22.04+, Debian 12+ | Windows Server 2019+ |

---

## Option A: Docker Deployment

### 1. Extract the distribution

```bash
unzip koonj-0.1.0-SNAPSHOT-deployment.zip
cd koonj-0.1.0-SNAPSHOT/docker
```

### 2. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and set **all `CHANGE_ME` values**:

```properties
# REQUIRED — database passwords
KOONJ_DB_PASSWORD=your_secure_db_password
KEYCLOAK_DB_PASSWORD=your_secure_kc_db_password
KEYCLOAK_ADMIN_PASSWORD=your_secure_kc_admin_password

# REQUIRED — external Keycloak URL (as seen by browsers)
KOONJ_KEYCLOAK_EXTERNAL_URL=https://auth.yourcompany.com

# CORS — set to your UI's public URL
KOONJ_CORS_ALLOWED_ORIGINS=https://koonj.yourcompany.com

# Data mount — absolute path to files you want to catalog
KOONJ_DATA_MOUNT=/mnt/shared-storage
```

Set `keycloakUrl` in `ui/config.js` to the same public URL as
`KOONJ_KEYCLOAK_EXTERNAL_URL`, without the realm path or a trailing slash.
For remote browsers, make Keycloak reachable through your reverse proxy or
configure `KOONJ_KEYCLOAK_BIND` for the appropriate host interface.
Before the first start, follow [Keycloak Setup](#keycloak-setup) to set the
console URL in the realm template.

### 3. Start the stack

```bash
docker compose up -d --build --wait
```

This starts all four services in dependency order:
1. PostgreSQL (database)
2. Keycloak (auth) — waits for PostgreSQL
3. koonj-server (API) — waits for PostgreSQL + Keycloak
4. koonj-ui (Nginx) — waits for koonj-server

To enable transparent recall from a shared drive, follow the
[Linux gateway setup](FUSE-RECALL.md). Use the `fuse` profile for a local
mount on the gateway or the `smb` profile to also export it over the network.

### 4. Verify

```bash
# Check all services are healthy
docker compose ps

# Test the API health endpoint through the UI proxy
curl -sf http://localhost/actuator/health

# Open the UI
open http://localhost    # or http://<server-ip>
```

### 5. Scale services

```bash
# Scale the API server to 3 instances
docker compose up -d --scale koonj-server=3

# Scale the UI to 2 instances
docker compose up -d --scale koonj-ui=2
```

> **Note:** When scaling `koonj-server`, the Nginx upstream in `koonj-ui`
> resolves via Docker's internal DNS round-robin. For production, consider
> placing an external load balancer (HAProxy, Traefik) in front.

### Stop & restart

```bash
docker compose down          # stop (preserves data volumes)
docker compose up -d --wait  # restart
```

---

## Optional Docker Observability with OpenObserve

The standard Docker command leaves telemetry export disabled. To enable it, set
these values in the extracted `docker/.env` file:

```dotenv
OPENOBSERVE_ROOT_EMAIL=monitoring@example.com
OPENOBSERVE_ROOT_PASSWORD='your_openobserve_password'
OPENOBSERVE_AUTHORIZATION='Basic <base64(email:password)>'
KOONJ_OPENOBSERVE_URL=http://localhost:5080/web
```

Replace the placeholder after `Basic ` with the Base64-encoded ingestion
credentials. For example, encode the email and password you configured above:

```bash
printf '%s' 'monitoring@example.com:your_openobserve_password' | base64 | tr -d '\r\n'
```

From the extracted `docker/` directory, start Compose with both files. The
base file defines the Koonj services and the second file adds OpenObserve and
enables log, metric, and trace export. Use the same file pair for subsequent Compose
commands.

```bash
docker compose -f docker-compose.yml -f docker-compose.otel.yml up -d --build --wait
docker compose -f docker-compose.yml -f docker-compose.otel.yml ps
curl -fsS http://127.0.0.1:5080/healthz
```

Open **Administration → Observability** in Koonj to open the Logs, Metrics, or
Traces view in OpenObserve. For remote browsers, set `KOONJ_OPENOBSERVE_URL` to
the reachable browser URL. Set `KOONJ_OPENOBSERVE_ORGANIZATION` if the ingestion
organization is different from `default`. Sign in to OpenObserve and open
**Traces** in the `default` organization after running a scan or transfer.
Look for the `koonj.job.execution` span and its `operation` and `outcome`
attributes. **Metrics** shows aggregates such as job execution count and
duration. Allow a minute for the first metrics export. **Logs** shows events from
Koonj application loggers in the `koonj_server` stream. Koonj sends all three signals directly over OTLP/HTTP.
For a host installation, set `KOONJ_OTLP_LOGS_ENDPOINT` to the complete OTLP/HTTP
logs endpoint, such as `http://127.0.0.1:5080/api/default/v1/logs`. The OpenObserve data volume persists
across container restarts.

When upgrading the same Compose project from the old Grafana setup, add
`--remove-orphans` to the startup command to remove the old telemetry
containers while preserving their named volumes.

## Option B: On-Premise Deployment

### 1. Extract the distribution

**Linux:**
```bash
unzip koonj-0.1.0-SNAPSHOT-deployment.zip
cd koonj-0.1.0-SNAPSHOT/on-premise
```

**Windows:**
Extract the deployment ZIP and open the `on-premise` directory inside
`koonj-<version>`.

### 2. Install

**Linux (with systemd + Nginx):**
```bash
sudo ./install.sh --systemd --nginx
```

**Linux (manual):**
```bash
sudo ./install.sh --prefix /opt/koonj
```

**Windows (as Administrator):**
```cmd
install.cmd --prefix C:\koonj
```

### 3. Set up PostgreSQL

Create the database and user. Example for a local PostgreSQL:

```sql
CREATE ROLE koonj LOGIN PASSWORD 'your_secure_password';
CREATE DATABASE koonj OWNER koonj;
```

### 4. Set up Keycloak

Copy the provided Koonj login theme to Keycloak's `themes` directory. Set the
console URL in the realm template using [Keycloak Setup](#keycloak-setup), then
import the realm.

```bash
cp -R /opt/koonj/keycloak/themes/koonj /opt/keycloak/themes/
```

**Option A — Automatic import at first start:**
```bash
# Copy the realm file to Keycloak's import directory
cp /opt/koonj/keycloak/koonj-realm.json \
   /opt/keycloak/data/import/

# Start Keycloak with --import-realm
/opt/keycloak/bin/kc.sh start --import-realm
```

**Option B — Manual import via Admin Console:**
1. Open the Keycloak Admin Console
2. Create a new realm named `koonj`
3. Import `koonj-realm.json` from the Realm Settings

> **Important:** Change the default user passwords in Keycloak after import.
> The realm template contains development-only credentials.

### 5. Configure Koonj

Edit the environment file:

**Linux:** `/opt/koonj/config/koonj.env`
**Windows:** `C:\koonj\config\koonj.env`

```properties
KOONJ_DB_URL=jdbc:postgresql://db-server:5432/koonj?reWriteBatchedInserts=true
KOONJ_DB_USERNAME=koonj
KOONJ_DB_PASSWORD=your_secure_password
KOONJ_AUTH_ISSUER_URI=https://auth.yourcompany.com/realms/koonj
KOONJ_AUTH_JWK_SET_URI=https://auth.yourcompany.com/realms/koonj/protocol/openid-connect/certs
KOONJ_CORS_ALLOWED_ORIGINS=https://koonj.yourcompany.com
```

In the installed `web/config.js`, set `keycloakUrl` to the browser-facing
Keycloak URL, such as `https://auth.yourcompany.com`.

### 6. Start the server

**Linux (systemd):**
```bash
sudo systemctl start koonj-server
sudo systemctl status koonj-server
# View logs:
sudo journalctl -u koonj-server -f
```

**Linux (manual):**
```bash
/opt/koonj/bin/koonj-server.sh start
/opt/koonj/bin/koonj-server.sh status
/opt/koonj/bin/koonj-server.sh logs
```

**Windows:**
```cmd
C:\koonj\bin\koonj-server.cmd start
C:\koonj\bin\koonj-server.cmd status
```

### 7. Configure Nginx for the UI

The installer with `--nginx` places the config at
`/etc/nginx/conf.d/koonj.conf`. For a manual install, copy it from the
extracted `on-premise/` directory and update the web root if you used a
custom installation prefix.

```bash
sudo cp nginx/koonj.conf /etc/nginx/conf.d/
sudo nginx -t && sudo systemctl reload nginx
```

**Windows (IIS):** Create a new website pointing to `C:\koonj\web` with a
URL Rewrite rule that proxies `/api/*` to `http://localhost:8080`.

### 8. Verify

```bash
curl -sf http://localhost:8080/actuator/health
# Open http://<server-hostname> in a browser
```

### Optional OTLP metrics

To send metrics to an existing OpenObserve instance, add these settings to
the installed `config/koonj.env`. Use the full metrics endpoint for your
organization and the ingestion authorization value described in
[Optional Docker Observability with OpenObserve](#optional-docker-observability-with-openobserve).

```dotenv
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true
MANAGEMENT_OTLP_METRICS_EXPORT_URL=https://observe.example.com/api/default/v1/metrics
OPENOBSERVE_AUTHORIZATION="Basic <base64(email:password)>"
```

Restart the server with `sudo systemctl restart koonj-server` for a systemd
installation, `/opt/koonj/bin/koonj-server.sh restart` for a manual Linux
installation, or `C:\koonj\bin\koonj-server.cmd restart` on Windows.

---

## Scaling

### Horizontal scaling — API Server

Each `koonj-server` instance is stateless (state is in PostgreSQL). Run
multiple instances on different ports or hosts.

**On-premise multi-instance:**
```bash
# Instance 1
KOONJ_SERVER_PORT=8080 /opt/koonj/bin/koonj-server.sh start

# Instance 2 (copy the installation or use a shared mount)
KOONJ_SERVER_PORT=8081 /opt/koonj/bin/koonj-server.sh start
```

Update the Nginx upstream to load-balance:
```nginx
upstream koonj_api {
    server 127.0.0.1:8080;
    server 127.0.0.1:8081;
    keepalive 32;
}
```

**Docker scaling:**
```bash
docker compose up -d --scale koonj-server=3
```

### Horizontal scaling — UI

The UI is static files served by Nginx. Scale by running multiple Nginx
instances behind a load balancer.

### Vertical scaling — Database

Adjust the connection pool size in `koonj.env`:
```properties
KOONJ_DB_POOL_SIZE=30
```

Adjust JVM heap to match available memory:
```properties
JAVA_OPTS=-Xms1g -Xmx4g -XX:+UseG1GC
```

---

## Project workspaces

Upgrade an existing PostgreSQL installation by backing up the Koonj database,
stopping the server, and applying the
[project workspace migration](../deploy/migrations/project-workspaces.sql) with
`psql -v ON_ERROR_STOP=1 -f deploy/migrations/project-workspaces.sql` against that
database before starting the new server. The migration assigns existing storage
connections, catalog records, jobs, and integrations to **Default project**.
Current temperature thresholds remain available in that project. Fresh
installations using `KOONJ_DDL_AUTO=update` create the project schema at startup.
The Activity log uses stable account identifiers for newly submitted jobs.
Older jobs without a verified owner remain in the database and in catalog
lineage where applicable. Resubmit any older paused jobs after upgrading.
Reapply the migration on installations that already have projects to add the
activity ownership column and index.

Existing administrators and the configured HTTP Basic account are assigned to
Default project on first sign-in. To preserve access for existing Keycloak
operators or viewers, set `KOONJ_PROJECTS_LEGACY_SUBJECTS` to a comma-separated
list of their Keycloak subject identifiers before rollout, or assign them to
Default project from **Administration → Projects** after their first sign-in.
Other new users receive a personal project and retain their Keycloak role
requirements. Administrators can create shared projects and manage memberships.
Use dedicated mount roots, shares, and buckets to keep the underlying storage
separate between projects.

API clients can call `POST /api/v1/projects/session` to obtain their memberships
and then pass the selected project UUID in `X-Koonj-Project`. Requests without
the header use Default project when the account belongs to it, or the account’s
first available project. FUSE uses Default project unless
`KOONJ_AGENT_PROJECT_ID` is set. For a project-specific FUSE mount, include that
UUID in the server’s `KOONJ_PROJECTS_AGENT_IDS` allowlist and use it as
`KOONJ_AGENT_PROJECT_ID` on the mount.

## Configuration Reference

### Environment variables

| Variable | Default | Description |
| -------- | ------- | ----------- |
| `KOONJ_DB_URL` | `jdbc:postgresql://localhost:5432/koonj` | JDBC connection URL |
| `KOONJ_DB_USERNAME` | `koonj` | Database user |
| `KOONJ_DB_PASSWORD` | — | Database password (**required**) |
| `KOONJ_DDL_AUTO` | `update` | Hibernate DDL mode (`update`, `validate`, `none`) |
| `KOONJ_DB_POOL_SIZE` | `20` | Maximum HikariCP pool size |
| `KOONJ_AUTH_ISSUER_URI` | `http://localhost:8180/realms/koonj` | Keycloak realm issuer |
| `KOONJ_AUTH_JWK_SET_URI` | `…/protocol/openid-connect/certs` | Keycloak JWKS endpoint |
| `KOONJ_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated CORS origins |
| `KOONJ_SERVER_PORT` | `8080` | Server HTTP port |
| `KOONJ_MAX_THREADS` | `200` | Tomcat max threads |
| `KOONJ_BASIC_AUTH_ENABLED` | `false` | Enable HTTP Basic Auth |
| `KOONJ_BASIC_AUTH_USERNAME` | — | Basic auth username |
| `KOONJ_BASIC_AUTH_PASSWORD` | — | Basic auth password |
| `KOONJ_BASIC_AUTH_ROLE` | `KOONJ_VIEWER` | Role for basic auth user |
| `JAVA_OPTS` | `-Xms512m -Xmx2g` | JVM options |
| `SPRING_PROFILES_ACTIVE` | `production` | Active Spring profiles |

### Role permissions

Open **Administration → Permissions** to change Operator and Viewer permissions
across projects. Keycloak assigns roles to users. Koonj combines the grants from
all roles on the signed-in account and still enforces project membership.
Administrator permissions are fixed. Operators can view Storage status by
default. Storage management is a separate permission.

For a deployment that already has projects and manages its schema manually,
reapply the project workspace migration to create the role permission settings
table before starting the new server.

### Keycloak roles

| Role | Access |
| ---- | ------ |
| `KOONJ_ADMIN` | Full access — connections, telemetry, jobs, purge |
| `KOONJ_OPERATOR` | Catalog read, scan, archive, recall |
| `KOONJ_VIEWER` | Read-only Home and Explorer |

---

## Keycloak Setup

Before importing `keycloak/koonj-realm.json`, update the `koonj-ui`
client's `redirectUris` and `post.logout.redirect.uris` entries to the
deployed console URL followed by `/*`. For example, use
`https://koonj.yourcompany.com/*` for a console at
`https://koonj.yourcompany.com`. The existing `webOrigins` setting uses the
origins from the allowed redirects. If the realm is already installed, update
these client settings in the Keycloak Admin Console.

New realms use the Koonj login theme from the realm template. For an existing
realm, deploy the theme files, restart Keycloak, then select **Koonj realm →
Realm settings → Themes → Login theme → koonj** and save. Keycloak skips
startup imports for realms that already exist. Rebuild the development Keycloak
container with `docker compose -f deploy/docker-compose.yml up -d --build keycloak`.

## Security Checklist

- [ ] Change all default passwords in `.env` / `koonj.env`
- [ ] Change Keycloak admin password and realm user passwords
- [ ] Run Keycloak in production mode (not `start-dev`)
- [ ] Enable HTTPS/TLS on the Nginx reverse proxy
- [ ] Bind PostgreSQL to `127.0.0.1` or use firewall rules
- [ ] Bind Keycloak admin console to internal network only
- [ ] Set `KOONJ_DDL_AUTO=validate` after initial schema creation
- [ ] Restrict filesystem permissions on `koonj.env` (`chmod 600`)
- [ ] Enable PostgreSQL SSL connections
- [ ] Review and harden `koonj-realm.json` (disable self-registration if needed)

---

## Troubleshooting

### Server won't start

1. Check Java version: `java -version` (must be 21+)
2. Check the log: `/opt/koonj/logs/koonj-server.log`
3. Verify database connectivity: `psql -h <host> -U koonj -d koonj`
4. Verify Keycloak is reachable: `curl <KOONJ_AUTH_JWK_SET_URI>`

### UI shows blank page

1. Verify static files exist in `/opt/koonj/web/`
2. Check Nginx config: `nginx -t`
3. Check browser console for errors (Keycloak URL mismatch is common)

### 401 Unauthorized on API calls

1. Verify `KOONJ_AUTH_ISSUER_URI` matches the Keycloak realm URL in the browser
2. Ensure the `koonj-server` client exists in Keycloak with `bearerOnly: true`
3. Check clock synchronization between server and Keycloak (JWT expiry)

### Database connection refused

1. Verify PostgreSQL is running and accepting connections
2. Check `KOONJ_DB_URL` host, port, and database name
3. Verify the user has `CONNECT` and `CREATE` privileges on the database

### Docker: service unhealthy

```bash
docker compose logs <service-name>
docker compose ps    # check health status
docker inspect --format='{{json .State.Health}}' <container>
```

### Docker: missing build context or telemetry export errors

- If Compose reports that `koonj-server` has neither an image nor a build
  context, run it from the extracted `docker/` directory and supply
  `docker-compose.yml` before `docker-compose.otel.yml`.
- If startup logs still reference `otel-collector` or `TEMPO_ENDPOINT`, use
  the updated deployment ZIP and rebuild the server image with `up --build`.
- If OpenObserve returns HTTP 401, check that `OPENOBSERVE_AUTHORIZATION`
  contains `Basic ` followed by the Base64-encoded ingestion credentials.
  Recreate `koonj-server` with both Compose files after updating `.env`.
