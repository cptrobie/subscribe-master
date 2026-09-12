package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.EmailMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration business logic (FR-01): create the customer, hash the password (FR-03), and record
 * a REGISTERED audit_logs entry (NFR-17).
 *
 * <p>Email verification (FR-30) is not yet implemented. When it lands, registration success will
 * need to trigger a second independent reaction (sending the verification email) alongside the
 * audit write already here -- that's the point at which an event/listener design becomes worth
 * its complexity, per the direct-call-vs-event-listener note at the call site below. For now, a
 * direct call is simpler and sufficient for a single reaction.
 */
@Service
public class AuthService {
  private final CustomerRepository customerRepo;
  private final PasswordEncoder pwdEncoder;
  private final AuditLogService auditLogService;

  // private final ApplicationEventPublisher events;
  // private final RegistrationProperties properties;

  private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

  public AuthService(CustomerRepository customerRepository, PasswordEncoder passwordEncoder, AuditLogService auditLogService) {
    this.customerRepo = customerRepository;
    this.pwdEncoder = passwordEncoder;
    this.auditLogService = auditLogService;
  }

  @Transactional
  public RegistrationResponse register(String email, String password, String ipAddress) {
    String maskedEmail = EmailMasker.mask(email);
    logger.info("registration attempt for {}", maskedEmail);

    if (customerRepo.existsByEmail(email)) {
      logger.warn("registration rejected for {}: email already registered", maskedEmail);
      throw new UserAlreadyExistsException();
    }

    Customer customer = customerRepo.saveAndFlush(new Customer(email, pwdEncoder.encode(password)));

    auditLogService.recordEvent(
            "customer", customer.getId(), "REGISTERED", "customers", customer.getId(), ipAddress);
    // TODO(FR-30): create a token and save to customer_email_verification_tokens, then send the
    // verification email. Out of scope for FR-01.
    /*
    events.publishEvent(
             new CustomerRegisteredEvent(
                     customer.getId(),
                     customer.getEmail(),
                     verificationLink,
                     expiresAt,
                     command.ipAddress()));

     */

    logger.info("customer {} registered.", customer.getId());

    return RegistrationResponse.accepted(
        customer.getId(), customer.getEmail(), customer.isEmailVerified(), customer.getCreatedAt());
  }
}
