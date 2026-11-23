package com.acuity.subscribemaster.staff;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first {@code admin} staff user at startup, from configuration (FR-06). Staff accounts
 * are never self-registered, and creating them through the API ({@code manage_staff}, FR-45/FR-46)
 * needs an admin to exist first, so this runner is how a fresh environment gets one. It runs once,
 * after the context has started and Flyway has migrated the schema.
 *
 * <p><b>The checks, in order.</b>
 *
 * <ol>
 *   <li>Only one of {@code app.staff.bootstrap-admin.email} and {@code .password} is set: startup
 *       fails with an {@link IllegalStateException}. A half-configured Vault is a mistake, and
 *       guessing what was meant would be worse than stopping. This is checked before anything else,
 *       so the mistake shows up on every start, not only on an empty database.
 *   <li>Neither is set: nothing happens. This is the normal case once an admin exists, and in any
 *       environment that doesn't use the keys.
 *   <li>{@code staff_users} already has a row: nothing happens, so the keys can stay in Vault and
 *       every restart is safe.
 *   <li>Otherwise one {@code admin} is created, with the email stripped and lowercased (the {@code
 *       citext} lookups are case-sensitive from JPA) and a BCrypt hash of the password. The
 *       password itself is never stripped, logged or stored.
 * </ol>
 *
 * <p>Both keys come only from Vault at {@code secret/subscribe-master}, never from {@code
 * application*.yaml}; see README "Getting started", step 5. A missing {@code admin} role also fails
 * startup, since it is seeded by {@code V1.1} and its absence means the schema is wrong.
 *
 * <p><b>Audit.</b> The new account is recorded as {@code STAFF_USER_CREATED} with {@code details}
 * {@code {"source": "bootstrap"}}. The actor is the new admin's own id, because {@code actor_id} is
 * NOT NULL and there is no system actor type. There is no request, so the IP address is null.
 *
 * <p><b>Several instances starting at once.</b> Two instances on an empty database can both pass
 * the empty-table check. The unique email constraint lets only one insert succeed; the other gets a
 * {@link DataIntegrityViolationException}, logs that another instance created the admin, and starts
 * normally without auditing. This relies on both instances having the same email, which they do
 * when they read the same Vault path. Instances given <em>different</em> emails would each create
 * an admin; that is a misconfiguration and isn't guarded against.
 *
 * <p>Logs carry the staff id only, never the email (NFR-23). See {@code
 * design-docs/staff_authorization_design.md}.
 */
@Component
public class StaffBootstrapAdminRunner implements ApplicationRunner {
  private final StaffUserRepository staffUserRepo;
  private final RoleRepository roleRepo;
  private final PasswordEncoder pwdEncoder;
  private final String email;
  private final String password;
  private final AuditLogService auditLogService;

  private static final Logger logger = LoggerFactory.getLogger(StaffBootstrapAdminRunner.class);

  public StaffBootstrapAdminRunner(
      StaffUserRepository staffUserRepo,
      RoleRepository roleRepo,
      PasswordEncoder pwdEncoder,
      @Value("${app.staff.bootstrap-admin.email:}") String email,
      @Value("${app.staff.bootstrap-admin.password:}") String password,
      AuditLogService auditLogService) {
    this.staffUserRepo = staffUserRepo;
    this.roleRepo = roleRepo;
    this.pwdEncoder = pwdEncoder;
    this.email = email;
    this.password = password;
    this.auditLogService = auditLogService;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean emailSet = email != null && !email.isBlank();
    boolean passwordSet = password != null && !password.isBlank();

    if (emailSet != passwordSet) {
      throw new IllegalStateException(
          "Set both app.staff.bootstrap-admin.email and "
              + "app.staff.bootstrap-admin.password, or neither");
    }

    if (!emailSet) { // passwordSet is false too, by the check above
      logger.debug("bootstrap admin skipped: app.staff.bootstrap-admin.* not set");
      return;
    }

    if (staffUserRepo.count() > 0) {
      logger.info("bootstrap admin skipped: staff users already exist");
      return;
    }

    var normalizedEmail = email.toLowerCase(Locale.ROOT).strip();
    var adminRole =
        roleRepo
            .findByName("admin")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Role 'admin' not found; it is seeded by V1.1__seed_roles_and_permissions"));
    StaffUser adminUser;
    try {
      adminUser =
          staffUserRepo.saveAndFlush(
              new StaffUser(normalizedEmail, pwdEncoder.encode(password), adminRole));
    } catch (DataIntegrityViolationException e) {
      logger.info("bootstrap admin skipped: created by another instance");
      logger.debug("bootstrap admin insert rejected by a constraint", e);
      return;
    }
    var adminUserId = adminUser.getId();

    auditLogService.recordEvent(
        ActorType.STAFF,
        adminUserId,
        AuditAction.STAFF_USER_CREATED,
        "staff_user",
        adminUserId,
        Map.of("source", "bootstrap"),
        null);
    logger.info("staff {} created as bootstrap admin", adminUserId);
  }
}
