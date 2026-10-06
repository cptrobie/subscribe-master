package com.acuity.subscribemaster.subscribe;

import static org.assertj.core.api.Assertions.assertThat;

import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogRepository;
import com.acuity.subscribemaster.auth.dto.LoginRequest;
import com.acuity.subscribemaster.auth.dto.LoginResponse;
import com.acuity.subscribemaster.auth.dto.RegistrationRequest;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionRequest;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionUpdateRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole subscription API against a real Postgres, with real JWT login: what the controller and
 * service unit tests, which mock everything below them, cannot show. Vault is switched off because
 * secret resolution is covered by SubscribeMasterApplicationIT, not by this suite.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.cloud.vault.enabled=false")
@AutoConfigureTestRestTemplate
class SubscriptionApiIT {

  private static final String PASSWORD = "easyPassword!#123";
  private static final String BASE = "/api/v1/subscribe";

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("subscribe_master")
          .withUsername("subscribe_master")
          .withPassword("test_only_not_a_real_secret");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("jwt.private-key", () -> readTestResource("test-jwt-keys/jwt-private.pem"));
    registry.add("jwt.public-key", () -> readTestResource("test-jwt-keys/jwt-public.pem"));
  }

  private static String readTestResource(String location) {
    try (var in = new ClassPathResource(location).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private CustomerSubscriptionRepository subscriptionRepo;
  @Autowired private SubscriptionProviderRepository providerRepo;
  @Autowired private AuditLogRepository auditLogRepo;
  @Autowired private JdbcTemplate jdbc;

  private record Caller(UUID id, String token) {}

  private Caller registerAndLogin() {
    var email = "it-" + UUID.randomUUID() + "@example.com";
    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, PASSWORD),
        RegistrationResponse.class);
    var login =
        restTemplate
            .postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), LoginResponse.class)
            .getBody();
    assertThat(login).isNotNull();
    return new Caller(login.id(), login.token());
  }

  private <T> ResponseEntity<T> call(
      HttpMethod method, String path, Caller as, Object body, Class<T> type) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(as.token());
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
  }

  private SubscriptionResponse create(Caller as, SubscriptionRequest request) {
    var response = call(HttpMethod.POST, BASE, as, request, SubscriptionResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }

  private SubscriptionResponse transition(Caller as, UUID id, String action) {
    var response =
        call(HttpMethod.POST, BASE + "/" + id + "/" + action, as, null, SubscriptionResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private static SubscriptionRequest customNameRequest(String name) {
    return new SubscriptionRequest(
        null,
        name,
        "account-1",
        null,
        Currency.USD,
        new BigDecimal("9.99"),
        BillingFrequency.MONTHLY,
        null,
        LocalDate.now().plusDays(7));
  }

  private UUID catalogProviderId(String name) {
    return providerRepo.findAll().stream()
        .filter(p -> p.getName().equals(name))
        .findFirst()
        .orElseThrow()
        .getId();
  }

  private int auditCount(Caller as, AuditAction action, UUID subscriptionId) {
    return (int)
        auditLogRepo.findByActorIdAndAction(as.id(), action).stream()
            .filter(a -> subscriptionId.equals(a.getResourceId()))
            .count();
  }

  @Test
  void createWithCatalogProvider_persistsRowResolvesProviderNameAndAuditsIt() {
    var customer = registerAndLogin();
    var netflixId = catalogProviderId("Netflix");
    var request =
        new SubscriptionRequest(
            netflixId,
            null,
            "account-1",
            null,
            Currency.GBP,
            new BigDecimal("12.99"),
            BillingFrequency.ANNUAL,
            null,
            LocalDate.now().plusDays(10));

    var created = create(customer, request);

    assertThat(created.providerName()).isEqualTo("Netflix");
    assertThat(created.customerId()).isEqualTo(customer.id());
    assertThat(created.status()).isEqualTo(SubscriptionStatus.ACTIVE);

    var row = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(row.getCustomerId()).isEqualTo(customer.id());
    assertThat(row.getProviderId()).isEqualTo(netflixId);
    assertThat(row.getCurrency()).isEqualTo(Currency.GBP);
    assertThat(row.getAmount()).isEqualByComparingTo("12.99");
    assertThat(row.getBillingFrequency()).isEqualTo(BillingFrequency.ANNUAL);
    assertThat(row.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(row.getVersion()).isZero();
    assertThat(auditCount(customer, AuditAction.SUBSCRIPTION_CREATED, created.id())).isEqualTo(1);
  }

  @Test
  void createWithCustomNameOnly_persistsWithoutProvider() {
    var customer = registerAndLogin();

    var created = create(customer, customNameRequest("Local paper delivery"));

    assertThat(created.providerName()).isNull();
    assertThat(created.customName()).isEqualTo("Local paper delivery");
    var row = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(row.getProviderId()).isNull();
    assertThat(row.getCustomName()).isEqualTo("Local paper delivery");
  }

  @Test
  void createWithCustomFrequency_persistsBillingIntervalDays() {
    var customer = registerAndLogin();
    var request =
        new SubscriptionRequest(
            null,
            "Every two weeks",
            null,
            null,
            Currency.EUR,
            new BigDecimal("4.50"),
            BillingFrequency.CUSTOM,
            14,
            LocalDate.now().plusDays(1));

    var created = create(customer, request);

    var row = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(row.getBillingFrequency()).isEqualTo(BillingFrequency.CUSTOM);
    assertThat(row.getBillingIntervalDays()).isEqualTo(14);
  }

  @Test
  void createWithUnknownProvider_returns404AndPersistsNothing() {
    var customer = registerAndLogin();
    var request =
        new SubscriptionRequest(
            UUID.randomUUID(),
            null,
            null,
            null,
            Currency.USD,
            new BigDecimal("1.00"),
            BillingFrequency.MONTHLY,
            null,
            LocalDate.now().plusDays(1));

    var response = call(HttpMethod.POST, BASE, customer, request, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.NOT_FOUND.name());
    assertThat(subscriptionRepo.findByCustomerId(customer.id())).isEmpty();
  }

  @Test
  void createWithNonPositiveCustomInterval_returns400OnTheFieldAndPersistsNothing() {
    var customer = registerAndLogin();
    var request =
        new SubscriptionRequest(
            null,
            "Bad interval",
            null,
            null,
            Currency.USD,
            new BigDecimal("1.00"),
            BillingFrequency.CUSTOM,
            0,
            LocalDate.now().plusDays(1));

    var response = call(HttpMethod.POST, BASE, customer, request, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    var error = response.getBody();
    assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    assertThat(error.fieldErrors())
        .extracting(ApiError.FieldError::field)
        .containsExactly("billingIntervalDays");
    assertThat(subscriptionRepo.findByCustomerId(customer.id())).isEmpty();
  }

  @Test
  void pauseResumeCancel_persistEachTransitionAndAuditIt() {
    var customer = registerAndLogin();
    var created = create(customer, customNameRequest("Lifecycle"));
    var originalDate = created.nextPaymentDate();

    var paused = transition(customer, created.id(), "pause");
    assertThat(paused.status()).isEqualTo(SubscriptionStatus.PAUSED);
    var pausedRow = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(pausedRow.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
    assertThat(pausedRow.getNextPaymentDate()).isEqualTo(originalDate);

    var resumed = transition(customer, created.id(), "resume");
    assertThat(resumed.status()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(subscriptionRepo.findById(created.id()).orElseThrow().getNextPaymentDate())
        .isEqualTo(originalDate);

    var cancelled = transition(customer, created.id(), "cancel");
    assertThat(cancelled.status()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(cancelled.nextPaymentDate()).isNull();
    assertThat(cancelled.cancellationReason()).isEqualTo(CancellationReason.CUSTOMER_REQUESTED);
    var cancelledRow = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(cancelledRow.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    assertThat(cancelledRow.getNextPaymentDate()).isNull();
    assertThat(cancelledRow.getCancellationReason())
        .isEqualTo(CancellationReason.CUSTOMER_REQUESTED);
    assertThat(cancelledRow.getCancelledAt()).isNotNull();

    assertThat(auditCount(customer, AuditAction.SUBSCRIPTION_PAUSED, created.id())).isEqualTo(1);
    assertThat(auditCount(customer, AuditAction.SUBSCRIPTION_RESUMED, created.id())).isEqualTo(1);
    assertThat(auditCount(customer, AuditAction.SUBSCRIPTION_CANCELLED, created.id())).isEqualTo(1);
  }

  @Test
  void resumeAfterMissedBillingCycles_persistsAnAdvancedNextPaymentDate() {
    var customer = registerAndLogin();
    var created = create(customer, customNameRequest("Missed cycles"));
    transition(customer, created.id(), "pause");
    jdbc.update(
        "update customer_subscriptions set next_payment_date = ? where id = ?",
        LocalDate.now().minusDays(70),
        created.id());

    var resumed = transition(customer, created.id(), "resume");

    var today = LocalDate.now();
    assertThat(resumed.nextPaymentDate()).isBetween(today, today.plusMonths(1));
    assertThat(subscriptionRepo.findById(created.id()).orElseThrow().getNextPaymentDate())
        .isEqualTo(resumed.nextPaymentDate());
  }

  @Test
  void invalidTransitions_return409AndLeaveTheRowUnchanged() {
    var customer = registerAndLogin();
    var active = create(customer, customNameRequest("Active one"));
    var cancelled = create(customer, customNameRequest("Cancelled one"));
    transition(customer, cancelled.id(), "cancel");

    var resumeActive =
        call(HttpMethod.POST, BASE + "/" + active.id() + "/resume", customer, null, ApiError.class);
    var cancelTwice =
        call(
            HttpMethod.POST,
            BASE + "/" + cancelled.id() + "/cancel",
            customer,
            null,
            ApiError.class);
    var pauseCancelled =
        call(
            HttpMethod.POST,
            BASE + "/" + cancelled.id() + "/pause",
            customer,
            null,
            ApiError.class);

    for (var response : List.of(resumeActive, cancelTwice, pauseCancelled)) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION.name());
    }
    assertThat(subscriptionRepo.findById(active.id()).orElseThrow().getStatus())
        .isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(subscriptionRepo.findById(cancelled.id()).orElseThrow().getStatus())
        .isEqualTo(SubscriptionStatus.CANCELLED);
  }

  @Test
  void update_persistsFieldsKeepsTheStoredProviderAndBumpsTheVersion() {
    var customer = registerAndLogin();
    var netflixId = catalogProviderId("Netflix");
    var created =
        create(
            customer,
            new SubscriptionRequest(
                netflixId,
                "Family plan",
                "old-account",
                null,
                Currency.USD,
                new BigDecimal("15.49"),
                BillingFrequency.MONTHLY,
                null,
                LocalDate.now().plusDays(5)));
    var update =
        new SubscriptionUpdateRequest(
            "Updated label",
            "new-account",
            Currency.EUR,
            new BigDecimal("5.55"),
            BillingFrequency.WEEKLY,
            null);

    var response =
        call(
            HttpMethod.PUT,
            BASE + "/" + created.id(),
            customer,
            update,
            SubscriptionResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().providerName()).isEqualTo("Netflix");
    var row = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(row.getProviderId()).isEqualTo(netflixId);
    assertThat(row.getCustomName()).isEqualTo("Updated label");
    assertThat(row.getAccountIdentifier()).isEqualTo("new-account");
    assertThat(row.getCurrency()).isEqualTo(Currency.EUR);
    assertThat(row.getAmount()).isEqualByComparingTo("5.55");
    assertThat(row.getBillingFrequency()).isEqualTo(BillingFrequency.WEEKLY);
    assertThat(row.getVersion()).isEqualTo(1);
    assertThat(auditCount(customer, AuditAction.SUBSCRIPTION_UPDATED, created.id())).isEqualTo(1);
  }

  @Test
  void updateThatWouldClearTheOnlyIdentity_returns400AndLeavesTheRowUnchanged() {
    var customer = registerAndLogin();
    var created = create(customer, customNameRequest("Only identity"));
    var update =
        new SubscriptionUpdateRequest(
            null,
            "account-1",
            Currency.USD,
            new BigDecimal("9.99"),
            BillingFrequency.MONTHLY,
            null);

    var response =
        call(HttpMethod.PUT, BASE + "/" + created.id(), customer, update, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_SUBSCRIPTION_IDENTITY.name());
    var row = subscriptionRepo.findById(created.id()).orElseThrow();
    assertThat(row.getCustomName()).isEqualTo("Only identity");
    assertThat(row.getVersion()).isZero();
  }

  @Test
  void customersCannotSeeOrChangeEachOthersSubscriptions() {
    var owner = registerAndLogin();
    var intruder = registerAndLogin();
    var owned = create(owner, customNameRequest("Owner's"));
    var update =
        new SubscriptionUpdateRequest(
            "Hijacked", "x", Currency.USD, new BigDecimal("1.00"), BillingFrequency.MONTHLY, null);

    var attempts =
        List.of(
            call(HttpMethod.GET, BASE + "/" + owned.id(), intruder, null, ApiError.class),
            call(HttpMethod.PUT, BASE + "/" + owned.id(), intruder, update, ApiError.class),
            call(
                HttpMethod.POST,
                BASE + "/" + owned.id() + "/pause",
                intruder,
                null,
                ApiError.class),
            call(
                HttpMethod.POST,
                BASE + "/" + owned.id() + "/resume",
                intruder,
                null,
                ApiError.class),
            call(
                HttpMethod.POST,
                BASE + "/" + owned.id() + "/cancel",
                intruder,
                null,
                ApiError.class));

    for (var response : attempts) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(response.getBody().code()).isEqualTo(ErrorCode.NOT_FOUND.name());
    }
    var untouched = subscriptionRepo.findById(owned.id()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(untouched.getCustomName()).isEqualTo("Owner's");
    assertThat(untouched.getVersion()).isZero();

    var intruderList = call(HttpMethod.GET, BASE, intruder, null, SubscriptionResponse[].class);
    var ownerList = call(HttpMethod.GET, BASE, owner, null, SubscriptionResponse[].class);
    assertThat(intruderList.getBody()).isEmpty();
    assertThat(ownerList.getBody())
        .extracting(SubscriptionResponse::id)
        .containsExactly(owned.id());
  }

  @Test
  void requestWithoutAToken_returns401() {
    var response = restTemplate.getForEntity(BASE, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void malformedSubscriptionId_returns400() {
    var customer = registerAndLogin();

    var response = call(HttpMethod.GET, BASE + "/not-a-uuid", customer, null, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
  }

  @Test
  void missingSubscriptionIdSegment_reachesTheNoHandlerFoundHandlerInTheRealApp() {
    var customer = registerAndLogin();
    var update =
        new SubscriptionUpdateRequest(
            "x", "x", Currency.USD, new BigDecimal("1.00"), BillingFrequency.MONTHLY, null);

    var response = call(HttpMethod.PUT, BASE + "/", customer, update, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
    assertThat(response.getBody().message()).isEqualTo("No matching route for this request.");
  }
}
