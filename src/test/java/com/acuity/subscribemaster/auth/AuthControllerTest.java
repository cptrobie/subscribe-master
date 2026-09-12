package com.acuity.subscribemaster.auth;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acuity.subscribemaster.error.GlobalExceptionHandler;
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
        // Spring Boot 4 / Jackson 3 — java.time support is built in, no module needed.
        //JsonMapper objectMapper = JsonMapper.builder().build();

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        /*
        AuthController controller =
            new AuthController(authService, objectMapper);
         */

        AuthController controller =
                new AuthController(authService);

        mvc =
            MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler())
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
