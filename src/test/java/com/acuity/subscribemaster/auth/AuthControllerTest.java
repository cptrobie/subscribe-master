package com.acuity.subscribemaster.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        AuthController controller =
                new AuthController(authService);

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
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"addme@example.com\",\"password\":\"easyPassword!#123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(customerId.toString()))
                .andExpect(jsonPath("$.email").value("addme@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.message").value("Account created."));
    }

    @Test
    void rejects_InvalidBodyWithFieldErrors() throws Exception {
        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(
                        jsonPath("$.fieldErrors[*].field").value(Matchers.hasItems("email", "password")));
    }

    @Test
    void duplicateEmail_returnsConflict() throws Exception {
        when(authService.register(anyString(), anyString(), anyString()))
                .thenThrow(new UserAlreadyExistsException());

        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"addme@example.com\",\"password\":\"easyPassword!#123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void malformedJsonBody_returnsBadRequest() throws Exception {
        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unexpectedServiceFailure_returnsInternalServerError() throws Exception {
        when(authService.register(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("boom"));

        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"addme@example.com\",\"password\":\"easyPassword!#123\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(
                        jsonPath("$.message")
                                .value("An unexpected error occurred. If it persists, contact support."));
    }
}
