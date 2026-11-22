# Transparent recall through the Linux gateway

Run one Koonj FUSE gateway on a Linux Docker Engine host. Compose manages the
gateway and can run an optional Samba sidecar. Windows Explorer, macOS Finder,
and Linux file managers access archived files through their built-in SMB client.
An SMB connection may ask for share credentials. The gateway authenticates to
Keycloak as `koonj-agent` and handles each recall without an interactive login.

## 1. Prepare the Linux host

Use a Linux Docker Engine host with `/dev/fuse` and a filesystem that can store
short symbolic links inline, such as ext4. Mount propagation requires Linux
bind mounts on the Docker daemon host. [Docker bind propagation](https://docs.docker.com/engine/storage/bind-mounts/#configure-bind-propagation)

From an extracted deployment ZIP, enter its `docker/` directory and run the
bundled setup script. From a source checkout, run `sudo ./scripts/setup-host-mount.sh`
from the repository root.

```sh
sudo ./setup-host-mount.sh
```

The script ensures `fuse3` and the FUSE 2 compatibility library are installed
on Debian or Ubuntu, creates `/srv/koonj/backing` and `/srv/koonj/share`, and
bind-mounts the share directory with shared propagation. On other Linux
distributions install the equivalent packages before running the script.
Add the `/etc/fstab` entry printed by the script if the mount should persist
across reboots. Supply `KOONJ_FUSE_BACKING_DIR` and `KOONJ_FUSE_MOUNT_POINT`
to the script's environment when using custom paths. Give the Koonj Server
process write access to the backing directory so it can replace links and
restore files.

## 2. Configure the gateway

In the ZIP's `docker/` directory, copy `.env.example` to `.env` and configure
the [production stack](DEPLOYMENT-GUIDE.md#option-a-docker-deployment). Set
`KOONJ_DATA_MOUNT` and `KOONJ_FUSE_BACKING_DIR` to the same host backing path.
The production server sees it at `/mnt/koonj` and the gateway sees it at
`/mnt/koonj-backing`. Set `KOONJ_FUSE_MOUNT_POINT` to the prepared host share
directory and keep `KOONJ_FUSE_ALLOW_OTHER=true` for access by Samba and local
users. Register `/mnt/koonj` as the source storage root in the Koonj catalog.

For a source checkout, copy `deploy/.env.example` to `deploy/.env` and run
Compose from `deploy/`. Run the Koonj API separately and set
`KOONJ_SERVER_URL` to an endpoint the gateway container can reach. Register
the same backing directory as seen by that API. The development stack also
provides the `koonj-local-archive` LocalStack bucket in `eu-west-2`.

Start the stack, then copy the generated secret from **Clients → koonj-agent
→ Credentials** in the Keycloak `koonj` realm. Set `KOONJ_AGENT_CLIENT_SECRET`
in `.env`. A realm that was imported before the agent was added needs the
confidential OIDC client configured in place. Enable client authentication
and service accounts, assign the realm role `KOONJ_OPERATOR` to the service
account, and include it in the client's role scope. Keep the `roles` client
scope and add `koonj-server` as an audience in its access token. The default
`KOONJ_AGENT_TOKEN_URI` addresses Keycloak over the Compose network.

Start the gateway from the same Compose directory.

```sh
docker compose --profile fuse up -d --build koonj-fuse
docker compose --profile fuse ps
findmnt --mountpoint /srv/koonj/share
```

With just this profile, clients on the Linux gateway can use `/srv/koonj/share`
directly. The gateway and Samba use Docker's `unless-stopped` restart policy.
If an existing deployment manages its schema without Hibernate, add
`transfer_jobs.priority integer NOT NULL DEFAULT 5` before upgrading the server.

## 3. Optionally export the share over SMB

Set `KOONJ_SMB_PASSWORD` in `.env` for the Samba account `koonj`. Set
`KOONJ_SMB_BIND` to the gateway's LAN address for remote access. Port 445 is the
default and is used by native clients. Limit access to the intended network.
Start the sidecar with:

```sh
docker compose --profile smb up -d --build koonj-smb
docker compose --profile smb ps
```

The `smb` profile also enables the FUSE gateway. The sidecar waits for the
live mount, then exports a read-only share named `Koonj` with extended
attribute support. Its `vfs_offline` module advertises all entries as
offline, including files already restored to disk. The gateway waits for
the first content read, so opening a handle to inspect file properties
leaves the backing link in place. Windows Explorer uses the flag to avoid thumbnail reads. Disable icon previews or indexing in
clients that still inspect file contents, including Finder configurations
that generate previews. [Samba Offline VFS](https://www.samba.org/samba/docs/current/man-html/vfs_offline.8.html)

| Client | Open the share |
| --- | --- |
| [Windows Explorer](FUSE-RECALL-WINDOWS.md) | Enter `\\gateway-host\Koonj` in the address bar or map it to a network drive. |
| [macOS Finder](FUSE-RECALL-MACOS.md) | Press Command-K and enter `smb://gateway-host/Koonj`. |
| Linux Files | Enter `smb://gateway-host/Koonj` in the file manager. On the gateway, open `/srv/koonj/share`. |

Use the SMB credentials when connecting to the share. Each client can then
double-click files using its default applications.

## 4. Verify an archive and recall

1. Create `probe.txt` in the backing directory and record its checksum.

   ```sh
   sha256sum /srv/koonj/backing/probe.txt
   ```

2. Register a writable S3 target in the catalog and archive the file in
   **PURGE** mode as an administrator. Choose S3 Standard for the
   immediate-recall test. Wait for the job to complete.
3. Compare the backing link and the virtual file before reading the file.

   ```sh
   readlink /srv/koonj/backing/probe.txt
   stat -c 'type=%F allocated_blocks=%b' /srv/koonj/backing/probe.txt
   stat -c 'type=%F logical_bytes=%s allocated_blocks=%b' /srv/koonj/share/probe.txt
   ```

   The backing link should hold `koonj-ref:/<uuid>` and show
   `allocated_blocks=0` on ext4. The virtual file should show the original
   length. In the catalog, the UUID appears as `koonj-ref://<uuid>` beside
   the local footprint of `0 B` and the original size in S3. The symlink
   still consumes inode metadata. Snapshots or other hard links may retain
   the source blocks.
4. Browse the SMB share and confirm the backing entry is still a link.
   Double-click `probe.txt` from the mounted share or SMB drive. The file
   should open using the original contents without a Keycloak prompt.
   On the gateway, `cat /srv/koonj/share/probe.txt` exercises the same
   content-open recall path.
5. Confirm the backing path is now a regular file, the checksum matches,
   and the catalog shows `LOCAL_ONLY`. The interactive recall job uses
   priority 100.

   ```sh
   test -f /srv/koonj/backing/probe.txt && test ! -L /srv/koonj/backing/probe.txt
   sha256sum /srv/koonj/backing/probe.txt
   ```

If an object has transitioned to Glacier Flexible Retrieval or Deep Archive,
the server starts an S3 restore and returns HTTP 202 with an estimate.
The client sees a retryable open error. Use `docker compose logs koonj-fuse`
to check the estimate and retry when the object is available. The archive
credentials require `s3:RestoreObject` in addition to ordinary read
permissions. For an immediate recall that exceeds the synchronous wait,
the job continues and a later open can use the restored file.

For older deployments, recall legacy archive links through the catalog and
purge them again to produce UUID links.
