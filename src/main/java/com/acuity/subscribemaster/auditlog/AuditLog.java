package com.acuity.subscribemaster.auditlog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
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
 *
 * <p>{@code details} ({@code V12}, NFR-41) records <i>what</i> an action changed, as a nullable
 * {@code jsonb} object. Rows with nothing to add leave it {@code null}.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {
  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "actor_type", nullable = false, updatable = false)
  private ActorType actorType;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private UUID actorId;

  @Column(name = "action", nullable = false, updatable = false)
  private AuditAction action;

  @Column(name = "resource", nullable = false, updatable = false)
  private String resource;

  @Column(name = "resource_id", updatable = false)
  private UUID resourceId;

  /**
   * What the action changed (NFR-41), or {@code null}. Convention: a small object holding only what
   * a reviewer needs. For a change, that's {@code {"from": ..., "to": ...}} plus a key naming the
   * thing changed (e.g. {@code "logger"}, {@code "setting"}). Never secrets or personal data
   * (NFR-23). Values may be {@code null} (e.g. {@code "from"} on a first-time set), so callers
   * shouldn't build the map with {@code Map.of}, which rejects nulls.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "details", columnDefinition = "jsonb", updatable = false)
  private Map<String, Object> details;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", columnDefinition = "inet", updatable = false)
  private String ipAddress;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AuditLog() {}

  /** Creates an entry with no {@code details}; equivalent to passing {@code null} for them. */
  public AuditLog(
      ActorType actorType,
      UUID actorId,
      AuditAction action,
      String resource,
      UUID resourceId,
      String ipAddress) {
    this(actorType, actorId, action, resource, resourceId, null, ipAddress);
  }

  /**
   * Creates an entry that also records what the action changed.
   *
   * @param details what changed, following the convention on the {@code details} field; may be
   *     {@code null}. Stored as given, not copied.
   */
  public AuditLog(
      ActorType actorType,
      UUID actorId,
      AuditAction action,
      String resource,
      UUID resourceId,
      Map<String, Object> details,
      String ipAddress) {
    this.actorType = actorType;
    this.actorId = actorId;
    this.action = action;
    this.resource = resource;
    this.details = details;
    this.resourceId = resourceId;
    this.ipAddress = ipAddress;
  }

  public UUID getId() {
    return id;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public UUID getActorId() {
    return actorId;
  }

  public AuditAction getAction() {
    return action;
  }

  public String getResource() {
    return resource;
  }

  public UUID getResourceId() {
    return resourceId;
  }

  public Map<String, Object> getDetails() {
    return details;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
