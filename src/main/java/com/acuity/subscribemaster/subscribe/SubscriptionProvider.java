package com.acuity.subscribemaster.subscribe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * Read-only entity mirroring the seeded {@code subscription_providers} catalog table. No setters —
 * providers are populated via Flyway migration only; there is no write path (no POST endpoint is
 * exposed for this resource).
 */
@Entity
@Table(name = "subscription_providers")
class SubscriptionProvider {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, unique = true)
  private String name;

  @Column(name = "category")
  private String category;

  @Column(name = "logo_url")
  private String logoUrl;

  @Column(name = "website_url")
  private String websiteUrl;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public SubscriptionProvider() {}

  public SubscriptionProvider(
      UUID id, String name, String category, String logoUrl, String websiteUrl, Instant createdAt) {
    this.id = id;
    this.name = name;
    this.category = category;
    this.logoUrl = logoUrl;
    this.websiteUrl = websiteUrl;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getCategory() {
    return category;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public String getWebsiteUrl() {
    return websiteUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
