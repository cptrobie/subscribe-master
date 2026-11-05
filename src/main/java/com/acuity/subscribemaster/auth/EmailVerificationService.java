package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import com.acuity.subscribemaster.auth.dto.EmailVerificationResponse;
import com.acuity.subscribemaster.auth.dto.ResendVerificationResponse;
import com.acuity.subscribemaster.customer.CustomerRepository;
import com.acuity.subscribemaster.support.Tokens;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The email verification lifecycle: confirming a customer's address from the token in their
 * verification link (FR-30), and resending that link -- optionally to a corrected address -- for an
 * account that isn't verified yet (FR-40).
 *
 * <p><b>Confirming ({@code verify}).</b> Checks run in a deliberate order. A token that's already
 * used, for a customer who's already verified, is treated as success ("already verified")
 * <i>before</i> expiry is checked: link scanners (e.g. Outlook Safe Links) often open the link
 * first, and the customer's own click -- possibly after the window has passed -- must not then see
 * an error. A used token for an unverified customer is a link a resend has superseded -- it marks
 * every earlier token used so only the newest works -- so it's rejected as invalid, not reported as
 * verified. That's an ordinary outcome (a customer clicking an older email), logged at {@code
 * info}.
 *
 * <p>Failures throw {@link InvalidVerificationTokenException} (unknown token, missing customer,
 * inconsistent state -- all just "this link doesn't work" to the user) or {@link
 * VerificationTokenExpiredException}; distinguishing expiry is safe because tokens are unguessable,
 * so it reveals nothing to a prober. Only success is audited ({@code EMAIL_VERIFIED}); failures are
 * logged at {@code info} or {@code warn}. Nothing here ever logs the raw token, its hash, or the
 * email address -- the token arrives in a URL, so it must stay out of every log.
 *
 * <p><b>Resending ({@code resendVerification}).</b> The caller proves they own the account through
 * {@link CredentialChecker} -- the same check and lockout as login, committed in its own
 * transaction, so a wrong guess still counts when the resend is rolled back. Then, in order: an
 * already-verified account is refused (409), as is a request over the rate limit (429, see {@link
 * EmailVerificationProperties}) and a corrected address that belongs to another account (409).
 * Every check runs before anything changes, and the rate limit runs before the address check, so
 * the endpoint can't be used at speed to find out which addresses are registered. Only then is the
 * address changed (audited {@code EMAIL_CHANGED}), every unused token marked used -- so only the
 * newest link works -- and a new token issued. The email goes out after commit, through {@link
 * VerificationEmailRequestedEvent}.
 *
 * <p>Package-private on purpose: only {@code AuthController} and {@code AuthService} call it.
 * {@code @Transactional} on a package-private method relies on Spring 6+ and Spring Boot's default
 * class-based proxies, and on being called from another bean -- a call from within this class would
 * bypass the transaction. Entities are loaded inside the transaction, so their changes are written
 * on commit with no explicit {@code save()}; only a newly issued token is saved explicitly.
 */
@Service
class EmailVerificationService {
  private final CustomerRepository customerRepo;
  private final CredentialChecker credChecker;
  private final CustomerEmailVerificationTokenRepository emailVerifyTokenRepo;
  private final EmailVerificationProperties emailVerifyProps;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(EmailVerificationService.class);

  public EmailVerificationService(
      CustomerRepository customerRepo,
      CredentialChecker credentialChecker,
      CustomerEmailVerificationTokenRepository emailVerificationTokenRepo,
      EmailVerificationProperties emailVerificationProperties,
      ApplicationEventPublisher eventPublisher,
      AuditLogService auditLogService) {
    this.customerRepo = customerRepo;
    this.credChecker = credentialChecker;
    this.emailVerifyTokenRepo = emailVerificationTokenRepo;
    this.emailVerifyProps = emailVerificationProperties;
    this.eventPublisher = eventPublisher;
    this.auditLogService = auditLogService;
  }

  @Transactional
  EmailVerificationResponse verify(String rawToken, String ipAddress) {
    var emailVerifyToken =
        emailVerifyTokenRepo
            .findByTokenHash(Tokens.hash(rawToken))
            .orElseThrow(
                () -> {
                  logger.info("verification rejected: unknown token");
                  return new InvalidVerificationTokenException();
                });
    var customer =
        customerRepo
            .findById(emailVerifyToken.getCustomerId())
            .orElseThrow(
                () -> {
                  logger.warn(
                      "verification rejected: token {} has no customer", emailVerifyToken.getId());
                  return new InvalidVerificationTokenException();
                });

    if (emailVerifyToken.isUsed()) {
      if (customer.getIsEmailVerified()) {
        logger.info("customer {} verification link reused", emailVerifyToken.getCustomerId());
        return EmailVerificationResponse.alreadyVerified();
      }
      logger.info(
          "verification rejected for customer {}: link superseded by a resend",
          emailVerifyToken.getCustomerId());
      throw new InvalidVerificationTokenException();
    }

    var now = Instant.now();
    if (emailVerifyToken.isExpired(now)) {
      logger.info(
          "verification rejected for customer {}: token expired", emailVerifyToken.getCustomerId());
      throw new VerificationTokenExpiredException();
    }

    emailVerifyToken.markUsed(now);
    customer.markEmailVerified();

    logger.info("customer {} email verified", emailVerifyToken.getCustomerId());

    auditLogService.recordEvent(
        ActorType.CUSTOMER,
        emailVerifyToken.getCustomerId(),
        AuditAction.EMAIL_VERIFIED,
        "auth",
        emailVerifyToken.getCustomerId(),
        ipAddress);

    return EmailVerificationResponse.verified();
  }

  /**
   * Resends the verification link (FR-40), first correcting the account's address when {@code
   * newEmail} is given and differs from it. Runs inside {@code IdempotencyService}'s transaction,
   * so any rejection rolls back every change and nothing is stored under the key.
   *
   * @param newEmail the corrected address, already normalized by {@code ResendVerificationRequest};
   *     null to resend to the current address
   * @return the address the new link is sent to
   * @throws InvalidCredentialsException if the email or password is wrong
   * @throws AccountLockedException if the account is locked
   * @throws EmailAlreadyVerifiedException if the account is already verified
   * @throws VerificationResendLimitException if too many links were issued within the window
   * @throws AccountAlreadyExistsException if {@code newEmail} belongs to another account
   */
  @Transactional
  ResendVerificationResponse resendVerification(
      String email, String password, String newEmail, String ipAddress) {
    var now = Instant.now();
    var customerId = credChecker.check(email, password, ipAddress);
    var customer = customerRepo.findById(customerId).orElseThrow(InvalidCredentialsException::new);

    if (customer.getIsEmailVerified()) {
      logger.info("customer {} resend rejected: already verified", customerId);
      throw new EmailAlreadyVerifiedException();
    }

    var sent =
        emailVerifyTokenRepo.countByCustomerIdAndCreatedAtAfter(
            customerId, now.minus(emailVerifyProps.resendWindow()));

    if (sent >= emailVerifyProps.resendLimit()) {
      logger.info("customer {} resend rejected: rate limit reached", customerId);
      throw new VerificationResendLimitException();
    }

    if (newEmail != null && !newEmail.equals(customer.getEmail())) {
      // TODO: a concurrent resend (or registration) claiming the same newEmail could still land
      // between this check and commit -- the citext unique constraint catches it, but it surfaces
      // as a raw 500 rather than AccountAlreadyExistsException. Same gap as register(); accepted
      // for now (FR-40 decision 6).
      if (customerRepo.existsByEmail(newEmail)) {
        logger.info("customer {} resend rejected: new email already in use", customerId);
        throw new AccountAlreadyExistsException();
      }
      customer.changeEmail(newEmail);
      auditLogService.recordEvent(
          ActorType.CUSTOMER, customerId, AuditAction.EMAIL_CHANGED, "auth", customerId, ipAddress);
    }

    // before the new token is saved: the query's auto-flush would otherwise mark it used too
    for (CustomerEmailVerificationToken emailVerifyToken :
        emailVerifyTokenRepo.findByCustomerIdAndUsedAtIsNull(customerId)) {
      emailVerifyToken.markUsed(now);
    }

    var rawToken = issueToken(customerId, now);

    eventPublisher.publishEvent(
        new VerificationEmailRequestedEvent(customerId, customer.getEmail(), rawToken, ipAddress));

    logger.info("customer {} verification link reissued", customerId);
    return ResendVerificationResponse.sentTo(customer.getEmail());
  }

  /**
   * Issues a new verification token for the customer: generates a random token, saves only its
   * hash, expiring {@code app.email-verification.token-ttl} after {@code now}, and returns the raw
   * value. Shared by registration and the FR-40 resend, so both make tokens the same way.
   *
   * <p>No {@code @Transactional} of its own, deliberately: it always runs in its caller's
   * transaction -- {@code AuthService.register()}'s, or {@link #resendVerification}'s through a
   * direct call -- so the token is saved, or rolled back, together with the rest of that change.
   *
   * @return the raw token -- the only copy, since only its hash is stored. It must go nowhere but
   *     the verification email's event: never into a log, a response body or anything persisted.
   */
  String issueToken(UUID customerId, Instant now) {
    var rawToken = Tokens.generate();
    var emailVerifyToken =
        new CustomerEmailVerificationToken(
            customerId, Tokens.hash(rawToken), now.plus(emailVerifyProps.tokenTtl()));
    emailVerifyTokenRepo.save(emailVerifyToken);

    return rawToken;
  }
}
