package com.acuity.subscribemaster.auditlog;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@link AuditLog} entries (NFR-17) and logs a one-line summary of each. Callers are
 * responsible for passing the correct {@link ActorType} and actor id, since nothing in the database
 * enforces it (ARCHITECTURE.md &sect;6.1).
 *
 * <p>Both {@code recordEvent} overloads run in their own transaction ({@code REQUIRES_NEW}), so the
 * entry is committed independently of the caller's transaction and survives if that transaction
 * later rolls back -- e.g. {@code REGISTRATION_REJECTED} and {@code ACCOUNT_LOCKED} are recorded
 * just before {@code AuthService} throws.
 *
 * <p>The overload taking {@code details} (NFR-41) also records what the action changed; see {@link
 * AuditLog} for the convention. The one without it leaves {@code details} {@code null}.
 */
@Service
public class AuditLogService {
  private final AuditLogRepository repository;

  private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

  public AuditLogService(AuditLogRepository repository) {
    this.repository = repository;
  }

  /**
   * Records an entry with no {@code details}. Delegates to the overload below with {@code null}.
   * That call stays inside this object, so it doesn't go through the Spring proxy, and this
   * method's own {@code @Transactional} is what opens the new transaction. Keep the annotation
   * here.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEvent(
      ActorType actorType,
      UUID actorId,
      AuditAction action,
      String resource,
      UUID resourceId,
      String ipAddress) {
    recordEvent(actorType, actorId, action, resource, resourceId, null, ipAddress);
  }

  /**
   * Records an entry that also says what the action changed (NFR-41).
   *
   * @param details what changed, following the convention on {@link AuditLog}'s {@code details}
   *     field; may be {@code null}. Must never hold secrets or personal data (NFR-23).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEvent(
      ActorType actorType,
      UUID actorId,
      AuditAction action,
      String resource,
      UUID resourceId,
      Map<String, Object> details,
      String ipAddress) {
    repository.save(
        new AuditLog(actorType, actorId, action, resource, resourceId, details, ipAddress));
    logger.info(
        "audit: actor={}/{} action={} resource={}/{}",
        actorType,
        actorId,
        action,
        resource,
        resourceId);
  }
}
