package com.acuity.subscribemaster.auth;

import static com.acuity.subscribemaster.support.Tokens.hash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.EmailVerificationResponse;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
  @Mock private CustomerRepository customerRepo;
  @Mock private CredentialChecker credChecker;
  @Mock private CustomerEmailVerificationTokenRepository tokenRepo;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private AuditLogService auditLogService;

  private final EmailVerificationProperties emailVerifyProps =
      new EmailVerificationProperties(
          "http://localhost:8080/api/v1/auth/verify-email",
          Duration.ofHours(24),
          3,
          Duration.ofHours(1));

  private EmailVerificationService verifyService;

  @BeforeEach
  void setUp() {
    verifyService =
        new EmailVerificationService(
            customerRepo,
            credChecker,
            tokenRepo,
            emailVerifyProps,
            eventPublisher,
            auditLogService);
  }

  @Test
  void verify_validToken_verifiesCustomerMarksTokenUsedAndAudits() {
    // Arrange
    var customerId = UUID.randomUUID();
    var rawToken = "a raw token";
    var now = Instant.now();
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, "test@example.com", "a hashed password", false, true);
    var verifyToken =
        new CustomerEmailVerificationToken(
            customerId, hash(rawToken), now.plus(24, ChronoUnit.HOURS));

    when(tokenRepo.findByTokenHash(hash(rawToken))).thenReturn(Optional.of(verifyToken));
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

    // Act
    var response = verifyService.verify(rawToken, ipAddress);

    // Assert
    assertThat(response).isEqualTo(EmailVerificationResponse.verified());

    assertThat(verifyToken.isUsed()).isTrue();

    assertThat(customer.getIsEmailVerified()).isTrue();

    verify(auditLogService, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.EMAIL_VERIFIED,
            "auth",
            customerId,
            ipAddress);
  }

  @Test
  void verify_unknownToken_throwsInvalid() {
    // Arrange
    var rawToken = "a raw token";
    var ipAddress = "192.168.0.1";

    // never reaches customerRepo.findById(customerId) so customers object and
    // when statement not needed
    when(tokenRepo.findByTokenHash(any())).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> verifyService.verify(rawToken, ipAddress))
        .isInstanceOf(InvalidVerificationTokenException.class)
        .hasMessage("This verification link is invalid.");

    // Assert
    verify(tokenRepo, times(1)).findByTokenHash(any());
    verify(customerRepo, never()).findById(any());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void verify_tokenWithoutCustomer_throwsInvalid() {
    // Arrange
    var customerId = UUID.randomUUID();
    var rawToken = "a raw token";
    var now = Instant.now();
    var ipAddress = "192.168.0.1";
    var verifyToken =
        new CustomerEmailVerificationToken(
            customerId, hash(rawToken), now.plus(24, ChronoUnit.HOURS));

    when(tokenRepo.findByTokenHash(hash(rawToken))).thenReturn(Optional.of(verifyToken));
    when(customerRepo.findById(customerId)).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> verifyService.verify(rawToken, ipAddress))
        .isInstanceOf(InvalidVerificationTokenException.class)
        .hasMessage("This verification link is invalid.");

    assertThat(verifyToken.isUsed()).isFalse();

    verify(tokenRepo, times(1)).findByTokenHash(any());
    verify(customerRepo, times(1)).findById(any());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void verify_usedTokenVerifiedCustomer_returnsAlreadyVerified() {
    // Arrange
    var customerId = UUID.randomUUID();
    var rawToken = "a raw token";
    var firstUse = Instant.now().minus(1, ChronoUnit.HOURS);
    var ipAddress = "192.168.0.1";

    var customer = new Customer(customerId, "test@example.com", "a hashed password", true, true);
    var usedToken =
        new CustomerEmailVerificationToken(
            customerId, hash(rawToken), Instant.now().plus(23, ChronoUnit.HOURS));

    usedToken.markUsed(firstUse);

    when(tokenRepo.findByTokenHash(hash(rawToken))).thenReturn(Optional.of(usedToken));
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

    // Act
    var response = verifyService.verify(rawToken, ipAddress);

    // Assert
    assertThat(response).isEqualTo(EmailVerificationResponse.alreadyVerified());
    assertThat(usedToken.getUsedAt()).isEqualTo(firstUse); // original time kept
    assertThat(usedToken.isUsed()).isTrue();
    assertThat(customer.getIsEmailVerified()).isTrue();

    verifyNoInteractions(auditLogService);
  }

  @Test
  void verify_usedAndExpiredTokenVerifiedCustomer_returnsAlreadyVerified() {
    // Arrange
    var customerId = UUID.randomUUID();
    var rawToken = "a raw token";
    var firstUse = Instant.now().minus(2, ChronoUnit.HOURS);
    var expiry = Instant.now().minus(1, ChronoUnit.HOURS); // clearly past

    var ipAddress = "192.168.0.1";

    var customer = new Customer(customerId, "test@example.com", "a hashed password", true, true);
    var usedToken = new CustomerEmailVerificationToken(customerId, hash(rawToken), expiry);
    usedToken.markUsed(firstUse);

    when(tokenRepo.findByTokenHash(hash(rawToken))).thenReturn(Optional.of(usedToken));
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

    // Act
    var response = verifyService.verify(rawToken, ipAddress);

    // Assert
    assertThat(response).isEqualTo(EmailVerificationResponse.alreadyVerified());
    assertThat(usedToken.getUsedAt()).isEqualTo(firstUse); // original time kept
    verifyNoInteractions(auditLogService);
  }

  @Test
  void verify_usedTokenUnverifiedCustomer_throwsInvalid() {
    // Arrange
    var customerId = UUID.randomUUID();
    var rawToken = "a raw token";
    var firstUse = Instant.now().minus(1, ChronoUnit.HOURS);
    var ipAddress = "192.168.0.1";

    var customer = new Customer(customerId, "test@example.com", "a hashed password", false, true);
    var usedToken =
        new CustomerEmailVerificationToken(
            customerId, hash(rawToken), Instant.now().plus(23, ChronoUnit.HOURS));

    when(tokenRepo.findByTokenHash(hash(rawToken))).thenReturn(Optional.of(usedToken));
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

    usedToken.markUsed(firstUse);

    // Act & Assert
    assertThatThrownBy(() -> verifyService.verify(rawToken, ipAddress))
        .isInstanceOf(InvalidVerificationTokenException.class)
        .hasMessage("This verification link is invalid.");

    assertThat(customer.getIsEmailVerified()).isFalse(); // not verified by mistake
    assertThat(usedToken.getUsedAt()).isEqualTo(firstUse); // not overwritten

    verify(tokenRepo, times(1)).findByTokenHash(any());
    verify(customerRepo, times(1)).findById(any());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void verify_expiredUnusedToken_throwsExpired() {
    // Arrange
    var customerId = UUID.randomUUID();
    var rawToken = "a raw token";
    var expiry = Instant.now().minus(1, ChronoUnit.HOURS);
    var ipAddress = "192.168.0.1";

    var customer = new Customer(customerId, "test@example.com", "a hashed password", false, true);
    var unusedToken = new CustomerEmailVerificationToken(customerId, hash(rawToken), expiry);

    when(tokenRepo.findByTokenHash(hash(rawToken))).thenReturn(Optional.of(unusedToken));
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

    // Act & Assert
    assertThatThrownBy(() -> verifyService.verify(rawToken, ipAddress))
        .isInstanceOf(VerificationTokenExpiredException.class)
        .hasMessage("This verification link has expired.");

    assertThat(customer.getIsEmailVerified()).isFalse(); // not verified by mistake
    assertThat(unusedToken.getUsedAt()).isNull(); // not marked used

    verify(tokenRepo, times(1)).findByTokenHash(any());
    verify(customerRepo, times(1)).findById(any());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void issueToken_savesOnlyHashAndExpiresAfterTtl() {
    // Arrange
    var customerId = UUID.randomUUID();
    var now = Instant.parse("2026-11-04T12:00:00Z"); // fixed, so the expiry is exact

    // Act
    var rawToken = verifyService.issueToken(customerId, now);

    // Assert
    var tokenCaptor = ArgumentCaptor.forClass(CustomerEmailVerificationToken.class);
    verify(tokenRepo).save(tokenCaptor.capture());
    var savedToken = tokenCaptor.getValue();

    assertThat(rawToken).isNotBlank();
    assertThat(savedToken.getTokenHash()).isEqualTo(hash(rawToken)).isNotEqualTo(rawToken);
    assertThat(savedToken.getCustomerId()).isEqualTo(customerId);
    assertThat(savedToken.getExpiresAt()).isEqualTo(now.plus(emailVerifyProps.tokenTtl()));
  }

  @Test
  void resendVerification_verifiedAccount_throwsAlreadyVerified() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, email, "a hashed password", true, true);

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));

    // Act & Assert
    assertThatThrownBy(() -> verifyService.resendVerification(email, rawPassword, null, ipAddress))
        .isInstanceOf(EmailAlreadyVerifiedException.class)
        .hasMessage("This email address is already verified. Log in instead.");

    // Assert -- rejected before anything is counted, changed, issued or sent
    verifyNoInteractions(tokenRepo, eventPublisher, auditLogService);
  }

  @Test
  void resendVerification_overLimit_throwsResendLimit() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, email, "a hashed password", false, true);

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
    when(tokenRepo.countByCustomerIdAndCreatedAtAfter(eq(customerId), any()))
        .thenReturn((long) emailVerifyProps.resendLimit());

    // Act & Assert
    assertThatThrownBy(() -> verifyService.resendVerification(email, rawPassword, null, ipAddress))
        .isInstanceOf(VerificationResendLimitException.class)
        .hasMessage("Too many verification emails requested. Try again later.");
    verify(tokenRepo)
        .countByCustomerIdAndCreatedAtAfter(
            eq(customerId),
            argThat(
                since ->
                    since.isBefore(
                        Instant.now().minus(emailVerifyProps.resendWindow()).plusSeconds(5))));

    // Assert -- rejected before anything is issued, sent or audit record written
    verify(tokenRepo, never()).findByCustomerIdAndUsedAtIsNull(any());
    verify(tokenRepo, never()).save(any());
    verify(customerRepo, never()).existsByEmail(any());
    verifyNoInteractions(eventPublisher, auditLogService);
  }

  @Test
  void resendVerification_newEmailTaken_throwsAccountAlreadyExists() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var newEmail = "updated@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, email, "a hashed password", false, true);

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
    when(tokenRepo.countByCustomerIdAndCreatedAtAfter(eq(customerId), any()))
        .thenReturn((long) emailVerifyProps.resendLimit() - 1L);
    when(customerRepo.existsByEmail(newEmail)).thenReturn(true);

    // Act & Assert
    assertThatThrownBy(
            () -> verifyService.resendVerification(email, rawPassword, newEmail, ipAddress))
        .isInstanceOf(AccountAlreadyExistsException.class)
        .hasMessage("An account with this email address already exists.");
    assertThat(customer.getEmail()).isEqualTo(email);

    // Assert -- rejected before anything is issued, sent or audit record written
    verify(tokenRepo, never()).findByCustomerIdAndUsedAtIsNull(any());
    verify(tokenRepo, never()).save(any());
    verifyNoInteractions(eventPublisher, auditLogService);
  }

  @Test
  void resendVerification_newEmailSameAsCurrent_resendsWithoutChange() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var newEmail = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, email, "a hashed password", false, true);
    var oldToken =
        new CustomerEmailVerificationToken(
            customerId, "an old hash", Instant.now().plus(1, ChronoUnit.HOURS));

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
    when(tokenRepo.countByCustomerIdAndCreatedAtAfter(eq(customerId), any()))
        .thenReturn((long) emailVerifyProps.resendLimit() - 1L);
    when(tokenRepo.findByCustomerIdAndUsedAtIsNull(customerId)).thenReturn(List.of(oldToken));

    // Act
    var resendResponse = verifyService.resendVerification(email, rawPassword, newEmail, ipAddress);

    // Assert
    assertThat(resendResponse.email()).isEqualTo(email);
    assertThat(customer.getEmail()).isEqualTo(email);
    assertThat(oldToken.isUsed()).isTrue();

    var eventCaptor = ArgumentCaptor.forClass(VerificationEmailRequestedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    assertThat(eventCaptor.getValue().email()).isEqualTo(email);

    // Assert -- an equal address is no change: no clash check, no EMAIL_CHANGED audit
    verify(customerRepo, never()).existsByEmail(any());
    verifyNoInteractions(auditLogService);
  }

  @Test
  void resendVerification_credentialsRejected_propagatesAndChangesNothing() {
    // Arrange
    var email = "test@example.com";
    var newEmail = "updated@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(credChecker.check(email, rawPassword, ipAddress))
        .thenThrow(new InvalidCredentialsException());

    // Act & Assert
    assertThatThrownBy(
            () -> verifyService.resendVerification(email, rawPassword, newEmail, ipAddress))
        .isInstanceOf(InvalidCredentialsException.class);

    // Assert
    verifyNoInteractions(customerRepo, tokenRepo, eventPublisher, auditLogService);
  }

  @Test
  void resendVerification_newEmail_changesAddressAuditsAndSendsToNew() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var newEmail = "updated@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, email, "a hashed password", false, true);
    var oldToken =
        new CustomerEmailVerificationToken(
            customerId, "an old hash", Instant.now().plus(1, ChronoUnit.HOURS));

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
    when(tokenRepo.countByCustomerIdAndCreatedAtAfter(eq(customerId), any()))
        .thenReturn((long) emailVerifyProps.resendLimit() - 1L);
    when(tokenRepo.findByCustomerIdAndUsedAtIsNull(customerId)).thenReturn(List.of(oldToken));

    // Act
    var resendResponse = verifyService.resendVerification(email, rawPassword, newEmail, ipAddress);

    var eventCaptor = ArgumentCaptor.forClass(VerificationEmailRequestedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    var event = eventCaptor.getValue();
    var tokenCaptor = ArgumentCaptor.forClass(CustomerEmailVerificationToken.class);

    // Assert
    assertThat(resendResponse.email()).isEqualTo(newEmail);

    assertThat(event.email()).isEqualTo(newEmail);
    assertThat(event.customerId()).isEqualTo(customerId);

    verify(tokenRepo).save(tokenCaptor.capture());
    assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(hash(event.rawToken()));

    assertThat(customer.getEmail()).isEqualTo(newEmail);
    assertThat(oldToken.isUsed()).isTrue();

    verify(customerRepo).existsByEmail(newEmail);
    verify(auditLogService, times(1))
        .recordEvent(
            ActorType.CUSTOMER,
            customerId,
            AuditAction.EMAIL_CHANGED,
            "auth",
            customerId,
            ipAddress);
  }

  @Test
  void resendVerification_noNewEmail_invalidatesOldTokensAndIssuesNew() {
    // Arrange
    var customerId = UUID.randomUUID();
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var customer = new Customer(customerId, email, "a hashed password", false, true);
    var oldToken1 =
        new CustomerEmailVerificationToken(
            customerId, "old hash 1", Instant.now().plus(1, ChronoUnit.HOURS));
    var oldToken2 =
        new CustomerEmailVerificationToken(
            customerId, "old hash 2", Instant.now().plus(1, ChronoUnit.HOURS));

    when(credChecker.check(email, rawPassword, ipAddress)).thenReturn(customerId);
    when(customerRepo.findById(customerId)).thenReturn(Optional.of(customer));
    when(tokenRepo.countByCustomerIdAndCreatedAtAfter(eq(customerId), any())).thenReturn(0L);
    when(tokenRepo.findByCustomerIdAndUsedAtIsNull(customerId))
        .thenReturn(List.of(oldToken1, oldToken2));

    // Act
    var resendResponse = verifyService.resendVerification(email, rawPassword, null, ipAddress);

    // Assert
    assertThat(resendResponse.email()).isEqualTo(email);

    var eventCaptor = ArgumentCaptor.forClass(VerificationEmailRequestedEvent.class);
    verify(eventPublisher).publishEvent(eventCaptor.capture());
    var event = eventCaptor.getValue();
    assertThat(event.email()).isEqualTo(email);
    assertThat(event.customerId()).isEqualTo(customerId);

    var tokenCaptor = ArgumentCaptor.forClass(CustomerEmailVerificationToken.class);
    verify(tokenRepo).save(tokenCaptor.capture());
    var newToken = tokenCaptor.getValue();

    assertThat(newToken.getTokenHash()).isEqualTo(hash(event.rawToken()));
    assertThat(newToken.getCustomerId()).isEqualTo(customerId);
    assertThat(newToken.isUsed()).isFalse();

    assertThat(customer.getEmail()).isEqualTo(email);
    assertThat(oldToken1.isUsed()).isTrue();
    assertThat(oldToken2.isUsed()).isTrue();

    verify(customerRepo, never()).existsByEmail(any());
    verifyNoInteractions(auditLogService);
  }
}
