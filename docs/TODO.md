# TODO / Review Findings

> Consolidated from build/lint/test runs, `/code-review` (working diff), and a manual security
> review (the `/security-review` skill couldn't run — no `origin/HEAD` in this VM).
> Date: 2026-06-26. Severity: 🔴 high · 🟠 medium · 🟡 low.

## Security

- 🟡 **`.env` is committed and not gitignored** — *accepted (2026-06-26)*: this is a demo that
  requires no credentials to run; `.env` holds only `REDIS_HOST`/`REDIS_PORT`. **Revisit if any
  secret (e.g. `REDIS_PASSWORD`) is ever added** — at that point gitignore `.env*`,
  `git rm --cached .env`, and ship a `.env.example`.
- 🟠 **No authentication/authorization** on any REST or WebSocket endpoint (ADR-0008). Acceptable
  for a localhost demo; a blocker before any network exposure.
- 🟠 **CORS and WebSocket origins are `*`** (`CorsConfig`, `WebSocketConfig`). Lock to an allow-list
  before deployment.
- 🟠 **Redis runs with no password by default** (`REDIS_PASSWORD` empty in compose/env). Set a
  password + consider ACLs/TLS for any non-local use (see `redis-security`).
- 🟡 `frontend/Dockerfile` adds `chmod -R a+r` on the nginx web root — benign (public static assets).

## Correctness / build

- 🟠 **No automated tests exist** — `src/test` is absent and there are no `*.spec.ts`. The "Running
  Tests" section of `augmentcode/startup_instructions.md` is aspirational. → Add at least smoke tests
  per pattern service and a few component specs.
- 🟠 **Frontend lint: 76 errors** (`npm run lint`). Dominant categories: `@angular-eslint/template/
  label-has-associated-control`, `click-events-have-key-events` / `interactive-supports-focus`
  (a11y), `@typescript-eslint/no-explicit-any`, `@angular-eslint/no-empty-lifecycle-method`.
  8 are auto-fixable (`npm run lint -- --fix`).
- 🟠 **Backend cannot be built/tested in this VM** — Java 21 + Maven are not installed; only the
  Docker multi-stage build path works. See the toolchain inventory below; the list to add to
  `scripts/vm-provision.sh` will be relayed to the host.

### Toolchain inventory (required vs VM, 2026-06-26)

| Tool | Required (source) | VM | Status |
|------|-------------------|----|--------|
| JDK | 21 (`pom.xml`, Dockerfile temurin-21) | — | 🔴 MISSING |
| Maven | 3.9 (Dockerfile `maven:3.9`) | — | 🔴 MISSING |
| Node | 22 (frontend Dockerfile `node:22-alpine`) | 24.16.0 | 🟠 wrong major (builds; not pinned) |
| Lua + lua-language-server | dev-only (`.luarc.json`) | — | 🟡 MISSING (edit/lint only; not needed at runtime) |
| redis-cli | client for `lua/load.sh` | 7.0.15 | ✅ ok as client; local *server* must be 8.4+ (use Docker) |
| Docker / Compose | modern | 29.5.2 / v5.1.4 | ✅ |
| npm / git | bundled / any | 11.13.0 / 2.43.0 | ✅ |

No multi-version needs (single Java 21, single Node line; no `.nvmrc`/`.tool-versions`/Python/Go/PHP/TF).

**For `scripts/vm-provision.sh` (relay to host):** `openjdk-21` (Temurin JDK) + `maven` 3.9.x
(🔴 unblocks local backend build/test); optionally `lua5.4` + `lua-language-server` (🟡 dev). Node
parity (🟠): pin the repo (`engines`/`.nvmrc`) or bump the frontend Dockerfile to `node:24-alpine`.
- 🟡 **`TokenBucketService.java:165`** changed `StreamEntryID.UNRECEIVED_ENTRY` →
  `StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY` (Jedis 7 API). Correct in principle but **not
  compile-verified here** (no Maven). → Confirm via `./launch-docker.sh --build`.

## Quality / cleanup (from `/code-review` of the working diff)

- 🟡 **Diagram-to-page mapping looks swapped.** `topic-routing.component.ts` (stream/Lua key routing)
  binds `diagrams.keyRouting`, while `pubsub-topic-routing.component.ts` binds `diagrams.topicRouting`.
  Both keys exist (so it compiles and the build passes), but the names suggest the two are crossed.
  → Verify each page shows its intended diagram.
- 🟡 **Hardcoded `http://localhost:8080` API base URLs** in `redis-api.service.ts`,
  `routing-rules.service.ts`, `websocket.service.ts`, and the `apiUrl` field of ~11 pattern
  components. Works only because ports are published to the host. → Centralize in one
  environment/config (Angular `environment.ts` or a runtime config) for portability.
- 🟡 The in-flight mermaid feature repeats the same `diagrams = inject(DiagramDefinitionsService)`
  + identical `<app-mermaid-diagram>` block across ~11 components. Acceptable, but a shared wrapper
  or a small base could reduce duplication.

## Docs

- ✅ Addressed in this pass: `docs/` (PRD, architecture, 11 specs, 8 ADRs, migration-status),
  root `CLAUDE.md`, and README pattern list sync (see `/doc-sync`).
- 🟡 `augmentcode/CONTEXT.md` & `IMPLEMENTATION_REFERENCE.md` describe only the original 4 patterns
  and legacy stream names (`test-stream`). Kept for history; `docs/` supersedes them.
