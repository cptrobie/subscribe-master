package com.acuity.subscribemaster.auth;

/**
 * A login was refused because the customer hasn't yet confirmed their email address (FR-31) --
 * {@code customers.email_verified} is still false, set true only by {@link
 * EmailVerificationService} when the FR-30 verification link is confirmed. Mapped to a 403 by
 * {@code GlobalExceptionHandler}.
 *
 * <p>Thrown only after the password has been checked, so it's never seen by anyone who doesn't
 * already know the password. That's why it can safely be distinct from {@link
 * InvalidCredentialsException}: a genuine user is told to check their email rather than left to
 * assume their password is wrong, without letting an attacker learn which emails are registered or
 * verified. See {@link AuthService} for the ordering of checks in {@code login}.
 */
public class EmailNotVerifiedException extends RuntimeException {

  public EmailNotVerifiedException() {
    super(
        "This account's email address has not been verified. Check your inbox for the verification link.");
  }
}
