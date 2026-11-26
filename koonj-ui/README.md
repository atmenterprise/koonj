# Koonj UI

<p>
  <a href="../LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0" /></a>
  <img src="https://img.shields.io/badge/React-19-61DAFB.svg" alt="React 19" />
  <img src="https://img.shields.io/badge/TypeScript-5.9-3178C6.svg" alt="TypeScript 5.9" />
  <img src="https://img.shields.io/badge/Vite-7-646CFF.svg" alt="Vite 7" />
  <img src="https://img.shields.io/badge/Tailwind%20CSS-4-06B6D4.svg" alt="Tailwind CSS 4" />
</p>

The web console for **Koonj**, developed and maintained by [Apps That Matter](https://www.appsthatmatter.uk). Built with React 19, TypeScript, Vite, TanStack Query, and Tailwind CSS, and served via Nginx in production.

---

## Prerequisites

- **Node.js:** 22.12+ with npm
- **Backend & Identity Stack:** Running `koonj-server`, PostgreSQL, LocalStack, and Keycloak (see [Option C: Local Development Stack](../docs/guide/DEPLOYMENT-GUIDE.md#option-c-local-development-stack))

## Configuration

Copy `.env.example` to `.env.local` if you need to override the defaults:

| Variable | Default | Description |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `/api/v1` | Base path for API requests (proxied to port `8080` by Vite in development and by Nginx in production) |
| `VITE_KEYCLOAK_URL` | `http://localhost:8180` | Keycloak base URL |
| `VITE_KEYCLOAK_REALM` | `koonj` | Keycloak realm name |
| `VITE_KEYCLOAK_CLIENT_ID` | `koonj-ui` | Public OIDC client ID |

If you set `VITE_API_BASE_URL` to a separate API origin at build time, add the UI origin to `KOONJ_CORS_ALLOWED_ORIGINS` on `koonj-server`.

## Run the UI and Server Together

1. Follow the [server setup](../docs/guide/DEPLOYMENT-GUIDE.md#start-the-development-stack) to start PostgreSQL, LocalStack, and Keycloak with Docker Compose and run the Spring Boot server on port `8080`.
2. From `koonj-ui`, install dependencies and start the Vite development server:

   ```sh
   npm ci
   npm run dev
   ```

3. Open [http://localhost:5173](http://localhost:5173).
4. Use **Sign In** or **Create Account** at the Koonj welcome screen. See the [Keycloak verification guide](../docs/guide/DEPLOYMENT-GUIDE.md#keycloak-and-role-verification) for the three seeded accounts and expected permissions. Keycloak issues short-lived Bearer tokens and the UI refreshes them before API requests:
   - **Admin:** Home, Explorer, Activity, Administration, and Storage.
   - **Operator:** Home, Explorer, and Activity.
   - **Viewer:** Read-only Home and Explorer.

## Scripts & Verification

```sh
# Run service contract and unit tests (Vitest)
npm test

# Type-check and build production assets into dist/
npm run build

# Preview the production build locally
npm run preview
```

## Verify Archive and Recall

1. Create a file such as `/tmp/koonj-source/docs/report.txt` on the machine running `koonj-server`.
2. Sign in as the seeded Koonj admin. In **Storage connections**, add a `LOCAL` connection with role `BOTH` and root `/tmp/koonj-source`. Add a `CUSTOM_S3` connection with role `BOTH`, bucket `koonj-local-archive`, endpoint `http://127.0.0.1:4566`, region `eu-west-2`, path-style access enabled, and LocalStack access and secret keys set to `test` (or use your Compose values if customized). Save and test both connections.
3. On **Home**, check that both endpoints are listed. Select **Scan Now** on the local connection and wait for the scan to complete in **Activity log**.
4. In **Catalog explorer**, select the local connection and the `docs` folder. Open `report.txt` to inspect its metadata.
5. Select the file, choose **Archive to S3**, pick the LocalStack bucket, and submit. Confirm the archive submission toast. In the browser's Network panel, the archive request is a `POST /api/v1/transfers/archive` with the selected catalog file ID.
6. Open **Activity log** and watch the archive job reach **Completed**. A small file may finish before the first refresh. Return to Explorer to check the **Archived** state and checksum. Select the archived file and use **Recall to Local/NAS** to restore it to an eligible mounted connection.

The dashboard totals, folder tree, and job history use the paginated API, and Explorer search matches a relative-path prefix under the selected folder.

## Source Layout

- `src/app/` — Console shell, route selection, application providers, and global styles
- `src/modules/` — Home, Catalog, Activity, and Storage pages and feature-specific components
- `src/auth/` — Keycloak adapter, authentication context, welcome gate, and account controls
- `src/api/` — Axios client, backend DTOs, services, React Query hooks, and contract tests
- `src/shared/` — Reusable UI components and formatters
- `src/legacy/` — Earlier demo workspace (current console routes use live modules)

## Related Documentation

- [Project README](../README.md)
- [Deployment Guide](../docs/guide/DEPLOYMENT-GUIDE.md)
- [FUSE & Samba Integration Guide](../docs/guide/FUSE-GUIDE.md)
- [Contributing Guidelines](../CONTRIBUTING.md)

## License

Copyright © 2026 [Apps That Matter](https://www.appsthatmatter.uk).

Licensed under the **Apache License, Version 2.0**. See [LICENSE](../LICENSE) for the full license text.
