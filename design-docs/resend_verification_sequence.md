# Resend verification email sequence (`FR-40`)

Captures the design for `FR-40`, settled on 2026-11-02 (see `subscribe_master_requirements.md` for the formal requirement and `IMPLEMENTATION_BACKLOG.md` for wave placement). Two diagrams, because they answer different questions:

- **Sequence diagram:** who calls whom, and where the transactions start and end. The email goes out only after commit, which is why the endpoint answers 202 rather than 200.
- **Flowchart:** the order of the checks, and the status code each one returns. Each check sits where it does for a reason (see "Key decisions").

## Sequence: a request that sends a new link

```mermaid
sequenceDiagram
    participant C as Customer
    participant AC as AuthController
    participant IS as IdempotencyService
    participant CV as Credential check<br/>(shared with login)
    participant AS as AuthService.resendVerification
    participant DB as PostgreSQL
    participant L as VerificationEmailListener
    participant M as MessageService (email)

    C->>AC: POST /api/v1/auth/resend-verification<br/>{email, password, newEmail?}<br/>[Idempotency-Key]
    AC->>IS: execute(key, RESEND_ENDPOINT, request, ...)

    alt key already stored, same request hash
        IS-->>C: stored 202 + body, Idempotent-Replayed: true
        Note over IS,C: operation not run: no new token,<br/>no email, no rate-limit count
    else key already stored, different hash
        IS-->>C: 422 IDEMPOTENCY_KEY_MISMATCH
    else no key, or key not seen yet
        Note over IS,DB: transaction T1 begins (key row + operation)
        IS->>CV: authenticate(email, password, ip)
        Note over CV,DB: own transaction (REQUIRES_NEW):<br/>failed-count / lock commits even when it throws
        CV->>DB: find customer, check lock, check password
        alt unknown email or wrong password
            CV-->>C: 401 generic credential error (counter +1)
        else locked, or this failure reaches the threshold
            CV-->>C: 429 account locked
        end
        CV-->>IS: customer id (counters reset)

        IS->>AS: resendVerification(customerId, newEmail, ip)
        AS->>DB: reload customer (T1)
        alt already verified
            AS-->>C: 409 EMAIL_ALREADY_VERIFIED
        end
        AS->>DB: countByCustomerIdAndCreatedAtAfter(now - 1h)
        alt 3 or more tokens in the last hour
            AS-->>C: 429 VERIFICATION_RESEND_LIMIT
        end
        opt newEmail given and different
            AS->>DB: existsByEmail(newEmail)
            alt taken
                AS-->>C: 409 ACCOUNT_ALREADY_EXISTS
            end
            AS->>DB: customer.changeEmail(newEmail)
            AS->>DB: audit EMAIL_CHANGED
        end
        AS->>DB: markUsed(now) on every unused token
        AS->>DB: insert new token (hash only, fresh expires_at)
        AS->>AS: publish VerificationEmailRequestedEvent
        AS-->>IS: 202 {email}
        IS->>DB: saveAndFlush idempotency key row
        Note over IS,DB: T1 commits
        IS-->>AC: 202
        AC-->>C: 202 Accepted {email}

        Note over L: AFTER_COMMIT, so a rolled-back<br/>request never emails a link
        L->>M: send(link with raw token) to the current address
        alt delivered
            L->>DB: audit EMAIL_VERIFICATION_SENT
            M-->>C: verification email
        else MessageDeliveryException
            L->>DB: audit EMAIL_VERIFICATION_SEND_FAILED (logged, not retried)
        end
    end
```

Any rejection inside T1 (409 or 429 from `AuthService`) rolls T1 back, so nothing is stored under the key and a retry runs again. The credential check's counter update isn't part of T1, so it survives.

## Order of checks and their responses

```mermaid
flowchart TD
    A[POST resend-verification] --> V{Body valid?}
    V -- no --> R400[400 validation error]
    V -- yes --> K{Idempotency-Key<br/>already stored?}
    K -- same hash --> RP[replay stored 202]
    K -- different hash --> R422[422 IDEMPOTENCY_KEY_MISMATCH]
    K -- no key / new key --> F{Customer found<br/>by email?}
    F -- no --> R401[401 generic credential error]
    F -- yes --> LK{Locked?}
    LK -- yes --> R429L[429 account locked]
    LK -- no --> P{Password matches?}
    P -- "no: counter +1" --> T{Threshold reached?}
    T -- no --> R401
    T -- "yes: lock + audit ACCOUNT_LOCKED" --> R429L
    P -- "yes: counters reset" --> VF{Already verified?}
    VF -- yes --> R409V[409 EMAIL_ALREADY_VERIFIED]
    VF -- no --> RL{3+ tokens<br/>in the last hour?}
    RL -- yes --> R429R[429 VERIFICATION_RESEND_LIMIT]
    RL -- no --> NE{newEmail given<br/>and different?}
    NE -- no --> OK
    NE -- yes --> CL{newEmail taken?}
    CL -- yes --> R409E[409 ACCOUNT_ALREADY_EXISTS]
    CL -- no --> CH[changeEmail + audit EMAIL_CHANGED] --> OK
    OK[mark old tokens used, insert new token,<br/>publish event] --> R202[202 Accepted]
```

## Key decisions this diagram encodes

- **202, not 200.** At response time the server has committed a new token and published the event, nothing more. The send happens after commit and can fail (recorded by `NFR-34`'s `EMAIL_VERIFICATION_SEND_FAILED`), so "accepted for sending" is the honest answer.
- **The password is checked the way login checks it, with the same lockout.** A wrong password counts toward `failedLoginCount`/`lockedUntil` and gets the same 401/429. Otherwise this endpoint would be an unthrottled password-guessing oracle beside login.
- **The credential check runs in its own transaction (`REQUIRES_NEW`).** `IdempotencyService` runs the operation inside a `TransactionTemplate`, which rolls back on any `RuntimeException`, so `login`'s `noRollbackFor` would not save the counter here. Without a separate transaction, a caller sending a fresh `Idempotency-Key` with every guess would never be locked out. Because it's a separate transaction, it has to be a separate bean (self-invocation skips the proxy), like `SubscriptionStatusUpdater`.
- **The credential check runs inside the operation, not before `execute()`.** A retry of a request that changed the address still sends the *old* email. Checking first would then answer 401 instead of replaying the original 202.
- **Verified before rate limit before address change.** A verified account gets 409 without touching the rate limit. The limit is checked before `newEmail`'s clash check, so a request that would only probe whether an address is registered is still capped once the account is over its limit. A clash probe below the limit leaks nothing registration doesn't already leak.
- **Old tokens are marked used, never deleted.** The rate limit counts token rows by `created_at`, so deleting them would reset it. `verify()` already rejects a used token on an unverified account (400), so a link sent to the old address can't verify the account under the new one.
- **A replay runs nothing.** No new token, no email, no rate-limit count. Its stored body holds only the address, never the raw token.
- **A new event, not `CustomerRegisteredEvent`.** `VerificationEmailRequestedEvent` keeps "new customer" consumers (`NFR-28`) from firing on a resend. The listener gets a second method sharing a private send helper with `onCustomerRegistered`.

## Not covered

- Notifying the old address when the email changes.
- Retrying a failed send (tabled under `NFR-34`).
- `FR-32`'s cleanup must use the customer's **latest** token's `expires_at` once resends exist. That change belongs to `FR-32`.
