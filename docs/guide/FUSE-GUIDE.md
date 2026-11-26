# Koonj — FUSE & Samba Integration Guide

Run one Koonj FUSE gateway on a Linux Docker Engine host. Compose manages the gateway and can run an optional Samba sidecar. Windows Explorer, macOS Finder, and Linux file managers access archived files through their built-in SMB client (or directly via the Linux FUSE mount). An SMB connection may ask for share credentials. The gateway authenticates to Keycloak as `koonj-agent` and handles each recall without an interactive login.

## Table of Contents

- [1. Prepare the Linux Host](#1-prepare-the-linux-host)
- [2. Configure and Start the FUSE Gateway](#2-configure-and-start-the-fuse-gateway)
- [3. Export the Share Over SMB](#3-export-the-share-over-smb)
- [4. Client Setup by Operating System](#4-client-setup-by-operating-system)
  - [Linux](#linux)
  - [macOS (Finder)](#macos-finder)
  - [Windows (File Explorer)](#windows-file-explorer)
- [5. Verify an Archive and Recall](#5-verify-an-archive-and-recall)
- [6. Troubleshooting](#6-troubleshooting)

---

## 1. Prepare the Linux Host

Use a Linux Docker Engine host with `/dev/fuse` and a filesystem that can store short symbolic links inline, such as ext4. Mount propagation requires Linux bind mounts on the Docker daemon host ([Docker bind propagation](https://docs.docker.com/engine/storage/bind-mounts/#configure-bind-propagation)).

From an extracted deployment ZIP, enter its `docker/` directory and run the bundled setup script. From a source checkout, run `sudo ./scripts/setup-host-mount.sh` from the repository root.

```sh
sudo ./setup-host-mount.sh
```

The script ensures `fuse3` and the FUSE 2 compatibility library are installed on Debian or Ubuntu, creates `/srv/koonj/backing` and `/srv/koonj/share`, and bind-mounts the share directory with shared propagation. On other Linux distributions install the equivalent packages before running the script. Add the `/etc/fstab` entry printed by the script if the mount should persist across reboots. Supply `KOONJ_FUSE_BACKING_DIR` and `KOONJ_FUSE_MOUNT_POINT` to the script's environment when using custom paths. Give the Koonj Server process write access to the backing directory so it can replace links and restore files.

---

## 2. Configure and Start the FUSE Gateway

In the ZIP's `docker/` directory, copy `.env.example` to `.env` and configure the [production stack](DEPLOYMENT-GUIDE.md#option-a-docker-deployment). Set `KOONJ_DATA_MOUNT` and `KOONJ_FUSE_BACKING_DIR` to the same host backing path. The production server sees it at `/mnt/koonj` and the gateway sees it at `/mnt/koonj-backing`. Set `KOONJ_FUSE_MOUNT_POINT` to the prepared host share directory and keep `KOONJ_FUSE_ALLOW_OTHER=true` for access by Samba and local users. Register `/mnt/koonj` as the source storage root in the Koonj catalog.

For a source checkout, copy `../../deploy/.env.example` to `deploy/.env` and run Compose from `deploy/`. Run the Koonj API separately and set `KOONJ_SERVER_URL` to an endpoint the gateway container can reach. Register the same backing directory as seen by that API. The development stack also provides the `koonj-local-archive` LocalStack bucket in `eu-west-2`.

Start the stack, then copy the generated secret from **Clients → koonj-agent → Credentials** in the Keycloak `koonj` realm. Set `KOONJ_AGENT_CLIENT_SECRET` in `.env`. A realm that was imported before the agent was added needs the confidential OIDC client configured in place. Enable client authentication and service accounts, assign the realm role `KOONJ_OPERATOR` to the service account, and include it in the client's role scope. Keep the `roles` client scope and add `koonj-server` as an audience in its access token. The default `KOONJ_AGENT_TOKEN_URI` addresses Keycloak over the Compose network.

Start the gateway from the same Compose directory:

```sh
docker compose --profile fuse up -d --build koonj-fuse
docker compose --profile fuse ps
findmnt --mountpoint /srv/koonj/share
```

With just this profile, clients on the Linux gateway can use `/srv/koonj/share` directly. The gateway and Samba use Docker's `unless-stopped` restart policy. If an existing deployment manages its schema without Hibernate, add `transfer_jobs.priority integer NOT NULL DEFAULT 5` before upgrading the server.

---

## 3. Export the Share Over SMB

Set `KOONJ_SMB_PASSWORD` in `.env` for the Samba account `koonj`. Set `KOONJ_SMB_BIND` to the gateway's LAN address for remote access. Port 445 is the default and is used by native clients. Limit access to the intended network. Start the sidecar with:

```sh
docker compose --profile smb up -d --build koonj-smb
docker compose --profile smb ps
```

The `smb` profile also enables the FUSE gateway. The sidecar waits for the live mount, then exports a read-only share named `Koonj` with extended attribute support. Its `vfs_offline` module advertises all entries as offline, including files already restored to disk. The gateway waits for the first content read, so opening a handle to inspect file properties leaves the backing link in place. Windows Explorer uses the flag to avoid thumbnail reads. Disable icon previews or indexing in clients that still inspect file contents, including Finder configurations that generate previews ([Samba Offline VFS](https://www.samba.org/samba/docs/current/man-html/vfs_offline.8.html)).

| Client | Open the share |
| --- | --- |
| [Windows Explorer](#windows-file-explorer) | Enter `\\gateway-host\Koonj` in the address bar or map it to a network drive. |
| [macOS Finder](#macos-finder) | Press Command+K and enter `smb://gateway-host/Koonj`. |
| [Linux](#linux) | Enter `smb://gateway-host/Koonj` in the file manager. On the gateway host itself, open `/srv/koonj/share`. |

Use the SMB credentials when connecting to the share. Each client can then double-click files using its default applications.

---

## 4. Client Setup by Operating System

### Linux

- **Direct FUSE access (on the gateway host):** Browse `/srv/koonj/share` directly using any local file manager or shell.
- **Remote SMB access (from another Linux host):** Open your file manager (e.g., GNOME Files / Nautilus or Dolphin) and enter `smb://gateway-host/Koonj`. When prompted, authenticate with the Samba username `koonj` and the configured `KOONJ_SMB_PASSWORD`.

### macOS (Finder)

Use Finder to connect to the `Koonj` SMB share on the Linux recall gateway. Ask your administrator for the gateway hostname and SMB credentials. The bundled Samba configuration uses the username `koonj`. macOS uses its built-in SMB client, and the gateway handles Keycloak authentication when a file is recalled.

#### Connect in Finder

1. In Finder, choose **Go → Connect to Server** or press **Command+K**.
2. Enter `smb://gateway-host/Koonj` and click **Connect**. Replace `gateway-host` with the hostname or LAN IP address you were given.
3. If prompted, choose **Registered User** and enter the SMB username and password. Open the `Koonj` share under **Locations** in the Finder sidebar. You can use the **Add** button in the Connect to Server dialog to save the address for later ([Apple's server connection instructions](https://support.apple.com/guide/mac-help/connect-mac-shared-computers-servers-mchlp1140/mac)).

#### Verify recall on macOS

1. Ask your administrator to [prepare an archived test file](#5-verify-an-archive-and-recall). Use a fresh archive if the same file was already opened on another workstation.
2. Switch the share to **List** view. If the Preview pane is visible, choose **View → Hide Preview**. Open **View → Show View Options** and turn off **Show icon preview** if it is available. Leave Quick Look closed during the check because previews can read file contents ([Apple's Finder view options](https://support.apple.com/guide/mac-help/-mchldaafb302/mac) · [Apple's Preview pane guide](https://support.apple.com/guide/mac-help/-mchl1e4644c2/mac)).
3. Find `probe.txt` at its original size. Before opening it, ask your administrator to confirm the backing path is still a symlink with zero allocated data blocks.
4. Double-click `probe.txt`. The default application should open it without a Keycloak prompt. The share is read-only. Save an edited copy to a writable location.
5. Confirm with your administrator that the Catalog entry changed to `LOCAL_ONLY`. If you have the original SHA-256 from the test, compare it with the result in Terminal. Use the actual mount path if Finder assigned a different name under `/Volumes`.

   ```sh
   shasum -a 256 "/Volumes/Koonj/probe.txt"
   ```

### Windows (File Explorer)

Use File Explorer to connect to the `Koonj` SMB share on the Linux recall gateway. Ask your administrator for the gateway hostname and SMB credentials. The bundled Samba configuration uses the username `koonj`. Windows uses its built-in SMB client, and the gateway handles Keycloak authentication when a file is recalled.

#### Connect or map a drive

1. Open File Explorer with **Windows+E**.
2. Enter `\\gateway-host\Koonj` in the address bar. Replace `gateway-host` with the hostname or LAN IP address you were given.
3. If prompted, enter the SMB username and password.
4. To assign a drive letter, select **This PC** and choose **Map network drive** from the toolbar's **More** menu on Windows 11 or the **Computer** tab on Windows 10. Choose a free letter such as `K:`, enter the same network path, and select **Finish**. Use **Reconnect at sign-in** if you want Windows to reconnect automatically ([Microsoft's drive mapping instructions](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/file-sharing-over-a-network-in-windows)).

#### Verify recall on Windows

1. Ask your administrator to [prepare an archived test file](#5-verify-an-archive-and-recall). Use a fresh archive if the same file was already opened on another workstation.
2. In File Explorer, switch to **Details** and close the **Preview pane** if it is open. Find `probe.txt`. It should appear with its original size. Before opening it, ask your administrator to confirm the backing path is still a symlink with zero allocated data blocks.
3. Double-click `probe.txt`. The default application should open it without a Keycloak prompt. The share is read-only. Save an edited copy to a writable location.
4. Confirm with your administrator that the Catalog entry changed to `LOCAL_ONLY`. If you have the original SHA-256 from the test, compare it with the result in PowerShell.

   ```powershell
   Get-FileHash -Algorithm SHA256 -LiteralPath '\\gateway-host\Koonj\probe.txt'
   ```

The share can continue to show the Offline attribute after a successful recall. Check the Catalog to confirm the current storage state ([Samba Offline VFS](https://www.samba.org/samba/docs/current/man-html/vfs_offline.8.html)).

---

## 5. Verify an Archive and Recall

1. Create `probe.txt` in the backing directory and record its checksum.

   ```sh
   sha256sum /srv/koonj/backing/probe.txt
   ```

2. Register a writable S3 target in the catalog and archive the file in **PURGE** mode as an administrator. Choose S3 Standard for the immediate-recall test. Wait for the job to complete.
3. Compare the backing link and the virtual file before reading the file.

   ```sh
   readlink /srv/koonj/backing/probe.txt
   stat -c 'type=%F allocated_blocks=%b' /srv/koonj/backing/probe.txt
   stat -c 'type=%F logical_bytes=%s allocated_blocks=%b' /srv/koonj/share/probe.txt
   ```

   The backing link should hold `koonj-ref:/<uuid>` and show `allocated_blocks=0` on ext4. The virtual file should show the original length. In the catalog, the UUID appears as `koonj-ref://<uuid>` beside the local footprint of `0 B` and the original size in S3. The symlink still consumes inode metadata. Snapshots or other hard links may retain the source blocks.
4. Browse the SMB share and confirm the backing entry is still a link. Double-click `probe.txt` from the mounted share or SMB drive. The file should open using the original contents without a Keycloak prompt. On the gateway, `cat /srv/koonj/share/probe.txt` exercises the same content-open recall path.
5. Confirm the backing path is now a regular file, the checksum matches, and the catalog shows `LOCAL_ONLY`. The interactive recall job uses priority 100.

   ```sh
   test -f /srv/koonj/backing/probe.txt && test ! -L /srv/koonj/backing/probe.txt
   sha256sum /srv/koonj/backing/probe.txt
   ```

If an object has transitioned to Glacier Flexible Retrieval or Deep Archive, the server starts an S3 restore and returns HTTP 202 with an estimate. The client sees a retryable open error. Use `docker compose logs koonj-fuse` to check the estimate and retry when the object is available. The archive credentials require `s3:RestoreObject` in addition to ordinary read permissions. For an immediate recall that exceeds the synchronous wait, the job continues and a later open can use the restored file.

For older deployments, recall legacy archive links through the catalog and purge them again to produce UUID links.

---

## 6. Troubleshooting

### macOS Finder

- If Finder cannot connect, confirm the hostname and share name. Try the LAN IP address. Ask the administrator to check that SMB on TCP port 445 is reachable from your Mac.
- If the SMB password changed, eject the share from **Locations** and reconnect with the updated credentials.
- If you can browse but an archived file temporarily fails to open, ask the administrator to check the gateway recall logs (`docker compose logs koonj-fuse`). A Glacier object may still be restoring. Retry after it is available.

### Windows File Explorer

- If the share cannot be reached, run `Test-NetConnection gateway-host -Port 445` in PowerShell. If the TCP test fails, ask the administrator to check the gateway's LAN binding and firewall.
- If Windows rejects the credentials, confirm that you are using the SMB account supplied by the gateway administrator. Disconnect an existing mapping before reconnecting with updated credentials.
- If you can browse but an archived file temporarily fails to open, ask the administrator to check the gateway recall logs (`docker compose logs koonj-fuse`). A Glacier object may still be restoring. Retry after it is available.
