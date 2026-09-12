package com.acuity.subscribemaster.auditlog;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {
  private final AuditLogRepository repository;

  private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

  public AuditLogService(AuditLogRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordEvent(
      String actorType,
      UUID actorId,
      String action,
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
