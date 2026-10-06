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
carry an explicit "blocked on `FR-06`" note, the same way `FR-33` already
notes its own block on `FR-30`.

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

**Not yet decided (implementation-time question):** whether permissions are
embedded as claims in the staff JWT at issuance time, or looked up per-request
against `role_permissions`. Either satisfies the "data-driven, not hardcoded"
principle; the tradeoff (token size / staleness vs. a DB lookup per request)
is worth weighing once this is actually being built, not before.

## Sizing note

Originally listed in `IMPLEMENTATION_BACKLOG.md` Wave 2 as `[M]`. Revised to
`[L]` — the original estimate covered "add role checks" in the abstract;
what's actually needed (staff login endpoint, staff JWT issuance, permission
loading, Spring Security integration, tests for all of it) is a bounded but
genuinely larger scope, closer to `FR-02`'s own login work than to a
one-day addition.

## Scheduling

Per the project's actual current sequencing (Wave 3 in progress, Wave 1 not
yet 100% closed — `FR-30`/`FR-31` deliberately deferred — Wave 2 bypassed
in the meantime): the intended order is finish Wave 3, close out Wave 1,
*then* Wave 2, with `FR-35` built immediately after `FR-06` — deliberately
chosen as the first real consumer of this mechanism, since it's low-stakes
(catalog metadata, no financial risk) compared to `FR-28` being the first
thing to exercise brand-new staff-auth infrastructure. See
`provider_catalog_management.md` for that design in full.
