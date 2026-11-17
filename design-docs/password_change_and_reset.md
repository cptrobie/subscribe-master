# Customer Password Change and Reset (`FR-47`, `FR-48`, `FR-49`) — Design Decisions

Scope settled on 2026-11-17. The formal requirements are in
`subscribe_master_requirements.md`, and wave placement is in
`IMPLEMENTATION_BACKLOG.md`. This records what was decided before any code
exists. The details marked **OPEN** are settled when each issue is picked up.

Staff passwords are a separate requirement: `FR-46` (staff password set and
reset), designed in `staff_account_administration.md`.

*(First merged in PR #158 as `FR-45`–`FR-47`, numbers already used by the
staff administration requirements. Renumbered to `FR-47`–`FR-49` the same
day.)*

## The requirement set

| ID | What | Size | Wave | Depends on |
|---|---|---|---|---|
| `FR-47` | Customer changes their own password while logged in | S | 5.7 | `FR-05` |
| `FR-48` | Customer forgot/reset password by email | M | 5.7 | `FR-05`, `FR-30` |
| `FR-49` | Staff-assisted password reset for a customer, locked or not | M | 8 | `FR-48`, `FR-06` |

**Why three requirements, not two.** `FR-47` and `FR-48` are different flows.
One is logged in and checks the current password. The other is not logged in,
uses an emailed token and has to avoid revealing which accounts exist. Splitting
them keeps hours mapped one-to-one to issues, the same reason `FR-40` was split
out of `FR-30`. `FR-49` is a different actor behind a permission check, and it
waits on `FR-06`.

## What already exists

- **`customer_password_reset_tokens` (`V1`):** hashed, expiring, single-use
  (`used_at`). `FR-48` needs no new table.
- **`edit_customers` permission (`V1.1`):** granted to `support_agent` and
  `admin`. `FR-49` needs no new permission.
- **Customer lockout columns:** `failed_login_attempts` and `locked_until`.
- **`FR-46`'s staff reset flow (Wave 5.7):** the same shape on
  `staff_password_reset_tokens`. Build `FR-48` to match it where the two
  audiences don't differ.

## DECIDED: a logged-in change needs the current password (`FR-47`)

The access token alone is not enough. A stolen or left-open session should not
be able to take over the account by changing its password.

- On success, the customer's refresh tokens (`FR-05`) are revoked, so other
  sessions end when their 15-minute access token expires.
- Audited as `PASSWORD_CHANGED`. `details` never holds password values or
  hashes.

## DECIDED: reset links go only to the profile email (`FR-48`)

- The request takes an email address and always returns the same response,
  whether or not an account exists, so it can't be used to find accounts.
- Rate-limited per account, like `FR-40`.
- The link is sent only to the email already stored on the customer's profile.
  A request can never send it somewhere else.

## DECIDED: completing a reset clears any lockout (`FR-48`)

Proving control of the profile email is stronger evidence than the failed
attempts that caused the lock. A completed reset sets `failed_login_attempts`
to 0 and clears `locked_until`. It also revokes the customer's refresh tokens.
`FR-46` does the same for staff.

## DECIDED: a reset verifies an unverified email (`FR-48`)

The link reached the profile email, which is the same proof `FR-30`'s
verification link gives. Completing a reset on an unverified account sets
`email_verified = true`, so the customer can log in straight away (`FR-31`).

## DECIDED: staff send a reset link and nothing more (`FR-49`)

- Gated by the existing `edit_customers` permission.
- Staff trigger `FR-48`'s flow for a customer. The link goes to that customer's
  profile email. Staff can't choose another address.
- Staff never set, see or receive a password, and there are no temporary
  passwords. That keeps the credential known only to the customer.
- Works whether or not the account is locked. The lockout clears when the
  customer completes the reset, not when staff send the link.
- Audited with the staff member as actor (`staff_users.id`) and the customer as
  the subject.

## OPEN: details to settle when each issue is picked up

- Endpoint paths and request/response shapes, and `Idempotency-Key` handling
  (`NFR-24`).
- Reset token lifetime, and whether a new reset request invalidates older
  unused tokens (as `FR-40` does for verification tokens, and `FR-46` proposes
  for staff).
- Rate-limit numbers, and whether staff sends from `FR-49` count toward the
  customer's limit.
- Password policy (length and so on), shared with registration.
- Audit action names and the migration widening `audit_logs_action_check`.
- Once `FR-33`/`FR-34` exist (Wave 5.5): a reset does not bypass 2FA. The next
  login still asks for the code. Confirm whether a password change should also
  be confirmed by a code when 2FA is on.
- Whether to notify the customer by email after a successful change or reset.

## Explicitly deferred

- **Staff changing their own password while logged in.** `FR-46` covers staff
  invitation, forgotten password and admin-triggered reset, but not a
  logged-in change. Parked in `FUTURE_RELEASES.md`.
- **A staff "unlock" action without a reset.** Not requested; a completed reset
  already clears the lock.

## Scheduling

- **Wave 5.7:** `FR-47` + `FR-48`, 52h → 68h. This is the token-lifecycle
  wave: it already holds `FR-05`, whose refresh tokens both revoke, and
  `FR-46`, the staff reset flow `FR-48` mirrors. It's after Wave 4.5, so they
  are built inside the module boundaries.
- **Wave 8:** `FR-49`, 52h → 64h, inside the 60–70h target. It comes well
  after `FR-48`, which it builds on.

Rejected placements:

- **Wave 5.5** (76h): any of these would pass the 80h cap.
- **Wave 6** (72h): `FR-47` + `FR-48` would make it 88h.
- **All three in Wave 5.7:** exactly 80h, with no slack.
- **`FR-49` in Wave 7:** 72h, above the 70h target.
- **Wave 4** (40h): auth code written before Wave 4.5's module boundaries,
  and it would delay 4.5.
