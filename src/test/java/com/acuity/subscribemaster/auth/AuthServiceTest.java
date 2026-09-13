package com.acuity.subscribemaster.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure unit test -- no Spring context, no infrastructure. Mocks every AuthService dependency to
 * isolate its actual logic.
 */
@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

  @Mock private CustomerRepository customerRepo;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AuditLogService auditLogService;

  @InjectMocks private AuthService authService;

  @Test
  void successfulRegistration_savesHashedPasswordAndRecordsAuditEvent() {
    // Arrange
    var email = "test@example.com";
    var rawPassword = "easyPassword!#123";
    var encodedPassword = "bCrypted_password";
    var ipAddress = "192.168.0.1";
    var customerId = UUID.randomUUID();
    var createdAt = Instant.now();

    // Customer has no constructor/setter for createdAt -- it's Hibernate-managed
    // via @CreationTimestamp -- so reflection is the only way to simulate a
    // persisted row's real timestamp here.
    var savedCustomer = new Customer(customerId, email, encodedPassword, false, true);
    ReflectionTestUtils.setField(savedCustomer, "createdAt", createdAt);

    when(customerRepo.existsByEmail(email)).thenReturn(false);
    when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
    when(customerRepo.saveAndFlush(any(Customer.class))).thenReturn(savedCustomer);

    // Act
    var response = authService.register(email, rawPassword, ipAddress);

    // Assert: the Customer passed to saveAndFlush carried the HASH, not the raw password
    var customerCaptor = ArgumentCaptor.forClass(Customer.class);
    verify(customerRepo).saveAndFlush(customerCaptor.capture());
    assertThat(customerCaptor.getValue().getPasswordHash()).isEqualTo(encodedPassword);

    // Assert: audit event recorded with the correct actor/resource
    verify(auditLogService, times(1))
        .recordEvent("customer", customerId, "REGISTERED", "customers", customerId, ipAddress);

    // Assert: response reflects the saved customer
    assertThat(response.id()).isEqualTo(customerId);
    assertThat(response.email()).isEqualTo(email);
    assertThat(response.emailVerified()).isFalse();
    assertThat(response.createdAt()).isEqualTo(createdAt);

    verify(customerRepo, times(1)).existsByEmail(email);
  }

  // Covers AuthService.duplicateEmailDetected -- the existsByEmail() check catching the
  // duplicate before any insert is attempted.
  @Test
  void existsByEmailCheck_throwsUserAlreadyExistsAndRecordsRejectionAudit() {

    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var ipAddress = "192.168.0.1";
    var existingId = UUID.randomUUID();
    var existingCustomer = new Customer(existingId, email, "someHash", false, true);

    when(customerRepo.existsByEmail(email)).thenReturn(true);
    when(customerRepo.findByEmail(email)).thenReturn(Optional.of(existingCustomer));

    // Act & Assert
    assertThatThrownBy(() -> authService.register(email, password, ipAddress))
        .isInstanceOf(UserAlreadyExistsException.class)
        .hasMessage("An account with this email address already exists."); // taken from the actual
    // exception

    // Verify repository interaction
    verify(customerRepo, times(1)).existsByEmail(email);
    verify(customerRepo, never()).save(any());
    verify(customerRepo, never()).saveAndFlush(any());

    // Assert: rejection is audited against the existing account, not a new one
    verify(auditLogService, times(1))
        .recordEvent(
            "customer", existingId, "REGISTRATION_REJECTED", "customers", existingId, ipAddress);
  }
  // TODO: Commenting out now because its not in scope however when we add idempotency it may
  // resurface. If at that time its still not needed we can make decision to remove or retain
  //
  // Covers AuthService.duplicateEmailRaceLost -- existsByEmail() misses it (lost the race with
  // a concurrent registration), and the DB's citext unique constraint catches it at insert time
  // instead, surfacing as DataIntegrityViolationException from saveAndFlush(). Deliberately does
  // NOT stub customerRepo.findByEmail(...): the real AuthService no longer calls it here, since
  // that query would run inside the transaction saveAndFlush's failure already poisoned against
  // a real Postgres connection (see the javadoc on duplicateEmailRaceLost). If this test ever
  // needed findByEmail stubbed to pass, that itself would be a regression back to the bug.
  /*
  @Test
  void saveAndFlushConstraintViolation_throwsUserAlreadyExistsAndRecordsRejectionAudit() {

    // Arrange
    var email = "test@example.com";
    var password = "easyPassword!#123";
    var ipAddress = "192.168.0.1";

    when(customerRepo.existsByEmail(email)).thenReturn(false);
    when(passwordEncoder.encode(password)).thenReturn("bCrypted_password");
    when(customerRepo.saveAndFlush(any(Customer.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    // Act & Assert
    assertThatThrownBy(() -> authService.register(email, password, ipAddress))
        .isInstanceOf(UserAlreadyExistsException.class);

    // Assert: rejection is audited against the "system" sentinel with no resourceId -- the
    // existing customer's id is deliberately not looked up here (see AuthService javadoc).
    verify(auditLogService, times(1))
        .recordEvent(
            "system", AuthService.SYSTEM_ACTOR_ID, "REGISTRATION_REJECTED", "customers", null, ipAddress);
  }
   */
}
