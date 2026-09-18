package com.acuity.subscribemaster.auth;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated public endpoints for gaining access to the system (FR-01: registration, FR-02:
 * login). Stays thin per NFR-01 -- all real logic lives in AuthService.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Account creation and login")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  // TODO(FR-30/FR-31): accept and honor an Idempotency-Key request header here --
  // check idempotency_keys before re-running register()'s side effects, validate
  // request_hash on key reuse, store the outcome after a successful first run (NFR-24).

  @Operation(
      summary = "Register a new customer account",
      description =
          "Creates a new customer account with a hashed password (BCrypt, FR-03). "
              + "The account is created immediately; email verification (FR-30) is not yet implemented.")
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
          "Login to a previously registered account. Does not currently require a "
              + "verified email, since FR-31's verification gate depends on FR-30, which is not "
              + "yet implemented.")
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
        authService.login(
            request.email(),
            request.password(),
            httpRequest.getHeader("User-Agent"),
            httpRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.OK).body(response);
  }
}
