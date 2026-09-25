package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** Login request body. */
@Schema(name = "LoginRequest")
public record LoginRequest(
    @Schema(example = "addme@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Email
        @Size(max = 254)
        String email,
    @Schema(
            description = "12–128 characters. Stored only as a BCrypt hash.",
            example = "easyPassword!#123",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 12, max = 128)
        String password) {

  // Normalizes before @Email/@Size run below, so existsByEmail() and the citext UNIQUE
  // constraint always compare byte-identical strings. This is a deliberate choice to sidestep
  // diagnosing whether citext's case-insensitive matching is actually working correctly at the
  // database level (CustomerRepository's Javadoc claims it should be) -- that's genuinely
  // unverified, not confirmed broken. See AuthControllerIT.duplicateEmailDifferentCase_...
  // for the test coverage this enables.
  // Left null on a null email so @NotBlank still reports its own message instead of NPEing here.
  public LoginRequest {
    if (email != null) {
      email = email.strip().toLowerCase(Locale.ROOT);
    }
  }
}
