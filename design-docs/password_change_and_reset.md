# Password Change and Reset (`FR-45`, `FR-46`, `FR-47`) — Design Decisions

Scope settled on 2026-11-17. The formal requirements are in
`subscribe_master_requirements.md`, and wave placement is in
`IMPLEMENTATION_BACKLOG.md`. This records what was decided before any code
exists. The details marked **OPEN** are settled when each issue is picked up.

## The requirement set

| ID | What | Size | Wave | Depends on |
|---|---|---|---|---|
| `FR-45` | Customer changes their own password while logged in | S | 6 | — |
| `FR-46` | Customer forgot/reset password by email | M | 6 | — |
| `FR-47` | Staff-assisted password reset for a customer, locked or not | M | 7 | `FR-46`, `FR-06` |

**Why three requirements, not two.** `FR-45` and `FR-46` are different flows.
One is logged in and checks the current password. The other is not logged in,
uses an emailed token and has to avoid revealing which accounts exist. Splitting
them keeps hours mapped one-to-one to issues, the same reason `FR-40` was split
out of `FR-30`. `FR-47` is a different actor behind a permission check, and it
waits on `FR-06`.

## What already exists

- **`customer_password_reset_tokens` (`V1`):** hashed, expiring, single-use
  (`used_at`). `FR-46` needs no new table.
- **`edit_customers` permission (`V1.1`):** granted to `support_agent` and
  `admin`. `FR-47` needs no new permission.
- **Customer lockout columns:** `failed_login_attempts` and `locked_until`.
- **`staff_password_reset_tokens` (`V1`):** exists, but stays unused for now
  (see "Explicitly deferred").

## DECIDED: a logged-in change needs the current password (`FR-45`)

The access token alone is not enough. A stolen or left-open session should not
be able to take over the account by changing its password.

- On success, the customer's refresh tokens are revoked, so other sessions end
  when their 15-minute access token expires.
- Audited as `PASSWORD_CHANGED`. `details` never holds password values or
  hashes.

## DECIDED: reset links go only to the profile email (`FR-46`)

- The request takes an email address and always returns the same response,
  whether or not an account exists, so it can't be used to find accounts.
- Rate-limited per account, like `FR-40`.
- The link is sent only to the email already stored on the customer's profile.
  A request can never send it somewhere else.

## DECIDED: completing a reset clears any lockout (`FR-46`)

Proving control of the profile email is stronger evidence than the failed
attempts that caused the lock. A completed reset sets `failed_login_attempts`
to 0 and clears `locked_until`. It also revokes the customer's refresh tokens.

## DECIDED: a reset verifies an unverified email (`FR-46`)

The link reached the profile email, which is the same proof `FR-30`'s
verification link gives. Completing a reset on an unverified account sets
`email_verified = true`, so the customer can log in straight away (`FR-31`).

## DECIDED: staff send a reset link and nothing more (`FR-47`)

- Gated by the existing `edit_customers` permission.
- Staff trigger `FR-46`'s flow for a customer. The link goes to that customer's
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
  unused tokens (as `FR-40` does for verification tokens).
- Rate-limit numbers, and whether staff sends from `FR-47` count toward the
  customer's limit.
- Password policy (length and so on), shared with registration.
- Audit action names and the migration widening `audit_logs_action_check`.
- Once `FR-33`/`FR-34` exist (Wave 5.5): a reset does not bypass 2FA. The next
  login still asks for the code. Confirm whether a password change should also
  be confirmed by a code when 2FA is on.
- Whether to notify the customer by email after a successful change or reset.

## Explicitly deferred

- **Staff passwords** (staff changing their own, or one staff member resetting
  another's). Moved to `FUTURE_RELEASES.md`. Only a role still to be decided
  should be able to reset another staff member's password.
- **A staff "unlock" action without a reset.** Not requested; a completed reset
  already clears the lock.

## Scheduling

Wave 5.5 (76h) couldn't take any of these without passing the 80h cap, and
Wave 6 couldn't take all three. Wave 4 had room but would mean writing auth
code before Wave 4.5's module boundaries, and it would delay 4.5.

- **Wave 6:** `FR-45` + `FR-46`, 60h → 76h. Neither depends on payments, so
  they can run while `NFR-21` is blocked on hosting.
- **Wave 7:** `FR-47`, 52h → 64h, next to the other staff endpoints (`FR-41`,
  `FR-42`), and after `FR-46`, which it builds on.
