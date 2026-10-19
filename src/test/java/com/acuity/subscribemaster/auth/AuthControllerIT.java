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
            "jwt.public-key=" + testPublicKeyPem);
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
  void register_mailServerDown_stillCreatesAccountAndSkipsSentAudit() {
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

    // But nothing claims the email went out
    assertThat(auditLogRepo.findByActorIdAndAction(customer.getId(), AuditAction.REGISTERED))
        .hasSize(1);
    assertThat(
            auditLogRepo.findByActorIdAndAction(
                customer.getId(), AuditAction.EMAIL_VERIFICATION_SENT))
        .isEmpty();
  }
}
