package com.acuity.subscribemaster.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A self-service customer account (table {@code customers}, created in {@code
 * V1__init_auth_and_authz.sql}).
 *
 * <p>Only the columns Wave&nbsp;1 needs are mapped. {@code base_currency} (FR-13), {@code
 * stripe_customer_id} (FR-12) and the OAuth columns are left to later waves; {@code base_currency}
 * has a {@code DEFAULT 'USD'} in the schema, so unmapped inserts still satisfy its {@code NOT
 * NULL}.
 */
@Entity
@Table(name = "customers")
public class Customer {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  // citext in the schema — declared here so Hibernate schema validation matches.
  @Column(columnDefinition = "citext", nullable = false, unique = true, updatable = false)
  private String email;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified = false;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Customer() {
    // for JPA
  }

  public Customer(String email, String passwordHash) {
    this.email = email;
    this.passwordHash = passwordHash;
  }
  /**
   * Test-support constructor only -- lets test code set a known, predictable id directly.
   * Never call this from production code: {@code id} is meant to come exclusively from
   * {@code @UuidGenerator}, and bypassing that here means the caller is choosing a
   * customer's identity rather than letting Hibernate generate one.
   */
  public Customer(UUID id, String email, String passwordHash, boolean emailVerified, boolean active) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
    this.emailVerified = emailVerified;
    this.active = active;
  }

  /** Marks the account verified (FR-31 will gate login on this; out of scope here). */
  public void markEmailVerified() {
    this.emailVerified = true;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
