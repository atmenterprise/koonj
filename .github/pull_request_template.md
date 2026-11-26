## Summary

<!-- One or two sentences describing what this PR does and why. -->

Fixes # <!-- issue number, if applicable -->

---

## Type of Change

<!-- Check all that apply. Your PR title must follow Conventional Commits — see CONTRIBUTING.md. -->

- [ ] `feat` — new feature (triggers a **minor** version bump)
- [ ] `fix` — bug fix (triggers a **patch** version bump)
- [ ] `perf` — performance improvement (triggers a **patch** version bump)
- [ ] `docs` — documentation only (no version bump)
- [ ] `refactor` — code refactoring, no behaviour change (no version bump)
- [ ] `test` — new or updated tests (no version bump)
- [ ] `build` — build system / dependency changes (no version bump)
- [ ] `ci` — CI/CD configuration (no version bump)
- [ ] `chore` — maintenance, tooling, etc. (no version bump)
- [ ] Contains a **breaking change** (append `!` to the type, e.g. `feat!:`, or add a `BREAKING CHANGE:` footer)

---

## Changes

<!-- Bullet-point summary of what was added, changed, or removed. -->

-
-

---

## Testing

<!-- How did you verify this works? Paste relevant test output or describe manual steps. -->

- [ ] Backend unit tests pass: `mvn -pl koonj-server,koonj-notification-worker -am test`
- [ ] Frontend type-check and tests pass: `npm run build && npm test` (in `koonj-ui/`)
- [ ] Full assembly builds without error: `mvn -pl koonj-assembly -am clean package`
- [ ] Manual smoke test performed (describe below if applicable)

---

## Documentation

- [ ] `docs/guide/` updated (if behaviour, configuration, or deployment changed)
- [ ] No documentation update needed

---

## Checklist

- [ ] PR title follows [Conventional Commits](https://www.conventionalcommits.org/) (required for automated releases)
- [ ] No secrets, `.env` files, or real credentials committed
- [ ] Changes are limited to a single concern
