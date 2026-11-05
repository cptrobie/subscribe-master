package com.acuity.subscribemaster.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Request body for resending the verification email (FR-40), optionally to a corrected address. The
 * caller proves they own the account with the same {@code email} and {@code password} as {@link
 * LoginRequest}, because a resend can change the account's address; the credential check (and its
 * lockout) is shared with login.
 *
 * <p>{@code newEmail} is optional. Omitted, blank or the same as the current address, the link goes
 * to the current address; otherwise the address is corrected first, which is allowed only while the
 * account is unverified. Both addresses are normalized the same way as {@link LoginRequest}'s, so a
 * difference in letter case alone is not treated as a correction.
 */
@Schema(name = "ResendVerificationRequest")
public record ResendVerificationRequest(
    @Schema(example = "addme@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Email
        @Size(max = 254)
        String email,
    @Schema(
            description = "12–128 characters. The account’s current password.",
            example = "easyPassword!#123",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(min = 12, max = 128)
        String password,
    @Schema(example = "updateme@example.com", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Email
        @Size(max = 254)
        String newEmail) {

  // Normalizes both addresses the same way as LoginRequest, for the same reasons -- see its
  // comment. A null email is left null so @NotBlank reports it. A null or blank newEmail becomes
  // null: @Email accepts "", so without this an empty string would pass validation and be saved as
  // the corrected address.
  public ResendVerificationRequest {
    if (email != null) {
      email = email.strip().toLowerCase(Locale.ROOT);
    }
    if (newEmail == null || newEmail.isBlank()) {
      newEmail = null;
    } else {
      newEmail = newEmail.strip().toLowerCase(Locale.ROOT);
    }
  }
}
