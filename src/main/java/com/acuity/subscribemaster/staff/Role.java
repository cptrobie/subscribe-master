package com.acuity.subscribemaster.staff;

import static jakarta.persistence.FetchType.LAZY;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.UuidGenerator;

/**
 * A named set of staff permissions (table {@code roles}, created in {@code V1}). Four are seeded in
 * {@code V1.1}: {@code admin}, {@code support_agent}, {@code billing_admin} and {@code auditor}.
 * Every {@link StaffUser} has exactly one.
 *
 * <p>The role is how permissions are grouped and granted; it's never what an endpoint checks. Code
 * asks whether the caller has a {@link Permission}, never whether their role is called {@code
 * admin} (FR-06, {@code ARCHITECTURE.md} §2.2). Giving a role more or less access is a {@code
 * role_permissions} data change, not a code change or redeploy.
 *
 * <p><b>Lazy permissions.</b> {@code permissions} is mapped through {@code role_permissions} and
 * loaded lazily (NFR-15). The staff security chain loads a user, their role and its permissions in
 * one query with {@link StaffUserRepository#findWithRoleAndPermissionsById}; with {@code
 * open-in-view: false}, calling {@link #getPermissionNames()} on a role loaded any other way,
 * outside a transaction, throws {@code LazyInitializationException}.
 *
 * <p><b>Reference data, read-only here.</b> Roles and their permissions change only through
 * migrations until {@code manage_roles} exists, so there's no public constructor and no setters.
 * {@code created_at}/{@code updated_at} are left unmapped for the same reason as on {@link
 * Permission}.
 */
@Entity
@Table(name = "roles")
public class Role {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false)
  private String name;

  @ManyToMany(fetch = LAZY)
  @JoinTable(
      name = "role_permissions",
      joinColumns = @JoinColumn(name = "role_id"),
      inverseJoinColumns = @JoinColumn(name = "permission_id"))
  private Set<Permission> permissions = new HashSet<>();

  @Column(name = "description")
  private String description;

  protected Role() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  /**
   * The names of this role's permissions, such as {@code view_audit_logs} -- the values the staff
   * security chain turns into authorities.
   *
   * @return an unmodifiable set; empty if the role has no permissions
   */
  public Set<String> getPermissionNames() {
    return permissions.stream().map(Permission::getName).collect(Collectors.toUnmodifiableSet());
  }

  public String getDescription() {
    return description;
  }
}
