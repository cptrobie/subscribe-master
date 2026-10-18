package com.acuity.subscribemaster.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A customer's email verification token (table {@code customer_email_verification_tokens}, {@code
 * V1}) -- FR-30.
 *
 * <p>Hash-only: {@code token_hash} is the SHA-256 of the raw token ({@link
 * com.acuity.subscribemaster.support.Tokens}); the raw value exists only in the emailed link, so a
 * leaked row can't be turned back into a working link (ARCHITECTURE.md &sect;2.3). Single-use:
 * {@code used_at} marks consumption and is the record support checks when a customer says their
 * link didn't work. Expires after {@code app.email-verification.token-ttl}.
 *
 * <p>{@code customer_id} is a plain UUID rather than a {@code @ManyToOne Customer}: {@code
 * Customer} belongs to the {@code customer} module, and cross-module references are by id only
 * (NFR-26). The row is deleted with its customer ({@code ON DELETE CASCADE}), which FR-32's cleanup
 * relies on.
 */
@Entity
@Table(name = "customer_email_verification_tokens")
public class CustomerEmailVerificationToken {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private UUID customerId;

  @Column(name = "token_hash", unique = true, nullable = false, updatable = false)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CustomerEmailVerificationToken() {}

  public CustomerEmailVerificationToken(UUID customerId, String tokenHash, Instant expiresAt) {
    this.customerId = customerId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  boolean isUsed() {
    return usedAt != null;
  }

  boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  void markUsed(Instant now) {
    if (usedAt != null) {
      throw new IllegalStateException("verification token already used");
    }
    usedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
