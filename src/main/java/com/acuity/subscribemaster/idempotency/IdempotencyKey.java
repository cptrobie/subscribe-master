package com.acuity.subscribemaster.idempotency;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * The stored outcome of a POST request made with an {@code Idempotency-Key} header (table {@code
 * idempotency_keys}, {@code V7}) -- NFR-24, first used by registration in NFR-33.
 *
 * <p>Keyed by {@code (idempotency_key, endpoint)} ({@link IdempotencyPk}): a key value only means
 * something within one endpoint, so the same key sent to two endpoints is two separate rows. A
 * retry with a matching {@code request_hash} gets {@code response_status} and {@code response_body}
 * replayed instead of re-running the request; a different hash means the key was reused for a
 * different request.
 *
 * <p>{@code caller_id} has no real FK, like {@code audit_logs.actor_id}: it may refer to a
 * customer, a staff user or nobody, and is null on unauthenticated endpoints such as registration.
 * {@code response_body} is replayed verbatim to whoever retries with the key, so it must never hold
 * a secret (a raw token, a password).
 *
 * <p>Append-only: {@code created_at} only, every column {@code updatable = false} (NFR-07). Rows
 * past {@code expires_at} have no further use.
 */
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

  /** The composite key, as Spring Data's {@link Persistable} contract expects. */
  @Override
  public IdempotencyPk getId() {
    return new IdempotencyPk(this.idempotencyKey, this.endpoint);
  }

  /**
   * True until the row has been inserted ({@code created_at} is filled on insert, and on load).
   *
   * <p>Without this, Spring Data would treat every instance as existing -- its id is always set by
   * hand -- and {@code save()} would {@code merge()}, quietly passing over a duplicate key instead
   * of inserting. With it, {@code save()} persists, so a second row for the same key fails on the
   * primary key. The INSERT runs at flush, so use {@code saveAndFlush()} to see that failure where
   * it happens.
   */
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
