# Contributing to Koonj

Thank you for your interest in contributing to **Koonj**, maintained by [Apps That Matter](https://www.appsthatmatter.uk). Whether you are reporting a bug, proposing a feature, improving documentation, or submitting code, we welcome your contributions.

## Code of Conduct

We are committed to fostering an open, welcoming, and respectful community. Please be constructive, inclusive, and considerate in all issues, pull requests, and discussions.

## Ways to Contribute

- **Report bugs:** Open an issue with clear reproduction steps, expected vs. actual behavior, environment details (OS, Java/Node/Docker versions), and relevant logs.
- **Suggest enhancements:** Open an issue describing the use case, proposed behavior, and any architectural or operational impact.
- **Improve documentation:** Fix typos, clarify setup steps, or expand guides in `docs/guide/`.
- **Submit code:** Fix bugs or implement approved features via pull requests.

## Development Prerequisites

- **Java:** JDK 21+ (OpenJDK or Eclipse Temurin)
- **Maven:** 3.9+
- **Node.js:** 22.12+ with npm
- **Docker:** Docker Engine 24+ with Docker Compose v2 (for PostgreSQL, LocalStack, Keycloak, and RabbitMQ)

## Local Development Setup

1. Fork the repository and clone your fork:

   ```sh
   git clone https://github.com/<your-username>/koonj.git
   cd koonj
   ```

2. Follow [Option C: Local Development Stack](docs/guide/DEPLOYMENT-GUIDE.md#option-c-local-development-stack) in the Deployment Guide to start PostgreSQL, LocalStack, and Keycloak and run `koonj-server`.
3. Follow [koonj-ui/README.md](koonj-ui/README.md) to install UI dependencies and run the Vite development server.

## Workflow & Conventions

1. **Create a branch** from `main` using a descriptive name:
   - `feat/<short-description>` for new features
   - `fix/<short-description>` for bug fixes
   - `docs/<short-description>` for documentation updates
2. **Keep changes focused:** Limit pull requests to a single concern and avoid unrelated formatting or refactoring changes.
3. **Follow existing conventions:**
   - **Backend (Java / Spring Boot):** Follow standard Java naming, keep methods concise, and preserve existing Javadocs and comments.
   - **Frontend (React / TypeScript):** Keep TypeScript types strict (`tsc --noEmit`) and follow the module layout in `koonj-ui/src/`.
   - **Commit messages:** Use clear, imperative commit messages (e.g., `feat: add Glacier restore status polling`, `fix: handle empty prefix in S3 scan`).

## Testing & Verification

Before opening a pull request, verify that all relevant builds and tests pass:

```sh
# Backend unit tests
mvn -pl koonj-server -am test

# Full Maven build (including assembly ZIP)
mvn -pl koonj-assembly -am clean package

# Frontend type-check, build, and tests
cd koonj-ui
npm ci
npm test
npm run build
```

> **Note:** Never commit secrets, `.env` files, personal access tokens, or real cloud credentials.

## Pull Request Process

1. Ensure all tests and builds pass locally.
2. Update documentation in `docs/guide/` if your change affects configuration, deployment, or user-facing behavior.
3. Open a pull request against `main` with a clear summary of the changes, linked issues, and screenshots or logs where applicable.
4. Address review feedback promptly.

## License

By contributing to Koonj, you agree that your contributions will be licensed under the [Apache License, Version 2.0](LICENSE), copyright [Apps That Matter](https://www.appsthatmatter.uk).
