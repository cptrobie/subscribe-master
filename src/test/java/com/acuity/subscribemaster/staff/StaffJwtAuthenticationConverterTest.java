package com.acuity.subscribemaster.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class StaffJwtAuthenticationConverterTest {

  @Mock private StaffUserRepository staffRepo;
  @InjectMocks private StaffJwtAuthenticationConverter converter;

  private static Jwt jwtWithSubject(String subject) {
    return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).build();
  }

  private StaffUser staffUser(UUID id, Role role) {
    var user = new StaffUser("a@example.com", "hash", role);
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  @Test
  void activeStaffUser_getsPermissionNamesAsAuthorities() {
    var user = staffUser(UUID.randomUUID(), mock(Role.class));
    var userId = user.getId();

    when(user.getRole().getPermissionNames()).thenReturn(Set.of("view_audit_logs", "manage_staff"));
    when(staffRepo.findWithRoleAndPermissionsById(userId)).thenReturn(Optional.of(user));

    var token = converter.convert(jwtWithSubject(userId.toString()));

    assertThat(token.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("view_audit_logs", "manage_staff");
    assertThat(token.getName()).isEqualTo(userId.toString());
  }

  @Test
  void activeStaffUserWithNoPermissions_isAuthenticatedWithNoAuthorities() {
    var user = staffUser(UUID.randomUUID(), mock(Role.class));
    var userId = user.getId();

    when(user.getRole().getPermissionNames()).thenReturn(Set.of());
    when(staffRepo.findWithRoleAndPermissionsById(userId)).thenReturn(Optional.of(user));

    var token = converter.convert(jwtWithSubject(userId.toString()));

    assertThat(token.getAuthorities()).isEmpty();
    assertThat(token.getName()).isEqualTo(userId.toString());
  }

  @Test
  void unknownStaffUser_throwsInvalidBearerToken() {
    var userId = UUID.randomUUID();
    var jwt = jwtWithSubject(userId.toString());

    when(staffRepo.findWithRoleAndPermissionsById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> converter.convert(jwt))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("Invalid token");
  }

  @Test
  void inactiveStaffUser_throwsInvalidBearerToken() {
    var user = staffUser(UUID.randomUUID(), mock(Role.class));
    var userId = user.getId();
    var jwt = jwtWithSubject(userId.toString());

    user.setActive(false);
    when(staffRepo.findWithRoleAndPermissionsById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> converter.convert(jwt))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("Invalid token");
  }

  @Test
  void subjectNotAUuid_throwsInvalidBearerTokenWithoutLookup() {
    var jwt = jwtWithSubject("not-a-uuid");

    assertThatThrownBy(() -> converter.convert(jwt))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("Invalid token");
    verifyNoInteractions(staffRepo);
  }

  @Test
  void missingSubject_throwsInvalidBearerTokenWithoutLookup() {
    var jwt = jwtWithSubject(null);

    assertThatThrownBy(() -> converter.convert(jwt))
        .isInstanceOf(InvalidBearerTokenException.class)
        .hasMessage("Invalid token");
    verifyNoInteractions(staffRepo);
  }
}
