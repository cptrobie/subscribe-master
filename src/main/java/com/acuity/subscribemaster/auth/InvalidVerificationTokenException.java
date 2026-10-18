package com.acuity.subscribemaster.auth;

/**
 * A verification link that can't be used: the token is unknown, its customer no longer exists, or
 * the data is inconsistent. One exception for all three, since to the person clicking they all mean
 * "this link doesn't work". The message is fixed and never includes the token.
 */
public class InvalidVerificationTokenException extends RuntimeException {
  public InvalidVerificationTokenException() {
    super("This verification link is invalid.");
  }
}
