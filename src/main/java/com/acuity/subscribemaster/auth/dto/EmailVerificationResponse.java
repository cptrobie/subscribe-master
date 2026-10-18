package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for a successful email verification (FR-30). {@code emailVerified} is {@code true}
 * in both cases -- the address is verified either way; only {@code message} says whether this
 * request did it or an earlier click (or a link scanner) already had. Deliberately carries no email
 * address or customer id. Failures are returned as {@code ApiError}, not this type.
 */
@Schema(name = "EmailVerificationResponse")
public record EmailVerificationResponse(boolean emailVerified, String message) {

  public static EmailVerificationResponse verified() {
    return new EmailVerificationResponse(true, "Email verified.");
  }

  public static EmailVerificationResponse alreadyVerified() {
    return new EmailVerificationResponse(true, "Email already verified.");
  }
}
