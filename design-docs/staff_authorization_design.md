# Staff Authentication & Permission-Based Authorization — Design Decisions

Captures the design decisions made for `FR-06` (role-based access control for
staff), ahead of implementing staff login, staff JWT issuance, and the
mechanism that turns the already-seeded `roles`/`permissions`/`role_permissions`
schema into actual enforced behavior. Supersedes prior in-chat discussion —
this is the single source of truth going forward.

## Current state — confirmed by reading the code, not assumed

As of this writing, staff authentication and authorization do not exist
anywhere in the running application:

- No `StaffController`, no staff login endpoint, no staff JWT issuance.
  `JwtIssuer.issue(UUID customerId)` only ever issues tokens for customers.
- Zero `@PreAuthorize`/`hasRole`/`hasAuthority`/`PermissionEvaluator` usage
  anywhere in `src/main/java`.
- `SecurityConfig`'s filter chain makes exactly one distinction: public
  (`/api/v1/auth/**`, docs) vs. "any authenticated principal." Its own
  Javadoc acknowledges this was a deliberate placeholder ("there's no real
  authenticated endpoint to protect yet") written early in the project and
  never revisited as real authenticated endpoints were built on top of it.
- The `roles` → `role_permissions` → `permissions` schema (V1/V1.1) is fully
  seeded (`admin`, `support_agent`, `billing_admin`, `auditor`, each with a
  distinct, non-overlapping permission set per `ARCHITECTURE.md`), but
  nothing in the application reads it. It's a correct design sitting idle.

**This is not limited to the feature that surfaced it** (`FR-35`, staff
provider-catalog management — see `provider_catalog_management.md`). It's a
cross-cutting gap affecting every staff-differentiated feature already in
the backlog, most concretely `FR-28` (partial refunds, Wave 7) — `billing_admin`
"can issue refunds" per `ARCHITECTURE.md`'s role description, but that
means nothing until this mechanism exists. `FR-28`'s backlog entry should
carry an explicit "blocked on `FR-06`" note, the same way `FR-33` noted its
block on `FR-30` (since resolved).

## DECIDED: permission-based checks, not hardcoded role checks

Matches `ARCHITECTURE.md`'s own stated principle directly: *"permission
checks should be data-driven... not `if (role == "ADMIN")` conditionals
scattered through the codebase."* Concretely:

- Endpoints check a specific permission (e.g. `hasAuthority('some_permission')`),
  never a role name.
- Granting/revoking access to a feature is a `role_permissions` data change,
  not a code change or redeploy.
- This project's first staff-authorized endpoint sets the pattern every
  later one will copy — worth getting right here rather than retrofitting
  after several endpoints have already taken the shortcut.

**Rejected:** hardcoded role checks (`staffUser.getRole().getName().equals("admin")`,
or Spring Security's `hasRole(...)` keyed to a role name). Faster to ship,
but directly contradicts the documented principle, and ignores schema
that's already built and paid for.

## Scope of this FR

`FR-06` builds the *mechanism* — staff login, staff JWT issuance, loading a
staff user's permissions into the security context, and the Spring Security
wiring so `hasAuthority(...)`-style checks actually evaluate against that
data. It does not itself define which permissions exist beyond what's
needed to prove the mechanism works — individual features (starting with
`FR-35`) define and seed their own permissions.

The implementation-time questions this section used to leave open were
settled on 2026-11-09, and are recorded in the four sections that follow.

## DECIDED: permissions are looked up per request, not carried in the token

The staff chain's JWT authentication converter reads the token's `sub`
(the `staff_users.id`) and runs one JPQL query. The query returns the
names of that user's permissions through `role` → `role_permissions` →
`permissions`, and only if `is_active = true`. Each name becomes a plain
`GrantedAuthority`, with no `ROLE_`/`SCOPE_` prefix, so endpoints check
`hasAuthority('view_audit_logs')`. The JWT itself carries no permissions.

- **Why:** revoking a permission (a `role_permissions` change) or
  deactivating a staff member takes effect on that person's very next
  request. With permissions as claims, both would keep working until the
  token expired, up to `app.jwt.access-token-duration-minutes` (15). Staff
  accounts are the most privileged in the system, so that window matters
  more than it would for customers.
- **Cost accepted:** one indexed query per staff request. Staff traffic is
  tiny compared with customer traffic. A short cache can be added later if
  measurements ever call for it, without changing any endpoint.
- **Inactive or deleted staff:** an empty result is treated as an invalid
  token (401), not as an authenticated user with no permissions (403).
- **Rejected:** permissions as JWT claims. They avoid the query, but leave
  the staleness window above, and they make "access changes are a data
  change" true only after a delay.
- Follows NFR-15 (a JPQL fetch query, no eager associations) and NFR-16
  (no native SQL).

## DECIDED: staff and customer tokens are told apart by `aud`, with two filter chains

A staff JWT and a customer JWT are signed with the same key. Before this
decision, a staff token would have passed the customer chain, and its `sub`
would have been read as a customer id on `/api/v1/subscribe/**`. Two
changes close that gap:

- `JwtIssuer` sets `aud`: `customer-api` on customer tokens, `staff-api` on
  staff tokens (a new `issueStaff(UUID)` method).
- `SecurityConfig` has two `SecurityFilterChain`s:
  - The **staff chain** is ordered first, matches `/api/v1/staff/**`, and
    permits only `/api/v1/staff/auth/**` without a token. Its decoder
    requires `aud = staff-api`, and it uses the converter described above.
  - The **customer chain** is the existing one. Its decoder now requires
    `aud = customer-api`, and its behaviour is otherwise unchanged.
- `@EnableMethodSecurity` is turned on, so staff endpoints can use
  `@PreAuthorize("hasAuthority('...')")`.
- **Deploy impact, accepted:** customer tokens issued before the deploy
  have no `aud`, so they are rejected and those customers must log in
  again. Tokens last at most 15 minutes.
- **Rejected:** a custom `principal_type` claim. It does the same job, but
  it's non-standard and its validator would be hand-written; `aud` is the
  registered claim built for exactly this purpose.
- NFR-36's actuator chain will sit alongside these two later, on its own
  management port.

## DECIDED: staff login has its own, stricter lockout

`staff_users` had no lockout columns. `V13` adds `failed_login_attempts`
and `locked_until`, matching `customers`. A `StaffCredentialChecker`
mirrors `CredentialChecker`, with the same `REQUIRES_NEW` /
`noRollbackFor` handling, unified credential errors and live lock-expiry
check.

It reads its own settings, `app.staff.login-lockout.*`: 3 attempts and 10
minutes, against the customer's 5 and 15. (This was revised on 2026-11-09;
the first decision reused the customer settings. The duration was lowered
from 15 to 10 minutes on 2026-11-11.)

The lock is shorter than the customer's, but staff still get fewer guesses
over time: at most 3 per 10 minutes (18 an hour), against 5 per 15 minutes
(20 an hour) for customers. The fewer attempts per lock are what make it
stricter, and the shorter lock means a staff member who mistypes is back in
sooner.

- **Why it's in FR-06's scope:** shipping the most privileged login in the
  system with weaker brute-force protection than customer login would be a
  regression against FR-02.
- **Why separate and stricter:** staff accounts can reach every customer's
  data and issue refunds, so fewer guesses are allowed. A staff member who
  mistypes three times is also far easier to help than a locked-out
  customer. Separate keys also mean tuning one population can't change the
  other by accident.
- **Why it's a separate class:** the repository and actor type differ.
  Extracting a shared abstraction can wait until a third caller appears.
- **Audit:** a staff lockout reuses `account_locked`, with
  `actor_type = 'staff'` telling it apart. A successful staff login is
  audited as `staff_logged_in`, unlike customer login, because staff
  access is privileged and rare enough that the record is useful rather
  than noise. `V13` widens `audit_logs_action_check` to allow it.

## DECIDED: the first admin is created from configuration at startup

No staff users are seeded, and creating staff (`manage_staff`) is a later
feature. When the application starts, a bootstrap runner checks two
things: is `staff_users` empty, and are both
`app.staff.bootstrap-admin.email` and `app.staff.bootstrap-admin.password`
set (supplied from Vault, never committed)? If both are true, it creates
one `admin` staff user with a BCrypt hash of that password. Otherwise it
does nothing, so it's safe on every restart and every instance.

- Audited as `staff_user_created`. The actor is the new admin's own id,
  since `actor_id` is NOT NULL and there's no system actor type, and
  `details` holds `{"source": "bootstrap"}`.
- Both keys live only in Vault at `secret/subscribe-master`, never in
  `application*.yaml`. The local dev values are in README "Getting
  started", step 5. If only one is set, startup fails with a clear error
  rather than guessing what was meant.
- **Rejected:** a Flyway seed with a password hash. That would put a
  credential in version control and give every environment the same admin
  password.
- **Rejected:** a dev-profile-only seed. It leaves no way to create a
  production admin until `manage_staff` exists.

## Proving the mechanism

FR-06 adds one real endpoint, `GET /api/v1/staff/me`, which returns the
caller's email, role and permissions. It requires authentication but no
particular permission. The 403 path is proved by a test-only controller
that uses `@PreAuthorize("hasAuthority('view_audit_logs')")`. The
integration tests cover:

- staff login, and the lockout triggering
- a customer token on a staff path returning 401, and a staff token on a
  customer path returning 401
- a missing permission returning 403, and a granted one returning 200
- a `role_permissions` change taking effect without a new token
- a deactivated staff user returning 401

`FR-35` is the first production endpoint to use `hasAuthority(...)`.

## Sizing note

Originally listed in `IMPLEMENTATION_BACKLOG.md` Wave 2 as `[M]`. Revised to
`[L]` — the original estimate covered "add role checks" in the abstract;
what's actually needed (staff login endpoint, staff JWT issuance, permission
loading, Spring Security integration, tests for all of it) is a bounded but
genuinely larger scope, closer to `FR-02`'s own login work than to a
one-day addition.

## Scheduling

Wave 1 closed 2026-11-05 and Wave 3 is complete, so Wave 2 is next. Within
it, `FR-35` is built immediately after `FR-06` — deliberately chosen as the
first real consumer of this mechanism, since it's low-stakes (catalog
metadata, no financial risk) compared to `FR-28` being the first thing to
exercise brand-new staff-auth infrastructure. See
`provider_catalog_management.md` for that design in full.
