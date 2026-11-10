package com.acuity.subscribemaster.staff;

import static jakarta.persistence.FetchType.LAZY;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * An internal staff account (table {@code staff_users}, created in {@code V1}; lockout columns
 * added in {@code V13}). Staff are a separate population from {@link
 * com.acuity.subscribemaster.customer.Customer}, with their own table, login and tokens ({@code
 * ARCHITECTURE.md} §2.1). What a staff member may do comes from their {@link Role}'s permissions
 * (FR-06).
 *
 * <p><b>Why this entity has associations when the rest of the project doesn't.</b> Elsewhere a
 * related row is held as a plain UUID, never a {@code @ManyToOne}, because those entities belong to
 * different domains that Wave 4.5's module boundaries will separate. A staff user, its role and the
 * role's permissions are one small group within a single domain, and the staff security chain needs
 * all three on every request. Mapping {@code role} as a lazy {@code @ManyToOne} (NFR-15) lets
 * {@link StaffUserRepository#findWithRoleAndPermissionsById} load them in one query; plain UUIDs
 * would need three. See {@code design-docs/staff_authorization_design.md}.
 *
 * <p><b>Lockout</b> works as {@code Customer}'s does: {@code failed_login_attempts} and {@code
 * locked_until}, checked when the lock might have expired, not by a scheduled job. Staff have their
 * own, stricter limits ({@code app.staff.login-lockout.*}), applied by the staff credential check.
 *
 * <p>{@code is_active = false} turns an account off without deleting it: the staff security chain
 * treats an inactive user's token as invalid (401) on the next request, since permissions are
 * looked up per request rather than carried in the token. {@code password_hash} is nullable in the
 * schema, to allow SSO-only accounts later.
 */
@Entity
@Table(name = "staff_users")
public class StaffUser {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  // citext in the schema — declared here so Hibernate schema validation matches.
  @Column(columnDefinition = "citext", nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash")
  private String passwordHash;

  @ManyToOne(fetch = LAZY, optional = false)
  @JoinColumn(name = "role_id", nullable = false)
  private Role role;

  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginCount = 0;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected StaffUser() {
    // for JPA
  }

  /**
   * A new, active staff account with no failed logins.
   *
   * @param email the login email, already stripped and lowercased; unique case-insensitively
   *     ({@code citext}), but looked up exactly -- see {@link StaffUserRepository}
   * @param passwordHash an already-encoded password hash, never a raw password
   * @param role the role granting this account's permissions, loaded with {@link
   *     RoleRepository#findByName}
   */
  public StaffUser(String email, String passwordHash, Role role) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
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

  /**
   * This account's role. Loaded lazily unless the user came from {@link
   * StaffUserRepository#findWithRoleAndPermissionsById}; see {@link Role} for what that means
   * outside a transaction.
   */
  public Role getRole() {
    return role;
  }

  public boolean isActive() {
    return isActive;
  }

  public int getFailedLoginCount() {
    return failedLoginCount;
  }

  public void setFailedLoginCount(int failedLoginCount) {
    this.failedLoginCount = failedLoginCount;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  /**
   * Locks the account until the given time, or clears the lock when {@code null}.
   *
   * @param lockedUntil when the lock expires; {@code null} for not locked
   */
  public void setLockedUntil(Instant lockedUntil) {
    this.lockedUntil = lockedUntil;
  }
}
