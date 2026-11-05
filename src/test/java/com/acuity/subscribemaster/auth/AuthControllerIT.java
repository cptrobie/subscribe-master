package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogRepository;
import com.acuity.subscribemaster.auth.dto.*;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.error.ErrorCode;
import com.acuity.subscribemaster.idempotency.IdempotencyPk;
import com.acuity.subscribemaster.idempotency.IdempotencyRepository;
import com.acuity.subscribemaster.idempotency.IdempotencyService;
import com.acuity.subscribemaster.support.PemKeys;
import com.acuity.subscribemaster.support.Tokens;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end registration coverage against a real Postgres + Vault, following the
 * Testcontainers/Vault bootstrap pattern established in SubscribeMasterApplicationIT. Exercises
 * what AuthControllerTest/AuthServiceTest structurally cannot: real BCrypt hash persistence and the
 * real (not hand-built) Jackson/Bean Validation wiring. Does NOT verify citext's case-insensitive
 * matching -- email is normalized to lowercase before reaching AuthService (see
 * duplicateEmailDifferentCase_isRejectedByExistsByEmailCheck), so this suite genuinely cannot
 * confirm whether citext's DB-level behavior is correct.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import=vault://")
@AutoConfigureTestRestTemplate
public class AuthControllerIT {

  private static final String VAULT_TOKEN = "test-root-token";
  private static final String REPLAYED_HEADER = "Idempotent-Replayed";

  @Value("${jwt.public-key}")
  private String jwtPublicKeyPem;

  private RSAPublicKey jwtPublicKey;

  @Value("${app.login-lockout.max-attempts}")
  private int maxLoginAttempts;

  @Value("${app.login-lockout.duration-minutes}")
  private int lockoutDurationMinutes;

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine")
          .withDatabaseName("subscribe_master")
          .withUsername("subscribe_master")
          .withPassword("test_only_not_a_real_secret");

  @Container
  static final GenericContainer<?> vault =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.17"))
          .withExposedPorts(8200)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", VAULT_TOKEN)
          .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
          .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200));

  @RegisterExtension
  static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private CustomerRepository customerRepo;
  @Autowired private CustomerEmailVerificationTokenRepository tokenRepo;
  @Autowired private IdempotencyRepository idemRepo;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AuditLogRepository auditLogRepo;

  @BeforeAll
  static void configureAndSeedVault() throws Exception {
    System.setProperty("spring.cloud.vault.host", vault.getHost());
    System.setProperty("spring.cloud.vault.port", String.valueOf(vault.getMappedPort(8200)));
    System.setProperty("spring.cloud.vault.scheme", "http");
    System.setProperty("spring.cloud.vault.authentication", "TOKEN");
    System.setProperty("spring.cloud.vault.token", VAULT_TOKEN);

    var loginResult =
        vault.execInContainer("vault", "login", "-address=http://127.0.0.1:8200", VAULT_TOKEN);
    if (loginResult.getExitCode() != 0) {
      throw new IllegalStateException("vault login failed: " + loginResult.getStderr());
    }

    var putResult =
        vault.execInContainer(
            "vault",
            "kv",
            "put",
            "-address=http://127.0.0.1:8200",
            "secret/subscribe-master",
            "spring.datasource.username=subscribe_master",
            "spring.datasource.password=test_only_not_a_real_secret");
    if (putResult.getExitCode() != 0) {
      throw new IllegalStateException("vault kv put failed: " + putResult.getStderr());
    }

    String testPrivateKeyPem = readTestResource("test-jwt-keys/jwt-private.pem");
    String testPublicKeyPem = readTestResource("test-jwt-keys/jwt-public.pem");

    putResult =
        vault.execInContainer(
            "vault",
            "kv",
            "put",
            "-address=http://127.0.0.1:8200",
            "secret/subscribe-master",
            "spring.datasource.username=subscribe_master",
            "spring.datasource.password=test_only_not_a_real_secret",
            "jwt.private-key=" + testPrivateKeyPem,
            "jwt.public-key=" + testPublicKeyPem,
            "app.idempotency.hmac-secret=test_only_not_a_real_secret");
    if (putResult.getExitCode() != 0) {
      throw new IllegalStateException("vault kv put failed: " + putResult.getStderr());
    }
  }

  private static String readTestResource(String classpathLocation) throws IOException {
    var resource = new ClassPathResource(classpathLocation);
    try (var inputStream = resource.getInputStream()) {
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @BeforeEach
  void parseJwtPublicKey() throws Exception {
    jwtPublicKey = PemKeys.parsePublicKey(jwtPublicKeyPem);
  }

  @AfterAll
  static void clearVaultSystemProperties() {
    System.clearProperty("spring.cloud.vault.host");
    System.clearProperty("spring.cloud.vault.port");
    System.clearProperty("spring.cloud.vault.scheme");
    System.clearProperty("spring.cloud.vault.authentication");
    System.clearProperty("spring.cloud.vault.token");
  }

  @DynamicPropertySource
  static void registerContainerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.mail.host", () -> "localhost");
    registry.add("spring.mail.port", () -> 3025);
  }

  @Test
  void registerNewCustomer_persistsHashedPasswordAndAuditEvent() {
    var email = "new.customer@example.com";
    var rawPassword = "easyPassword!#123";
    var request = new RegistrationRequest(email, rawPassword);

    ResponseEntity<RegistrationResponse> response =
        restTemplate.postForEntity("/api/v1/auth/register", request, RegistrationResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.email()).isEqualTo(email);
    assertThat(body.emailVerified()).isFalse();
    assertThat(body.id()).isNotNull();
    assertThat(body.createdAt()).isNotNull();

    var savedCustomer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(savedCustomer.getPasswordHash()).isNotEqualTo(rawPassword);
    assertThat(passwordEncoder.matches(rawPassword, savedCustomer.getPasswordHash())).isTrue();

    var auditEvents =
        auditLogRepo.findByActorIdAndAction(savedCustomer.getId(), AuditAction.REGISTERED);
    assertThat(auditEvents).hasSize(1);
    assertThat(auditEvents.get(0).getResource()).isEqualTo("auth");
    assertThat(auditEvents.get(0).getResourceId()).isEqualTo(savedCustomer.getId());
  }

  @Test
  void register_thenVerifyLink_marksEmailVerifiedAndIsSafeToRepeat() throws Exception {
    // Arrange -- register, which sends the verification email after commit
    var email = "verify.roundtrip@example.com";
    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, "easyPassword!#123"),
        RegistrationResponse.class);
    var customer = customerRepo.findByEmail(email).orElseThrow();

    // The email arrived, to the right person, from the configured sender
    MimeMessage[] received = greenMail.getReceivedMessages();
    assertThat(received).hasSize(1);
    var mail = received[0];
    assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
    assertThat(mail.getSubject()).contains("Subscribe Master");
    var from = (InternetAddress) mail.getFrom()[0];
    assertThat(from.getAddress()).isEqualTo("no-reply@localhost.test");
    assertThat(from.getPersonal()).isEqualTo("Subscribe Master");

    // Pull the raw token out of the link -- getContent() decodes the transfer encoding
    var body = (String) mail.getContent();
    var matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(body);
    assertThat(matcher.find()).isTrue();
    var rawToken = matcher.group(1);

    // Act -- open the link
    var first =
        restTemplate.getForEntity(
            "/api/v1/auth/verify-email?token={token}", EmailVerificationResponse.class, rawToken);

    // Assert -- verified, and actually saved to the database (not just in memory)
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody()).isEqualTo(EmailVerificationResponse.verified());
    assertThat(customerRepo.findById(customer.getId()).orElseThrow().getIsEmailVerified()).isTrue();
    assertThat(tokenRepo.findByTokenHash(Tokens.hash(rawToken)).orElseThrow().isUsed()).isTrue();
    assertThat(
            auditLogRepo.findByActorIdAndAction(
                customer.getId(), AuditAction.EMAIL_VERIFICATION_SENT))
        .hasSize(1);
    assertThat(auditLogRepo.findByActorIdAndAction(customer.getId(), AuditAction.EMAIL_VERIFIED))
        .hasSize(1);

    // Act + Assert -- opening it again is harmless (the link-scanner case)
    var second =
        restTemplate.getForEntity(
            "/api/v1/auth/verify-email?token={token}", EmailVerificationResponse.class, rawToken);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getBody()).isEqualTo(EmailVerificationResponse.alreadyVerified());
    assertThat(auditLogRepo.findByActorIdAndAction(customer.getId(), AuditAction.EMAIL_VERIFIED))
        .hasSize(1);
  }

  @Test
  void duplicateEmail_returnsConflictAndDoesNotDuplicateRow() {
    var email = "duplicate@example.com";
    var request = new RegistrationRequest(email, "easyPassword!#123");

    restTemplate.postForEntity("/api/v1/auth/register", request, RegistrationResponse.class);

    ResponseEntity<ApiError> secondAttempt =
        restTemplate.postForEntity("/api/v1/auth/register", request, ApiError.class);

    assertThat(secondAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(secondAttempt.getBody()).isNotNull();
    assertThat(secondAttempt.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_ALREADY_EXISTS.name());
    assertThat(customerRepo.existsByEmail(email)).isTrue();
  }

  @Test
  void duplicateEmailDifferentCase_isRejectedByExistsByEmailCheck() {
    var request = new RegistrationRequest("Case.Test@Example.com", "easyPassword!#123");
    restTemplate.postForEntity("/api/v1/auth/register", request, RegistrationResponse.class);

    var sameEmailDifferentCase =
        new RegistrationRequest("case.test@example.com", "anotherPassword!#456");

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/v1/auth/register", sameEmailDifferentCase, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_ALREADY_EXISTS.name());

    // RegistrationRequest normalizes email to lowercase before it ever reaches AuthService,
    // so both requests above resolve to the identical string "case.test@example.com" --
    // existsByEmail() gets a plain exact match. This was a deliberate choice to sidestep
    // diagnosing whether citext's case-insensitive matching is actually working correctly
    // at the database level (CustomerRepository's Javadoc claims it should be) -- that's
    // genuinely unverified, not confirmed broken, and worth a real investigation later if
    // it matters (e.g. once a client that can't normalize email itself needs to rely on it).
    var savedCustomer = customerRepo.findByEmail("case.test@example.com").orElseThrow();
    assertThat(savedCustomer.getEmail()).isEqualTo("case.test@example.com");

    var rejections =
        auditLogRepo.findByActorIdAndAction(
            savedCustomer.getId(), AuditAction.REGISTRATION_REJECTED);
    assertThat(rejections).hasSize(1);
    assertThat(rejections.get(0).getActorType()).isEqualTo(ActorType.CUSTOMER);
  }

  @Test
  void invalidBody_returnsValidationFailedWithFieldErrors() {
    var request = new RegistrationRequest("not-an-email", "short");

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/v1/auth/register", request, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
    assertThat(response.getBody().fieldErrors())
        .extracting(ApiError.FieldError::field)
        .contains("email", "password");
  }

  @Test
  void malformedJsonBody_returnsBadRequest() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var entity = new HttpEntity<>("{not valid json", headers);

    ResponseEntity<ApiError> response =
        restTemplate.postForEntity("/api/v1/auth/register", entity, ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
  }

  @Test
  void registerThenLogin_issuesValidSignedJwtForTheCorrectCustomer() throws Exception {
    var email = "login.roundtrip@example.com";
    var rawPassword = "easyPassword!#123";

    var regResponse =
        restTemplate.postForEntity(
            "/api/v1/auth/register",
            new RegistrationRequest(email, rawPassword),
            RegistrationResponse.class);

    var customer = customerRepo.findByEmail(regResponse.getBody().email()).orElseThrow();
    customer.markEmailVerified();
    customerRepo.saveAndFlush(customer);

    var logResponse =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, rawPassword), LoginResponse.class);

    assertThat(logResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = logResponse.getBody();
    assertThat(body).isNotNull();
    assertThat(body.token()).isNotBlank();
    assertThat(body.expiresAt()).isNotNull().isAfter(Instant.now());

    var savedCustomer = customerRepo.findByEmail(email).orElseThrow();

    // Prove the token is a genuinely valid, correctly-signed JWT -- not just a
    // non-blank string -- by parsing it and verifying its signature against the
    // real public key, then checking its claims match the customer who logged in.
    SignedJWT signedJwt = SignedJWT.parse(body.token());
    RSASSAVerifier verifier = new RSASSAVerifier(jwtPublicKey);
    assertThat(signedJwt.verify(verifier)).isTrue();
    assertThat(signedJwt.getJWTClaimsSet().getSubject())
        .isEqualTo(savedCustomer.getId().toString());
    assertThat(signedJwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isCloseTo(body.expiresAt(), within(2, ChronoUnit.SECONDS));
  }

  @Test
  void loginWithWrongPassword_returnsUnauthorized() {
    var email = "addme@example.com";
    var rawPassword = "easyPassword!#123";
    var wrongPassword = "wrongPassword!#123";

    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, rawPassword),
        RegistrationResponse.class);

    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, wrongPassword), ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
  }

  @Test
  void loginWithMaxWrongPasswords_locksAccountAndPersistsLock() {
    // Arrange
    var email = "lockout.persist@example.com";
    var rawPassword = "easyPassword!#123";
    var wrongPassword = "wrongPassword!#123";

    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, rawPassword),
        RegistrationResponse.class);

    // Act + Assert -- every failure below the threshold is a plain 401
    for (int attempt = 1; attempt < maxLoginAttempts; attempt++) {
      var response =
          restTemplate.postForEntity(
              "/api/v1/auth/login", new LoginRequest(email, wrongPassword), ApiError.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
    }

    // Act + Assert -- the failure that reaches the threshold locks the account
    var lockingResponse =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, wrongPassword), ApiError.class);
    assertThat(lockingResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(lockingResponse.getBody()).isNotNull();
    assertThat(lockingResponse.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.name());

    // Assert -- the lock was actually saved to the database, not rolled back with the exception
    var lockedCustomer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(lockedCustomer.getFailedLoginCount()).isEqualTo(maxLoginAttempts);
    assertThat(lockedCustomer.getLockedUntil())
        .isNotNull()
        .isCloseTo(
            Instant.now().plus(lockoutDurationMinutes, ChronoUnit.MINUTES),
            within(5, ChronoUnit.SECONDS));
    assertThat(
            auditLogRepo.findByActorIdAndAction(lockedCustomer.getId(), AuditAction.ACCOUNT_LOCKED))
        .hasSize(1);

    // Act + Assert -- while locked, even the correct password is refused
    var correctPasswordResponse =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, rawPassword), ApiError.class);
    assertThat(correctPasswordResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(correctPasswordResponse.getBody()).isNotNull();
    assertThat(correctPasswordResponse.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.name());
  }

  @Test
  void
      loginBeforeEmailVerified_returnsForbiddenAndPersistsCounterReset_thenSucceedsAfterVerifying() {
    // Arrange
    var email = "unverified.persist@example.com";
    var rawPassword = "easyPassword!#123";
    var wrongPassword = "wrongPassword!#123";

    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, rawPassword),
        RegistrationResponse.class);

    // Act + Assert -- every failure below the threshold is a plain 401
    for (int attempt = 1; attempt < maxLoginAttempts; attempt++) {
      var response =
          restTemplate.postForEntity(
              "/api/v1/auth/login", new LoginRequest(email, wrongPassword), ApiError.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
    }

    // Act + Assert -- login using correct password however, email has NOT been verified
    var unverifiedResponse =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, rawPassword), ApiError.class);
    assertThat(unverifiedResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(unverifiedResponse.getBody()).isNotNull();
    assertThat(unverifiedResponse.getBody().code()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED.name());

    // Assert + Arrange
    var resetCustomer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(resetCustomer.getFailedLoginCount()).isEqualTo(0);
    assertThat(resetCustomer.getLockedUntil()).isNull();

    resetCustomer.markEmailVerified(); // verified directly
    customerRepo.saveAndFlush(resetCustomer);

    // Act + Assert a successful login
    var successfulResponse =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, rawPassword), LoginResponse.class);
    assertThat(successfulResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(successfulResponse.getBody().token()).isNotBlank();
  }

  @Test
  void loginWithWrongPasswordAfterLockExpires_restartsFailedCountAtOne() {
    // Arrange -- put the account in the state a real lock leaves behind once it runs out:
    // the counter still at the threshold, lockedUntil just in the past
    var email = "lockout.expired@example.com";
    var wrongPassword = "wrongPassword!#123";

    restTemplate.postForEntity(
        "/api/v1/auth/register",
        new RegistrationRequest(email, "easyPassword!#123"),
        RegistrationResponse.class);

    var customer = customerRepo.findByEmail(email).orElseThrow();
    customer.setFailedLoginCount(maxLoginAttempts);
    customer.setLockedUntil(Instant.now().minus(1, ChronoUnit.MINUTES));
    customerRepo.saveAndFlush(customer);

    // Act -- one wrong password after the lock has expired
    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/login", new LoginRequest(email, wrongPassword), ApiError.class);

    // Assert -- treated as a first failure, not an immediate re-lock
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());

    var savedCustomer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(savedCustomer.getFailedLoginCount()).isEqualTo(1);
    assertThat(savedCustomer.getLockedUntil()).isNull();
    assertThat(auditLogRepo.findByActorIdAndAction(customer.getId(), AuditAction.ACCOUNT_LOCKED))
        .isEmpty();
  }

  @Test
  void loginWithNonexistentEmail_returnsIdenticalResponseToWrongPassword() {
    // No registration here -- this email was never created. The whole point of
    // this test is that the response is INDISTINGUISHABLE from a wrong password
    // against a real account (see AuthService's Javadoc on enumeration protection).
    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/login",
            new LoginRequest("never.registered@example.com", "anyPassword!#123"),
            ApiError.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
  }

  @Test
  void register_mailServerDown_stillCreatesAccountAndAuditsSendFailure() {
    // Arrange -- take the SMTP server down, so the after-commit send fails
    greenMail.stop();
    var email = "mail.outage@example.com";

    // Act
    ResponseEntity<RegistrationResponse> response =
        restTemplate.postForEntity(
            "/api/v1/auth/register",
            new RegistrationRequest(email, "easyPassword!#123"),
            RegistrationResponse.class);

    // Assert -- registration is unaffected by the mail failure (decision B)
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var customer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(customer.getIsEmailVerified()).isFalse();

    // The token was still saved -- it committed with the customer, before the send was attempted
    assertThat(tokenRepo.findAll())
        .anyMatch(t -> t.getCustomerId().equals(customer.getId()) && !t.isUsed());

    // Nothing claims the email went out, but the failed send is recorded
    assertThat(auditLogRepo.findByActorIdAndAction(customer.getId(), AuditAction.REGISTERED))
        .hasSize(1);
    assertThat(
            auditLogRepo.findByActorIdAndAction(
                customer.getId(), AuditAction.EMAIL_VERIFICATION_SENT))
        .isEmpty();
    assertThat(
            auditLogRepo.findByActorIdAndAction(
                customer.getId(), AuditAction.EMAIL_VERIFICATION_SEND_FAILED))
        .hasSize(1);
  }

  // --- NFR-33: Idempotency-Key on register ---

  @Test
  void sameKeyTwice_registersOnce_andReplaysIdenticalResponse() {
    // Arrange
    var email = "register.thenReuse@example.com";
    var rawPassword = "easyPassword!#123";
    var reqKey = UUID.randomUUID().toString();
    var regRequest = new RegistrationRequest(email, rawPassword);

    // Act
    var regOrigResponse = register(regRequest, reqKey, RegistrationResponse.class);
    var customerId = regOrigResponse.getBody().id();
    var regDupResponse = register(regRequest, reqKey, RegistrationResponse.class);

    // Assert
    var row = idemRepo.findById(new IdempotencyPk(reqKey, AuthController.REGISTER_ENDPOINT));
    assertThat(row).isPresent();
    assertThat(row.get().getResponseStatus()).isEqualTo(201);

    assertThat(regOrigResponse.getHeaders().getFirst(REPLAYED_HEADER)).isNull();
    assertThat(regDupResponse.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");

    assertThat(regOrigResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(regDupResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    assertThat(regDupResponse.getBody()).isEqualTo(regOrigResponse.getBody());

    assertThat(auditLogRepo.findByActorIdAndAction(customerId, AuditAction.REGISTERED)).hasSize(1);
    assertThat(tokenRepo.findAll())
        .filteredOn(t -> t.getCustomerId().equals(customerId))
        .hasSize(1);

    greenMail.waitForIncomingEmail(5000, 1);
    assertThat(greenMail.getReceivedMessagesForDomain(email)).hasSize(1);
  }

  @Test
  void sameKeyDifferentBody_returnsMismatch_andRegistersNothing() {
    // Arrange
    var origEmail = "register.mismatch.orig@example.com";
    var diffEmail = "register.mismatch.diff@example.com";
    var rawPassword = "easyPassword!#123";
    var reqKey = UUID.randomUUID().toString();
    var origRegRequest = new RegistrationRequest(origEmail, rawPassword);
    var diffRegRequest = new RegistrationRequest(diffEmail, rawPassword);

    // Act
    var origResponse = register(origRegRequest, reqKey, RegistrationResponse.class);
    var errorResponse = register(diffRegRequest, reqKey, ApiError.class);

    // Assert
    assertThat(customerRepo.existsByEmail(diffEmail)).isFalse();

    assertThat(origResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    assertThat(errorResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(errorResponse.getBody()).isNotNull();
    assertThat(errorResponse.getBody().code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_MISMATCH.name());
  }

  @Test
  void overLongKey_returnsMalformedRequest() {
    // Arrange
    var email = "register.longKey@example.com";
    var rawPassword = "easyPassword!#123";
    var tooLongKey = "a".repeat(IdempotencyService.MAX_KEY_LENGTH + 1);
    var tooLongRequest = new RegistrationRequest(email, rawPassword);

    // Act
    var tooLongResponse = register(tooLongRequest, tooLongKey, ApiError.class);

    // Assert
    assertThat(customerRepo.existsByEmail(email)).isFalse();

    assertThat(tooLongResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tooLongResponse.getBody()).isNotNull();
    assertThat(tooLongResponse.getBody().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST.name());
    assertThat(tooLongResponse.getBody().message())
        .contains(String.valueOf(IdempotencyService.MAX_KEY_LENGTH));
  }

  /**
   * Two requests with the same key, released together. Which one wins, and whether they actually
   * overlap, can't be controlled, so this only asserts what must hold in every interleaving: both
   * get the same 201, exactly one is a replay, and the customer was registered (and emailed) once.
   * If they happen not to overlap it still passes, as an ordinary replay -- the lost-race paths are
   * covered deterministically in IdempotencyServiceTest. Deliberately says nothing about
   * REGISTRATION_REJECTED audits: a loser that reaches existsByEmail after the winner commits
   * writes one (REQUIRES_NEW), and one that blocks on the insert doesn't.
   */
  @Test
  void concurrentSameKey_registersOnce_bothGetTheSameResponse() throws Exception {
    // Arrange
    var email = "register.concurrent@example.com";
    var reqKey = UUID.randomUUID().toString();
    var regRequest = new RegistrationRequest(email, "easyPassword!#123");
    var start = new CountDownLatch(1);
    Callable<ResponseEntity<RegistrationResponse>> call =
        () -> {
          start.await(); // hold both threads until both are ready, then release them together
          return register(regRequest, reqKey, RegistrationResponse.class);
        };

    // Act
    ResponseEntity<RegistrationResponse> first;
    ResponseEntity<RegistrationResponse> second;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var firstFuture = pool.submit(call);
      var secondFuture = pool.submit(call);
      start.countDown();
      first = firstFuture.get(30, TimeUnit.SECONDS);
      second = secondFuture.get(30, TimeUnit.SECONDS);
    }

    // Assert: both succeeded with the same response, exactly one of them a replay
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(second.getBody()).isEqualTo(first.getBody());
    assertThat(List.of(first, second))
        .filteredOn(r -> "true".equals(r.getHeaders().getFirst(REPLAYED_HEADER)))
        .hasSize(1);

    // Assert: registered once
    var customerId = first.getBody().id();
    assertThat(auditLogRepo.findByActorIdAndAction(customerId, AuditAction.REGISTERED)).hasSize(1);
    assertThat(tokenRepo.findAll())
        .filteredOn(t -> t.getCustomerId().equals(customerId))
        .hasSize(1);
    greenMail.waitForIncomingEmail(5000, 1);
    assertThat(greenMail.getReceivedMessagesForDomain(email)).hasSize(1);
  }

  // --- FR-40: resend verification email ---

  @Test
  void resend_oldLinkStopsWorking_newLinkVerifies() throws Exception {
    // Arrange -- register, and keep the first link
    var email = "resend.oldlink@example.com";
    var rawPassword = "easyPassword!#123";
    registerWithoutKey(email, rawPassword);
    greenMail.waitForIncomingEmail(5000, 1);
    var firstToken = tokenFrom(greenMail.getReceivedMessages()[0]);

    // Act
    var response =
        resend(
            new ResendVerificationRequest(email, rawPassword, null),
            null,
            ResendVerificationResponse.class);

    // Assert -- accepted, and a second, different link went to the same address
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isEqualTo(ResendVerificationResponse.sentTo(email));

    assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
    var resent = greenMail.getReceivedMessages()[1];
    assertThat(resent.getAllRecipients()[0].toString()).isEqualTo(email);
    var secondToken = tokenFrom(resent);
    assertThat(secondToken).isNotEqualTo(firstToken);

    // Assert -- the superseded link is refused...
    var oldLink =
        restTemplate.getForEntity(
            "/api/v1/auth/verify-email?token={token}", ApiError.class, firstToken);
    assertThat(oldLink.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(oldLink.getBody()).isNotNull();
    assertThat(oldLink.getBody().code()).isEqualTo(ErrorCode.INVALID_VERIFICATION_TOKEN.name());

    // ...and the new one verifies the account
    var newLink =
        restTemplate.getForEntity(
            "/api/v1/auth/verify-email?token={token}",
            EmailVerificationResponse.class,
            secondToken);
    assertThat(newLink.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(newLink.getBody()).isEqualTo(EmailVerificationResponse.verified());
    assertThat(customerRepo.findByEmail(email).orElseThrow().getIsEmailVerified()).isTrue();
  }

  @Test
  void resend_newEmail_changesAddressSendsToNewAndAudits() throws Exception {
    // Arrange
    var email = "resend.typo@exmaple.com";
    var newEmail = "resend.fixed@example.com";
    var rawPassword = "easyPassword!#123";
    var customerId = registerWithoutKey(email, rawPassword);

    // Act
    var response =
        resend(
            new ResendVerificationRequest(email, rawPassword, newEmail),
            null,
            ResendVerificationResponse.class);

    // Assert -- the response and the new link both go to the corrected address
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getBody()).isEqualTo(ResendVerificationResponse.sentTo(newEmail));

    assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
    var resent = greenMail.getReceivedMessages()[1];
    assertThat(resent.getAllRecipients()[0].toString()).isEqualTo(newEmail);

    // Assert -- the address change was saved and audited
    assertThat(customerRepo.findByEmail(newEmail).orElseThrow().getId()).isEqualTo(customerId);
    assertThat(customerRepo.findByEmail(email)).isEmpty();
    assertThat(auditLogRepo.findByActorIdAndAction(customerId, AuditAction.EMAIL_CHANGED))
        .hasSize(1);

    // Assert -- the new link verifies the account under its new address
    var newLink =
        restTemplate.getForEntity(
            "/api/v1/auth/verify-email?token={token}",
            EmailVerificationResponse.class,
            tokenFrom(resent));
    assertThat(newLink.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(customerRepo.findById(customerId).orElseThrow().getIsEmailVerified()).isTrue();
  }

  @Test
  void resend_wrongPasswordWithFreshKeys_stillLocksAccount() {
    // Arrange
    var email = "resend.lockout@example.com";
    var rawPassword = "easyPassword!#123";
    var wrongPassword = "wrongPassword!#123";
    registerWithoutKey(email, rawPassword);
    var badRequest = new ResendVerificationRequest(email, wrongPassword, null);

    // Act + Assert -- a new Idempotency-Key per guess must not reset or dodge the lockout
    for (int attempt = 1; attempt < maxLoginAttempts; attempt++) {
      var key = UUID.randomUUID().toString();
      var response = resend(badRequest, key, ApiError.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());
      // a rejected request stores nothing under its key
      assertThat(idemRepo.findById(new IdempotencyPk(key, AuthController.RESEND_ENDPOINT)))
          .isEmpty();
    }

    var lockingResponse = resend(badRequest, UUID.randomUUID().toString(), ApiError.class);
    assertThat(lockingResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(lockingResponse.getBody()).isNotNull();
    assertThat(lockingResponse.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.name());

    // Assert -- the failed count was committed even though each resend rolled back
    var lockedCustomer = customerRepo.findByEmail(email).orElseThrow();
    assertThat(lockedCustomer.getFailedLoginCount()).isEqualTo(maxLoginAttempts);
    assertThat(lockedCustomer.getLockedUntil()).isNotNull();

    // Assert -- while locked, even the correct password can't resend
    var correctPassword =
        resend(
            new ResendVerificationRequest(email, rawPassword, null),
            UUID.randomUUID().toString(),
            ApiError.class);
    assertThat(correctPassword.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(correctPassword.getBody()).isNotNull();
    assertThat(correctPassword.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED.name());
  }

  @Test
  void resend_thirdWithinWindowOfRegistration_returnsRateLimit() {
    // Arrange -- the registration token counts toward the limit
    var email = "resend.ratelimit@example.com";
    var rawPassword = "easyPassword!#123";
    var customerId = registerWithoutKey(email, rawPassword);
    var request = new ResendVerificationRequest(email, rawPassword, null);

    // Act + Assert -- registration plus two resends reaches the limit of three
    assertThat(resend(request, null, ResendVerificationResponse.class).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    assertThat(resend(request, null, ResendVerificationResponse.class).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);

    var limited = resend(request, null, ApiError.class);
    assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(limited.getBody()).isNotNull();
    assertThat(limited.getBody().code()).isEqualTo(ErrorCode.VERIFICATION_RESEND_LIMIT.name());

    // Assert -- the refused request issued and sent nothing
    assertThat(tokensFor(customerId)).hasSize(3);
    greenMail.waitForIncomingEmail(5000, 3);
    assertThat(greenMail.getReceivedMessages()).hasSize(3);
  }

  @Test
  void resend_alreadyVerified_returnsConflict() throws Exception {
    // Arrange -- register and verify
    var email = "resend.verified@example.com";
    var rawPassword = "easyPassword!#123";
    var customerId = registerWithoutKey(email, rawPassword);
    greenMail.waitForIncomingEmail(5000, 1);
    restTemplate.getForEntity(
        "/api/v1/auth/verify-email?token={token}",
        EmailVerificationResponse.class,
        tokenFrom(greenMail.getReceivedMessages()[0]));

    // Act
    var response =
        resend(new ResendVerificationRequest(email, rawPassword, null), null, ApiError.class);

    // Assert
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.EMAIL_ALREADY_VERIFIED.name());
    assertThat(tokensFor(customerId)).hasSize(1);
  }

  @Test
  void resend_newEmailTaken_returnsConflictAndChangesNothing() {
    // Arrange -- two accounts; the first tries to move onto the second's address
    var email = "resend.mover@example.com";
    var takenEmail = "resend.taken@example.com";
    var rawPassword = "easyPassword!#123";
    var customerId = registerWithoutKey(email, rawPassword);
    registerWithoutKey(takenEmail, rawPassword);

    // Act
    var response =
        resend(new ResendVerificationRequest(email, rawPassword, takenEmail), null, ApiError.class);

    // Assert -- refused, and the first account is exactly as it was
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.ACCOUNT_ALREADY_EXISTS.name());

    assertThat(customerRepo.findById(customerId).orElseThrow().getEmail()).isEqualTo(email);
    assertThat(auditLogRepo.findByActorIdAndAction(customerId, AuditAction.EMAIL_CHANGED))
        .isEmpty();
    assertThat(tokensFor(customerId)).hasSize(1);
  }

  @Test
  void resend_sameKeyAfterEmailChange_replaysWithoutResending() {
    // Arrange
    var email = "resend.replay@exmaple.com";
    var newEmail = "resend.replayed@example.com";
    var rawPassword = "easyPassword!#123";
    var customerId = registerWithoutKey(email, rawPassword);
    var key = UUID.randomUUID().toString();
    var request = new ResendVerificationRequest(email, rawPassword, newEmail);

    // Act -- the retry's body still names the old address, which no longer exists, so a
    // re-run would fail the credential check; it must be replayed instead
    var original = resend(request, key, ResendVerificationResponse.class);
    var retry = resend(request, key, ResendVerificationResponse.class);

    // Assert
    assertThat(original.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(original.getHeaders().getFirst(REPLAYED_HEADER)).isNull();
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(retry.getHeaders().getFirst(REPLAYED_HEADER)).isEqualTo("true");
    assertThat(retry.getBody()).isEqualTo(original.getBody());

    // Assert -- the change and the email happened once
    assertThat(auditLogRepo.findByActorIdAndAction(customerId, AuditAction.EMAIL_CHANGED))
        .hasSize(1);
    assertThat(tokensFor(customerId)).hasSize(2);
    greenMail.waitForIncomingEmail(5000, 2);
    assertThat(greenMail.getReceivedMessages()).hasSize(2);
  }

  @Test
  void resend_newEmailSameAddressDifferentCaseOrBlank_changesNothing() {
    // Arrange
    var email = "resend.normalize@example.com";
    var rawPassword = "easyPassword!#123";
    var customerId = registerWithoutKey(email, rawPassword);

    // Act -- a padded, upper-cased copy of the current address, then a blank one
    var sameAddress =
        resend(
            new ResendVerificationRequest(email, rawPassword, "  " + email.toUpperCase() + " "),
            null,
            ResendVerificationResponse.class);
    var blank =
        resend(
            new ResendVerificationRequest(email, rawPassword, "   "),
            null,
            ResendVerificationResponse.class);

    // Assert -- both resend to the current address, with no address change
    assertThat(sameAddress.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(sameAddress.getBody()).isEqualTo(ResendVerificationResponse.sentTo(email));
    assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(blank.getBody()).isEqualTo(ResendVerificationResponse.sentTo(email));

    assertThat(customerRepo.findById(customerId).orElseThrow().getEmail()).isEqualTo(email);
    assertThat(auditLogRepo.findByActorIdAndAction(customerId, AuditAction.EMAIL_CHANGED))
        .isEmpty();
  }

  /** Registers without an Idempotency-Key and returns the new customer's id. */
  private UUID registerWithoutKey(String email, String rawPassword) {
    var response =
        restTemplate.postForEntity(
            "/api/v1/auth/register",
            new RegistrationRequest(email, rawPassword),
            RegistrationResponse.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().id();
  }

  /** Posts a resend, sending the Idempotency-Key header only when {@code key} isn't null. */
  private <T> ResponseEntity<T> resend(
      ResendVerificationRequest request, String key, Class<T> type) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      headers.set("Idempotency-Key", key);
    }
    return restTemplate.postForEntity(
        "/api/v1/auth/resend-verification", new HttpEntity<>(request, headers), type);
  }

  /** Pulls the raw token out of a verification email's link. */
  private static String tokenFrom(MimeMessage mail) throws Exception {
    var matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher((String) mail.getContent());
    assertThat(matcher.find()).isTrue();
    return matcher.group(1);
  }

  private List<CustomerEmailVerificationToken> tokensFor(UUID customerId) {
    return tokenRepo.findAll().stream().filter(t -> t.getCustomerId().equals(customerId)).toList();
  }

  private <T> ResponseEntity<T> register(RegistrationRequest request, String key, Class<T> type) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return restTemplate.postForEntity(
        "/api/v1/auth/register", new HttpEntity<>(request, headers), type);
  }
}
