# Subscribe Master

A backend system that consolidates a customer's paid subscriptions (Netflix, Spotify, Apple TV+, etc.) into one place, tracks spending in a customer-chosen base currency using real-time exchange rates, schedules and processes payments via Stripe, and sends renewal reminders before a payment is due.

This project started from a take-home assignment brief and has since grown into a broader exercise in applying enterprise-level backend patterns — RBAC, optimistic locking, audit logging, distributed tracing, dunning/retry logic, secrets management — across a single realistic application. See **[A note on `task.pdf`](#a-note-on-taskpdf)** below for that history.

---

## Status

This project is in early setup (Wave 0 — Project bootstrap, see `IMPLEMENTATION_BACKLOG.md`). 

The Maven/Spring Boot project scaffold exists (`pom.xml`, `src/`, a main `@SpringBootApplication` class) with the core dependencies added (JPA, PostgreSQL, Flyway, Vault), but no actual business logic (entities, controllers, services) has been written yet, and the Flyway migration files haven't been added to the repo yet either.

**Currently runnable:** local Postgres + Vault (dev mode) via `docker-compose.yml`. **Not yet functionally runnable:** the application boots to an empty shell at best — there's no business logic, and the migrations aren't in the repo to validate the database against yet.

This section will be updated as Wave 0 progresses.

---

## Prerequisites

| Tool | Needed for | Notes |
|---|---|---|
| **Java 21** | Building/running the application | |
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
          -locations=filesystem:src/main/resources/db/migration \
          migrate
   ```
   This applies every migration in `src/main/resources/db/migration/` in order (`V1`, `V1.1`, `V2`–`V4`, `R__seed_subscription_provider_catalog`, `V6`) and seeds the roles/permissions and subscription provider catalog.
4. **Inspect the result** in pgAdmin 4, or `psql`, to confirm the schema and seed data landed as expected.
5. **Seed Vault with the database credentials** the app will read at startup. Either method works — the CLI, if installed:

   ```
      vault login -address=http://localhost:8200 local-dev-root-token
      vault kv put -address=http://localhost:8200 secret/subscribe-master \
        spring.datasource.username=subscribe_master \
        spring.datasource.password=local_dev_only_not_a_real_secret
   ```
   Or via the browser UI at `http://localhost:8200` — sign in with `local-dev-root-token`, go to **Secrets Engines → secret/ → Create secret**, set the path to `subscribe-master`, and add the same two key/value pairs.

   Without this step, the app fails to start — Vault's dev-mode container starts empty; nothing is seeded into it automatically just because Postgres itself is running.
6. *(Once the application is otherwise runnable)* — this section will be extended with build/run instructions.

---

## Project structure

```
.
├── src/main/resources/db/migration/     # Flyway schema + seed migrations (source of truth for the DB)
├── subscribe_master_erd.drawio          # Entity-relationship diagram (open in draw.io / app.diagrams.net)
├── docker-compose.yml                   # Local Postgres + Vault (dev mode)
├── ARCHITECTURE.md                      # Design decisions, dev/ops guidance
├── subscribe_master_requirements.md     # Functional/non-functional requirements, with schema coverage status
├── IMPLEMENTATION_BACKLOG.md            # GitHub issue backlog, wave-based prioritization, sizing
└── COMMON_QUERIES.md                    # Reference SQL for common tasks
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
| Stripe | Payment processing |
| Docker / Docker Compose | Local infrastructure |

(Additional libraries — MapStruct, Resilience4j, Apache POI, springdoc-openapi — get added as the relevant features are built; see `IMPLEMENTATION_BACKLOG.md` for when.)

---

## A note on `task.pdf`

This project is referenced throughout its documentation as having originated from a take-home assignment brief, but that brief is intentionally **not included in this repository**. It described a narrower scope with a 3–5 day deadline and an external evaluator — neither of which reflects this project's actual goals. This project has since grown well beyond that original brief as a multi-domain learning exercise, and several requirements (partial refunds, payment retry/dunning, audit logging, distributed tracing, CI/CD, Vault-based secrets management) were added deliberately, beyond what the original brief asked for, because they're what a real commercial subscription-billing system would need.

Where the original brief is cited in other documents in this repo, it's for provenance only (explaining why a requirement is numbered the way it is) — not as a claim that it's still an active specification. The current, authoritative scope is `subscribe_master_requirements.md` plus `ARCHITECTURE.md`.
