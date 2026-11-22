# Open archived Koonj files on Windows

Use File Explorer to connect to the `Koonj` SMB share on the
[Linux recall gateway](FUSE-RECALL.md#3-optionally-export-the-share-over-smb).
Ask your administrator for the gateway hostname and SMB credentials. The
bundled Samba configuration uses the username `koonj`. Windows uses its
built-in SMB client, and the gateway handles Keycloak authentication when
a file is recalled.

## Connect or map a drive

1. Open File Explorer with **Windows+E**.
2. Enter `\\gateway-host\Koonj` in the address bar. Replace `gateway-host`
   with the hostname or LAN IP address you were given.
3. If prompted, enter the SMB username and password.
4. To assign a drive letter, select **This PC** and choose **Map network
   drive** from the toolbar's **More** menu on Windows 11 or the
   **Computer** tab on Windows 10. Choose a free letter such as `K:`,
   enter the same network path, and select **Finish**. Use **Reconnect at
   sign-in** if you want Windows to reconnect automatically.
   [Microsoft's drive mapping instructions](https://support.microsoft.com/en-us/windows/experience/connectivity-networking/file-sharing-over-a-network-in-windows)

## Verify recall

1. Ask your administrator to [prepare an archived test file](FUSE-RECALL.md#4-verify-an-archive-and-recall).
   Use a fresh archive if the same file was already opened on another
   workstation.
2. In File Explorer, switch to **Details** and close the **Preview pane**
   if it is open. Find `probe.txt`. It should appear with its original
   size. Before opening it, ask your administrator to confirm the backing
   path is still a symlink with zero allocated data blocks.
3. Double-click `probe.txt`. The default application should open it
   without a Keycloak prompt. The share is read-only. Save an edited copy
   to a writable location.
4. Confirm with your administrator that the Catalog entry changed to
   `LOCAL_ONLY`. If you have the original SHA-256 from the test, compare
   it with the result in PowerShell.

   ```powershell
   Get-FileHash -Algorithm SHA256 -LiteralPath '\\gateway-host\Koonj\probe.txt'
   ```

The share can continue to show the Offline attribute after a successful
recall. Check the Catalog to confirm the current storage state.
[Samba Offline VFS](https://www.samba.org/samba/docs/current/man-html/vfs_offline.8.html)

## Troubleshoot

- If the share cannot be reached, run `Test-NetConnection gateway-host -Port 445`
  in PowerShell. If the TCP test fails, ask the administrator to check the
  gateway's LAN binding and firewall.
- If Windows rejects the credentials, confirm that you are using the SMB
  account supplied by the gateway administrator. Disconnect an existing
  mapping before reconnecting with updated credentials.
- If you can browse but an archived file temporarily fails to open, ask
  the administrator to check the [gateway recall logs](FUSE-RECALL.md#4-verify-an-archive-and-recall).
  A Glacier object may still be restoring. Retry after it is available.
