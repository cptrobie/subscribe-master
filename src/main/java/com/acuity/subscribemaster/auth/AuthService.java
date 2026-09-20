package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.LoginResponse;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.EmailMasker;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login business logic (FR-01, FR-02): create the customer, hash the password
 * (FR-03), record a REGISTERED audit_logs entry on registration (NFR-17), and issue a session on
 * successful login.
 *
 * <p>Email verification (FR-30) is not yet implemented, and neither is FR-31's
 * login-blocked-until-verified gate -- it genuinely can't be built yet: every customer's {@code
 * email_verified} is {@code false} with no way to ever flip it until FR-30 exists, so enforcing the
 * gate now would make login untestable, not just premature.
 *
 * <p><b>Credential errors are unified deliberately.</b> "Email not found" and "wrong password" both
 * throw the same {@link InvalidCredentialsException}, with the same message and HTTP status --
 * distinguishing them would let an attacker enumerate registered emails via the login endpoint
 * itself, the same vulnerability {@link UserAlreadyExistsException}'s generic message already
 * guards against on registration.
 *
 * <p><b>Lockout (5 failed attempts, 15-minute window)</b> is live-expiry-checked on {@code
 * customers.locked_until}, not a scheduled job -- same reasoning as FR-33's 2FA lockout design.
 * Unlike the credential-unification above, a locked account returns a distinct {@link
 * AccountLockedException}, not the generic {@link InvalidCredentialsException} -- deliberately,
 * after weighing the trade-off: a genuine user locked out by a handful of mistyped attempts has no
 * way to know why their presumably-correct password stopped working, or when to retry, under a
 * fully generic response. The information this reveals to an attacker (that the account exists)
 * costs them 5 full attempts to obtain, versus a single request via registration enumeration -- a
 * meaningfully higher attack cost, and lockout itself already blocks sustained automated probing
 * regardless of what the response reveals. Only the lockout trigger itself (the 5th failure) is
 * audited, attributed to the real customer being locked -- not every failed attempt, which would
 * make audit_logs unusably noisy for what's often just a routine mistyped password.
 *
 * <p><b>Known, deliberately deferred gap:</b> nonexistent-email login attempts get no rate-limiting
 * or audit logging at all -- there's no customer row to attach a counter to. A real fix needs
 * IP-based rate limiting, a genuinely separate mechanism from the per-account lockout above; out of
 * scope here, revisit if abuse is ever observed.
 *
 * <p>Idempotency (NFR-24) is deliberately not enforced on login: unlike registration, where a
 * retried request risked creating a duplicate customer, a retried login's worst case is a second,
 * redundant session row -- a minor annoyance, not a correctness bug worth the added complexity
 * here.
 */
@Service
public class AuthService {
  private final CustomerRepository customerRepo;
  private final CustomerSessionRepository sessionRepo;
  private final PasswordEncoder pwdEncoder;
  private final AuditLogService auditLogService;
  private final long sessionDurationHours;
  private final int maxLoginAttempts;
  private final int lockoutDurationMinutes;

  // private final ApplicationEventPublisher events;
  // private final RegistrationProperties properties;

  private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

  public AuthService(
      CustomerRepository customerRepository,
      CustomerSessionRepository customerSessionRepository,
      PasswordEncoder passwordEncoder,
      AuditLogService auditLogService,
      @Value("${app.session.duration-hours}") long sessionDurationHours,
      @Value("${app.login-lockout.max-attempts}") int maxLoginAttempts,
      @Value("${app.login-lockout.duration-minutes}") int lockoutDurationMinutes) {
    this.customerRepo = customerRepository;
    this.sessionRepo = customerSessionRepository;
    this.pwdEncoder = passwordEncoder;
    this.auditLogService = auditLogService;
    this.sessionDurationHours = sessionDurationHours;
    this.maxLoginAttempts = maxLoginAttempts;
    this.lockoutDurationMinutes = lockoutDurationMinutes;
  }

  @Transactional
  public RegistrationResponse register(String email, String password, String ipAddress) {
    var maskedEmail = EmailMasker.mask(email);
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
    var customer = customerRepo.saveAndFlush(new Customer(email, pwdEncoder.encode(password)));

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
        customer.getId(),
        customer.getEmail(),
        customer.getIsEmailVerified(),
        customer.getCreatedAt());
  }

  @Transactional
  public LoginResponse login(String email, String password, String userAgent, String ipAddress) {
    var maskedEmail = EmailMasker.mask(email);

    logger.info("login attempt for {}", maskedEmail);

    // 1. Check login credential's validity
    var customer = customerRepo.findByEmail(email).orElseThrow(this::invalidCredentials);

    // 2. Check if Customer is Locked
    if (customer.getLockedUntil() != null && customer.getLockedUntil().isAfter(Instant.now())) {
      logger.warn("Customer {}: account is locked", customer.getId());
      throw new AccountLockedException();
    }

    // 3. Verify the password & set the failedLoginCounter if not verified
    if (!pwdEncoder.matches(password, customer.getPasswordHash())) {

      customer.setFailedLoginCount(customer.getFailedLoginCount() + 1);

      if (customer.getFailedLoginCount() < maxLoginAttempts) {
        throw invalidCredentials();
      }
      customer.setLockedUntil(Instant.now().plus(lockoutDurationMinutes, ChronoUnit.MINUTES));
      throw LockAccount(customer.getId(), ipAddress);
    }

    // 4. If successful, clear brute-force counters
    customer.setFailedLoginCount(0);
    customer.setLockedUntil(null);

    // 5. Create the token
    var rawToken = Tokens.generate();
    var hashedToken = Tokens.hash(rawToken);

    // 6. Record token identifier in the customer session table
    // TODO(FR-05): revisit once refresh tokens land -- likely shorten this to 1-2h,
    // with refresh tokens covering longer-lived "stay logged in" sessions instead.
    var expiresAt = Instant.now().plus(sessionDurationHours, ChronoUnit.HOURS);
    var session =
        new CustomerSession(customer.getId(), hashedToken, ipAddress, userAgent, expiresAt);
    sessionRepo.save(session);

    // 7. Build and return the response with the token in the message body
    logger.info("customer {} login successful.", customer.getId());
    return LoginResponse.accepted(
        customer.getId(), customer.getEmail(), customer.getIsEmailVerified(), rawToken, expiresAt);
  }

  /**
   * The existsByEmail check found a pre-existing account for this email before any insert was
   * attempted -- an ordinary, expected rejection. Attributed to the existing customer, since this
   * is (from an audit standpoint) that account being referenced again.
   */
  private AccountAlreadyExistsException duplicateEmailDetected(String email, String ipAddress) {
    var existingCustomer =
        customerRepo.findByEmail(email).orElseThrow(AccountAlreadyExistsException::new);
    auditLogService.recordEvent(
        "customer",
        existingCustomer.getId(),
        "REGISTRATION_REJECTED",
        "customers",
        existingCustomer.getId(),
        ipAddress);
    return new AccountAlreadyExistsException();
  }

  // TODO: Commenting out now because its not in scope however when we add idempotency it may
  // resurface. If at that time its still not needed we can make decision to remove or retain
  //
  /**
   * existsByEmail missed it -- a concurrent registration for the same email won the race -- and the
   * DB's citext unique constraint caught it instead. No authenticated actor exists in this public
   * endpoint, and unlike {@link #duplicateEmailDetected}, it wasn't application logic that caught
   * this one; attributed to "system"/{@link #SYSTEM_ACTOR_ID} rather than the customer that
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

  private InvalidCredentialsException invalidCredentials() {
    // Placeholder for IP-based rate limiting on nonexistent-email login attempts --
    // see this class's Javadoc ("Known, deliberately deferred gap") for why this
    // isn't built yet. This is the natural landing spot for that logic once it is.
    return new InvalidCredentialsException();
  }

  private AccountLockedException LockAccount(UUID customerId, String ipAddress) {
    auditLogService.recordEvent(
        "customer", customerId, "ACCOUNT_LOCKED", "customers", customerId, ipAddress);
    return new AccountLockedException();
  }
}
