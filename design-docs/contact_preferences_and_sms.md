# Customer Contact Preferences, Phone Verification and SMS (Twilio) — Design Decisions

Captures the design decisions for `FR-36`–`FR-39`, ahead of implementation. A
customer can store a phone number and choose how the system contacts them
(email or text message); Twilio delivers the text messages. **Not sourced from
`task.pdf`** — added deliberately. Amends `FR-33` (2FA), `FR-19` and `FR-20`
(payment notifications) — see "Impact on existing requirements". Supersedes prior
in-chat discussion — this is the single source of truth going forward.

**Email verification is unaffected.** `FR-30`/`FR-31`/`FR-32` are unchanged:
registration still verifies the email address, and login still requires
`email_verified`, regardless of the preferred contact method.

## The requirement set

| ID | Requirement | Size | Wave | Depends on |
|---|---|---|---|---|
| `FR-36` | Phone number + `preferred_contact_method` (email/text, default email) on `customers`; customer self-service update endpoint | M (12h) | 2 | nothing |
| `FR-37` | `SmsSender` backed by Twilio (reusable, mirrors `EmailSender`) | M (12h) | 8 | nothing (Twilio account) |
| `FR-38` | Phone verification (SMS code, `phone_verified`); required before text can become the preferred method | L (24h) | 8 | `FR-36`, `FR-37` |
| `FR-39` | 2FA code delivered on the preferred channel, with email fallback (amends `FR-33`) | M (12h) | 8 | `FR-33`, `FR-38` |

New work: ~60h. Plus a revision to `FR-20` (S → M, +8h) for channel routing of
payment notifications — total ~68h.

## Core behavioral rules

- **Text can only become the preferred method after the phone number is
  verified.** Selecting `TEXT` while `phone_verified` is false is rejected with a
  distinct, specific error, not silently ignored.
- **Email is always selectable** and is the default for every customer.
- **Changing or removing the phone number resets `phone_verified` to false and,
  if the preference was `TEXT`, forces it back to `EMAIL`** in the same
  transaction. A customer is never left with text selected for a number nobody
  has verified.
- **Phone numbers are stored in E.164** (`+15551234567`) and validated at the DTO
  boundary. Twilio requires it, and it avoids per-country parsing later.
- **Customers update their own phone number and preference only** — same
  per-customer isolation as `FR-04`; no staff involvement.
- Exact endpoint paths and DTO shapes are implementation detail, left open like
  the other design docs.

## Schema changes (not yet built)

`customers`:
- `phone_number TEXT` (nullable) with a CHECK on the E.164 shape.
- `phone_verified BOOLEAN NOT NULL DEFAULT FALSE`.
- `preferred_contact_method TEXT NOT NULL DEFAULT 'email'` with
  `CHECK (preferred_contact_method IN ('email', 'text'))` — lowercase-string bridge
  to a Java enum, same pattern as the other enum-like columns.
- Defense-in-depth CHECK enforcing the core rule at the database level, in the same
  spirit as `chk_subscription_has_name`:
  `CHECK (preferred_contact_method <> 'text' OR (phone_number IS NOT NULL AND phone_verified))`.

New table `customer_phone_verification_codes` (`FR-38`): `customer_id` (FK, cascade),
**`phone_number` — the number being verified, not just the customer**, so changing
the number mid-flow invalidates any outstanding code), `code_hash`, `expires_at`,
`attempt_count`, `used_at`, `created_at`. Same hash-storage pattern as the
password-reset and email-verification token tables; the raw code is never stored.

Related changes the new behavior forces:
- **`audit_logs.action` CHECK (`V10`) is a closed list.** New audit actions
  (phone number changed, contact preference changed, phone verification sent,
  phone verified, SMS-to-email fallback) need the `AuditAction` enum, its
  converter, and that CHECK updated together.
- **`notification_log.channel` CHECK only allows `('log', 'email')`**, yet the Java
  `NotificationChannel` enum already has `SMS` — an existing mismatch. The CHECK
  must add `'sms'` before payment notifications can be sent by text.
- `FR-33`'s `customer_two_factor_codes` records which channel each code used.
- Migration mechanics (fold into `V1`/`V2` vs. a new migration) follow the
  pre-deployment precedent noted in `login_2fa_sequence.md`; decide at build time.
- **ERD (`subscribe_master_erd.drawio`) must be updated** for the new `customers`
  columns and the new table.

## Phone verification flow (`FR-38`)

- Authenticated customer requests a code for their currently stored number; the
  code is sent by `SmsSender`. Confirming it sets `phone_verified = true`.
- Code lifetime ~10 minutes; **3 wrong attempts invalidate that code** and a new
  one must be requested — consistent with `FR-33`'s 3-attempt rule.
- **No account lockout here**, unlike `FR-33`'s login challenge: the customer is
  already authenticated, so the abuse risk is SMS cost, not account takeover.
  The control is a **per-customer send-rate limit** (default 5 sends/hour)
  plus a configurable **country-code allowlist**, which also limits international
  SMS-pumping fraud.
- Sends and confirmations are recorded in `audit_logs` (account-level,
  security-relevant), **not** `notification_log` (subscription-scoped dedup).

## 2FA on the preferred channel (`FR-39`)

- At login, after the password check, the code goes to the preferred channel;
  `TEXT` is only possible when the phone is verified (guaranteed by the CHECK).
- A wrong-code auto-resend uses the **same channel** as the original send.
- **If Twilio fails, fall back to email** rather than locking the customer out;
  the fallback is audit-logged. Email is always available because login requires
  `email_verified`. This fallback is a deliberate, customer-confirmed choice.
- The challenge response tells the customer which channel was used, with a
  masked destination (`***1234`). It is only reachable after a correct password.

## Payment notifications (`FR-19`/`FR-20`)

- Delivery honors `preferred_contact_method` at send time: `TEXT` uses
  `SmsSender`, otherwise `EmailSender`. These are subscription-scoped, so they stay
  in `notification_log` with `channel = 'sms'`/`'email'` — the channel actually used.
- Same fallback as 2FA: Twilio failure falls back to email; the log records the
  channel that really delivered.

## Twilio integration decisions (`FR-37`)

- **Twilio Messaging API (plain send), not Twilio Verify.** *Default — flagged
  for confirmation.* Verify would manage OTP generation and attempt limits for us,
  but it ties verification state to Twilio and its limits differ from `FR-33`'s
  designed 3-attempt/15-minute-lockout semantics. Keeping codes app-owned lets both
  channels share one code-table pattern and keeps the sender swappable.
- `SmsSender` mirrors `EmailSender`'s shape so `FR-20`'s Strategy abstraction wraps
  both without rework (same principle as `ARCHITECTURE.md` §2.4: don't build a
  second mechanism, extend the existing one).
- **Credentials (account SID, auth token, sender number) live in Vault**
  (`NFR-20`), never in committed yaml.
- **Tests never send real SMS**: unit tests mock `SmsSender`; integration tests
  use a fake sender bean. Twilio errors are translated, not leaked (`NFR-02`).
- Phone numbers are PII: never log one in full (mask all but the last 4 digits).

## Explicitly deferred

- Twilio delivery-status webhooks (we record "sent", not "delivered") — same
  stance as the Stripe-webhook deferral in `payment_retry_and_dunning.md`.
- Handling inbound STOP/opt-out replies. Twilio blocks sends to opted-out numbers;
  until handled, such a send fails and falls back to email.
- Customer-facing SMS consent wording / compliance review before any production launch.
- Exact rate-limit numbers and the allowed-country list — tunable config.

## Impact on existing requirements

- **`FR-33`**: "email OTP" becomes "OTP on the preferred channel" (`FR-39` owns
  the change). Its blocker on `FR-30`'s `EmailSender` remains.
- **`FR-34`**: unchanged — still only the toggle.
- **`FR-19`/`FR-20`**: `FR-20` grows S → M (channel routing and the SMS strategy).
- **`login_2fa_sequence.md`** and **`ARCHITECTURE.md` §2.4**: amended to point here.

## Scheduling

The set is split across two waves by dependency, not placed in one.

- **Wave 2: `FR-36` only**, before `FR-33`/`FR-34`. It has no dependencies, and
  `FR-34` also changes `customers` (`two_factor_enabled`), so one pass over that
  schema is cheaper than two. On its own it lets a customer store a number but
  keep email selected; text becomes selectable only once `FR-38` verifies it.
- **Wave 8: `FR-37` → `FR-38` → `FR-39`**, then `FR-19`'s routing, after `FR-20`.
  `FR-37` is the sibling of `EmailSender`/`FR-20`'s abstraction and `FR-19` is its
  consumer, so they belong together. `FR-33`/`FR-34` ship email-only in Wave 2 as
  originally designed, and `FR-39` later amends `FR-33`.
- **Why not all in Wave 2:** Wave 2 is "Authorization extensions" and gates Wave
  4.5 and Wave 9 on RBAC work. Adding ~48h of SMS work there would delay that
  gate for features nothing downstream of Wave 2 needs.
- **Pull-forward option:** `FR-37`–`FR-39` have no dependency on Wave 3 or Wave 6.
  If text-message 2FA is wanted sooner, build them right after `FR-33` (which
  still needs Wave 1's deferred `FR-30`).

Effect on wave totals: Wave 2 gains ~12h; Wave 8 gains ~48h plus the ~8h `FR-20`
revision.
