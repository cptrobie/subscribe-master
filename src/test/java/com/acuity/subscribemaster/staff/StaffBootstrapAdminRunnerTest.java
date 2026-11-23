package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acuity.subscribemaster.auditlog.ActorType;
import com.acuity.subscribemaster.auditlog.AuditAction;
import com.acuity.subscribemaster.auditlog.AuditLogService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link StaffBootstrapAdminRunner} (FR-06): every path through the startup check
 * that creates the first admin. It's a plain Mockito test, with no Spring context or database, so
 * it runs in milliseconds and counts towards the JaCoCo unit-test gate.
 *
 * <p>Each test builds its own runner through {@link #runner}, because the email and password are
 * what vary from test to test: both set, only one, neither, blank, or with spaces or mixed case.
 * The configuration cases pass {@code ""} as well as {@code null}, since {@code ""} is what Spring
 * supplies at startup for a missing key.
 *
 * <p>Where a test expects the runner to stop early, {@code verifyNoInteractions} or {@code never()}
 * proves it stopped <em>before</em> the work it skips, not just that it didn't fail. The end-to-end
 * behaviour against a real database and Vault is in {@code StaffBootstrapAdminRunnerIT}.
 */
@ExtendWith(MockitoExtension.class)
public class StaffBootstrapAdminRunnerTest {
  @Mock private StaffUserRepository staffUserRepo;
  @Mock private RoleRepository roleRepo;
  @Mock private PasswordEncoder pwdEncoder;
  @Mock private AuditLogService auditLogService;

  private final Role adminRole = mock(Role.class);

  private static final String EMAIL = "admin@example.com";
  private static final String PASSWORD = "adminEasyPassword!#123";
  private static final UUID ADMIN_ID = UUID.randomUUID();

  /** A runner configured with these bootstrap values; {@code null} or blank means unset. */
  private StaffBootstrapAdminRunner runner(String email, String password) {
    return new StaffBootstrapAdminRunner(
        staffUserRepo, roleRepo, pwdEncoder, email, password, auditLogService);
  }

  @Captor private ArgumentCaptor<StaffUser> staffUserCaptor;

  /**
   * The staff user passed to the one {@code saveAndFlush} call; fails if there wasn't exactly one.
   */
  private StaffUser savedStaffUser() {
    verify(staffUserRepo).saveAndFlush(staffUserCaptor.capture());
    return staffUserCaptor.getValue();
  }

  /**
   * The path to the save: no staff yet, the seeded {@code admin} role found, and {@code PASSWORD}
   * hashed to {@code "bcrypt-hash"}. Deliberately leaves {@code saveAndFlush} unstubbed, so each
   * test chooses whether the save succeeds ({@link #stubSaveAssigningId}) or throws.
   */
  private void stubEmptyTableWithAdminRole() {
    when(staffUserRepo.count()).thenReturn(0L);
    when(roleRepo.findByName("admin")).thenReturn(Optional.of(adminRole));
    when(pwdEncoder.encode(PASSWORD)).thenReturn("bcrypt-hash");
  }

  /**
   * Makes the mocked save behave like the real one: it returns the entity it was given, with {@code
   * ADMIN_ID} as its id. A fixed id lets tests check that the audit row names the saved admin.
   */
  private void stubSaveAssigningId() {
    when(staffUserRepo.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              StaffUser user = inv.getArgument(0);
              ReflectionTestUtils.setField(user, "id", ADMIN_ID);
              return user;
            });
  }

  @Test
  void run_bothSetAndNoStaff_createsAdminAndAudits() {
    stubEmptyTableWithAdminRole();
    stubSaveAssigningId();

    runner(EMAIL, PASSWORD).run(null);
    var savedStaffUser = savedStaffUser();

    assertThat(savedStaffUser.getEmail()).isEqualTo(EMAIL);
    assertThat(savedStaffUser.getPasswordHash()).isEqualTo("bcrypt-hash");
    assertThat(savedStaffUser.getRole()).isSameAs(adminRole);

    verify(auditLogService)
        .recordEvent(
            ActorType.STAFF,
            ADMIN_ID,
            AuditAction.STAFF_USER_CREATED,
            "staff_user",
            ADMIN_ID,
            Map.of("source", "bootstrap"),
            null);
  }

  @Test
  void run_mixedCaseEmailWithSpaces_savesNormalizedEmail() {
    stubEmptyTableWithAdminRole();
    stubSaveAssigningId();

    runner("  Admin@Example.COM  ", PASSWORD).run(null);

    assertThat(savedStaffUser().getEmail()).isEqualTo(EMAIL);
  }

  @Test
  void run_passwordWithSpaces_encodesItUnstripped() {
    var spacedPassword = "  pass with spaces  ";
    when(staffUserRepo.count()).thenReturn(0L);
    when(roleRepo.findByName("admin")).thenReturn(Optional.of(adminRole));
    when(pwdEncoder.encode(spacedPassword)).thenReturn("bcrypt-hash");
    stubSaveAssigningId();

    runner(EMAIL, spacedPassword).run(null);

    assertThat(savedStaffUser().getPasswordHash()).isEqualTo("bcrypt-hash");
  }

  @Test
  void run_passwordEmpty_throws() {
    assertThatThrownBy(() -> runner(EMAIL, "").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.staff.bootstrap-admin.password");

    verifyNoInteractions(staffUserRepo, roleRepo, pwdEncoder, auditLogService);
  }

  @Test
  void run_onlyEmailSet_throws() {
    assertThatThrownBy(() -> runner(EMAIL, null).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.staff.bootstrap-admin.password");

    verifyNoInteractions(staffUserRepo, roleRepo, pwdEncoder, auditLogService);
  }

  @Test
  void run_onlyPasswordSet_throws() {
    assertThatThrownBy(() -> runner(null, PASSWORD).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.staff.bootstrap-admin.email");

    verifyNoInteractions(staffUserRepo, roleRepo, pwdEncoder, auditLogService);
  }

  @Test
  void run_neitherSet_doesNothing() {
    runner(null, null).run(null);

    verifyNoInteractions(staffUserRepo, roleRepo, pwdEncoder, auditLogService);
  }

  @Test
  void run_staffAlreadyExist_doesNothing() {
    when(staffUserRepo.count()).thenReturn(1L);

    runner(EMAIL, PASSWORD).run(null);

    verify(staffUserRepo, never()).saveAndFlush(any());
    verifyNoInteractions(roleRepo, pwdEncoder, auditLogService);
  }

  @Test
  void run_adminRoleMissing_throws() {
    when(staffUserRepo.count()).thenReturn(0L);
    when(roleRepo.findByName("admin")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> runner(EMAIL, PASSWORD).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Role 'admin' not found; it is seeded by V1.1__seed_roles_and_permissions");

    verify(staffUserRepo, never()).saveAndFlush(any());
    verifyNoInteractions(pwdEncoder, auditLogService);
  }

  @Test
  void run_concurrentCreate_logsAndReturns() {
    stubEmptyTableWithAdminRole();
    when(staffUserRepo.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatCode(() -> runner(EMAIL, PASSWORD).run(null)).doesNotThrowAnyException();

    verifyNoInteractions(auditLogService);
  }
}
