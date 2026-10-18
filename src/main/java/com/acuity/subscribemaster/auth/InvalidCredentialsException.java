package com.acuity.subscribemaster.auth;

/**
 * A login failed because the email is not registered or the password is wrong. One exception for
 * both, deliberately: distinguishing them would let a caller enumerate registered emails through
 * the login endpoint (see {@link AuthService}). Mapped to a 401 by {@code GlobalExceptionHandler},
 * which returns its own fixed "Invalid email or password" message, not this exception's.
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("An account with this email address was not found");
  }
}
