package com.acuity.subscribemaster.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.auth.dto.EmailVerificationResponse;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.error.GlobalExceptionHandler;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

public class AuthControllerTest {

  private final AuthService authService = mock(AuthService.class);
  private final EmailVerificationService verifyService = mock(EmailVerificationService.class);

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    AuthController controller = new AuthController(authService, verifyService);

    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void acceptsValidBody_returnsCreatedWithResponseBody() throws Exception {
    var customerId = UUID.randomUUID();
    var createdAt = Instant.parse("2026-01-01T00:00:00Z");
    var response = RegistrationResponse.accepted(customerId, "addme@example.com", false, createdAt);

    when(authService.register(anyString(), anyString(), anyString())).thenReturn(response);

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"addme@example.com\",\"password\":\"easyPassword!#123\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(customerId.toString()))
        .andExpect(jsonPath("$.email").value("addme@example.com"))
        .andExpect(jsonPath("$.emailVerified").value(false))
        .andExpect(
            jsonPath("$.message")
                .value("Account created. Check your email to verify your address."));
  }

  @Test
  void verifyEmail_validToken_returnsOkVerified() throws Exception {
    when(verifyService.verify(eq("the raw token"), anyString()))
        .thenReturn(EmailVerificationResponse.verified());

    mvc.perform(get("/api/v1/auth/verify-email").param("token", "the raw token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(jsonPath("$.message").value("Email verified."));
  }

  @Test
  void verifyEmail_alreadyVerified_returnsOkAlreadyVerified() throws Exception {
    when(verifyService.verify(anyString(), anyString()))
        .thenReturn(EmailVerificationResponse.alreadyVerified());

    mvc.perform(get("/api/v1/auth/verify-email").param("token", "the raw token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(jsonPath("$.message").value("Email already verified."));
  }

  @Test
  void rejects_InvalidBodyWithFieldErrors() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
        .andExpect(
            jsonPath("$.fieldErrors[*].field").value(Matchers.hasItems("email", "password")));
  }

  @Test
  void duplicateEmail_returnsConflict() throws Exception {
    when(authService.register(anyString(), anyString(), anyString()))
        .thenThrow(new AccountAlreadyExistsException());

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"addme@example.com\",\"password\":\"easyPassword!#123\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCode.ACCOUNT_ALREADY_EXISTS.name()))
        .andExpect(jsonPath("$.message").value("Email already registered"));
  }

  @Test
  void malformedJsonBody_returnsBadRequest() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not valid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
  }

  @Test
  void unexpectedServiceFailure_returnsInternalServerError() throws Exception {
    when(authService.register(anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("boom"));

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"addme@example.com\",\"password\":\"easyPassword!#123\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR.name()))
        .andExpect(
            jsonPath("$.message")
                .value("An unexpected error occurred. If it persists, contact support."));
  }

  @Test
  void verifyEmail_invalidToken_returnsBadRequest() throws Exception {
    when(verifyService.verify(anyString(), anyString()))
        .thenThrow(new InvalidVerificationTokenException());

    mvc.perform(get("/api/v1/auth/verify-email").param("token", "the raw token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_VERIFICATION_TOKEN.name()))
        .andExpect(jsonPath("$.message").value("This verification link is invalid."));
  }

  @Test
  void verifyEmail_expiredToken_returnsBadRequest() throws Exception {
    when(verifyService.verify(anyString(), anyString()))
        .thenThrow(new VerificationTokenExpiredException());

    mvc.perform(get("/api/v1/auth/verify-email").param("token", "the raw token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.VERIFICATION_TOKEN_EXPIRED.name()))
        .andExpect(jsonPath("$.message").value("This verification link has expired."));
  }

  @Test
  void verifyEmail_missingToken_returnsBadRequest() throws Exception {
    mvc.perform(get("/api/v1/auth/verify-email"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()))
        .andExpect(jsonPath("$.message").value("Missing required parameter 'token'"));

    verifyNoInteractions(verifyService);
  }
}
