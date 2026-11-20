# Koonj Server

## Start the development stack

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

cd koonj-server
mvn spring-boot:run
```

Hibernate creates and updates the PostgreSQL tables on startup. Set the same `KOONJ_DB_*` variables in the run configuration when launching `KoonjServerApplication` from IntelliJ. Open [Swagger UI](http://localhost:8080/swagger-ui/start) or [OpenAPI JSON](http://localhost:8080/v3/api-docs).

The current `deploy/Dockerfile.server` expects a nested Maven module selected by `MODULE`. The standalone `koonj-server` is launched with Maven in this setup.

## Keycloak and role verification

1. Open the [Keycloak Admin Console](http://localhost:8180/admin) and sign in to the **master** realm with the bootstrap credentials. The development defaults are `admin` / `admin`. Set `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, and `KEYCLOAK_DB_PASSWORD` before the first Compose startup to customize them.
2. Select the **koonj** realm. Under **Clients**, confirm `koonj-ui` is public, uses Standard Flow and S256 PKCE, and sends the `koonj-server` audience. Under **Identity providers**, choose **OpenID Connect v1.0** or **SAML v2.0** to add an external broker. Configure the callback URL shown by Keycloak with the external provider. Configure LDAP or AD under **User federation**, and configure SMTP under **Realm settings → Email** to deliver password reset links.
3. Start the [Koonj UI](../koonj-ui/README.md) and visit [http://localhost:5173](http://localhost:5173). Use **Sign In** with one of the development accounts below, or use **Create Account** and assign the new user a Koonj realm role in **Users → Role mapping**. A self-registered account can sign in and sees **Workspace access pending** until an administrator assigns a role. Sign out and back in to get a token with the new role.
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

## Register storage and archive a file

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

Register the bucket created by LocalStack. Run this in the shell where you set the Compose variables, or export the same values again.

```sh
curl -fsS http://localhost:8080/api/v1/connections \
  -H "Authorization: Bearer $KOONJ_ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"LocalStack Archive\",\"providerType\":\"CUSTOM_S3\",\"role\":\"BOTH\",
       \"basePathOrBucket\":\"${KOONJ_LOCAL_BUCKET}\",\"endpointUrl\":\"http://127.0.0.1:${KOONJ_S3_PORT}\",
       \"region\":\"${KOONJ_LOCAL_REGION}\",\"accessKeyOrUsername\":\"test\",\"secretKeyOrPassword\":\"test\",
       \"pathStyleAccessEnabled\":true}"
```

For your own MinIO instance, use `MINIO` and supply its endpoint, bucket, region and credentials with path-style access enabled. For AWS use `AWS_S3`, the bucket's region, and `pathStyleAccessEnabled:false`. Omit `endpointUrl` for AWS. Omitting both AWS credential fields uses the SDK's default credentials chain.

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

## Stop the development stack

From the repository root:

```sh
docker compose -f deploy/docker-compose.yml down
```
