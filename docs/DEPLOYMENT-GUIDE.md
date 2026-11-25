# Koonj — Deployment Guide

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Build the Deployment ZIP](#build-the-deployment-zip)
- [Prerequisites](#prerequisites)
- [Option A: Docker Deployment](#option-a-docker-deployment)
- [Server logging](#server-logging)
- [Optional Docker Observability with OpenObserve](#optional-docker-observability-with-openobserve)
- [Option B: On-Premise Deployment](#option-b-on-premise-deployment)
- [Scaling](#scaling)
- [Configuration Reference](#configuration-reference)
- [Keycloak Setup](#keycloak-setup)
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

Scale API replicas, transfer workers, notification workers, and Nginx independently. See [transfer workers](TRANSFER-WORKERS.md) for reservation rules and upgrade steps. The deployment ZIP includes the guide at `docs/TRANSFER-WORKERS.md`.

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

## Server logging

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

## Scaling

### Horizontal scaling — API Server

Enable [RabbitMQ transfer dispatch](TRANSFER-WORKERS.md) and run the API replicas on different ports or hosts. Run separate transfer workers for storage access and job execution.

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

## Storage authentication and secret stores

Koonj defaults to host or workload identity where available. Object storage can also use an AWS Secrets Manager reference or credentials encrypted by Koonj before PostgreSQL receives them.

| Storage | Authentication method |
|---|---|
| LOCAL, SMB/CIFS, NAS | `HOST_IDENTITY`. Register the path the API and worker service accounts can access. Mount SMB shares on Linux using the host's Kerberos identity or protected mount credentials. For Windows UNC paths, grant the Windows service account or gMSA access to the share and its filesystem ACLs. |
| AWS S3 in a dedicated deployment | `AWS_DEFAULT_CHAIN`. The AWS SDK uses the deployment's temporary session credentials, for example an ECS task role, EC2 instance role, Kubernetes web identity, or IAM Roles Anywhere. |
| AWS S3 in a customer account | `AWS_ASSUME_ROLE`. Configure the customer IAM role and use Koonj's external ID in its trust policy. |
| MinIO, RustFS, Scality and Custom S3 | `ENCRYPTED_CREDENTIALS` for access keys encrypted by Koonj, or `SECRET_REF` for AWS Secrets Manager. Prefer temporary sessions supplied through Secrets Manager where the provider supports them. |
| AWS S3 using a legacy static key | `ENCRYPTED_CREDENTIALS` or `SECRET_REF`. Omit `endpointUrl` for AWS S3. |

Custom S3 endpoints use HTTPS. The server setting `KOONJ_STORAGE_ALLOW_INSECURE_ENDPOINTS=true` is available for isolated development environments such as LocalStack. Deploy shared SaaS workers with egress restricted to approved object storage destinations, a trusted DNS resolver and separate worker roles from other services. Allow administrators to provision only the targets their projects need. Match worker mount permissions to Koonj project boundaries.

### AWS workload identity and customer roles

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

**AWS KMS.** Grant the API workload `kms:GenerateDataKey` on the active key and grant the API and transfer workers `kms:Decrypt` on their approved keys. Use workload identity and restrict the key policy and IAM permissions to the deployment. The KMS encryption context includes `purpose=koonj-storage-credential-v1`, project, connection ID and revision. Those values are identifiers that can appear in CloudTrail. [AWS KMS data keys](https://docs.aws.amazon.com/kms/latest/developerguide/data-keys.html) · [AWS KMS encryption context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html)

**Local or air-gapped.** Generate each wrapping key once using a CSPRNG, for example `openssl rand -base64 32`, and save it in the deployment's protected key escrow. Inject the keyring through the restricted service environment on the API and workers. Keep it outside the database, database backups, source control and container images. Restrict access to Docker and process inspection to trusted operators. Avoid printing the resolved Compose configuration or service environment. Keep a separately protected recovery copy of the active and prior keys.

**In Koonj.** Choose **Encrypted access keys** on an object storage connection and submit the access key ID and secret access key over HTTPS. Use dedicated credentials scoped to the target bucket and operations. Koonj saves an AES-256-GCM envelope containing a fresh per-write data key wrapped under KMS or a versioned local key. The payload is authenticated against the project, connection, provider and target settings. An administrator can replace credentials through Storage or use the write-only `PUT /api/v1/connections/{id}/credentials` endpoint. Project-scoped authorization applies to both operations. The endpoint probes the replacement before saving and can run while transfers are active because it only changes the credential. Connection responses show authentication metadata without returning keys.

For SaaS, use TLS at the proxy and keep request bodies, signing headers and runtime key settings out of proxy logs, application diagnostics and telemetry. Keep worker egress scoped to the object storage destinations approved for your deployment.

### Upgrade existing storage connections

1. Inventory the connection IDs, projects and providers. Prepare replacement keys, AWS roles or AWS Secrets Manager references. For connections previously using OpenBao, provision new credentials or securely recover the current ones before retiring that service. Take a protected backup and drain in-flight jobs before stopping the API and transfer workers.
2. Apply the [project workspace migration](../deploy/migrations/project-workspaces.sql) if required. For schemas predating explicit storage authentication, apply [Phase 1](../deploy/migrations/storage-auth-phase-1.sql). Then apply [the encryption migration](../deploy/migrations/storage-auth-encryption.sql) using `psql -X -v ON_ERROR_STOP=1 -f deploy/migrations/storage-auth-encryption.sql`. In the release bundle the scripts are under `deploy/migrations`. Fresh installations using schema creation skip these upgrade scripts.
3. Configure the wrapping key provider on all replicas. Remove the retired OpenBao Compose overlay from the startup command and start the new release. The encryption migration retains AWS Secrets Manager references and existing connection IDs. Previous OpenBao references and quarantined plaintext connections show **Migration required**.
4. Edit each original connection, test the new method and save. Legacy AWS S3 connections with an explicit custom endpoint can be reclassified as **Custom S3** while keeping the bucket, endpoint and region. A successful update clears quarantined legacy values through the database guard. Check worker access and run a representative job before resuming traffic.
5. Rotate or revoke previously stored credentials at their storage provider. Database backups, snapshots and WAL can still hold historical plaintext. Retain and restrict those copies according to your retention policy. After every active connection has migrated and quarantined values are cleared, apply [Phase 3](../deploy/migrations/storage-auth-phase-3.sql) to drop the old plaintext columns. For a retired connection whose catalog history must be retained, clear both legacy columns and set `active=false` in a maintenance transaction before applying Phase 3.

### Rotate and recover

- **Access key rotation.** Issue a replacement with overlapping validity. Enter both new keys while keeping the connection settings as they are and save. Koonj verifies the new keys and atomically replaces the encrypted envelope. Workers reload it after their credential-cache deadline, including during multipart transfers. Wait for that deadline and confirm representative jobs on each worker pool before revoking the previous key. For AWS Secrets Manager, publish a new current version of the same reference.
- **Wrapping key rotation.** Add the new key to every replica before switching the active writer. Keep the previous local key in the keyring or the previous KMS ARN in the decrypt allowlist. Re-enter or replace credentials on each connection to create envelopes under the new writer key. Keep old wrapping keys available for retained backups and validate a restore before retiring them.
- **Recovery.** Restore PostgreSQL into an isolated deployment with the original retained wrapping keys and matching key IDs. Restore KMS permissions or the escrowed local keyring separately. Test encrypted connection access and restore representative data before resuming jobs. A missing key or KMS outage causes affected connections to fail once cached credentials expire. Restore key access or enter replacement credentials under a new key. Catalog browsing and independent authentication modes remain available.

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
`none`, apply the [notification migration](../deploy/migrations/notifications.sql)
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
and use the broker settings in `deploy/.env.example` when launching the worker.
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

In **Administration → Notifications & alerts**, a `KOONJ_ADMIN` can add and
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
  or a Power Automate webhook that accepts an Adaptive Card message envelope.
  [Slack incoming webhooks](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/) ·
  [Teams Workflows webhooks](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook)
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

New realm imports assign the **Koonj Administrators** default group to new
accounts. The group grants `KOONJ_ADMIN` on the first sign-in after registration
by email, Google, or GitHub. The default group applies realm-wide to newly
created accounts. Existing accounts keep their current role assignments,
including when linking a social provider.

For an existing realm, open **Groups** in the Keycloak Admin Console. Create
**Koonj Administrators** and assign the `KOONJ_ADMIN` realm role under the group's
**Role mapping** tab. Then open **Realm settings → User registration → Default
Groups** and add **Koonj Administrators**. This gives anyone who can complete
registration full access to Koonj, including access to the Default project and
administrative operations.

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

## MCP server

For a PostgreSQL deployment with `KOONJ_DDL_AUTO=validate` or `none`, apply the
[PAT migration](../deploy/migrations/user-api-keys.sql) after the project workspace
migration and before starting the updated server.

```bash
psql -v ON_ERROR_STOP=1 -f deploy/migrations/user-api-keys.sql
```

The shipped Docker and on-premise Nginx configurations include dedicated SSE and
message locations. When using another reverse proxy, apply the
[MCP Nginx locations](../deploy/nginx.conf) or equivalent rules. Serve both routes
over HTTPS. The SSE location disables buffering, caching, compression, and access
logging and has a 24-hour read timeout. If another gateway logs URLs, redact the
`token` query parameter on the SSE route. Set `KOONJ_CORS_ALLOWED_ORIGINS` to the
browser workspace origin if the API is hosted on a separate origin.

SSE sessions are held in memory. Route `/api/v1/mcp/sse` and
`/api/v1/mcp/message` to the same Koonj instance, including when the rest of the API
uses multiple replicas. A single upstream for these paths works with all supported
clients. The server sends a heartbeat every 20 seconds and renews the session through
client reconnection after a restart or the 24-hour session timeout. Each instance
defaults to 512 open SSE sessions and 64 simultaneous MCP requests, configurable
with `KOONJ_MCP_MAX_SESSIONS` and `KOONJ_MCP_MAX_CALLS`. Each PAT can open up to
16 streams and each stream can run up to eight requests concurrently.

See [MCP integration](MCP-INTEGRATION.md) for client setup and smoke tests.
