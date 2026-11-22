# koonj
Faithful messenger carrying things across vast distances and reliably returning home

## Foundation metadata API

Submit a scan with `POST /api/scans` and a JSON body such as `{"rootId":1}`. The API queues work for a registered root and returns a scan ID with HTTP 202. Read progress at `GET /api/scans/{id}`. A second active scan for the same root returns HTTP 409.

Browse files with `GET /api/inventory?rootId=1&limit=100`. The page size ranges from 1 to 200. Follow the opaque `nextCursor` in the next request's `cursor` parameter with the same root. A null cursor marks the end. Inventory uses ascending file IDs and reflects committed changes between requests.

Queue a generation-specific operation with `POST /api/operations` and a JSON body such as `{"generationId":1,"operation":"RECALL"}`. Supported operations are `ARCHIVE`, `TIER`, and `RECALL`. Read the returned operation ID at `GET /api/operations/{id}`. Both command endpoints accept JSON metadata and reject unknown fields.

The API and console are accessible without signing in. Use `GET /api/roots` to list registered storage roots.

Subscribe to `GET /api/events/scans/{id}` or `GET /api/events/operations/{id}`. Each stream sends the current persisted status as a `progress` event and heartbeats between changes. On reconnect, refresh the corresponding REST status and resubscribe. The API returns HTTP 429 at the subscriber cap.

Register existing S3 buckets and their AWS regions in the console. Registration returns a location UUID. Archive commands require `targetLocationId` with the registered bucket UUID.

During an upgrade, stop old workers before applying the migrations. Use **Confirm legacy archive location** to bind key-only archive records and previously queued archives to one registered bucket and region for the database. Confirm the original bucket explicitly. The API records the confirmation time and rejects attempts to change the mapping.

Start the five local services with `docker compose -f deploy/docker-compose.yml up --build -d --wait`. The console listens on `http://localhost:8080`. Set `KOONJ_ROOT_PATH` to the source directory and register its container path under `/mnt/koonj`. The worker image includes OpenSSL for native file digests. LocalStack provisions the bucket specified by `KOONJ_LOCAL_BUCKET` in `KOONJ_LOCAL_REGION`. Register that bucket from the console to use it.

Enable tiering only for dedicated roots whose writers are Koonj workers. Before marking a shared mount managed, run the worker JAR with `--hold-lock <probe-path>` on one worker host. While it holds the lock, run the same JAR with `--probe-lock <probe-path>` on every other worker host using the same shared file. Require exit code zero from each probe. During managed operations the worker also checks lock exclusion with a second process on its own host. The mount must support atomic same-filesystem rename for quarantine and hard-link creation for no-clobber recall. Reserve the root's `.koonj` directory for worker coordination.

## Verify storage connections

In **Administration → Storage configuration**, select **Add connection**, fill in the storage details, and select **Test connection**. The test runs from the Koonj API. It checks read access to a local directory or a host-mounted NAS/SMB share under the server's `koonj.mount` directory. Mount network shares on the server before testing them.

Amazon S3 is checked with a read-only `HeadBucket` request. For MinIO, Scality, and other S3-compatible services, supply access keys and add the endpoint URL to the server's comma-separated `KOONJ_CONNECTION_ALLOWED_S3_ENDPOINTS` setting. Docker Compose includes the local LocalStack endpoint by default. Custom endpoint checks use path-style addressing.

The console saves connection settings and the last successful verification in the browser. Entered credentials stay in memory and are sent to the API for a test. A credential reference is saved as a name and requires a backend secret resolver before it can be used for verification.

## Open archived files from a native file manager

See the [Linux gateway and SMB setup](docs/FUSE-RECALL.md) for transparent recall from Windows Explorer, macOS Finder, and Linux file managers.


## Code Quality

https://sonarcloud.io/project/overview?id=atmenterprise_koonj

