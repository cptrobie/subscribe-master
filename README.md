# Subscribe Master

A backend system that consolidates a customer's paid subscriptions (Netflix, Spotify, Apple TV+, etc.) into one place, tracks spending in a customer-chosen base currency using real-time exchange rates, schedules and processes payments via Stripe, and sends renewal reminders before a payment is due.

This project started from a take-home assignment brief and has since grown into a broader exercise in applying enterprise-level backend patterns — RBAC, optimistic locking, audit logging, distributed tracing, dunning/retry logic, secrets management — across a single realistic application. See **[A note on `task.pdf`](#a-note-on-taskpdf)** below for that history.

---

## Status

- **Wave-by-wave progress:** see `IMPLEMENTATION_BACKLOG.md` — wave markers (🚧/⏸️/✅) and
  each completed wave's rollup line reflect real, current state. Per-requirement status
  lives on the GitHub issues: closed means done, labeled by `wave-*`, `size-*` and `domain:*`.
- **Time logged, per issue and per wave:** run `./scripts/time_tracking/project_time_report.sh`
  for a live report generated from actual GitHub issue comments, not a hand-maintained summary.

---

## Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| **Java 21** | Building/running the application | |
| **Maven** | Build tool | |
| **Docker Desktop** | Local Postgres + Vault + Mailpit | Required now — see Getting Started |
| **IntelliJ IDEA** | Development | Recommended, not required |
| **Flyway CLI** | Validating migrations independently of the app | Recommended — lets you run `flyway migrate` against local Postgres without starting the app |
| **pgAdmin 4** | Inspecting the database | Optional, but useful alongside Flyway's own output |
| **Vault CLI** | Poking at the dev-mode Vault container directly | Optional — the Vault UI in a browser works too |

---

## Getting started

1. **Clone the repo:**
   ```
   git clone https://github.com/cptrobie/subscribe-master.git
   cd subscribe-master
   ```
2. **Start local infrastructure:**
   ```
   docker compose up -d
   ```
   This starts Postgres (`localhost:5432`), Vault in dev mode (`localhost:8200`, root token: `local-dev-root-token` — see `compose.yaml` for why dev mode is safe to hardcode locally but nowhere else), and Mailpit, a local mail catcher (SMTP on `localhost:1025`, inbox UI at `http://localhost:8025`). Every email the app sends locally — e.g. `FR-30`'s verification link — lands in Mailpit's inbox instead of a real mailbox.
3. **Run the database migrations:**
   ```
      flyway -url=jdbc:postgresql://localhost:5432/subscribe_master \
          -user=subscribe_master -password=local_dev_only_not_a_real_secret \
          -locations=filesystem:src/main/resources/db/migration \
          migrate
   ```
   This applies every migration in `src/main/resources/db/migration/` in order (`V1`, `V1.1`, `V2`–`V4`, `V6`–`V11`, then the repeatable `R__seed_subscription_provider_catalog`) and seeds the roles/permissions and subscription provider catalog.
4. **Inspect the result** in pgAdmin 4, or `psql`, to confirm the schema and seed data landed as expected.
5. **Seed Vault with the database credentials** the app will read at startup. Either method works — the CLI, if installed:

   ```
   vault login -address=http://localhost:8200 local-dev-root-token

   # Generate a local RSA key pair for JWT signing (RS256) -- only needs
   # doing once; re-run the vault kv put below whenever Vault's dev-mode
   # data is lost (e.g. after `docker compose down -v`).
      mkdir -p src/main/resources/local-dev-keys
   openssl genrsa -out src/main/resources/local-dev-keys/jwt-private.pem 2048
   openssl rsa -in src/main/resources/local-dev-keys/jwt-private.pem -pubout -out src/main/resources/local-dev-keys/jwt-public.pem

   vault kv put -address=http://localhost:8200 secret/subscribe-master \
     spring.datasource.username=subscribe_master \
     spring.datasource.password=local_dev_only_not_a_real_secret \
     jwt.private-key=@src/main/resources/local-dev-keys/jwt-private.pem \
     jwt.public-key=@src/main/resources/local-dev-keys/jwt-public.pem \
     app.idempotency.hmac-secret=local_dev_only_not_a_real_secret
   ```

   **Already seeded before `app.idempotency.hmac-secret` existed?** Add just that key with `patch`, not `put` -- `vault kv put` replaces every key at the path, which would wipe the database credentials and JWT keys:

   ```
   vault kv patch -address=http://localhost:8200 secret/subscribe-master \
     app.idempotency.hmac-secret=local_dev_only_not_a_real_secret
   ```

   `app.idempotency.hmac-secret` keys the HMAC that fingerprints `Idempotency-Key` requests (NFR-33); the app won't start without it.

   **No SMTP credentials are needed locally** — Mailpit accepts mail without authentication. Production is different: its SMTP credentials live in Vault under the same path, as `spring.mail.username` and `spring.mail.password` (see `ARCHITECTURE.md` §9).
 
6. Or via the browser UI at `http://localhost:8200` — sign in with `local-dev-root-token`, go to **Secrets Engines → secret/ → Create secret**, set the path to `subscribe-master`, and add the same five key/value pairs.

   Without this step, the app fails to start — Vault's dev-mode container starts empty; nothing is seeded into it automatically just because Postgres itself is running.
7. **Build, test and run the application:**
   ```
   ./mvnw verify
   SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
   ```
   `./mvnw verify` is the same command CI runs: unit and integration tests (Testcontainers starts its own Postgres and Vault, so the compose stack isn't needed for this step), plus the JaCoCo 30% coverage gate. `spring-boot:run` needs the compose stack from step 2 and the Vault secrets from step 5. The profile has to be set explicitly; `application.yaml` deliberately doesn't set one. Once it's up, the API docs are at `http://localhost:8080/swagger-ui.html`, and verification emails land in Mailpit at `http://localhost:8025`.

## API surface

Everything is versioned under `/api/v1` (NFR-25). Full request/response schemas are in Swagger (`/swagger-ui.html`) once the app is running.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | none | Create an account; sends a verification email. Accepts `Idempotency-Key` (NFR-33) |
| `POST` | `/api/v1/auth/login` | none | Returns a 15-minute RS256 JWT; 5 failures lock the account for 15 minutes |
| `GET` | `/api/v1/auth/verify-email?token=…` | none | Confirms the emailed verification link; safe to repeat |
| `POST` | `/api/v1/auth/resend-verification` | none | Re-sends the verification email, optionally to a corrected address (202). Accepts `Idempotency-Key` |
| `POST` | `/api/v1/subscribe` | Bearer JWT | Create a subscription (201) |
| `GET` | `/api/v1/subscribe` | Bearer JWT | List the caller's subscriptions |
| `GET` | `/api/v1/subscribe/{id}` | Bearer JWT | Fetch one subscription |
| `PUT` | `/api/v1/subscribe/{id}` | Bearer JWT | Update name, amount, currency, billing frequency |
| `POST` | `/api/v1/subscribe/{id}/pause` | Bearer JWT | `ACTIVE → PAUSED` |
| `POST` | `/api/v1/subscribe/{id}/resume` | Bearer JWT | `PAUSED → ACTIVE` |
| `POST` | `/api/v1/subscribe/{id}/cancel` | Bearer JWT | `ACTIVE/PAUSED → CANCELLED` (terminal)|

Subscription routes only ever return the caller's own rows (FR-04); a concurrent edit to the same row returns 409 `CONCURRENT_MODIFICATION` (NFR-04). See `design-docs/subscription_status_transitions.md` for the state rules.

---

## Project structure

```
.
├── src/main/java/com/acuity/subscribemaster/  # Application code, one package per domain (entry point: SubscribeMasterApplication)
│   ├── auth/                                  # Registration, login (JWT), credential checks, email verification + resend (FR-30, FR-40)
│   │   └── dto/                               # Auth request/response records (registration, login, verification, resend)
│   ├── customer/                              # Customer entity and repository
│   ├── subscribe/                             # Provider catalog, customer subscriptions, status transitions, subscription REST API
│   │   └── dto/                               # Subscription request/response records
│   ├── payment/                               # Payment attempts, payment history, refunds (entities and repositories; Stripe flow is Wave 6)
│   │   └── dto/                               # Payment request/response records
│   ├── idempotency/                           # Idempotency-Key handling: stored keys, request hashing, replay (NFR-33)
│   ├── auditlog/                              # Audit log entity, audit actions and actor types, audit service
│   ├── notifylog/                             # Notification log of messages sent to customers
│   ├── message/                               # Channel-agnostic outbound messaging API (MessageService, OutboundMessage)
│   │   ├── email/                             # SMTP email sender and its properties
│   │   └── internal/                          # Dispatcher and per-channel sender strategy -- internal to the module
│   ├── error/                                 # Error codes, API error body, global exception handler
│   ├── config/                                # Spring Security and OpenAPI configuration
│   └── support/                               # Shared helpers: JWT issuing, PEM keys, tokens, email masking, branding, common exceptions
├── src/main/resources/
│   ├── application*.yaml                      # Base config plus dev and prod profiles
│   ├── db/migration/                          # Flyway schema + seed migrations, V1–V11 + repeatable catalog seed (source of truth for the DB)
│   └── local-dev-keys/                        # Locally-generated JWT signing key pair -- gitignored, see "Getting started" step 5
├── src/test/java/com/acuity/subscribemaster/  # Unit and integration tests (Testcontainers), mirroring the main packages
├── src/test/resources/test-jwt-keys/          # Dedicated test-only JWT key pair -- committed (disposable fixture, never a real credential)
├── scripts/                                   # Project-management scripts for GitHub issues (bash + gh)
│   ├── one_time/                              # One-off scripts: issue creation, body fixes, domain tagging, wave rebalances, NFR additions
│   ├── issue_hygiene/                         # Recurring checks, e.g. every issue has exactly one domain:* label
│   └── time_tracking/                         # Per-issue time totals vs. estimate, and a whole-project time report
├── design-docs/                               # Design docs ahead of formal requirements (sequence/flow diagrams, key decisions)
│   ├── actuator_observability.md
│   ├── contact_preferences_and_sms.md
│   ├── login_2fa_sequence.md
│   ├── payment_retry_and_dunning.md
│   ├── provider_catalog_management.md
│   ├── resend_verification_sequence.md
│   ├── staff_authorization_design.md
│   ├── subscription_status_transitions.md
│   └── white_label_branding_and_multipart_email.md
├── .github/workflows/                         # GitHub Actions CI: ./mvnw verify (unit + integration tests)
├── pom.xml, mvnw, .mvn/                       # Maven build and Maven wrapper
├── compose.yaml                               # Local Postgres + Vault (dev mode) + Mailpit
├── subscribe_master_erd.drawio                # Entity-relationship diagram (open in draw.io / app.diagrams.net)
├── ARCHITECTURE.md                            # Design decisions, dev/ops guidance
├── subscribe_master_requirements.md           # Functional/non-functional requirements, with schema coverage status
├── IMPLEMENTATION_BACKLOG.md                  # GitHub issue backlog, wave-based prioritization, sizing
├── COMMON_QUERIES.md                          # Reference SQL for common tasks
├── FUTURE_RELEASES.md                         # Parking lot for out-of-scope features and extensions
└── load_testing_open_questions.md             # Open load/performance-testing questions (not yet a formal NFR)
```

---

## Documentation

| Document | What it covers |
|---|---|
| `ARCHITECTURE.md` | Why each major decision was made, plus what developers and ops/support/audit need to know once the system is live |
| `subscribe_master_requirements.md` | The full functional/non-functional requirements list, with a status column showing what the schema supports today |
| `IMPLEMENTATION_BACKLOG.md` | The GitHub issue backlog: one issue per requirement, sequenced into waves, sized S/M/L |
| `COMMON_QUERIES.md` | Practical SQL for the questions that come up repeatedly against this schema |
| `subscribe_master_erd.drawio` | The entity-relationship diagram, with a legend explaining the color-coded domain sections |
| `design-docs/login_2fa_sequence.md` | Sequence diagram and key decisions for the login/2FA design |
| `design-docs/subscription_status_transitions.md` | Flowchart and key decisions for subscription status transitions and payment-date impact |
| `design-docs/contact_preferences_and_sms.md` | Phone number, preferred contact method, phone verification and SMS (Twilio) — `FR-36`–`FR-39` |
| `design-docs/payment_retry_and_dunning.md` | Payment retry scheduling, backoff and dunning |
| `design-docs/provider_catalog_management.md` | Staff-managed subscription provider catalog |
| `design-docs/staff_authorization_design.md` | Staff authentication and permission-based authorization |
| `design-docs/staff_account_administration.md` | Proposed: staff invitations, password set/reset, role changes and deactivation |
| `design-docs/white_label_branding_and_multipart_email.md` | Draft: per-deployment white-label branding, and the future plain-text → multipart (text + HTML) email refactor |
| `design-docs/actuator_observability.md` | Actuator baseline, health model, metrics contract and observability wave placement — `NFR-36`–`NFR-42` |
| `design-docs/resend_verification_sequence.md` | Sequence and flow diagrams, and the 9 settled decisions, for resending the verification email — `FR-40` |


---

## Tech stack

| Technology | Role |
|---|---|
| Java 21 | Language |
| Maven | Build tool |
| Spring Boot 4.x | Application framework |
| PostgreSQL | Primary database |
| Flyway | Database migrations |
| HashiCorp Vault | Secrets management, all environments including local dev |
| Stripe | Payment processing (planned, Wave 6) |
| Docker / Compose | Local infrastructure |

(Additional libraries — MapStruct, Resilience4j, Apache POI — get added as the relevant features are built; see `IMPLEMENTATION_BACKLOG.md` for when.)

---

## A note on `task.pdf`

This project is referenced throughout its documentation as having originated from a take-home assignment brief, but that brief is intentionally **not included in this repository**. It described a narrower scope with a 3–5 day deadline and an external evaluator — neither of which reflects this project's actual goals. This project has since grown well beyond that original brief as a multi-domain learning exercise, and several requirements (partial refunds, payment retry/dunning, audit logging, distributed tracing, CI/CD, Vault-based secrets management) were added deliberately, beyond what the original brief asked for, because they're what a real commercial subscription-billing system would need.

Where the original brief is cited in other documents in this repo, it's for provenance only (explaining why a requirement is numbered the way it is) — not as a claim that it's still an active specification. The current, authoritative scope is `subscribe_master_requirements.md` plus `ARCHITECTURE.md`.
