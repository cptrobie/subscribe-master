package com.acuity.subscribemaster.subscribe;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acuity.subscribemaster.error.GlobalExceptionHandler;
import com.acuity.subscribemaster.idempotency.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Shared MockMvc/JWT/validator wiring for {@link SubscriptionController} unit tests, split across
 * one file per endpoint (create/update/status-transitions/queries) rather than one large class.
 *
 * <p>JUnit skips abstract classes during test discovery, so this carries the {@code Test} suffix
 * safely -- it's never itself instantiated or run, only extended.
 */
abstract class AbstractSubscriptionControllerTest {

  protected final SubscriptionService subscriptionSvc = mock(SubscriptionService.class);
  protected final IdempotencyService idempotencySvc = mock(IdempotencyService.class);
  protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  protected UUID customerId;
  protected MockMvc mvc;

  @BeforeEach
  void setUp() {
    customerId = UUID.randomUUID();

    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(customerId.toString())
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    when(idempotencySvc.execute(any(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> inv.<Supplier<?>>getArgument(5).get());

    SubscriptionController controller = new SubscriptionController(subscriptionSvc, idempotencySvc);

    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }
}
