package com.acuity.subscribemaster.staff.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in staff user's own profile, returned by {@code GET /api/v1/staff/me} (FR-06).
 *
 * <p>{@code permissions} are what staff endpoints check ({@code hasAuthority(...)}); {@code role}
 * is the role's name, for display only, and nothing should make decisions on it. The compact
 * constructor sorts the permissions, so the order is the same on every call however the response is
 * built.
 *
 * @param id the staff user's id, the same as the token's {@code sub}
 * @param email the staff user's email, already lowercase
 * @param role the role name, such as {@code auditor}
 * @param permissions the role's permission names, sorted
 */
@Schema(name = "StaffMeResponse")
public record StaffMeResponse(
    UUID id,
    @Schema(example = "addme@example.com") String email,
    @Schema(example = "auditor") String role,
    @ArraySchema(schema = @Schema(example = "view_audit_logs")) List<String> permissions) {

  public StaffMeResponse {
    permissions = permissions.stream().sorted().toList();
  }
}
