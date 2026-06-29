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
- ✅ **CORS and WebSocket origins locked to an explicit allow-list** (`CorsConfig`, `WebSocketConfig`,
  `app.cors.allowed-origins`, default local frontend/backend) — *done 2026-06-29, PR #3*. Previously `*`.
- ✅ **Mermaid diagram rendering sanitized** — `securityLevel: 'antiscript'` (DOMPurify on the SVG
  before the `innerHTML` sink) — *done 2026-06-29, PR #3*.
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
- ✅ **Backend builds & runs locally in this VM** — *resolved 2026-06-29*: Java 21 + Maven 3.9.16
  are now installed (host VM provisioning), so `mvn compile`/`mvn package` work directly; the Docker
  multi-stage path also works. Lua lint available via `luacheck`.

### Toolchain inventory (required vs VM, updated 2026-06-29)

| Tool | Required (source) | VM | Status |
|------|-------------------|----|--------|
| JDK | 21 (`pom.xml`, Dockerfile temurin-21) | 21.0.11 | ✅ installed |
| Maven | 3.9 (Dockerfile `maven:3.9`) | 3.9.16 | ✅ installed |
| Node | 22 (frontend Dockerfile `node:22-alpine`) | 24.16.0 | 🟡 runtime VM is 24; build image still `node:22-alpine` (builds fine; not pinned) |
| luacheck | dev-only Lua lint (`.luarc.json`) | 1.2.0 (Lua 5.1) | ✅ installed |
| redis-cli | client for `lua/load.sh` | 7.0.15 | ✅ ok as client; local *server* must be 8.4+ (use Docker) |
| Docker / Compose | modern (`docker compose`, no v1 `docker-compose`) | 29.5.2 / v5.1.4 | ✅ |
| npm / git | bundled / any | 11.13.0 / 2.43.0 | ✅ |

No multi-version needs (single Java 21, single Node line; no `.nvmrc`/`.tool-versions`/Python/Go/PHP/TF).
Node parity (🟡): pin the repo (`engines`/`.nvmrc`) or bump the frontend Dockerfile build stage to
`node:24-alpine` to match the runtime VM.
- ✅ **`TokenBucketService` `XREADGROUP_UNDELIVERED_ENTRY`** (Jedis 7 API) — compile-verified via the
  Docker build; the service also now uses registered `FCALL acquire_token`/`release_token` instead of
  inline `EVAL`.

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

## Code review & security

- ✅ **First full `/code-review`** (2026-06-29) — findings tracked in
  [`specs/code-review-findings.md`](specs/code-review-findings.md); P1/P2 fixes shipped in PR #2
  (Redis/pattern correctness, dedicated pub/sub connections, CORS allow-list, Lua loader hardening,
  inline `EVAL` → registered functions). Remaining follow-up: extract a typed decoder for `fcall`
  replies (readability of the critical paths).
- ✅ **First full `/security-review`** (2026-06-29) — no exploitable vuln beyond the by-design no-auth
  posture; the two concrete origin/sink gaps (WebSocket origin, Mermaid `innerHTML`) fixed in PR #3.

## Docs

- ✅ Addressed in this pass: `docs/` (PRD, architecture, 11 specs, 8 ADRs, migration-status),
  root `CLAUDE.md`, and README pattern list sync (see `/doc-sync`).
- 🟡 `augmentcode/CONTEXT.md` & `IMPLEMENTATION_REFERENCE.md` describe only the original 4 patterns
  and legacy stream names (`test-stream`). Kept for history; `docs/` supersedes them.
