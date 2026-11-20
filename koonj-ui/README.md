# Koonj UI

## Run the UI and server together

1. Follow the [server setup](../koonj-server/README-SERVER.md#start-the-development-stack) to start PostgreSQL, LocalStack, and Keycloak with Docker Compose and run the Spring Boot server on port 8080.
2. From `koonj-ui`, run `npm ci` and `npm run dev`. Open [http://localhost:5173](http://localhost:5173).
3. The development client sends same-origin requests to `/api/v1`. Vite proxies them to the server on port 8080. `.env.example` shows the default backend URL. For a production build behind a same-origin reverse proxy, set `VITE_API_BASE_URL=/api/v1` at build time. An absolute custom API URL is used directly and needs CORS permission if it has a different origin.
4. Use **Sign In** or **Create Account** at the Koonj welcome screen. See the [Keycloak verification guide](../koonj-server/README-SERVER.md#keycloak-and-role-verification) for the three seeded accounts and expected permissions. `.env.example` lists the Keycloak URL, realm, and client ID. Keycloak issues short-lived Bearer tokens and the UI refreshes them before API requests. Admin has Home, Explorer, Activity, Administration, and Storage. Operator has Home, Explorer, and Activity. Viewer has read-only Home and Explorer.

## Verify archive and recall

1. Create a file such as `/tmp/koonj-source/docs/report.txt` on the machine running the server.
2. Sign in as the seeded Koonj admin. In **Storage connections**, add a `LOCAL` connection with role `BOTH` and root `/tmp/koonj-source`. Add a `CUSTOM_S3` connection with role `BOTH`, bucket `koonj-local-archive`, endpoint `http://127.0.0.1:4566`, region `eu-west-2`, path-style access enabled, and LocalStack access and secret keys set to `test`. Use the Compose values if you changed them. Save and test both connections.
3. On **Home**, check that both endpoints are listed. Select **Scan Now** on the local connection and wait for the scan to complete in **Activity log**.
4. In **Catalog explorer**, select the local connection and the `docs` folder. Open `report.txt` to inspect its metadata.
5. Select the file, choose **Archive to S3**, pick the LocalStack bucket, and submit. Confirm the archive submission toast. In the browser's Network panel, the archive request is a `POST /api/v1/transfers/archive` with the selected catalog file ID.
6. Open **Activity log** and watch the archive job reach **Completed**. A small file may finish before the first refresh. Return to Explorer to check the **Archived** state and checksum. Select the archived file and use **Recall to Local/NAS** to restore it to an eligible mounted connection.

Run `npm test` for the service contract tests and `npm run build` for TypeScript and the production build. The dashboard totals, folder tree, and job history use the milestone's paginated API. Explorer search matches a relative-path prefix under the selected folder.

## Source layout

- `src/app/` contains the console shell, route selection, application providers, and global styles.
- `src/modules/` contains the Home, Catalog, Activity, and Storage pages and their feature-specific components.
- `src/auth/` contains the Keycloak adapter, authentication context, welcome gate, and account controls.
- `src/api/` contains the Axios client, backend DTOs, services, React Query hooks, and contract tests.
- `src/shared/` contains reusable UI components and formatters.
- `src/legacy/` contains the earlier demo workspace. The current console routes use the live modules.
