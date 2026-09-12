package com.acuity.subscribemaster.auditlog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Derived-query repository only — no native SQL (NFR-16). */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

  List<AuditLog> findByActorIdAndAction(UUID actorId, String action);
}
