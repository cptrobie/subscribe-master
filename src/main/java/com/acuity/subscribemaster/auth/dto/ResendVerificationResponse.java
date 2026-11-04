package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for an accepted verification resend (FR-40), returned with 202. Carries only the
 * address the new link was sent to -- the corrected one if the request changed it -- so the client
 * can tell the customer where to look. 202 rather than 200 because the email goes out after the
 * transaction commits, and a delivery failure doesn't fail the request (see {@code
 * VerificationEmailListener}).
 *
 * <p>Deliberately holds no token and no customer id: {@code IdempotencyService} stores this body
 * and replays it to any retry with the same key. Failures are returned as {@code ApiError}, not
 * this type.
 */
@Schema(name = "ResendVerificationResponse")
public record ResendVerificationResponse(String email) {

  public static ResendVerificationResponse sentTo(String email) {
    return new ResendVerificationResponse(email);
  }
}
