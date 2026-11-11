package com.acuity.subscribemaster.staff.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Staff login request body for {@code POST /api/v1/staff/auth/login} (FR-06). The constraints match
 * the customer {@code LoginRequest}, so a staff password follows the same 12–128 character rule.
 */
@Schema(name = "StaffLoginRequest")
public record StaffLoginRequest(
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

  // Normalizes the email before validation runs. This is required, not cosmetic: JPA lookups on
  // the citext email column are case-sensitive (see StaffUserRepository), so without it
  // Admin@x.test couldn't log in as admin@x.test.
  // Left null on a null email so @NotBlank reports its own message instead of an NPE here.
  public StaffLoginRequest {
    if (email != null) {
      email = email.strip().toLowerCase(Locale.ROOT);
    }
  }
}
