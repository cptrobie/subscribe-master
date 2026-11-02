package com.acuity.subscribemaster.auth;

/**
 * A verification email resend (FR-40) was refused because the account has reached its send limit:
 * three verification tokens in the last hour, counted from the customer's {@link
 * CustomerEmailVerificationToken} rows, with the one issued at registration included. Mapped to a
 * 429 by {@code GlobalExceptionHandler}.
 *
 * <p>The limit is what keeps the endpoint from being used to mail-bomb an address. It's why earlier
 * tokens are marked used rather than deleted on a resend: deleting them would reset the count. A
 * replayed request ({@code Idempotency-Key}) sends nothing and doesn't count. Kept separate from
 * {@link AccountLockedException}, although both are 429s, so a client can tell "too many emails
 * requested" from "too many wrong passwords". The message carries no counts or timings.
 */
public class VerificationResendLimitException extends RuntimeException {
  public VerificationResendLimitException() {
    super("Too many verification emails requested. Try again later.");
  }
}
