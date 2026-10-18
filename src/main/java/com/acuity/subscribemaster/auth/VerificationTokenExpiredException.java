package com.acuity.subscribemaster.auth;

/**
 * A verification link whose token was never used but is past its {@code expires_at}. Kept separate
 * from {@link InvalidVerificationTokenException} so the person knows to request a new link (FR-40)
 * rather than assume they mistyped it. The message is fixed and never includes the token.
 */
public class VerificationTokenExpiredException extends RuntimeException {
  public VerificationTokenExpiredException() {
    super("This verification link has expired.");
  }
}
