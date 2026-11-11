package com.acuity.subscribemaster.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Staff login response body (FR-06). On success the token is returned in the body, with the instant
 * it expires. It's a {@code staff-api} token, so it works only on {@code /api/v1/staff/**}.
 */
@Schema(name = "StaffLoginResponse")
public record StaffLoginResponse(
    UUID id,
    @Schema(example = "addme@example.com") String email,
    @Schema(example = "a1b2c3Z9xY87") String token,
    Instant expiresAt,
    String message) {

  /** The response for a successful login, with the standard success message. */
  public static StaffLoginResponse accepted(
      UUID id, String email, String token, Instant expiresAt) {
    return new StaffLoginResponse(id, email, token, expiresAt, "Login successful.");
  }
}
