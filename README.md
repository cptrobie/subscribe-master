# Subscribe Master

A backend system that consolidates a customer's paid subscriptions (Netflix, Spotify, Apple TV+, etc.) into one place, tracks spending in a customer-chosen base currency using real-time exchange rates, schedules and processes payments via Stripe, and sends renewal reminders before a payment is due.

This project started from a take-home assignment brief and has since grown into a broader exercise in applying enterprise-level backend patterns — RBAC, optimistic locking, audit logging, distributed tracing, dunning/retry logic, secrets management — across a single realistic application. See **[A note on `task.pdf`](#a-note-on-taskpdf)** below for that history.

---

## Status

This project is in early setup (**Wave 0 — Project bootstrap**, see `IMPLEMENTATION_BACKLOG.md`). No application code exists yet — what's currently in the repo is the design phase output: the database schema, ERD, and planning documents, plus the local infrastructure needed to start building against.

**Currently runnable:** local Postgres + Vault (dev mode), and the Flyway migrations against them.
**Not yet runnable:** the application itself — there's no Spring Boot project in this repo yet.

This section will be updated as Wave 0 progresses.

---

## Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| **Java 17** | Building/running the application | Once the project exists (Wave 0, step 3) |
| **Maven** | Build tool | |
| **Docker Desktop** | Local Postgres + Vault | Required now — see Getting Started |
| **IntelliJ IDEA** | Development | Recommended, not required |
| **Flyway CLI** | Validating migrations independently of the app | Recommended — lets you run `flyway migrate` against local Postgres before the app exists |
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
   This starts Postgres (`localhost:5432`) and Vault in dev mode (`localhost:8200`, root token: `local-dev-root-token` — see `docker-compose.yml` for why dev mode is safe to hardcode locally but nowhere else).
3. **Run the database migrations:**
   ```
   flyway -url=jdbc:postgresql://localhost:5432/subscribe_master \
          -user=subscribe_master -password=local_dev_only_not_a_real_secret \
          -locations=filesystem:migrations \
          migrate
   ```
   This applies every migration in `migrations/` in order (`V1`, `V1.1`, `V2`–`V4`, `R__seed_subscription_provider_catalog`, `V6`) and seeds the roles/permissions and subscription provider catalog.
4. **Inspect the result** in pgAdmin 4, or `psql`, to confirm the schema and seed data landed as expected.
5. *(Once the application project exists)* — this section will be extended with build/run instructions.

---

## Project structure

```
.
├── migrations/                          # Flyway schema + seed migrations (source of truth for the DB)
├── subscribe_master_erd.drawio          # Entity-relationship diagram (open in draw.io / app.diagrams.net)
├── docker-compose.yml                   # Local Postgres + Vault (dev mode)
├── ARCHITECTURE.md                      # Design decisions, dev/ops guidance
├── subscribe_master_requirements.md     # Functional/non-functional requirements, with schema coverage status
├── IMPLEMENTATION_BACKLOG.md            # GitHub issue backlog, wave-based prioritization, sizing
├── COMMON_QUERIES.md                    # Reference SQL for common tasks
├── erd_design_notes.md                  # Design rationale: auth/authz
├── subscription_erd_design_notes.md     # Design rationale: subscriptions, currency, Stripe
├── billing_retry_refund_design_notes.md # Design rationale: retry/dunning, partial refunds
└── gap_closure_design_notes.md          # Design rationale: requirement gap closures
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
| `erd_design_notes.md`, `subscription_erd_design_notes.md`, `billing_retry_refund_design_notes.md`, `gap_closure_design_notes.md` | The design history behind specific decisions, if you want the full reasoning rather than the summary in `ARCHITECTURE.md` |

---

## Tech stack

| Technology | Role |
|---|---|
| Java 17 | Language |
| Maven | Build tool |
| Spring Boot 3.x | Application framework |
| PostgreSQL | Primary database |
| Flyway | Database migrations |
| HashiCorp Vault | Secrets management, all environments including local dev |
| Stripe | Payment processing |
| Docker / Docker Compose | Local infrastructure |

(Additional libraries — MapStruct, Resilience4j, Apache POI, springdoc-openapi — get added as the relevant features are built; see `IMPLEMENTATION_BACKLOG.md` for when.)

---

## A note on `task.pdf`

This project is referenced throughout its documentation as having originated from a take-home assignment brief, but that brief is intentionally **not included in this repository**. It described a narrower scope with a 3–5 day deadline and an external evaluator — neither of which reflects this project's actual goals. This project has since grown well beyond that original brief as a multi-domain learning exercise, and several requirements (partial refunds, payment retry/dunning, audit logging, distributed tracing, CI/CD, Vault-based secrets management) were added deliberately, beyond what the original brief asked for, because they're what a real commercial subscription-billing system would need.

Where the original brief is cited in other documents in this repo, it's for provenance only (explaining why a requirement is numbered the way it is) — not as a claim that it's still an active specification. The current, authoritative scope is `subscribe_master_requirements.md` plus `ARCHITECTURE.md`.
