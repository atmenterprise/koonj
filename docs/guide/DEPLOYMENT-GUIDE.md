# Koonj — Deployment Guide

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Build the Deployment ZIP](#build-the-deployment-zip)
- [Prerequisites](#prerequisites)
- [Option A: Docker Deployment](#option-a-docker-deployment)
- [Option B: On-Premise Deployment](#option-b-on-premise-deployment)
- [Option C: Local Development Stack](#option-c-local-development-stack)
- [Server Logging](#server-logging)
- [Optional Docker Observability with OpenObserve](#optional-docker-observability-with-openobserve)
- [Scaling](#scaling)
- [Archive and Recall Workers](#archive-and-recall-workers)
- [Project Workspaces & Private Projects](#project-workspaces--private-projects)
- [Storage Authentication and Secret Stores](#storage-authentication-and-secret-stores)
- [Configuration Reference](#configuration-reference)
- [Keycloak Setup](#keycloak-setup)
- [MCP Server & Integration](#mcp-server--integration)
- [Security Checklist](#security-checklist)
- [Troubleshooting](#troubleshooting)

---

## Overview

Koonj is a data catalog and explorer application with independently deployable components.

| Component        | Technology             | Purpose                              |
| ---------------- | ---------------------- | ------------------------------------ |
| **koonj-server** | Spring Boot 3.5 / JDK 21 | REST API, catalog access, transfer admission |
| **koonj-transfer-worker** | Koonj server JAR, worker role | Archive, recall, and catalog scan execution |
| **koonj-notification-worker** | Spring Boot 3.5 / JDK 21 | Notification routing, RabbitMQ publishing, and delivery |
| **koonj-ui**     | React SPA / Nginx      | Web console served as static files   |

Scale API replicas, transfer workers, notification workers, and Nginx independently. See [Archive and Recall Workers](#archive-and-recall-workers) for reservation rules and upgrade steps.

### External dependencies

| Service        | Version | Purpose                    |
| -------------- | ------- | -------------------------- |
| PostgreSQL     | 15+     | Catalog metadata store     |
| Keycloak       | 24+     | Identity & access management |
| RabbitMQ       | 4.2     | Durable transfer and notification queues |

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

The API reserves both storage endpoints in PostgreSQL and records a dispatch
request with the job. RabbitMQ delivers the job ID to an eligible transfer
worker. Workers update job progress in PostgreSQL.

The API and transfer workers write notification events to a PostgreSQL outbox in the job transaction.
The notification worker reads the outbox, records each pending delivery, and
publishes its ID to RabbitMQ. Separate RabbitMQ queues feed SMTP consumers and
webhook consumers. SMTP and HTTP connections run in the worker process.

---

## Build the Deployment ZIP

From the repository root, with JDK 21, Maven 3.9+, and Node.js 22.12+ with npm:

```bash
mvn -pl koonj-assembly -am clean package
```

The build writes `koonj-assembly/target/koonj-<version>-deployment.zip`.
Use `-Ddeployment.version=1.0.0` to set the version in the ZIP name and its
top-level directory. The ZIP contains `docker/` and `on-premise/`, each
with the executable server, notification worker, and FUSE JARs and the compiled UI. The Docker
package includes the Linux host setup script, the FUSE gateway, and the
optional Samba sidecar configuration. Follow the
[FUSE & Samba Integration Guide](FUSE-GUIDE.md) to provide SMB access to
[Windows](FUSE-GUIDE.md#windows-file-explorer), [macOS](FUSE-GUIDE.md#macos-finder), and [Linux](FUSE-GUIDE.md#linux) clients.

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
KOONJ_RABBITMQ_PASSWORD=your_secure_broker_password

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

Compose starts PostgreSQL and RabbitMQ, then Keycloak and the API. The UI
waits for the API. The transfer and notification workers wait for the API and RabbitMQ.
RabbitMQ is reachable within the Docker network and stores its data in the
`koonj_rabbitmq_data` volume. Compose provisions its user and virtual host on
initial startup. To use an external cluster, set the worker's broker host,
port, username, password, virtual host and TLS mode in `.env`.

To enable transparent recall from a shared drive, follow the
[FUSE & Samba Integration Guide](FUSE-GUIDE.md). Use the `fuse` profile for a local
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
# Scale API and transfer workers independently
docker compose up -d --scale koonj-server=3 --scale koonj-transfer-worker=3

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

### Optional OpenObserve dashboards and OTLP

To provision the dashboards and send telemetry to an existing OpenObserve instance, add these settings
to the installed `config/koonj.env`. Use the full endpoints for your
organization and the ingestion authorization value described in
[Optional Docker Observability with OpenObserve](#optional-docker-observability-with-openobserve).

```dotenv
KOONJ_OPENOBSERVE_URL=https://observe.example.com/web
KOONJ_OPENOBSERVE_ORGANIZATION=default
KOONJ_OPENOBSERVE_DASHBOARDS_ENDPOINT=https://observe.example.com/api/default/dashboards
KOONJ_OTLP_LOGS_ENDPOINT=https://observe.example.com/api/default/v1/logs
MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true
MANAGEMENT_OTLP_METRICS_EXPORT_URL=https://observe.example.com/api/default/v1/metrics
MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true
MANAGEMENT_OTLP_TRACING_ENDPOINT=https://observe.example.com/api/default/v1/traces
MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
OPENOBSERVE_AUTHORIZATION="Basic <base64(email:password)>"
```

Restart the server with `sudo systemctl restart koonj-server` for a systemd
installation, `/opt/koonj/bin/koonj-server.sh restart` for a manual Linux
installation, or `C:\koonj\bin\koonj-server.cmd restart` on Windows.

---

## Option C: Local Development Stack

### Start the development stack

Docker Compose runs PostgreSQL 18, LocalStack 3.8, and Keycloak. Run the Spring Boot server on the host with Java 21 and Maven.

From the repository root:

```sh
export KOONJ_PG_PORT=5432
export KOONJ_S3_PORT=4566
export KOONJ_LOCAL_REGION=eu-west-2
export KOONJ_LOCAL_BUCKET=koonj-local-archive

docker compose -f deploy/docker-compose.yml up -d --wait
```

The LocalStack readiness script creates the configured S3 bucket. One PostgreSQL container hosts both the `koonj` and `keycloak` databases. PostgreSQL creates the Keycloak database and login role when it initializes a new volume. Keycloak stores its imported realm in that database.

If your PostgreSQL volume predates Keycloak and lacks the `keycloak` database, run the bootstrap SQL once inside the existing PostgreSQL service. The script preserves existing roles, databases, and credentials.

```sh
docker compose -f deploy/docker-compose.yml up -d --wait postgres
docker compose -f deploy/docker-compose.yml exec -T postgres \
  sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -f /docker-entrypoint-initdb.d/10-keycloak.sql'
docker compose -f deploy/docker-compose.yml up -d --wait
```

```sh
export KOONJ_DB_URL="jdbc:postgresql://localhost:${KOONJ_PG_PORT}/koonj?reWriteBatchedInserts=true"
export KOONJ_DB_USERNAME=koonj
export KOONJ_DB_PASSWORD=password
export KOONJ_DDL_AUTO=update

# For a disposable LocalStack setup. Keep a stable escrowed key for a persistent database.
export KOONJ_STORAGE_KEY_PROVIDER=local
export KOONJ_STORAGE_LOCAL_ACTIVE_KEY=dev
export KOONJ_STORAGE_LOCAL_KEYS="dev:$(openssl rand -base64 32)"
export KOONJ_STORAGE_ALLOW_INSECURE_ENDPOINTS=true

cd koonj-server
mvn spring-boot:run
```

Hibernate creates and updates the PostgreSQL tables on startup. Set the same `KOONJ_DB_*` variables in the run configuration when launching `KoonjServerApplication` from IntelliJ. Open [Swagger UI](http://localhost:8080/swagger-ui/start) or [OpenAPI JSON](http://localhost:8080/v3/api-docs).

The current `deploy/Dockerfile.server` expects a nested Maven module selected by `MODULE`. The standalone `koonj-server` is launched with Maven in this setup.

### Keycloak and role verification

1. Open the [Keycloak Admin Console](http://localhost:8180/admin) and sign in to the **master** realm with the bootstrap credentials. The development defaults are `admin` / `admin`. Set `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, and `KEYCLOAK_DB_PASSWORD` before the first Compose startup to customize them.
2. Select the **koonj** realm. Under **Clients**, confirm `koonj-ui` is public, uses Standard Flow and S256 PKCE, and sends the `koonj-server` audience. Under **Identity providers**, choose **OpenID Connect v1.0** or **SAML v2.0** to add an external broker. Configure the callback URL shown by Keycloak with the external provider. Configure LDAP or AD under **User federation**, and configure SMTP under **Realm settings → Email** to deliver password reset links.
3. Start the [Koonj UI](../../koonj-ui/README.md) and visit [http://localhost:5173](http://localhost:5173). Use **Sign In** with one of the development accounts below, or use **Create Account** and assign the new user a Koonj realm role in **Users → Role mapping**. A self-registered account can sign in and sees **Workspace access pending** until an administrator assigns a role. Sign out and back in to get a token with the new role.
4. In the UI's browser Network tab, inspect `GET /api/v1/catalog/locations`. The request includes `Authorization: Bearer …`. Use the access token locally in Swagger **Authorize** or in the shell below. Sign out and back in to switch users. The master realm account manages Keycloak and the seeded realm admin manages Koonj.

| Development sign-in | Password | Expected access |
| --- | --- | --- |
| `admin@appsthatmatter.uk` | `KoonjDev-Admin-26!` | Home, Explorer, Activity, Administration telemetry, and Storage. Manage and test connections, scan, archive, recall, and purge |
| `operator@appsthatmatter.uk` | `KoonjDev-Operator-26!` | Home, Explorer, and Activity. Scan, archive, and recall. Administration, Storage, and purge require Admin |
| `viewer@appsthatmatter.uk` | `KoonjDev-Viewer-26!` | Read-only Home and Explorer. Job history, connection management, telemetry, and write requests return 403 |

The server validates the token's signature, issuer, expiry, and `koonj-server` audience. `KOONJ_AUTH_ISSUER_URI` and `KOONJ_AUTH_JWK_SET_URI` select the realm and its signing keys. `KOONJ_CORS_ALLOWED_ORIGINS` accepts a comma-separated list of explicit origins and defaults to `http://localhost:5173`. When using a different browser origin, update the Keycloak client's allowed redirects and origins as well.

```sh
curl -i http://localhost:8080/api/v1/connections
# Expected without a token: 401

printf 'Paste a short-lived viewer token: '
read -rs KOONJ_ACCESS_TOKEN
printf '\n'
curl -i -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" http://localhost:8080/api/v1/catalog/locations
# Expected: 200
curl -i -X POST -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" \
  http://localhost:8080/api/v1/catalog/scan/00000000-0000-0000-0000-000000000000
# Expected: 403
```

Legacy scripts can opt in to HTTP Basic with `KOONJ_BASIC_AUTH_ENABLED=true`, `KOONJ_BASIC_AUTH_USERNAME`, and `KOONJ_BASIC_AUTH_PASSWORD`. This account defaults to `KOONJ_VIEWER`. Set `KOONJ_BASIC_AUTH_ROLE` to the role the script needs. JWT Bearer is the default.

Compose uses Keycloak development mode and seeded credentials. Use a production Keycloak configuration and real secrets for deployed environments. The startup import skips an existing realm, so customize persisted realms in the admin console. PostgreSQL's initial credentials also apply only when initializing a new volume.

### Register storage and archive a file

Read an **admin** access token into `KOONJ_ACCESS_TOKEN` using the command above before running these examples.

Create a sample file on the host where the server runs. For NAS, use the absolute path of the mounted share.

```sh
mkdir -p /tmp/koonj-source/docs
printf 'Koonj archive example\n' > /tmp/koonj-source/docs/report.txt

curl -fsS http://localhost:8080/api/v1/connections \
  -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Primary Source","providerType":"LOCAL","role":"BOTH","basePathOrBucket":"/tmp/koonj-source"}'
```

Register the bucket created by LocalStack using its dummy `test` keys. The server encrypts these credentials using the local wrapping key configured above. For a persistent installation, see [Storage Authentication and Secret Stores](#storage-authentication-and-secret-stores).

```sh
curl -fsS http://localhost:8080/api/v1/connections \
  -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"LocalStack Archive\",\"providerType\":\"CUSTOM_S3\",\"role\":\"BOTH\",
       \"basePathOrBucket\":\"${KOONJ_LOCAL_BUCKET}\",\"endpointUrl\":\"http://127.0.0.1:${KOONJ_S3_PORT}\",
       \"region\":\"${KOONJ_LOCAL_REGION}\",\"authMode\":\"ENCRYPTED_CREDENTIALS\",\"credentials\":{\"accessKeyId\":\"test\",\"secretAccessKey\":\"test\"},
       \"pathStyleAccessEnabled\":true}"
```

For MinIO use `MINIO`, its endpoint, bucket, region, and either encrypted access keys or a project-scoped AWS Secrets Manager reference. For AWS use `AWS_S3` with the bucket's region and `pathStyleAccessEnabled:false`. Use the AWS workload identity default or configure a customer IAM role.

Copy the two IDs from the responses. The optional scan creates a catalog listing. Archive can also discover the selected files directly.

```sh
LOCAL_ID='<local connection UUID>'
S3_ID='<LocalStack connection UUID>'
curl -fsS -X POST -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" "http://localhost:8080/api/v1/connections/$S3_ID/test"
curl -fsS -X POST -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" "http://localhost:8080/api/v1/catalog/scan/$LOCAL_ID"

curl -fsS http://localhost:8080/api/v1/transfers/archive \
  -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"sourceConnectionId\":\"$LOCAL_ID\",\"targetConnectionId\":\"$S3_ID\",
       \"paths\":[\"/docs/report.txt\"],\"storageClass\":\"STANDARD\",\"purgeSource\":false}"
```

Use `GET /api/v1/transfers/jobs/{jobId}` with the returned ID. Use `GET /api/v1/catalog/files?connectionId=...` to inspect the catalog. To recall, swap the two connection IDs and submit the same path to `POST /api/v1/transfers/recall`.

Archive writes to the corresponding object key. Recall replaces the corresponding destination file after staging the received bytes. Keep writers quiescent during purge and dedicate the archive prefix or bucket to Koonj. Recall requires an object that is immediately readable, including any cold object restored separately by the provider.

Phase 1 runs jobs on one server instance. Interrupted jobs require resubmission. Multipart uploads support objects up to 625 GiB, subject to provider limits. Scans and folder selections keep discovered metadata in memory. SMB registration validates the share syntax and reports that the driver is scheduled for Phase 2. PostgreSQL stores configured storage credentials, so restrict access to the metadata database.

### Stop the development stack

From the repository root:

```sh
docker compose -f deploy/docker-compose.yml down
```

---

## Server Logging

The API and transfer worker write to the console at INFO by default. Request
logs use HTTP route templates and status codes, and scan and transfer logs use
job IDs. Set `KOONJ_LOG_LEVEL=DEBUG` for diagnostics or `KOONJ_LOG_LEVEL=TRACE`
for DETAIL events such as batch progress. DETAIL uses Logback's TRACE level.
WARN and ERROR are visible at the default setting.

Set `KOONJ_LOG_FILE` to a file on a writable shared mount to also write rolling
logs. With Docker, set `KOONJ_LOG_FILE` for the API and
`KOONJ_TRANSFER_WORKER_LOG_FILE` for the worker in `docker/.env`. Use container
paths on a mounted directory. Each replica needs its own file for safe rotation.
With systemd, console output is available through `journalctl -u koonj-server`.
For file output outside `/opt/koonj/logs` or `/mnt/koonj`, add the writable
mount directory to the unit's `ReadWritePaths`.

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

Open **Administration → Observability → Open dashboards** in Koonj. On startup,
Koonj imports these dashboards into the default folder of the configured OpenObserve
organization:

- **Koonj | API and JVM** shows request rate, 5xx percentage, p95 latency, JVM heap
  and CPU usage, and database connection waiters.
- **Koonj | Transfer Jobs and Traces** shows execution outcomes, p95 job duration,
  queue pressure, and trace IDs for recent failed jobs. Queue metrics require
  RabbitMQ transfer dispatch.
- **Koonj | Server Logs** shows volume by severity, errors grouped by logger, and
  recent warnings and errors. Search for a job ID in Logs to follow its activity.

Startup imports missing dashboard titles and preserves dashboards you have edited.
The importer retries temporary OpenObserve outages in the background. If the OTLP
credentials can only ingest telemetry, set `KOONJ_OPENOBSERVE_DASHBOARDS_AUTHORIZATION`
to a credential with dashboard list and create permissions. Use the same
authorization header format as for OTLP.

For remote browsers, set `KOONJ_OPENOBSERVE_URL` to
the reachable browser URL. Set `KOONJ_OPENOBSERVE_ORGANIZATION` if the ingestion
organization is different from `default`. Sign in to OpenObserve and open
**Traces** in the `default` organization after running a scan or transfer.
Look for the `koonj.job.execution` span and its `operation` and `outcome`
attributes. **Metrics** shows aggregates such as job execution count and
duration. Allow a minute for the first metrics export. **Logs** shows events from
the API and transfer worker in the `koonj_server` stream. Search for ERROR
records and use the job ID to correlate related events. Framework errors emitted
while the exporter is running also appear in this stream. Koonj sends telemetry
directly over OTLP/HTTP.
For a host installation, set `KOONJ_OTLP_LOGS_ENDPOINT` to the complete OTLP/HTTP
logs endpoint, such as `http://127.0.0.1:5080/api/default/v1/logs`. The OpenObserve data volume persists
across container restarts.

When upgrading the same Compose project from the old Grafana setup, add
`--remove-orphans` to the startup command to remove the old telemetry
containers while preserving their named volumes.

---

## Scaling

### Horizontal scaling — API Server

Enable [Archive and Recall Workers](#archive-and-recall-workers) and run the API replicas on different ports or hosts. Run separate transfer workers for storage access and job execution.

**On-premise multi-instance**

Use one configuration file per process. Set `KOONJ_TRANSFER_RABBIT_ENABLED=true`
throughout the deployment. Set `KOONJ_TRANSFER_WORKER_ENABLED=false` on API
replicas and give each replica its own port.

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

## Archive and Recall Workers

Koonj uses PostgreSQL for job status, file and folder reservations, and pending dispatch. RabbitMQ carries job IDs and dispatch generations to independently scalable transfer workers. Workers load the saved job plan and use the existing transfer, checksum, catalog, and audit logic.

### What users see

- Archive and recall can run at the same time on disjoint storage paths, including within one project or registered connection. The job must fit the worker and storage capacity limits.
- Once accepted, a queued or running job reserves the selected paths on both storage endpoints. A folder reserves its descendants. An overlapping submission returns HTTP 409 without exposing the other job. A project-authorized FUSE caller can wait on an existing interactive recall for the same catalog file.
- Mounted folder recall resolves catalog aliases during admission and verifies each source and destination against the reservation before transferring it. A root selection can reserve an entire storage location. Catalog scans reserve their scanned tree against transfers while different connections can be scanned independently.
- Pause and stop allow active files to finish. Paused and stopped jobs release their reservations after writes finish. Resume and retry reacquire paths and can return 409 if another job reserved them in the meantime.
- Distributed interactive recall returns the provider's estimate when S3 is restoring a cold object. The same job retains its paths and polls again through RabbitMQ without occupying a worker slot while waiting. Bulk recall retains its existing per-file result for a cold object and can be retried later.

### Enable distributed transfers

1. For an upgrade, stop accepting work on the old release, let pending and running work finish, and stop all old instances. Keep paused jobs for users to resume through the new version. If a prior instance was interrupted, reconcile storage and those jobs before switching modes. For an existing database using `KOONJ_DDL_AUTO=validate`, apply `../../deploy/migrations/transfer-dispatch.sql` before starting the new release. With `update`, start one server to update the schema before adding replicas.
2. Enable `KOONJ_TRANSFER_RABBIT_ENABLED=true` in every API and worker process sharing the database. Production Docker Compose starts a separate `koonj-transfer-worker` by default. A standalone installation runs the same server JAR with `KOONJ_TRANSFER_WORKER_ENABLED=false` on API replicas and `KOONJ_TRANSFER_RELAY_ENABLED=false` on worker instances. Leave a relay enabled on at least one API. Keep worker HTTP ports on a private network.
3. Give transfers and notifications separate RabbitMQ credentials and virtual hosts in a SaaS deployment. Set `KOONJ_TRANSFER_RABBIT_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_VHOST`, and `_SSL` as needed. The transfer settings fall back to the shared broker settings. On deployment, the worker declares the queues for its pool. Use a replicated RabbitMQ cluster for quorum queue failover and a highly available PostgreSQL instance.
4. Give API admission processes and eligible workers access to configured mounts at the registered paths. Use identical namespace, alias, case, and routing configuration across replicas. Scale one service at a time and inspect queue age, worker throughput, and S3 and filesystem utilization.

Embedded execution is available for single-process deployments with `KOONJ_TRANSFER_RABBIT_ENABLED=false`. Its job concurrency defaults to one and can be raised with `KOONJ_TRANSFER_EMBEDDED_JOBS`. Drain the transfer workers and resolve stalled attempts before changing modes. Run all processes connected to a given database in the same mode.

### Storage identity and routing

The reservation namespace is scoped to the physical storage. Koonj identifies S3 storage by endpoint and bucket, and mounted paths by their resolved root and relative path inside the configured mounted domain. Nested roots under the same domain can therefore overlap. Case-insensitive mounted-path comparisons default to enabled. Set `KOONJ_TRANSFER_CASE_INSENSITIVE_MOUNTS=false` only if every mount in the domain is case-sensitive.

Use `KOONJ_TRANSFER_STORAGE_ALIASES` to register alternate access paths that resolve to the same underlying data, such as bind mounts or a second hostname for an S3 gateway. Specify semicolon-separated pairs and use one canonical identity per physical location.

```properties
KOONJ_TRANSFER_STORAGE_ALIASES="mount:/srv/media-alias=mount:/mnt/media;s3:s3-alias.internal:443/archive=s3:s3.internal:443/archive"
KOONJ_TRANSFER_MOUNT_POOLS="/mnt/media=media;/mnt/projects=projects"
```

Mounted routes match the longest canonical mount prefix. Run a worker with `KOONJ_TRANSFER_WORKER_POOL=media` for the first route and with `KOONJ_TRANSFER_WORKER_POOL=projects` for the second. Unmatched routes and S3-only scans use the `default` pool. For each pool, register only paths its workers can access. Physical changes to mounts or endpoint alias configuration should be made after affected jobs drain.

| Setting | Default | Scope |
| --- | --- | --- |
| `KOONJ_TRANSFER_BACKGROUND_WORKERS` | 2 | Concurrent bulk jobs per worker instance |
| `KOONJ_TRANSFER_INTERACTIVE_WORKERS` | 1 | Concurrent FUSE recalls per worker instance |
| `KOONJ_TRANSFER_TENANT_JOBS` | 2 | Active bulk jobs per project across workers |
| `KOONJ_TRANSFER_TENANT_INTERACTIVE_JOBS` | 1 | Active FUSE recalls per project across workers |
| `KOONJ_TRANSFER_BACKEND_JOBS` | 8 | Active jobs per bucket or mounted domain across workers. One slot is reserved for interactive work. |
| `KOONJ_TRANSFER_MAX_PARALLEL_FILES` | 24 | Concurrent files per transfer job |
| `KOONJ_TRANSFER_IN_FLIGHT_MIB` | 512 | In-flight file size budget per transfer job |

The backend job cap bounds aggregate file concurrency and byte exposure through the per-job limits. Configure identical cluster-wide limits on workers. The relay batches dispatch across projects. A job that cannot obtain capacity is rescheduled briefly so other projects can proceed.

### Failure and recovery

A queued job is stored with its reservation before the API returns. The relay resumes publication after an unconfirmed publish. Each worker consumer keeps its delivery unacknowledged while it executes the claimed job. With prefetch set to one, RabbitMQ can deliver other jobs to free consumers. Deferred and stale deliveries are acknowledged after their database decisions. Repeated deliveries with the same generation cannot start another attempt. Set the broker's delivery acknowledgement timeout above your expected longest job to avoid unnecessary channel closure and redelivery. A confirmed dispatch is backed by RabbitMQ persistence. After a full broker data loss, rearm only unclaimed pending jobs by setting their `dispatch_after` to the current timestamp in PostgreSQL once replacement queues exist.

Watch `koonj.transfer.jobs.pending`, `koonj.transfer.jobs.oldest.pending.seconds`, `koonj.transfer.attempts.stalled`, and the pool's two RabbitMQ queues and invalid queue. Oldest pending age excludes interactive recall jobs that are already waiting on cold storage. If job age rises while worker slots remain free, check queue routing and endpoint limits.

A stale heartbeat keeps the reservation because a disconnected worker may still be writing. Distributed startup leaves other workers' attempts alone. To release a stalled job:

1. Identify its `worker_instance`, `worker_attempt`, and `worker_heartbeat` in `transfer_jobs`. Confirm that the identified process has exited or that its storage access has been revoked. Check the job events, file movement records, staged S3 objects, destination content, and mounted links. Reconcile storage and the catalog before releasing the reservation if a copy, link replacement, or deletion was interrupted.
2. With the job and attempt IDs copied from that review, run the following in `psql` as a database operator. The attempt and heartbeat conditions prevent this action from reclaiming a different or recently live attempt.

```sql
-- psql variables job_id and attempt_id are the UUIDs reviewed above.
WITH stopped AS (
    UPDATE transfer_jobs
    SET status = 'FAILED', completed_at = current_timestamp,
        details = 'Stalled worker stopped and storage reviewed by an operator.',
        worker_attempt = NULL, worker_instance = NULL, worker_heartbeat = NULL,
        dispatch_after = NULL, active_paths = NULL
    WHERE id = :'job_id'::uuid AND worker_attempt = :'attempt_id'::uuid
      AND worker_heartbeat < current_timestamp - interval '90 seconds'
    RETURNING id, details
), released AS (
    DELETE FROM transfer_reservations WHERE job_id IN (SELECT id FROM stopped)
)
INSERT INTO job_events (job_id, recorded_at, level, message)
SELECT id, current_timestamp, 'ERROR', details FROM stopped;
```

3. Retry from Activity after checking the failed job. Automatic takeover requires a storage layer that can fence the former attempt. The mounted filesystem and S3 connectors do not currently provide that guarantee.

For maintenance, let active jobs finish before removing workers. An instance terminated mid-transfer can require the same recovery procedure.

### Worker integration test

The normal server suite runs against H2. The broker integration test is opt-in. Point `KOONJ_TRANSFER_IT_POSTGRES_URL` at a disposable PostgreSQL database and set `KOONJ_TRANSFER_IT_USERNAME`, `KOONJ_TRANSFER_IT_PASSWORD`, `KOONJ_TRANSFER_IT_RABBIT_PORT`, and the matching `KOONJ_TRANSFER_IT_RABBIT_USERNAME`, `KOONJ_TRANSFER_IT_RABBIT_PASSWORD`, and `KOONJ_TRANSFER_IT_RABBIT_VHOST` for a disposable local RabbitMQ broker. Run `mvn -pl koonj-server -am test`.

---

## Project Workspaces & Private Projects

Upgrade an existing PostgreSQL installation by backing up the Koonj database,
stopping the server, and applying the
[project workspace migration](../../deploy/migrations/project-workspaces.sql) with
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

For existing project installations, complete the [Upgrade to private projects](#upgrade-to-private-projects)
procedure before restarting the API and worker. Human accounts receive private personal projects.
Use **Projects** to create additional projects or invite a member by email.

API clients can call `POST /api/v1/projects/session` to obtain their memberships,
roles and personal default ID. Pass the selected project UUID in `X-Koonj-Project`.
Requests without the header use the account's personal default. FUSE uses Default project unless
`KOONJ_AGENT_PROJECT_ID` is set. For a project-specific FUSE mount, include that
UUID in the server’s `KOONJ_PROJECTS_AGENT_IDS` allowlist and use it as
`KOONJ_AGENT_PROJECT_ID` on the mount.

### Upgrade to private projects

Back up PostgreSQL and stop the API and notification workers. Apply the existing project-workspaces migration first. Export the current memberships for review:

```sql
SELECT p.id, p.name, m.account_subject, a.display_name
FROM project p JOIN project_membership m ON m.project_id = p.id
JOIN project_account a ON a.subject = m.account_subject ORDER BY p.id;
```

In one `psql` session, create the temporary mapping below and insert operator-reviewed rows for at least one existing member of each occupied project. Mark a row personal only when you can confirm the user's original personal project. Keep the shared legacy project's personal flag false. Empty projects retain their IDs and data.

```sql
CREATE TEMP TABLE project_admin_mapping (
    project_id uuid NOT NULL, subject varchar(255) NOT NULL, personal boolean NOT NULL DEFAULT false,
    PRIMARY KEY(project_id, subject)
);
-- INSERT INTO project_admin_mapping VALUES ('<project UUID>', '<exact account subject>', false);
\set ON_ERROR_STOP on
\i deploy/migrations/private-projects.sql
```

Every unmapped personal default is provisioned during migration. Verify the following query returns zero rows before restarting services:

```sql
SELECT a.subject FROM project_account a WHERE NOT EXISTS (
    SELECT FROM project_membership m WHERE m.project_id = a.default_project_id
    AND m.account_subject = a.subject AND m.role = 'ADMIN'
);
```

Set **Koonj Administrators** as the Keycloak registration default group and confirm it grants `KOONJ_ADMIN`. Configure Keycloak SMTP and email verification for new email accounts.

After both the private projects and notifications migrations, apply [project-invitation-channels.sql](../../deploy/migrations/project-invitation-channels.sql) and then [project-lifecycle.sql](../../deploy/migrations/project-lifecycle.sql) before starting the upgraded server and worker. Set `KOONJ_PUBLIC_UI_URL` to the browser-accessible HTTPS UI origin on the worker.

In **Projects → Email invitations**, select an enabled email channel or use **Configure email channel** to create one and select it for this project. The worker uses the channel’s SMTP relay, credentials, TLS settings and sender. Fields left blank follow the email channel’s deployment-default rules. Existing pending deliveries without a channel require a resend after selection. Previously delivered links retain their expiry.

Before rollout, sign in as two test users and confirm each project rejects the other user's ID. Send an invitation to a test mailbox and accept it using the recipient's verified account.

---

## Storage Authentication and Secret Stores

Koonj defaults to host or workload identity where available. Object storage can also use an AWS Secrets Manager reference or credentials encrypted by Koonj before PostgreSQL receives them.

| Storage | Authentication method |
|---|---|
| LOCAL, SMB/CIFS, NAS | `HOST_IDENTITY`. Register the path the API and worker service accounts can access. Mount SMB shares on Linux using the host's Kerberos identity or protected mount credentials. For Windows UNC paths, grant the Windows service account or gMSA access to the share and its filesystem ACLs. |
| AWS S3 in a dedicated deployment | `AWS_DEFAULT_CHAIN`. The AWS SDK uses the deployment's temporary session credentials, for example an ECS task role, EC2 instance role, Kubernetes web identity, or IAM Roles Anywhere. |
| AWS S3 in a customer account | `AWS_ASSUME_ROLE`. Configure the customer IAM role and use Koonj's external ID in its trust policy. |
| MinIO, RustFS, Scality and Custom S3 | `ENCRYPTED_CREDENTIALS` for access keys encrypted by Koonj, or `SECRET_REF` for AWS Secrets Manager. Prefer temporary sessions supplied through Secrets Manager where the provider supports them. |
| AWS S3 using a legacy static key | `ENCRYPTED_CREDENTIALS` or `SECRET_REF`. Omit `endpointUrl` for AWS S3. |

Custom S3 endpoints use HTTPS. The server setting `KOONJ_STORAGE_ALLOW_INSECURE_ENDPOINTS=true` is available for isolated development environments such as LocalStack. Deploy shared SaaS workers with egress restricted to approved object storage destinations, a trusted DNS resolver and separate worker roles from other services. Allow administrators to provision only the targets their projects need. Match worker mount permissions to Koonj project boundaries.

### Test with LocalStack from Docker Compose

Run these steps from the release bundle’s `docker/` directory. Start LocalStack with S3 enabled and its edge port published to the Docker Desktop host. Use a bucket created in LocalStack. The examples below use the development bucket `koonj-local-archive` in `eu-west-2`. If your LocalStack container or bucket uses a different name, substitute it when checking the bucket.

```sh
docker exec localstack-localstack-1 awslocal s3api get-bucket-location \
  --bucket koonj-local-archive --query LocationConstraint --output text
```

Configure a local wrapping key so Koonj can save the LocalStack test keys. Generate the wrapping key once with `openssl rand -base64 32` and keep it in a protected location outside PostgreSQL. If you already use a wrapping key, keep that configuration. For a new local keyring, set these values in the deployment environment or its restricted `.env` file.

```dotenv
KOONJ_STORAGE_KEY_PROVIDER=local
KOONJ_STORAGE_LOCAL_ACTIVE_KEY=v1
KOONJ_STORAGE_LOCAL_KEYS=v1:<generated-base64-key>
```

For an HTTP LocalStack endpoint, create `docker-compose.localstack-auth.yml` in the same directory. Use this override only in an isolated development deployment.

```yaml
services:
  koonj-server:
    environment:
      KOONJ_STORAGE_ALLOW_INSECURE_ENDPOINTS: "true"
  koonj-transfer-worker:
    environment:
      KOONJ_STORAGE_ALLOW_INSECURE_ENDPOINTS: "true"
```

```sh
docker compose -f docker-compose.yml \
  -f docker-compose.otel.yml \
  -f docker-compose.localstack-auth.yml \
  up -d koonj-server koonj-transfer-worker
```

Use the Compose overlays already enabled for your stack. You can omit the telemetry overlay if you run without OpenObserve. On Docker Desktop, both containers can reach a published host port through `host.docker.internal`. If LocalStack is on a shared Docker network with Koonj, use its service DNS name on that network ([Docker Desktop networking](https://docs.docker.com/desktop/features/networking/networking-how-tos/) · [Docker Compose networking](https://docs.docker.com/compose/how-tos/networking/)).

In **Storage**, add the connection with these settings:

| Field | Example |
|---|---|
| Provider | **Custom S3** |
| Endpoint URL | `http://host.docker.internal:4566` |
| Bucket | `koonj-local-archive` |
| Region | `eu-west-2` |
| Path-style access | Enabled |
| Authentication | **Encrypted access keys** |
| Access key ID and secret access key | `test` and `test` for the standard LocalStack test setup |

Choose **Test Connection**. A successful test enables **Save connection** for a new connection.

### AWS workload identity and customer roles

#### Test Amazon S3 from local Docker Compose

Use temporary AWS CLI session credentials for local testing against real Amazon S3. Both the API and transfer worker need the session. If your organization uses IAM Identity Center, configure the host’s AWS CLI v2 profile once with `aws configure sso --profile koonj-dev`. The profile needs access to the bucket you want to test ([AWS CLI IAM Identity Center setup](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html)).

In the release bundle’s `docker/` directory, set `KOONJ_AWS_ALLOW_DEFAULT_CHAIN=true` in `.env` and create `docker-compose.aws-dev.yml`.

```yaml
services:
  koonj-server:
    environment: &aws-session
      AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID:?Export an AWS session first}
      AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY:?Export an AWS session first}
      AWS_SESSION_TOKEN: ${AWS_SESSION_TOKEN:?Export an AWS session first}
  koonj-transfer-worker:
    environment: *aws-session
```

Sign in to the profile and check which AWS identity it uses. The following example requires `jq`. The subshell passes the three credentials to Compose without writing them to a configuration file or leaving them exported in the parent shell ([AWS CLI credential export](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-files.html)).

```sh
(
  set -eu
  aws sso login --profile koonj-dev
  aws sts get-caller-identity --profile koonj-dev

  koonj_aws_session=$(aws configure export-credentials --profile koonj-dev --format process)
  AWS_ACCESS_KEY_ID=$(printf '%s' "$koonj_aws_session" | jq -er '.AccessKeyId | strings | select(length > 0)')
  AWS_SECRET_ACCESS_KEY=$(printf '%s' "$koonj_aws_session" | jq -er '.SecretAccessKey | strings | select(length > 0)')
  AWS_SESSION_TOKEN=$(printf '%s' "$koonj_aws_session" | jq -er '.SessionToken | strings | select(length > 0)')
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  unset koonj_aws_session

  docker compose -f docker-compose.yml \
    -f docker-compose.otel.yml \
    -f docker-compose.aws-dev.yml \
    up -d koonj-server koonj-transfer-worker
)
```

Keep any existing overlays in the Compose command, including the LocalStack override if you use both services. Avoid printing the resolved Compose configuration while the credentials are set. Repeat the session setup when the credentials expire so Compose recreates the affected containers with fresh credentials.

In **Storage**, select **AWS S3 → AWS workload identity**. Enter the real bucket name and its AWS region, leave the endpoint override unset, and turn off **Path-style access** for a standard Amazon S3 bucket. Choose **Test Connection**, then **Save connection**. The bucket and prefix permissions below also apply to the local AWS CLI session.

#### Attach a workload role in production

On Amazon ECS, assign IAM **task roles** to the API and transfer worker tasks. On a dedicated EC2 deployment, attach an **instance profile** and ensure both containers can reach the EC2 Instance Metadata Service (IMDSv2). Keep `KOONJ_AWS_ALLOW_DEFAULT_CHAIN=true` for a dedicated deployment using its own bucket. Start the production workloads without the local development credential overlay. The AWS SDK retrieves and refreshes the role’s temporary credentials ([ECS task IAM roles](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html) · [EC2 role credentials for the AWS SDK for Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/ec2-iam-roles.html)).

#### Assume a role in a customer AWS account

1. Configure the API and transfer workers with a temporary AWS workload identity. If the SDK chain selects a long-lived environment or profile key, remove that key so it reaches the workload's credential source. In shared SaaS deployments set `KOONJ_AWS_ALLOW_DEFAULT_CHAIN=false` so customers cannot use the deployment's S3 permissions directly.
2. Generate a UUID with `uuidgen` for `KOONJ_AWS_EXTERNAL_ID_NAMESPACE`. Keep it identical across API replicas and workers and include it in configuration backups. Give the deployment identity `sts:AssumeRole` access to the approved customer role ARNs.
3. In Storage, select **AWS S3 → Assume AWS IAM role**, enter the ARN and choose **Get external ID**. Automation can use `GET /api/v1/connections/aws-external-id?roleArn=...` in the selected project. The external ID is stable for a project and AWS account within the deployment. Provide it to the customer administrator configuring the role.
4. In the customer IAM role trust policy, allow `sts:AssumeRole` for the exact Koonj workload role and require a `StringEquals` condition on `sts:ExternalId` with that value. Scope the role policy to the configured bucket and optional object prefix. Koonj also applies a session policy limited to that storage location and its supported S3 operations.
5. Grant `s3:ListBucket` on the bucket, with an `s3:prefix` condition matching `<prefix>/*` if configured. Grant `s3:GetObject` on the object path and add `s3:PutObject`, `s3:DeleteObject`, `s3:AbortMultipartUpload` and `s3:ListMultipartUploadParts` where workflows require them. Grant `s3:RestoreObject` for cold recall and the needed KMS key permissions for SSE-KMS. Write probes create, read and delete a temporary object under `.koonj-transfer/` within the configured prefix. Recall with deletion needs `s3:DeleteObject` on the archive source.
6. Enter the bucket's region and use the AWS endpoint. Test access. A configured prefix is the root of the connection. Existing catalog and transfer paths are relative to that root. Register a separate connection to change the root of an already cataloged location.

For AWS Secrets Manager set `KOONJ_AWS_SECRETS_REGION`. Grant the AWS workload identity read access through `secretsmanager:GetSecretValue` to the allowed project secret names, plus `kms:Decrypt` on the applicable customer-managed key if used. Use the reference `aws-sm:archive-storage` for a secret named `koonj/<project-uuid>/archive-storage` in that region. Koonj reads the current version. Store a JSON object containing `accessKeyId`, `secretAccessKey` and `bucket`. Include the exact `endpointUrl` for a custom S3 target and the configured `objectPrefix` when applicable. For temporary sessions, also include `sessionToken` and an ISO-8601 `expiresAt`.

### Encrypt credentials before storing them in PostgreSQL

Enable a wrapping key provider on the API and every transfer worker. Start with a separate wrapping key or keyring for each deployment. All replicas that share a database need access to that database's active and retained keys.

| Setting | Value |
|---|---|
| `KOONJ_STORAGE_KEY_PROVIDER` | `aws-kms` for AWS-backed or shared deployments, `local` for disconnected deployments, or `disabled` when only identity and AWS Secrets Manager are used. Defaults to `disabled`. |
| `KOONJ_STORAGE_KMS_KEY_ARN` | Full ARN of a symmetric customer-managed KMS key. Use a key ARN to identify the key independently of alias changes. |
| `KOONJ_STORAGE_KMS_PREVIOUS_KEY_ARNS` | Comma-separated full ARNs of previous KMS wrapping keys kept for decryption. |
| `KOONJ_STORAGE_LOCAL_ACTIVE_KEY` | ID of the local wrapping key used for new writes, for example `v1`. |
| `KOONJ_STORAGE_LOCAL_KEYS` | Comma-separated `key-id:base64-key` pairs. Each key is 32 securely generated bytes encoded as standard Base64. Include prior versions while their envelopes or backups are retained. |
| `KOONJ_STORAGE_SECRET_CACHE_TTL` | Maximum reuse time for resolved credentials. Defaults to `PT30S`, with a supported range of 1–300 seconds. |

**AWS KMS.** Grant the API workload `kms:GenerateDataKey` on the active key and grant the API and transfer workers `kms:Decrypt` on their approved keys. Use workload identity and restrict the key policy and IAM permissions to the deployment. The KMS encryption context includes `purpose=koonj-storage-credential-v1`, project, connection ID and revision. Those values are identifiers that can appear in CloudTrail ([AWS KMS data keys](https://docs.aws.amazon.com/kms/latest/developerguide/data-keys.html) · [AWS KMS encryption context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html)).

**Local or air-gapped.** Generate each wrapping key once using a CSPRNG, for example `openssl rand -base64 32`, and save it in the deployment's protected key escrow. Inject the keyring through the restricted service environment on the API and workers. Keep it outside the database, database backups, source control and container images. Restrict access to Docker and process inspection to trusted operators. Avoid printing the resolved Compose configuration or service environment. Keep a separately protected recovery copy of the active and prior keys.

**In Koonj.** Choose **Encrypted access keys** on an object storage connection and submit the access key ID and secret access key over HTTPS. Use dedicated credentials scoped to the target bucket and operations. Koonj saves an AES-256-GCM envelope containing a fresh per-write data key wrapped under KMS or a versioned local key. The payload is authenticated against the project, connection, provider and target settings. An administrator can replace credentials through Storage or use the write-only `PUT /api/v1/connections/{id}/credentials` endpoint. Project-scoped authorization applies to both operations. The endpoint probes the replacement before saving and can run while transfers are active because it only changes the credential. Connection responses show authentication metadata without returning keys.

For SaaS, use TLS at the proxy and keep request bodies, signing headers and runtime key settings out of proxy logs, application diagnostics and telemetry. Keep worker egress scoped to the object storage destinations approved for your deployment.

### Upgrade existing storage connections

1. Inventory the connection IDs, projects and providers. Prepare replacement keys, AWS roles or AWS Secrets Manager references. For connections previously using OpenBao, provision new credentials or securely recover the current ones before retiring that service. Take a protected backup and drain in-flight jobs before stopping the API and transfer workers.
2. Apply the [project workspace migration](../../deploy/migrations/project-workspaces.sql) if required. For schemas predating explicit storage authentication, apply [Phase 1](../../deploy/migrations/storage-auth-phase-1.sql). Then apply [the encryption migration](../../deploy/migrations/storage-auth-encryption.sql) using `psql -X -v ON_ERROR_STOP=1 -f deploy/migrations/storage-auth-encryption.sql`. In the release bundle the scripts are under `../../deploy/migrations`. Fresh installations using schema creation skip these upgrade scripts.
3. Configure the wrapping key provider on all replicas. Remove the retired OpenBao Compose overlay from the startup command and start the new release. The encryption migration retains AWS Secrets Manager references and existing connection IDs. Previous OpenBao references and quarantined plaintext connections show **Migration required**.
4. Edit each original connection, test the new method and save. Legacy AWS S3 connections with an explicit custom endpoint can be reclassified as **Custom S3** while keeping the bucket, endpoint and region. A successful update clears quarantined legacy values through the database guard. Check worker access and run a representative job before resuming traffic.
5. Rotate or revoke previously stored credentials at their storage provider. Database backups, snapshots and WAL can still hold historical plaintext. Retain and restrict those copies according to your retention policy. After every active connection has migrated and quarantined values are cleared, apply [Phase 3](../../deploy/migrations/storage-auth-phase-3.sql) to drop the old plaintext columns. For a retired connection whose catalog history must be retained, clear both legacy columns and set `active=false` in a maintenance transaction before applying Phase 3.

### Rotate and recover

- **Access key rotation.** Issue a replacement with overlapping validity. Enter both new keys while keeping the connection settings as they are and save. Koonj verifies the new keys and atomically replaces the encrypted envelope. Workers reload it after their credential-cache deadline, including during multipart transfers. Wait for that deadline and confirm representative jobs on each worker pool before revoking the previous key. For AWS Secrets Manager, publish a new current version of the same reference.
- **Wrapping key rotation.** Add the new key to every replica before switching the active writer. Keep the previous local key in the keyring or the previous KMS ARN in the decrypt allowlist. Re-enter or replace credentials on each connection to create envelopes under the new writer key. Keep old wrapping keys available for retained backups and validate a restore before retiring them.
- **Recovery.** Restore PostgreSQL into an isolated deployment with the original retained wrapping keys and matching key IDs. Restore KMS permissions or the escrowed local keyring separately. Test encrypted connection access and restore representative data before resuming jobs. A missing key or KMS outage causes affected connections to fail once cached credentials expire. Restore key access or enter replacement credentials under a new key. Catalog browsing and independent authentication modes remain available.

---

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

### Notifications and alerts

On an existing PostgreSQL installation using `KOONJ_DDL_AUTO=validate` or
`none`, apply the [notification migration](../../deploy/migrations/notifications.sql)
before starting the new server. Run
`psql -v ON_ERROR_STOP=1 -f deploy/migrations/notifications.sql` against the
Koonj database. Hibernate applies the schema changes with `KOONJ_DDL_AUTO=update`.

The API owns schema updates. Start the updated API or apply the SQL migration
before starting the worker with its default schema validation. Set the
following variables in the production Docker `.env` or the on-premise
`koonj.env`. SMTP and webhook defaults belong to the worker. Quota and
license monitoring remains in the API.

For an on-premise install, provision the configured RabbitMQ user and virtual
host with permission to declare, publish to, and consume the notification
queues. After the API and RabbitMQ are running, run
`sudo systemctl enable --now koonj-notification-worker` with the supplied
systemd unit. Without systemd, launch `java -jar lib/koonj-notification-worker.jar`
with the worker environment exported. For development, start the broker with
`docker compose -f deploy/docker-compose.yml --profile notifications up -d rabbitmq`
and use the broker settings in `../../deploy/.env.example` when launching the worker.
Point the worker at the same database as the API using `KOONJ_DB_URL`,
`KOONJ_DB_USERNAME`, and `KOONJ_DB_PASSWORD`.

| Variable | Default | Purpose |
|---|---|---|
| `KOONJ_RABBITMQ_HOST` | `rabbitmq` in Compose, `localhost` otherwise | Broker host |
| `KOONJ_RABBITMQ_PORT` | `5672` | AMQP port |
| `KOONJ_RABBITMQ_USERNAME` | `koonj` | Broker user |
| `KOONJ_RABBITMQ_PASSWORD` | required in production Compose | Broker password |
| `KOONJ_RABBITMQ_VHOST` | `koonj` | Broker virtual host |
| `KOONJ_RABBITMQ_SSL` | `false` | Enable AMQP over TLS |
| `KOONJ_NOTIFICATION_DB_POOL_SIZE` | `4` | Worker PostgreSQL connection pool limit |
| `KOONJ_NOTIFICATION_EMAIL_CONCURRENCY` | `2` | SMTP consumers per worker instance |
| `KOONJ_NOTIFICATION_WEBHOOK_CONCURRENCY` | `2` | Webhook consumers per worker instance |
| `KOONJ_NOTIFICATION_POLL_INTERVAL` | `1s` | Interval for scanning outbox events and deliveries due to publish |
| `KOONJ_MAIL_MAX_ATTEMPTS` | `3` | SMTP attempts for transient connection errors and SMTP 4xx replies (1–10) |
| `KOONJ_MAIL_RETRY_DELAY` | `30s` | Initial SMTP retry delay |
| `KOONJ_MAIL_HOST` | empty | SMTP relay hostname |
| `KOONJ_MAIL_PORT` | `587` | SMTP relay port |
| `KOONJ_MAIL_USERNAME` | empty | SMTP authentication username |
| `KOONJ_MAIL_PASSWORD` | empty | SMTP authentication password |
| `KOONJ_MAIL_FROM` | empty | Sender mailbox |
| `KOONJ_MAIL_TLS` | `STARTTLS` | Required STARTTLS, implicit TLS (`SSL`), or plaintext (`NONE`) |
| `KOONJ_MAIL_TIMEOUT` | `10s` | SMTP connect, read, and write timeout |
| `KOONJ_WEBHOOK_CONNECT_TIMEOUT` | `5s` | HTTP connection timeout |
| `KOONJ_WEBHOOK_REQUEST_TIMEOUT` | `10s` | HTTP timeout per attempt |
| `KOONJ_WEBHOOK_MAX_ATTEMPTS` | `3` | Total attempts, including the first one. Supported range is 1–10. |
| `KOONJ_WEBHOOK_RETRY_DELAY` | `1s` | Initial delay for network failures, timeouts, and HTTP 5xx responses. Retries use exponential backoff with jitter. |
| `KOONJ_QUOTA_CATALOGED_FILES` | `0` | Cataloged regular-file limit across projects |
| `KOONJ_QUOTA_STORAGE_CONNECTIONS` | `0` | Registered storage connection limit across projects |
| `KOONJ_QUOTA_TRANSFER_CONCURRENCY` | `0` | Peak simultaneous file transfers per server instance |
| `KOONJ_LICENSE_EXPIRES_AT` | empty | License expiry as a UTC timestamp such as `2027-06-30T23:59:59Z` |
| `KOONJ_LICENSE_GRACE_ENDS_AT` | empty | Optional UTC timestamp when the grace period ends |
| `KOONJ_NOTIFICATION_LIMIT_CHECK_INTERVAL` | `1m` | Quota and license sampling interval |

A quota value of zero disables that metric. The server reports usage and expiry
from the limits and dates configured for the deployment. Rules default to an
80% quota warning, a separate limit alert at 100%, and a 30-day license warning.
Grace and expired states have separate cooldowns.

In **Integration → Notifications & Alerts**, a `KOONJ_ADMIN` can add and
test channels, assign them to enabled rules, and inspect delivery history.
A batch rule defaults to 1,000 files or 10 GB (10,000,000,000 bytes). Enter one
threshold to use it on its own. Transfer failure rules match failed transfer
jobs and can also alert when file failures reach a configured count.
The default cooldown is 60 minutes per rule, channel, and event key.
Each batch-completion rule delivers at most once per channel for a job.
Settings and delivery history apply across the Koonj installation.

- Generic webhooks receive a JSON POST. Custom headers can supply authorization.
  To verify the optional `X-Koonj-Signature`, compute HMAC-SHA256 over the exact
  request body and compare it to the hex value after `sha256=`. The
  `deliveryId` is stable across retries.
- Slack uses an incoming webhook and Block Kit. Teams uses an incoming webhook
  or a Power Automate webhook that accepts an Adaptive Card message envelope
  ([Slack incoming webhooks](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/) ·
  [Teams Workflows webhooks](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook)).
- SMTP channels inherit worker settings and can override the relay, TLS mode,
  credentials, sender, and templates. A custom relay host uses the channel's
  credentials. Templates support `{title}`, `{eventType}`, and `{summary}`.
  Quota and license rules use the configured admin recipients. A test uses
  that list when present or the requesting admin's verified sign-in address.
- Activity email goes to the initiating user's verified OIDC address recorded
  when the job is created. It requires the `email` and `email_verified` token
  claims. Older jobs and jobs without a verified address have no email recipient.
  From the profile menu, select **Settings → Email notifications** to control global email
  delivery, category mutes, and snooze. Dispatch checks the latest preferences
  before sending and records `SUPPRESSED_OPT_OUT` when applicable.

The settings API accepts endpoint URLs, header values, signing secrets, and
SMTP passwords as write-only fields. These values are stored in the server
database. Delivery history records outcomes and retry counts. Pending outbox
events and delivery tasks survive process restarts. The worker retries broker
publishes when confirmation fails and acknowledges RabbitMQ messages after
recording a delivery outcome or scheduling a retry. It rechecks email
preferences before every SMTP attempt. A retry after an interrupted SMTP
exchange can produce a duplicate if the relay already accepted the email.

RabbitMQ uses two durable quorum queues, `koonj.notifications.email.v1` and
`koonj.notifications.webhook.v1`. Invalid queue messages are dead-lettered to
`koonj.notifications.invalid.v1`. Terminal transport failures appear in the
delivery history. The broker is shared infrastructure and future tasks can
declare their own exchanges and queues. For node-level broker redundancy,
connect the worker to a multi-node RabbitMQ cluster.

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

### Google and GitHub sign-in and registration

The Koonj login theme shipped alongside Keycloak 26.8.2 in the Docker deployment
shows enabled providers on the registration form. In the `koonj` realm, open
**Identity providers**. New realm imports include Google and GitHub entries that
stay disabled until you configure them. For an existing realm, use **Add provider**
to add the built-in **Google** and **GitHub** providers with the aliases `google`
and `github`, or open the entries if they already exist. You can enable either
provider independently. Rebuilding or recreating the Docker containers leaves the
realm settings in PostgreSQL, so configure the providers in that existing realm.

1. Use the **Redirect URI** displayed by Keycloak for each provider. It uses the
   public Keycloak URL and the provider alias, as in the examples below.

   | Provider | OAuth application | Example callback |
   | -------- | ----------------- | ---------------- |
   | Google | [Web application OAuth client](https://developers.google.com/identity/protocols/oauth2/web-server) | `https://auth.yourcompany.com/realms/koonj/broker/google/endpoint` |
   | GitHub | [GitHub OAuth App](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app) | `https://auth.yourcompany.com/realms/koonj/broker/github/endpoint` |

2. In each OAuth application, register that URL as the authorized redirect URI
   for Google or the authorization callback URL for GitHub. For local development,
   copy the full redirect URI shown by the local Keycloak instance.
3. Enter the provider's **Client ID** and **Client Secret** in Keycloak, then
   enable the provider and save. Use the default scopes for Google and
   `user:email` for GitHub so Keycloak can retrieve a private primary email
   address. Leave **Trust email** off and use the default **First Broker Login**
   flow. Keep **Hide on login page** off.

Open Koonj in a private browser window and use **Continue with Google** or
**Continue with GitHub** below **Sign In** and **Create Account**. The shortcuts
use Keycloak's **Identity Provider Redirector** in the browser flow. If a shortcut
opens the regular Keycloak sign-in form, check that the provider is enabled with
the matching alias and that the browser flow includes the redirector. Enabled
providers also appear on Keycloak's sign-in and registration pages. The first
successful social sign-in creates a Keycloak user. If the email already belongs
to a realm user, Keycloak asks the person to verify ownership before linking the
accounts.

### Default role for new accounts

New registrations inherit the `KOONJ_ADMIN` realm role through the **Koonj Administrators** default group. Project membership still controls which projects the account can open.

For an existing realm, add **Koonj Administrators** under **Realm settings → User registration → Default Groups** and confirm its role mapping includes `KOONJ_ADMIN`. Remove the viewer group from registration defaults if it was used for new accounts. Changing default groups affects subsequent registrations.

---

## MCP Server & Integration

Koonj exposes a project-scoped MCP server over HTTP with Server-Sent Events. The connection uses the MCP `2024-11-05` SSE transport. An SSE stream announces a session-specific POST endpoint. The client sends JSON-RPC requests there and reads replies from the original stream ([MCP transport specification](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)).

### Server & Proxy Deployment

For a PostgreSQL deployment with `KOONJ_DDL_AUTO=validate` or `none`, apply the
[PAT migration](../../deploy/migrations/user-api-keys.sql) after the project workspace
migration and before starting the updated server.

```bash
psql -v ON_ERROR_STOP=1 -f deploy/migrations/user-api-keys.sql
```

The shipped Docker and on-premise Nginx configurations include dedicated SSE and
message locations. When using another reverse proxy, apply the
[MCP Nginx locations](../../deploy/nginx.conf) or equivalent rules. Serve both routes
over HTTPS. The SSE location disables buffering, caching, compression, and access
logging and has a 24-hour read timeout. If another gateway logs URLs, redact the
`token` query parameter on the SSE route. Set `KOONJ_CORS_ALLOWED_ORIGINS` to the
browser workspace origin if the API is hosted on a separate origin.

For browser SSE clients that cannot set headers on the GET, `/api/v1/mcp/sse?token=<PAT>` accepts a query token. Send the PAT in the `Authorization` header on POST. Keep query strings out of proxy and application logs and prefer headers wherever possible.

SSE sessions are held in memory. Route `/api/v1/mcp/sse` and
`/api/v1/mcp/message` to the same Koonj instance, including when the rest of the API
uses multiple replicas. A single upstream for these paths works with all supported
clients. The server sends a heartbeat every 20 seconds and renews the session through
client reconnection after a restart or the 24-hour session timeout. Each instance
defaults to 512 open SSE sessions and 64 simultaneous MCP requests, configurable
with `KOONJ_MCP_MAX_SESSIONS` and `KOONJ_MCP_MAX_CALLS`. Each PAT can open up to
16 streams and each stream can run up to eight requests concurrently.

### Generate a Personal Access Token

1. Select your project in Koonj.
2. Open **Integration → MCP & AI Assistant → Generate New Token**.
3. Name the token and optionally choose an expiration. Copy the secret or use the populated client configuration. The API reveals the secret once at creation and stores a SHA-256 digest.
4. Select **Test connection** to initialize a session and list the tools available to the new token.

Tokens use your Koonj roles at issuance. Changes to Koonj’s role permissions and project membership apply to existing tokens. Revoke existing tokens when changing a user’s identity-provider role assignment, then issue replacements with the new roles. Revocation stops new requests immediately and the heartbeat closes existing streams within 20 seconds under normal operation. Already queued jobs continue under Koonj’s job controls.

Use a private client configuration or secret store and keep PATs out of version control. Set `Authorization: Bearer <PAT>` on both HTTP endpoints. The `X-Koonj-Project` header is optional because the project is part of the token. If you send it, use the token’s project ID.

### Claude Desktop

Install Node.js 20 or later and use `mcp/mcp-bridge.mjs` from the extracted deployment bundle or [mcp-bridge.mjs](../../deploy/scripts/mcp/mcp-bridge.mjs) from the repository. Put this entry under `mcpServers` in `claude_desktop_config.json`. Use absolute paths to your Node executable and the script. On Windows, double backslashes in JSON paths, or use forward slashes.

```json
{
  "mcpServers": {
    "koonj": {
      "command": "/absolute/path/to/node",
      "args": [
        "/absolute/path/to/mcp/mcp-bridge.mjs",
        "--url",
        "https://koonj.example.com/api/v1/mcp/sse",
        "--project",
        "00000000-0000-0000-0000-000000000001"
      ],
      "env": {
        "KOONJ_TOKEN": "YOUR_KOONJ_TOKEN"
      }
    }
  }
}
```

Find the file at `~/Library/Application Support/Claude/claude_desktop_config.json` on macOS or `%APPDATA%\Claude\claude_desktop_config.json` on Windows. Fully quit and reopen Claude Desktop after editing it. The bridge also accepts `--token` for scripted launches. Use `KOONJ_TOKEN` to keep the secret out of process arguments ([MCP guide for Claude Desktop](https://py.sdk.modelcontextprotocol.io/get-started/real-host/)).

### Cursor

Save the following in your private `~/.cursor/mcp.json` or a private project-level `.cursor/mcp.json`. If your Cursor build requires a stdio server, use the Claude Desktop entry in the same file ([Cursor MCP configuration](https://cursor.com/docs/mcp)).

```json
{
  "mcpServers": {
    "koonj": {
      "url": "https://koonj.example.com/api/v1/mcp/sse",
      "headers": {
        "Authorization": "Bearer ${env:KOONJ_TOKEN}",
        "X-Koonj-Project": "00000000-0000-0000-0000-000000000001"
      }
    }
  }
}
```

### Windsurf

Add the following to `~/.codeium/windsurf/mcp_config.json` and refresh the MCP servers in Windsurf. The Claude Desktop entry also works as a stdio configuration.

```json
{
  "mcpServers": {
    "koonj": {
      "serverUrl": "https://koonj.example.com/api/v1/mcp/sse",
      "headers": {
        "Authorization": "Bearer YOUR_KOONJ_TOKEN",
        "X-Koonj-Project": "00000000-0000-0000-0000-000000000001"
      }
    }
  }
}
```

### Custom Python agents and LangChain

Install the MCP Python SDK with `python -m pip install mcp`. Set `KOONJ_MCP_URL` to the SSE URL, `KOONJ_PROJECT` to your project UUID, and `KOONJ_TOKEN` to your PAT ([MCP Python SSE client](https://py.sdk.modelcontextprotocol.io/client/transports/)).

```python
import asyncio
import os

from mcp import ClientSession
from mcp.client.sse import sse_client

async def main():
    headers = {
        "Authorization": f"Bearer {os.environ['KOONJ_TOKEN']}",
        "X-Koonj-Project": os.environ["KOONJ_PROJECT"],
    }
    async with sse_client(os.environ["KOONJ_MCP_URL"], headers=headers) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            print([tool.name for tool in (await session.list_tools()).tools])
            result = await session.call_tool("search_catalog_files", {"limit": 10})
            print(result.content[0].text)
            if job_id := os.environ.get("KOONJ_JOB_ID"):
                status = await session.call_tool("get_job_status", {"jobId": job_id})
                print(status.content[0].text)

asyncio.run(main())
```

For an agent using `langchain-mcp-adapters`, configure `MultiServerMCPClient` with an explicit SSE transport ([LangChain MCP adapters](https://github.com/langchain-ai/langchain-mcp-adapters)).

```python
import asyncio
import os

from langchain_mcp_adapters.client import MultiServerMCPClient

async def load_tools():
    client = MultiServerMCPClient({
        "koonj": {
            "transport": "sse",
            "url": os.environ["KOONJ_MCP_URL"],
            "headers": {
                "Authorization": f"Bearer {os.environ['KOONJ_TOKEN']}",
                "X-Koonj-Project": os.environ["KOONJ_PROJECT"],
            },
        }
    })
    return await client.get_tools()

async def main():
    print([tool.name for tool in await load_tools()])

asyncio.run(main())
```

### Check the connection with curl

Set `KOONJ_BASE_URL` to the Koonj origin and set `KOONJ_TOKEN` and `KOONJ_PROJECT` as above. Leave the first terminal running.

```sh
curl -sS -N "$KOONJ_BASE_URL/api/v1/mcp/sse" \
  -H 'Accept: text/event-stream' \
  -H "Authorization: Bearer $KOONJ_TOKEN" \
  -H "X-Koonj-Project: $KOONJ_PROJECT"
```

Copy the relative URL from the stream’s `endpoint` event into `KOONJ_MESSAGE_PATH` in a second terminal. Wait for the initialization response before sending the initialized notification. POSTs return HTTP 202 and the first terminal displays JSON-RPC replies.

```sh
mcp_post() {
  curl -fsS "$KOONJ_BASE_URL$KOONJ_MESSAGE_PATH" \
    -H "Authorization: Bearer $KOONJ_TOKEN" \
    -H "X-Koonj-Project: $KOONJ_PROJECT" \
    -H 'Content-Type: application/json' -d "$1"
}

mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
mcp_post '{"jsonrpc":"2.0","method":"notifications/initialized"}'
mcp_post '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
mcp_post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_catalog_files","arguments":{"limit":10}}}'
```

To inspect a job, set `KOONJ_JOB_ID` to a job ID you own in this project:

```sh
mcp_post "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_job_status\",\"arguments\":{\"jobId\":\"$KOONJ_JOB_ID\"}}}"
```

### Tool permissions

| Tool | Required permission |
| --- | --- |
| `list_storage_locations`, `search_catalog_files`, `get_file_details`, `get_file_lineage` | `CATALOG_VIEW` |
| `get_storage_status` | `STORAGE_VIEW` |
| `queue_archive` | `TRANSFER_RUN`, plus `TRANSFER_PURGE` for `PURGE` |
| `queue_recall`, `sync_recall_file` | `TRANSFER_RUN` |
| `get_job_status` | `ACTIVITY_VIEW` and job ownership |
| `control_job` | `ACTIVITY_CONTROL` and job ownership. Resume and retry also need permissions to run the original operation. |
| `trigger_catalog_scan` | `CATALOG_OPERATE` |
| `get_system_health` | `OBSERVABILITY_VIEW` |

Search uses the project’s last-modified thresholds for `HOT`, `WARM`, and `COLD`. The response includes `archiveState` separately. Pages start at zero and contain up to 50 entries. Pass the file ID to `get_file_details` for the full path and metadata. Monitor queued jobs using the returned `jobId`. Permission denials appear as MCP tool results with `isError: true` and leave the session open.

---

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
2. Check `journalctl -u koonj-server` or the manual launcher's `logs/koonj-server-console.log`
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
