# Future Releases — Ideas Napkin

A parking lot for features, and extensions of existing ones, that are **outside
the project's current scope** (everything scheduled in `IMPLEMENTATION_BACKLOG.md`).
These are rough notes, not requirements: no IDs, no sizes, no waves.

**How to use it**
- Add an idea as a short bullet: what it is, why it might matter, and where it
  came from. A date helps.
- Note prerequisites when they're obvious, so the order of things isn't lost.
- When an idea is picked up, give it an `FR`/`NFR` ID in
  `subscribe_master_requirements.md`, schedule it in the backlog, and strike it
  through here with a pointer to the ID (don't delete it — the history of *why*
  is useful).
- *Source* tags: **(you)** = your idea; **(repo)** = already deferred somewhere
  in this repo, with the file; **(suggested)** = suggested in review, keep or
  drop as you see fit.

---

## Architecture

- **Complete the migration to microservices** — **(you)**
  - Split the Wave 4.5 modules into independently deployable services.
  - Already prepared for in places:
    - JWTs are RS256 so verifying services only need the public key
      (`ARCHITECTURE.md` §9).
    - Wave 4.5 enforces module boundaries and partitions the schema
      (`NFR-26`–`NFR-29`).
    - `NFR-42` adds tracing across calls.
  - Would need: a database per service, a message broker, an API gateway,
    service discovery/config, contract tests between services, and a deployment
    platform (`NFR-21`).
- **Message broker + transactional outbox (RabbitMQ or Kafka)** — **(repo:
  `payment_retry_and_dunning.md`)**
  - Deliberately rejected while the app is one deployable; it only becomes
    necessary once modules are split apart.
  - Goes with the microservices item, not before it.
- **Secondary, always-in-sync database for failover** — **(you)**
  - Removes the database as a single point of failure.
  - Effects already noted in `design-docs/actuator_observability.md`:
    - synchronous replication slows every write, so batching matters more;
    - archive files need their own durable storage;
    - ShedLock keeps working after a failover.
- **API gateway with global rate limiting** — **(suggested)**
  - Generalizes the per-account limit `FR-40` adds into one place in front of
    every endpoint.
  - Natural partner to the microservices item.

## Payments

- **Stripe webhooks plus a payment completion page (3D Secure)** — **(repo:
  `payment_retry_and_dunning.md`)**
  - Webhooks alone only fix how outcomes are *recorded*. Letting a customer
    actually complete a challenged payment needs a page they're sent to.
- **Scheduled Stripe reconciliation job** — **(repo: `ARCHITECTURE.md` §4.1,
  §11)**
  - Compare `payment_history` against Stripe on a schedule and resolve
    differences in Stripe's favour, rather than waiting for customers to
    report them.
- **Choice of exchange-rate provider at install** — **(you, 2026-10-23)**
  - Today rates come from one source, cbu.uz (`FR-14`). Offer a list of
    geographically spread providers (e.g. central banks and commercial rate
    APIs for different regions) for the buyer to pick from when they install
    their deployment.
  - Optionally let them plug in their own provider that isn't on the list.
  - **Per deployment, not per customer:** like white-label branding, it's set
    once in configuration.
  - Rough notes on what it would touch:
    - `FR-14`'s client becomes one implementation behind a provider interface,
      with one adapter per listed provider.
    - A "bring your own" option needs a configurable adapter: URL, response
      mapping, and an API key held in Vault (`NFR-20`).
    - `FR-16`'s circuit breaker and fallback, and `FR-17`'s rate-date display,
      must work for every provider.
    - Providers differ in base currency, update frequency and currency
      coverage, so the cache (`FR-15`) and conversions need to handle that.
- **Reactivating a cancelled subscription** — **(repo:
  `subscription_status_transitions.md`)**
  - Today cancellation is final by design: coming back means a new
    subscription. A real reactivation flow (keeping history on one row) would
    be a new feature.

## Messaging and notifications

- **Verification email reliability** — **(repo: tabled 2026-10-19, see
  `NFR-34`)**
  - Ideas set aside:
    - automatic retry of failed sends;
    - a staff "resend" button;
    - `FR-32` auditing the expired accounts it deletes, and judging expiry by
      the newest token;
    - dashboard metrics for send-failure and dropped-account rates (partly
      covered by `NFR-40`).
  - Decisions already made so they aren't re-argued:
    - No broker.
    - If retry is built, first compare Spring Modulith's event publication
      registry with JobRunr.
    - Queue only the customer id, and issue a fresh token at send time.
- **SMS follow-ups** — **(repo: `contact_preferences_and_sms.md`)**
  - Twilio delivery-status webhooks (today we know "sent", not "delivered").
  - Handling STOP / opt-out replies.
  - SMS consent wording and a compliance review before any real launch.
- **White-label branding and HTML email** — **(repo:
  `white_label_branding_and_multipart_email.md`)**
  - A buyer's deployment shows *their* brand; email moves from plain text to
    text + HTML.
  - Drafted but not yet a requirement. The draft suggests Wave 8, so this may be
    pulled into current scope rather than a future release.
- **Push notifications as a third channel** — **(suggested)**
  - Another `ChannelSender` beside email and SMS, if a mobile app ever exists.

## Observability and operations

- **Move application logs to a log aggregator (e.g. Loki)** — **(repo:
  `ARCHITECTURE.md` §6.2)**
  - `NFR-43` keeps `app_logs` as an optional table with archiving; once volume
    grows, logs belong in a dedicated tool instead.
- **Auto-reset for runtime log levels** — **(repo:
  `actuator_observability.md`, open decision 7)**
  - A logger raised to DEBUG resets itself after a set time instead of staying
    there until someone notices.
- **Single sign-on for staff (OIDC, e.g. Keycloak or Okta)** — **(repo:
  `staff_users.password_hash` is nullable "for future SSO-only accounts")**
  - Also gives each operator their own identity, which matters once more than
    one person runs the system.
  - **Large:** it replaces how `FR-06` issues staff tokens (today the app signs
    its own, with permissions loaded from the database). Map the identity
    provider's user id to `staff_users.id` so `audit_logs` stays consistent.
  - Pays off in several places at once: Spring Boot Admin (option 4 below),
    microservices (one login for every service), and SSO-only staff accounts.
- **Spring Boot Admin (SBA) as the operations console** — **(you + suggested,
  2026-10-23)**
  - **What it is:** an open-source admin server (by codecentric), a separate
    small Spring Boot app with a web UI over Actuator:
    - health, info, metrics, log levels (view and change), environment,
      threads, scheduled tasks, Flyway history, caches, Actuator audit events,
      JMX (via Jolokia);
    - finds instances by a client library or by Spring Cloud discovery
      (Eureka, Consul, Zookeeper, Kubernetes);
    - sends status-change alerts (email, Slack, Teams, PagerDuty, Opsgenie,
      Telegram, Discord…);
    - its login uses any Spring Security method;
    - custom screens are possible but written in Vue/JavaScript.
  - **Not for business actions:** catalog, refunds and staff/role management
    stay in the staff API.
  - **Decided for now: option 1.** Keep `FR-41` (status) and `FR-42` (log
    levels, audited, saved) as custom staff API endpoints. Low regret: if SBA is
    adopted later, `FR-42`'s audit-and-save logic is what SBA's audit hook would
    call, so only `FR-41` (~4h) is discarded.
  - **The critical gap:** SBA calls the app as *itself*, and has its own
    logins. Out of the box, `audit_logs` can't record which staff member changed
    a log level, and changes reset on restart and may reach only one instance.

  | | 1. Current plan (chosen) | 2. SBA read-only | 3. SBA + staff token passed through | 4. SBA + single sign-on |
  |---|---|---|---|---|
  | What you get | `FR-41`/`FR-42` as staff API endpoints | SBA replaces `FR-41`; `FR-42` stays custom | SBA UI for status and log changes, audited as the staff member | As 3, staff log in through a shared identity provider |
  | Effort | ~16h (planned) | ~+8h net | ~+8 to +20h net | +36h or more |
  | Pros | Smallest; nothing wasted later | Off-the-shelf UI and alerts; no identity work | Off-the-shelf UI; custom code only closes the gap; audit stays consistent | Most standard; single sign-on pays off elsewhere |
  | Cons | No UI (Swagger/curl) | Another app to run; view in SBA, change via API; more endpoints exposed | Most moving parts; needs `FR-06` + `FR-05` | Reworks `FR-06`; too big for SBA alone |
  | Risk | Low | Low | Medium (spike first) | High today |

  - **How option 3 would work:**
    1. A small SBA login provider calls `FR-06`'s staff login and keeps the
       staff token.
    2. SBA forwards *that* token when the user acts.
    3. The management port checks it with the existing RS256 public key.
    4. An audit hook on Actuator's `loggers` writes `actor_type = staff` with
       old → new levels, and saves the change through `FR-42`'s logic.
  - **What a spike for option 3 must prove:**
    - a separate service credential for SBA's own background polling;
    - 15-minute token expiry versus a longer SBA session (needs `FR-05`
      refresh);
    - forwarding the current user's token in SBA's reactive request
      pipeline;
    - an SBA version that supports Spring Boot 4.
  - **Also needed for any SBA option:** expose more read-only Actuator
    endpoints to SBA than `NFR-36`'s `health`/`info`, with
    `show-values: never` on `env`/`configprops`.
  - **Revisit when any of these is true:**
    - the app is deployed (`NFR-21`) and actually operated;
    - there's more than one instance or more than one operator;
    - staff single sign-on is adopted (option 4's cost is then mostly paid);
    - a UI for operations becomes wanted.
- **Load testing** — **(repo: `load_testing_open_questions.md`)**
  - Still open questions rather than a requirement. `NFR-39`/`NFR-40` provide
    the visibility it was waiting on.
- **Alert delivery (email, chat, paging)** — **(suggested)**
  - `NFR-32` creates Grafana alert rules; deciding where alerts actually go,
    and who gets woken up, is a separate step.
- **Tamper-evident audit log** — **(suggested)**
  - Chain each `audit_logs` row to the previous one with a hash, so a deleted or
    edited row is detectable.
  - Goes beyond today's integrity check in `ARCHITECTURE.md` §11.

## Product

- **A user interface (web and/or mobile)** — **(suggested)**
  - The project is API-only. Several docs already note what a UI would need,
    e.g. a confirmation step before cancelling a subscription
    (`subscription_status_transitions.md`).
- **Customer data export and account deletion** — **(suggested)**
  - Let a customer download their data and close their account, with
    financial records kept as the law requires (`payment_history` is
    intentionally never deleted).

## Small parked questions

- **Provider catalog** — **(repo: `provider_catalog_management.md`)**
  - Can a deactivated provider be reactivated, or must a new one be created?
  - Should renaming a provider re-check that the name is unique among active
    providers?
  - Both are low-stakes and undecided.

---

*Started 2026-10-23. Add to it freely — rough notes are the point.*
