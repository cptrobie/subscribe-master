package com.acuity.subscribemaster.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One thing a staff member can be allowed to do, such as {@code process_refunds} or {@code
 * view_audit_logs} (table {@code permissions}, created in {@code V1} and seeded in {@code V1.1}).
 * Roles are granted permissions through {@code role_permissions}; see {@link Role}.
 *
 * <p>{@link #getName()} is the value endpoints check: the staff security chain turns each of a
 * caller's permission names into a {@code GrantedAuthority} with no prefix, so an endpoint is
 * protected with {@code hasAuthority('process_refunds')}, never with a role name (FR-06, {@code
 * ARCHITECTURE.md} §2.2). {@code resource} and {@code action} describe the permission for access
 * reviews; nothing checks them.
 *
 * <p><b>Reference data, read-only here.</b> Permissions are added by migrations (each feature that
 * needs a new one seeds it, as {@code FR-35} will), never by application code. So there's no public
 * constructor and no setters, and {@code created_at}/{@code updated_at} are left unmapped: the
 * columns' {@code DEFAULT now()} fills them, and {@code ddl-auto: validate} only checks the columns
 * that are mapped.
 */
@Entity
@Table(name = "permissions")
public class Permission {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name")
  private String name;

  @Column(name = "resource")
  private String resource;

  @Column(name = "action")
  private String action;

  protected Permission() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getResource() {
    return resource;
  }

  public String getAction() {
    return action;
  }
}
