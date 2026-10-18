package com.acuity.subscribemaster.auth;

/**
 * A login was refused because the account is locked: {@code app.login-lockout.max-attempts}
 * consecutive failed attempts lock it for {@code app.login-lockout.duration-minutes}. Thrown on the
 * failure that triggers the lock and on every attempt while it lasts, even with the correct
 * password. Mapped to a 429 by {@code GlobalExceptionHandler}.
 *
 * <p>Deliberately distinct from {@link InvalidCredentialsException}, although it reveals that the
 * account exists -- a locked-out user otherwise has no way to know why login stopped working or
 * when to retry. See {@link AuthService} for the trade-off.
 */
public class AccountLockedException extends RuntimeException {

  public AccountLockedException() {
    super("An account with this email address is locked. Try again later.");
  }
}
