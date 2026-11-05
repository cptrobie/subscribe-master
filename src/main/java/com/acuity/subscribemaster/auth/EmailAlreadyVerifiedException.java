package com.acuity.subscribemaster.auth;

/**
 * A verification email resend (FR-40) was requested for an account whose email is already verified,
 * so there is nothing to resend. Mapped to a 409 by {@code GlobalExceptionHandler}.
 *
 * <p>Distinct from the generic {@link InvalidCredentialsException} on purpose, and safe to be: it
 * is only thrown after {@link CredentialChecker} has accepted the email and password, so the caller
 * has already proved they own the account and learns nothing they couldn't see by logging in. A
 * verified account changing its address is a different flow, out of FR-40's scope.
 */
public class EmailAlreadyVerifiedException extends RuntimeException {
  public EmailAlreadyVerifiedException() {
    super("This email address is already verified. Log in instead.");
  }
}
