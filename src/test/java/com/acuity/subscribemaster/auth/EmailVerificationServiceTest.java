package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.EmailVerificationResponse;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
  @Mock private CustomerRepository customerRepo;
  @Mock private CustomerEmailVerificationTokenRepository tokenRepo;
  @Mock private AuditLogService auditLogService;

  private EmailVerificationService verifyService;

  @BeforeEach
  void setUp() {
    verifyService = new EmailVerificationService(customerRepo, tokenRepo, auditLogService);
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
            customerId, Tokens.hash(rawToken), now.plus(24, ChronoUnit.HOURS));

    when(tokenRepo.findByTokenHash(Tokens.hash(rawToken))).thenReturn(Optional.of(verifyToken));
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
            customerId, Tokens.hash(rawToken), now.plus(24, ChronoUnit.HOURS));

    when(tokenRepo.findByTokenHash(Tokens.hash(rawToken))).thenReturn(Optional.of(verifyToken));
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
            customerId, Tokens.hash(rawToken), Instant.now().plus(23, ChronoUnit.HOURS));

    usedToken.markUsed(firstUse);

    when(tokenRepo.findByTokenHash(Tokens.hash(rawToken))).thenReturn(Optional.of(usedToken));
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
    var usedToken = new CustomerEmailVerificationToken(customerId, Tokens.hash(rawToken), expiry);
    usedToken.markUsed(firstUse);

    when(tokenRepo.findByTokenHash(Tokens.hash(rawToken))).thenReturn(Optional.of(usedToken));
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
            customerId, Tokens.hash(rawToken), Instant.now().plus(23, ChronoUnit.HOURS));

    when(tokenRepo.findByTokenHash(Tokens.hash(rawToken))).thenReturn(Optional.of(usedToken));
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
    var unusedToken = new CustomerEmailVerificationToken(customerId, Tokens.hash(rawToken), expiry);

    when(tokenRepo.findByTokenHash(Tokens.hash(rawToken))).thenReturn(Optional.of(unusedToken));
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
}
