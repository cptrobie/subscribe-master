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
      throw duplicateEmailDetected(email, ipAddress);
    }

    // TODO: Commenting out now because its not in scope however when we add idempotency it may
    // resurface. If at that time its still not needed we can make decision to remove or retain
    //
    /*
     Customer customer;
    try {
    customer = customerRepo.saveAndFlush(new Customer(email, pwdEncoder.encode(password)));
    } catch (DataIntegrityViolationException e) {
      // Lost a race with a concurrent registration for the same email between the
      // existsByEmail check above and this insert -- the DB's citext unique
      // constraint is the actual source of truth here.
      throw duplicateEmailRaceLost(ipAddress);
    }
     */
    // TODO: a concurrent registration for the same email could still land between the
    // existsByEmail check above and this insert -- the DB's citext unique constraint would
    // catch it, but it would currently surface as a raw, unhandled exception rather than a
    // clean UserAlreadyExistsException. Deliberately deferred -- revisit alongside NFR-24
    // (idempotency), which would also help here.
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

  /**
   * The existsByEmail check found a pre-existing account for this email before any insert was
   * attempted -- an ordinary, expected rejection. Attributed to the existing customer, since this
   * is (from an audit standpoint) that account being referenced again.
   */
  private UserAlreadyExistsException duplicateEmailDetected(String email, String ipAddress) {
    Customer existing = customerRepo.findByEmail(email).orElseThrow(UserAlreadyExistsException::new);
    auditLogService.recordEvent(
        "customer",
        existing.getId(),
        "REGISTRATION_REJECTED",
        "customers",
        existing.getId(),
        ipAddress);
    return new UserAlreadyExistsException();
  }
  // TODO: Commenting out now because its not in scope however when we add idempotency it may
  // resurface. If at that time its still not needed we can make decision to remove or retain
  //
  /**
   * existsByEmail missed it -- a concurrent registration for the same email won the race -- and
   * the DB's citext unique constraint caught it instead. No authenticated actor exists in this
   * public endpoint, and unlike {@link #duplicateEmailDetected}, it wasn't application logic that
   * caught this one; attributed to "system"/{@link #SYSTEM_ACTOR_ID} rather than the customer that
   * happened to win the race, who had no involvement in this request.
   *
   * <p>Deliberately does NOT look up the existing customer for resourceId: saveAndFlush's failure
   * already poisoned the current transaction (Postgres aborts the whole transaction on any failed
   * statement), so any further query on customerRepo here -- still the same transaction -- would
   * itself fail with a second, uncaught exception. Fixing that properly needs a savepoint
   * (PROPAGATION_NESTED) around the original insert; out of scope for now, so resourceId is null.
   */
  /*
  private UserAlreadyExistsException duplicateEmailRaceLost(String ipAddress) {
    auditLogService.recordEvent(
        "system", SYSTEM_ACTOR_ID, "REGISTRATION_REJECTED", "customers", null, ipAddress);
    return new UserAlreadyExistsException();
  }
   */
}
