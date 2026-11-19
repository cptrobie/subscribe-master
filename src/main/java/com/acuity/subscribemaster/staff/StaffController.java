package com.acuity.subscribemaster.staff;

import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.staff.dto.StaffMeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints about the signed-in staff user (FR-06). Today that's only {@code GET /api/v1/staff/me},
 * the first staff endpoint that needs a token.
 *
 * <p>Every path here is behind the staff security chain in {@code SecurityConfig}: the request
 * needs a {@code staff-api} token, and {@link StaffJwtAuthenticationConverter} has already loaded
 * the caller's permissions and rejected an unknown or deactivated user (401) before a method here
 * runs. {@code /me} needs no particular permission, so it has no {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/staff")
@Tag(name = "Staff", description = "The signed-in staff user's own profile and permissions.")
public class StaffController {

  private final StaffService staffService;

  public StaffController(StaffService staffService) {
    this.staffService = staffService;
  }

  /**
   * Returns the caller's own profile: id, email, role name and permission names.
   *
   * <p>The caller is identified from the token's {@code sub}, never from anything the client sends,
   * so a staff user can only ever see their own profile. The converter has already validated {@code
   * sub}, so parsing it here can't fail. The permissions are what endpoints check; the role name is
   * returned only for display.
   *
   * @param jwt the verified staff token, supplied by Spring Security
   * @return 200 with the caller's profile and permissions, sorted by name
   */
  @Operation(
      summary = "Get the signed-in staff user",
      description =
          "Returns the caller's id, email, role and permissions, identified from the staff token's subject."
              + " The role is for display only; staff endpoints check permissions, never the role.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "The caller's profile and permissions.",
        content = @Content(schema = @Schema(implementation = StaffMeResponse.class))),
    @ApiResponse(
        responseCode = "401",
        description = "No token, a customer token, or an inactive or unknown staff user",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StaffMeResponse> getCurrentStaffUser(@AuthenticationPrincipal Jwt jwt) {
    var response = staffService.currentStaffUser(UUID.fromString(jwt.getSubject()));
    return ResponseEntity.ok(response);
  }
}
