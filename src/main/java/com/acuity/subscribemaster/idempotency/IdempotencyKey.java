package com.acuity.subscribemaster.idempotency;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyPk.class)
public class IdempotencyKey implements Persistable<IdempotencyPk> {

  @Id
  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  @Id
  @Column(name = "endpoint", nullable = false, updatable = false)
  private String endpoint;

  @Column(name = "caller_id", updatable = false)
  private UUID callerId;

  @Column(name = "request_hash", nullable = false, updatable = false)
  private String requestHash;

  @Column(name = "response_status", nullable = false, updatable = false)
  private int responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", columnDefinition = "jsonb", updatable = false)
  private String responseBody;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected IdempotencyKey() {}

  public IdempotencyKey(
      String idempotencyKey,
      String endpoint,
      UUID callerId,
      String requestHash,
      int responseStatus,
      String responseBody,
      Instant expiresAt) {
    this.idempotencyKey = idempotencyKey;
    this.endpoint = endpoint;
    this.callerId = callerId;
    this.requestHash = requestHash;
    this.responseStatus = responseStatus;
    this.responseBody = responseBody;
    this.expiresAt = expiresAt;
  }

  @Override
  public IdempotencyPk getId() {
    return new IdempotencyPk(this.idempotencyKey, this.endpoint);
  }

  @Override
  public boolean isNew() {
    return createdAt == null;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public int getResponseStatus() {
    return responseStatus;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
