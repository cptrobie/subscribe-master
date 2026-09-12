package com.acuity.subscribemaster.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.applog.AppLogService;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.error.GlobalExceptionHandler;
import com.acuity.subscribemaster.idempotency.IdempotencyService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;


public class AuthControllerTest {


    private final AuthService registrationService = mock(AuthService.class);
    private final AppLogService appLogService = mock(AppLogService.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Spring Boot 4 / Jackson 3 — java.time support is built in, no module needed.
        JsonMapper objectMapper = JsonMapper.builder().build();

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        AuthController controller =
            new AuthController(registrationService, objectMapper);

        mvc =
            MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler(appLogService))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                    .setValidator(validator)
                    .build();
    }

    @Test
    void rejectsInvalidBodyWithFieldErrors() throws Exception {
        mvc.perform(
                        post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(
                        jsonPath("$.fieldErrors[*].field").value(Matchers.hasItems("email", "password")));
    }

}
