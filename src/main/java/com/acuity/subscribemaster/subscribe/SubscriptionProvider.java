package com.acuity.subscribemaster.subscribe;

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
 * A provider in the {@code subscription_providers} catalog (Netflix, Spotify, ...) that a customer
 * can pick when creating a subscription. The catalog starts from {@code
 * R__seed_subscription_provider_catalog.sql}; since FR-35, staff create, update and deactivate
 * providers through the staff API, so the entity is mutable.
 *
 * <p><b>Deactivated, never deleted.</b> A provider is deactivated by setting {@code deletedAt}; the
 * row is never removed, so subscriptions that already reference it are never orphaned. A
 * deactivated provider is treated as not found when a customer creates a subscription.
 *
 * <p><b>Name uniqueness.</b> {@code name} is unique among active providers only, enforced by the
 * partial unique index from {@code V14} ({@code WHERE deleted_at IS NULL}), not by a JPA {@code
 * unique} flag. Deactivating a provider frees its name, so it can be added again.
 *
 * <p><b>Category is a snapshot.</b> A subscription copies its provider's {@code category} when it
 * is created. Changing a provider's category later doesn't change subscriptions that already exist.
 *
 * <p>See {@code design-docs/provider_catalog_management.md}.
 */
@Entity
@Table(name = "subscription_providers")
class SubscriptionProvider {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
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

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public SubscriptionProvider() {}

  public SubscriptionProvider(String name, String category, String logoUrl, String websiteUrl) {
    this.name = name;
    this.category = category;
    this.logoUrl = logoUrl;
    this.websiteUrl = websiteUrl;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getLogoUrl() {
    return logoUrl;
  }

  public void setLogoUrl(String logoUrl) {
    this.logoUrl = logoUrl;
  }

  public String getWebsiteUrl() {
    return websiteUrl;
  }

  public void setWebsiteUrl(String websiteUrl) {
    this.websiteUrl = websiteUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }
}
