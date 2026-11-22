# Open archived Koonj files on macOS

Use Finder to connect to the `Koonj` SMB share on the
[Linux recall gateway](FUSE-RECALL.md#3-optionally-export-the-share-over-smb).
Ask your administrator for the gateway hostname and SMB credentials. The
bundled Samba configuration uses the username `koonj`. macOS uses its
built-in SMB client, and the gateway handles Keycloak authentication when
a file is recalled.

## Connect in Finder

1. In Finder, choose **Go → Connect to Server** or press **Command+K**.
2. Enter `smb://gateway-host/Koonj` and click **Connect**. Replace
   `gateway-host` with the hostname or LAN IP address you were given.
3. If prompted, choose **Registered User** and enter the SMB username and
   password. Open the `Koonj` share under **Locations** in the Finder
   sidebar. You can use the **Add** button in the Connect to Server
   dialog to save the address for later.
   [Apple's server connection instructions](https://support.apple.com/guide/mac-help/connect-mac-shared-computers-servers-mchlp1140/mac)

## Verify recall

1. Ask your administrator to [prepare an archived test file](FUSE-RECALL.md#4-verify-an-archive-and-recall).
   Use a fresh archive if the same file was already opened on another
   workstation.
2. Switch the share to **List** view. If the Preview pane is visible,
   choose **View → Hide Preview**. Open **View → Show View Options**
   and turn off **Show icon preview** if it is available. Leave Quick
   Look closed during the check because previews can read file contents.
   [Apple's Finder view options](https://support.apple.com/guide/mac-help/-mchldaafb302/mac) · [Apple's Preview pane guide](https://support.apple.com/guide/mac-help/-mchl1e4644c2/mac)
3. Find `probe.txt` at its original size. Before opening it, ask your
   administrator to confirm the backing path is still a symlink with zero
   allocated data blocks.
4. Double-click `probe.txt`. The default application should open it
   without a Keycloak prompt. The share is read-only. Save an edited copy
   to a writable location.
5. Confirm with your administrator that the Catalog entry changed to
   `LOCAL_ONLY`. If you have the original SHA-256 from the test, compare
   it with the result in Terminal. Use the actual mount path if Finder
   assigned a different name under `/Volumes`.

   ```sh
   shasum -a 256 "/Volumes/Koonj/probe.txt"
   ```

## Troubleshoot

- If Finder cannot connect, confirm the hostname and share name. Try the
  LAN IP address. Ask the administrator to check that SMB on TCP port 445
  is reachable from your Mac.
- If the SMB password changed, eject the share from **Locations** and
  reconnect with the updated credentials.
- If you can browse but an archived file temporarily fails to open, ask
  the administrator to check the [gateway recall logs](FUSE-RECALL.md#4-verify-an-archive-and-recall).
  A Glacier object may still be restoring. Retry after it is available.
