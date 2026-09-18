package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Login response body. On success, the token is provided in the Response body. */
@Schema(name = "LoginResponse")
public record LoginResponse(
    UUID id,
    @Schema(example = "addme@example.com") String email,
    @Schema(example = "false") boolean emailVerified,
    @Schema(example = "a1b2c3Z9xY87") String token,
    Instant expiresAt,
    String message) {

  public static LoginResponse accepted(
      UUID id, String email, boolean emailVerified, String token, Instant expiresAt) {
    return new LoginResponse(id, email, emailVerified, token, expiresAt, "Login successful.");
  }
}
