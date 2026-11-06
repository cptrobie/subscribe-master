package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.LoginResponse;
import com.acuity.subscribemaster.auth.dto.RegistrationResponse;
import com.acuity.subscribemaster.customer.Customer;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.EmailMasker;
import com.acuity.subscribemaster.support.JwtIssuer;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login business logic (FR-01, FR-02): create the customer, hash the password
 * (FR-03), record a REGISTERED audit_logs entry on registration (NFR-17), issue the email
 * verification token (FR-30), and issue a signed JWT (RS256) on successful login -- only to a
 * verified account (FR-31).
 *
 * <p><b>JWT, not opaque DB-backed sessions.</b> The original implementation used opaque, hashed
 * session tokens (customer_sessions), inferred from that table's own inline schema comment without
 * checking it against task.pdf's actual, literal requirement -- Spring Security + JWT, stated
 * explicitly, twice. Revisited and corrected (see V1's migration comment for the fuller history):
 * the opaque design's main claimed advantage, instant session revocation, was never actually backed
 * by any detection mechanism in this project, so the real trade-off favored JWT once examined
 * honestly -- less hand-rolled code for FR-04's auth filter (Spring's oauth2-resource-server
 * handles most of it), no per-request database dependency, and it matches the literal spec. Signing
 * is RSA (RS256), not HMAC, specifically ahead of a planned future modulith migration to multiple
 * independently-deployable builds -- under HMAC, every module that needs to verify a token would
 * need the same shared secret that's also capable of signing forged tokens; under RSA, only the
 * public key needs distributing to verifying modules. See {@link
 * com.acuity.subscribemaster.support.JwtIssuer} for the actual signing logic.
 *
 * <p><b>Email verification (FR-30).</b> Registration has {@link EmailVerificationService} issue the
 * verification token -- the same code the FR-40 resend uses -- and publishes a {@link
 * CustomerRegisteredEvent} with the raw token it returns, all inside the registration transaction,
 * so the customer and token are saved together or not at all. The email itself is sent by {@link
 * VerificationEmailListener} only after that transaction commits, and a failed send doesn't fail
 * registration. Confirming and resending the link are {@link EmailVerificationService}'s job, not
 * this class's.
 *
 * <p><b>Login is blocked until the email is verified (FR-31).</b> An unverified account with the
 * correct password gets a distinct {@link EmailNotVerifiedException} (403), so a genuine user knows
 * to check their email rather than assume their password is wrong. The check runs only after the
 * password has been verified -- a wrong password still gets the generic {@link
 * InvalidCredentialsException} -- so it reveals nothing to anyone who doesn't already know the
 * password. It also runs after the failed-attempt counter is reset: a correct password proves the
 * customer, so a user waiting on their verification email isn't left one typo from a lockout.
 *
 * <p><b>Credentials and lockout are {@link CredentialChecker}'s job.</b> Login calls it first; it
 * owns the unified credential error, the per-account lockout, why the failed-attempt counter is
 * committed in its own transaction, and the deferred gap for nonexistent emails. Login itself
 * changes nothing in the database, so it has no transaction of its own: it reloads the customer by
 * the id the checker returns, checks verification, then issues the JWT.
 *
 * <p>Idempotency (NFR-24) is deliberately not enforced on login: a retried request simply issues a
 * second, independent JWT -- both remain valid until their own expiry, which is a minor annoyance,
 * not a correctness bug worth the added complexity here.
 */
@Service
public class AuthService {
  private final CustomerRepository customerRepo;
  private final EmailVerificationService emailVerifyService;
  private final JwtIssuer jwtIssuer;
  private final PasswordEncoder pwdEncoder;
  private final CredentialChecker credChecker;
  private final ApplicationEventPublisher events;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

  public AuthService(
      CustomerRepository customerRepository,
      EmailVerificationService emailVerificationService,
      JwtIssuer jwtIssuer,
      PasswordEncoder passwordEncoder,
      CredentialChecker credentialChecker,
      ApplicationEventPublisher eventPublisher,
      AuditLogService auditLogService) {
    this.customerRepo = customerRepository;
    this.emailVerifyService = emailVerificationService;
    this.jwtIssuer = jwtIssuer;
    this.pwdEncoder = passwordEncoder;
    this.credChecker = credentialChecker;
    this.events = eventPublisher;
    this.auditLogService = auditLogService;
  }

  @Transactional
  public RegistrationResponse register(String email, String password, String ipAddress) {
    var maskedEmail = EmailMasker.mask(email);
    logger.info("registration attempt for {}", maskedEmail);

    if (customerRepo.existsByEmail(email)) {
      logger.warn("registration rejected for {}: email already registered", maskedEmail);
      throw duplicateEmailDetected(email, ipAddress);
    }

    // TODO: a concurrent registration for the same email could still land between the
    // existsByEmail check above and this insert -- the DB's citext unique constraint would
    // catch it, but it would currently surface as a raw, unhandled exception rather than a
    // clean AccountAlreadyExistsException. Still open after NFR-33: an Idempotency-Key only
    // collapses retries that share a key, so two different keys (or none) for the same email
    // can still race here.
    var customer = customerRepo.saveAndFlush(new Customer(email, pwdEncoder.encode(password)));
    var customerId = customer.getId();

    auditLogService.recordEvent(
        ActorType.CUSTOMER, customerId, AuditAction.REGISTERED, "auth", customerId, ipAddress);

    var rawToken = emailVerifyService.issueToken(customerId, Instant.now());

    events.publishEvent(
        new CustomerRegisteredEvent(customerId, customer.getEmail(), rawToken, ipAddress));

    logger.info("customer {} registered.", customerId);

    return RegistrationResponse.accepted(
        customerId, customer.getEmail(), customer.getIsEmailVerified(), customer.getCreatedAt());
  }

  public LoginResponse login(String email, String password, String ipAddress) {
    var maskedEmail = EmailMasker.mask(email);

    logger.info("login attempt for {}", maskedEmail);

    // Steps 1–4 have moved to CredentialChecker.check()
    var customerId = credChecker.check(email, password, ipAddress);
    var customer = customerRepo.findById(customerId).orElseThrow(InvalidCredentialsException::new);

    // 5. Check if the account has been verified before proceeding
    if (!customer.getIsEmailVerified()) {
      logger.warn("Customer {}: login refused, email not verified", customerId);
      throw new EmailNotVerifiedException();
    }

    // 6. Issue a signed JWT -- self-verifying, nothing to persist
    var issuedToken = jwtIssuer.issue(customerId);

    // 7. Build and return the response with the token in the message body
    logger.info("customer {} login successful.", customerId);
    return LoginResponse.accepted(
        customerId,
        customer.getEmail(),
        customer.getIsEmailVerified(),
        issuedToken.token(),
        issuedToken.expiresAt());
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
        ActorType.CUSTOMER,
        existingCustomer.getId(),
        AuditAction.REGISTRATION_REJECTED,
        "auth",
        existingCustomer.getId(),
        ipAddress);
    return new AccountAlreadyExistsException();
  }

  /*
  private UserAlreadyExistsException duplicateEmailRaceLost(String ipAddress) {
    auditLogService.recordEvent(
        "system", SYSTEM_ACTOR_ID, "REGISTRATION_REJECTED", "customers", null, ipAddress);
    return new UserAlreadyExistsException();
  }
   */
}
