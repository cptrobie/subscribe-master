package com.acuity.subscribemaster.staff;

import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.staff.dto.StaffLoginRequest;
import com.acuity.subscribemaster.staff.dto.StaffLoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff login endpoint (FR-06): {@code POST /api/v1/staff/auth/login}. Staff accounts are created
 * by an administrator or the bootstrap runner, never self-registered, so unlike {@link
 * com.acuity.subscribemaster.auth.AuthController} there's no registration, email verification or
 * {@code Idempotency-Key} handling here. Logging in is safe to retry, as it changes nothing a
 * second call would duplicate.
 *
 * <p>The controller only validates the body and passes the caller's address on for the audit log.
 * {@link StaffAuthService} and {@link StaffCredentialChecker} do the work, and {@code
 * GlobalExceptionHandler} turns their exceptions into responses: {@code
 * InvalidCredentialsException} into 401 and {@code AccountLockedException} into 429.
 *
 * <p>The path is open to unauthenticated callers through a temporary {@code permitAll()} entry in
 * {@code SecurityConfig}'s single chain. It moves to the staff chain in step 5 of FR-06, when the
 * customer and staff chains are split.
 */
@RestController
@RequestMapping("/api/v1/staff/auth")
@Tag(
    name = "Staff authentication",
    description =
        "Staff login. Staff accounts are created by an administrator, never self-registered.")
public class StaffAuthController {

  private final StaffAuthService staffAuthSvc;

  public StaffAuthController(StaffAuthService staffAuthService) {
    this.staffAuthSvc = staffAuthService;
  }

  /**
   * Logs a staff user in and returns a {@code staff-api} access token.
   *
   * @param request the email (already stripped and lowercased by {@link StaffLoginRequest}) and
   *     password
   * @param httpRequest used only for the caller's address, which goes on the audit entries
   * @return 200 with the staff user's id and email, the token and its expiry
   */
  @Operation(
      summary = "Log in as a staff user",
      description =
          "Logs in to a staff account created by an administrator and returns a staff access token."
              + " The token works only on `/api/v1/staff/**`; customer endpoints reject it.\n\n"
              + "An unknown email, a wrong password and a deactivated account all return the same"
              + " 401, so the response never shows whether a staff account exists. After"
              + " `app.staff.login-lockout.max-attempts` wrong passwords in a row the account is"
              + " locked for `app.staff.login-lockout.duration-minutes`, and every login returns"
              + " 429 until the lock expires, even with the correct password.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Successful login.",
        content = @Content(schema = @Schema(implementation = StaffLoginResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Body validation failure",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "401",
        description =
            "Unknown email, wrong password or deactivated account (deliberately"
                + " indistinguishable)",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "429",
        description = "Account locked after too many failed attempts",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(
      path = "/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StaffLoginResponse> login(
      @Valid @RequestBody StaffLoginRequest request, HttpServletRequest httpRequest) {
    var response =
        staffAuthSvc.login(request.email(), request.password(), httpRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }
}
