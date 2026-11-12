# Staff Account Administration — Proposed Design

Covers `FR-45` (staff account administration) and `FR-46` (staff password
set and reset). Both close a gap found on 2026-11-12, during `FR-06`: once
the bootstrap admin exists, there's no way to add another staff member,
deactivate one, change a role or reset a password, short of SQL run by hand
against the database. `FR-06` already enforces `is_active` on every staff
request, but nothing can change it. V1.1 seeds the `manage_staff`
permission (granted to `admin`), and V1 has `staff_password_reset_tokens`,
but no requirement used either. **Not sourced from `task.pdf`.**

**Status: proposed.** These are recommendations, not settled decisions.
Confirm or change each one when `FR-46` is picked up, then mark it DECIDED,
as `staff_authorization_design.md` does for `FR-06`.

## Scheduling

Both go in **Wave 5.7**, a new wave between 5.5 and 6:

- **After Wave 4.5**, so they're built inside the module boundaries from
  their first line, and their success audits use `NFR-45`'s after-commit
  pattern from the start rather than being retrofitted.
- **Before Wave 6**, which holds the first real deployment (`NFR-21`). A
  deployed system with one admin and no way to add staff isn't usable.
- **Before Wave 7**, whose `FR-28` (refunds) needs a `billing_admin` and
  whose diagnostics are for `auditor` staff. Neither role can be given to
  anyone until `FR-45` exists.

`FR-46` comes first: the invitation in `FR-45` sends a set-password link,
and that link needs `FR-46`'s endpoint to land on. `FR-46` can be tested
alone with the bootstrap admin, through the forgot-password flow.

## PROPOSED: invitation by one-time link, never a temporary password

An admin creates a staff member with an email and a role. The account is
saved with `password_hash = NULL` (the column is already nullable) and an
email goes out with a one-time link to set a password.

- **Why not a temporary password:** it has to be sent or read out
  somewhere, it's usable by whoever sees it, and people rarely change it
  promptly. A link expires, works once and is never stored in plain text.
- **Logging in before setting a password fails like a wrong password.** A
  `NULL` hash never matches, so `StaffCredentialChecker` returns the same
  generic 401. No special case is needed.
- The link expires after `app.staff.invitation.token-ttl` (proposed 48h).
  An admin can send a fresh one; the earlier link stops working.

## PROPOSED: one token flow for invitation, forgotten password and admin reset

All three end the same way: the person proves they received an email and
chooses a password. So they share one mechanism, built on the existing
`staff_password_reset_tokens` table (hashed token, `expires_at`,
`used_at`), following the pattern of `customer_email_verification_tokens`.

- `POST /api/v1/staff/auth/password` with `{token, newPassword}` sets the
  password. It's single-use. Success clears the lockout counters, so a
  locked-out staff member can recover by resetting.
- `POST /api/v1/staff/auth/password/forgot` with `{email}` always returns
  the same 202, whether or not the email belongs to an active staff
  member, so it can't be used to find out which staff emails exist. It
  needs a resend limit, like `FR-40`'s.
- `POST /api/v1/staff/users/{id}/password-reset` lets an admin send the
  link on someone's behalf. The admin never sees or chooses the password.
- Issuing a new token invalidates earlier unused ones for that person.
- Deactivated accounts get no link, and a link can't reactivate an
  account.

## PROPOSED: the administration endpoints

All under `/api/v1/staff/users`, behind `hasAuthority('manage_staff')`:

| Endpoint | What it does |
|---|---|
| `POST /` | Create with email and role; sends the invitation |
| `GET /` | List, paginated, filterable by role and active/inactive |
| `GET /{id}` | One staff member |
| `PATCH /{id}/role` | Change role |
| `POST /{id}/deactivate` | Deactivate; takes effect on that person's next request |
| `POST /{id}/reactivate` | Reactivate |
| `POST /{id}/invitation` | Send a fresh invitation to someone who hasn't set a password |

- **Deactivate, never delete.** Audit rows point at `staff_users.id`, so
  the row must stay. This matches `FR-35`'s soft delete.
- Responses never include `password_hash`, and creating a staff member
  never accepts one.

## PROPOSED: nobody can lock everyone out

- An admin can't deactivate themselves or change their own role.
- Deactivating, or changing the role of, the **last active `admin`** is
  refused with 409.
- Two admins demoting each other at the same moment could both pass a
  simple count check and leave no admin. The check therefore locks the
  active admin rows first (`@Lock(PESSIMISTIC_WRITE)` on a derived query,
  allowed under `NFR-16`) in the same transaction as the change.

## PROPOSED: auditing

Every change is audited with the acting staff member as actor
(`ActorType.STAFF`) and the affected staff member as target, using
`NFR-41`'s `details`. State changes use `NFR-45`'s after-commit pattern.

| Action | `details` |
|---|---|
| `STAFF_USER_CREATED` (exists since V13) | `{"source": "admin", "role": ...}` (the bootstrap admin uses `{"source": "bootstrap"}`) |
| `STAFF_USER_DEACTIVATED` | none |
| `STAFF_USER_REACTIVATED` | none |
| `STAFF_ROLE_CHANGED` | `{"from": ..., "to": ...}` |
| `STAFF_INVITATION_SENT` | none |
| `STAFF_PASSWORD_RESET_REQUESTED` | `{"by": "self"}` or `{"by": "admin"}` |
| `STAFF_PASSWORD_SET` | none |

The new actions need a migration widening `audit_logs_action_check`, as
V11 and V13 did. A forgot-password request for an unknown email isn't
audited: there's no staff row to attach it to (the same deferred gap as
login).

## Out of scope

- **Editing roles and permissions.** V1.1 also seeds `manage_roles`, but
  the four seeded roles cover the known needs. Revisit if a fifth role is
  needed.
- **SSO-only staff accounts.** `FUTURE_RELEASES.md` already tracks them;
  the nullable `password_hash` leaves room.
- **`staff_sessions`.** The table predates the JWT pivot and nothing uses
  it. Dropping it is a separate clean-up, not part of these features.
- **Customer password reset.** `customer_password_reset_tokens` exists, but
  no requirement covers a customer flow yet. It's tracked separately, not
  folded in here.
