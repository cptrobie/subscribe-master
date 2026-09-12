package com.acuity.subscribemaster.auditlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A "who did what, to what, when" record (table {@code audit_logs}, {@code V1}).
 *
 * <p>{@code actor_id} is a polymorphic reference (customer <i>or</i> staff, per {@code actor_type})
 * with no real FK — integrity is an application concern (ARCHITECTURE.md &sect;6.1). Append-only:
 * {@code created_at} only (NFR-07).
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {
  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "actor_type", nullable = false, updatable = false)
  private String actorType;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private UUID actorId;

  @Column(nullable = false, updatable = false)
  private String action;

  @Column(nullable = false, updatable = false)
  private String resource;

  @Column(name = "resource_id", updatable = false)
  private UUID resourceId;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", columnDefinition = "inet", updatable = false)
  private String ipAddress;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {}

  public AuditLog(
      String actorType,
      UUID actorId,
      String action,
      String resource,
      UUID resourceId,
      String ipAddress) {
    this.actorType = actorType;
    this.actorId = actorId;
    this.action = action;
    this.resource = resource;
    this.resourceId = resourceId;
    this.ipAddress = ipAddress;
  }

  public UUID getId() {
    return id;
  }

  public String getActorType() {
    return actorType;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getAction() {
    return action;
  }

  public String getResource() {
    return resource;
  }

  public UUID getResourceId() {
    return resourceId;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
