package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auth.dto.EmailVerificationResponse;
import com.acuity.subscribemaster.auth.dto.LoginRequest;
import com.acuity.subscribemaster.auth.dto.LoginResponse;
import com.acuity.subscribemaster.auth.dto.RegistrationRequest;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated public endpoints for gaining access to the system (FR-01: registration, FR-02:
 * login, FR-30: email verification). Stays thin per NFR-01 -- all real logic lives in {@link
 * AuthService} and {@link EmailVerificationService}.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Account creation, email verification and login")
public class AuthController {

  private final AuthService authService;
  private final EmailVerificationService verifyService;

  public AuthController(
      AuthService authService, EmailVerificationService emailVerificationService) {
    this.authService = authService;
    this.verifyService = emailVerificationService;
  }

  // TODO(NFR-33): accept and honor an Idempotency-Key request header on register -- check
  // idempotency_keys before re-running its side effects (customer row, verification email),
  // validate request_hash on key reuse, store the outcome after a successful first run (NFR-24).
  // Split out of FR-30 into its own issue.

  @Operation(
      summary = "Register a new customer account",
      description =
          "Creates a customer account with a hashed password (FR-03) and emails a verification "
              + "link (FR-30). Registration succeeds even if the email can't be sent; the "
              + "account then stays unverified until a new link is requested (FR-40).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Account created",
        content = @Content(schema = @Schema(implementation = RegistrationResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Body validation failure",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Email already registered",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(
      path = "/register",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<RegistrationResponse> register(
      @Valid @RequestBody RegistrationRequest request, HttpServletRequest httpRequest) {
    var response =
        authService.register(request.email(), request.password(), httpRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @Operation(
      summary = "Login to an account",
      description =
          "Login to a previously registered account. Does not yet require a verified email -- "
              + "that gate is FR-31.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Successful login.",
        content = @Content(schema = @Schema(implementation = LoginResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Body validation failure",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Invalid email or password",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "429",
        description = "Account locked due to too many failed login attempts",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(
      path = "/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    var response =
        authService.login(request.email(), request.password(), httpRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }

  @Operation(
      summary = "Verify an email address",
      description =
          "Confirms the email address from the link sent at registration (FR-30). A GET so the "
              + "emailed link works on its own. Safe to repeat: opening an already-used link for "
              + "an already-verified account returns 200 \"Email already verified.\", so link "
              + "scanners opening it first don't break the customer's own click.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Email verified, or already verified by an earlier click",
        content = @Content(schema = @Schema(implementation = EmailVerificationResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid, expired or missing verification token",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  /**
   * Confirms a customer's email address from the token in their verification link.
   *
   * <p><b>The token arrives in the URL query string.</b> Never log the request URL, the query
   * string or the {@code token} parameter here, and keep it out of any request-logging filter added
   * later -- a logged token is a working verification link until it's used or expires. Only its
   * hash is ever looked up ({@link EmailVerificationService}).
   */
  @GetMapping(path = "/verify-email", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<EmailVerificationResponse> verifyEmail(
      @RequestParam(name = "token") String token, HttpServletRequest httpRequest) {
    var response = verifyService.verify(token, httpRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }
}
