package com.acuity.subscribemaster.auditlog;

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
 * <p>{@link #recordEvent} runs in its own transaction ({@code REQUIRES_NEW}), so the entry is
 * committed independently of the caller's transaction and survives if that transaction later rolls
 * back -- e.g. {@code REGISTRATION_REJECTED} and {@code ACCOUNT_LOCKED} are recorded just before
 * {@code AuthService} throws.
 */
@Service
public class AuditLogService {
  private final AuditLogRepository repository;

  private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

  public AuditLogService(AuditLogRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEvent(
      ActorType actorType,
      UUID actorId,
      AuditAction action,
      String resource,
      UUID resourceId,
      String ipAddress) {
    repository.save(new AuditLog(actorType, actorId, action, resource, resourceId, ipAddress));
    logger.info(
        "audit: actor={}/{} action={} resource={}/{}",
        actorType,
        actorId,
        action,
        resource,
        resourceId);
  }
}
