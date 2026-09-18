package com.acuity.subscribemaster.customer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customer_sessions")
public class CustomerSession {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "customer_id", nullable = false, updatable = false) // Foreign key column name
  private UUID customerId;

  @Column(name = "session_token", unique = true, nullable = false, updatable = false)
  private String sessionToken;

  @JdbcTypeCode(SqlTypes.INET)
  @Column(name = "ip_address", columnDefinition = "inet", updatable = false)
  private String ipAddress;

  @Column(name = "user_agent", updatable = false)
  private String userAgent;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public CustomerSession() {
    // for JPA
  }

  public CustomerSession(
      UUID customerId, String sessionToken, String ipAddress, String userAgent, Instant expiresAt) {
    this.customerId = customerId;
    this.sessionToken = sessionToken;
    this.ipAddress = ipAddress;
    this.userAgent = userAgent;
    this.expiresAt = expiresAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getSessionToken() {
    return sessionToken;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
