package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acuity.subscribemaster.auth.InvalidCredentialsException;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class StaffServiceTest {
  @Mock private StaffUserRepository staffRepo;
  @InjectMocks private StaffService staffService;

  private StaffUser staffUser(UUID id, Role role) {
    var user = new StaffUser("a@example.com", "hash", role);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  @Test
  void currentStaffUser_existingUser_returnsProfileWithSortedPermissions() {
    var id = UUID.randomUUID();
    var role = mock(Role.class);
    var user = staffUser(id, role);

    when(role.getName()).thenReturn("auditor");
    when(role.getPermissionNames())
        .thenReturn(Set.of("view_system_health", "view_audit_logs", "view_app_logs"));
    when(staffRepo.findWithRoleAndPermissionsById(id)).thenReturn(Optional.of(user));

    var me = staffService.currentStaffUser(id);

    assertThat(me.id()).isEqualTo(id);
    assertThat(me.email()).isEqualTo(user.getEmail());
    assertThat(me.role()).isEqualTo("auditor");
    assertThat(me.permissions())
        .containsExactly("view_app_logs", "view_audit_logs", "view_system_health");
  }

  @Test
  void currentStaffUser_existingUserWithNoPermissions_returnsEmptyPermissions() {
    var id = UUID.randomUUID();
    var role = mock(Role.class);
    var user = staffUser(id, role);

    when(role.getName()).thenReturn("auditor");
    when(role.getPermissionNames()).thenReturn(Collections.emptySet());
    when(staffRepo.findWithRoleAndPermissionsById(id)).thenReturn(Optional.of(user));

    var me = staffService.currentStaffUser(id);

    assertThat(me.id()).isEqualTo(id);
    assertThat(me.email()).isEqualTo(user.getEmail());
    assertThat(me.role()).isEqualTo("auditor");
    assertThat(me.permissions()).isEmpty();
  }

  @Test
  void currentStaffUser_missingUser_throwsInvalidCredentials() {
    var id = UUID.randomUUID();

    when(staffRepo.findWithRoleAndPermissionsById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> staffService.currentStaffUser(id))
        .isInstanceOf(InvalidCredentialsException.class);
  }
}
