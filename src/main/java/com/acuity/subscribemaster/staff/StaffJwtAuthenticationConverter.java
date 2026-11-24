package com.acuity.subscribemaster.staff;

import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a verified staff token into an authenticated staff user with their permissions as
 * authorities (FR-06). The staff filter chain in {@code SecurityConfig} calls this once per
 * request, after its decoder has checked the signature, expiry and {@code aud = staff-api}.
 *
 * <p><b>Permissions are looked up on every request, not read from the token.</b> The token carries
 * only the staff id ({@code sub}). Each call loads the user, role and permissions in one query
 * ({@link StaffUserRepository#findWithRoleAndPermissionsById}), so a {@code role_permissions}
 * change or a deactivation takes effect on that person's very next request instead of when their
 * token expires. See {@code design-docs/staff_authorization_design.md}.
 *
 * <p><b>Authorities are permission names, never role names.</b> Each permission becomes a plain
 * {@link SimpleGrantedAuthority} with no {@code ROLE_} or {@code SCOPE_} prefix, so endpoints check
 * {@code hasAuthority('view_audit_logs')}.
 *
 * <p><b>401 versus 403.</b> A missing or malformed {@code sub}, an unknown id and a deactivated
 * account all throw {@link InvalidBearerTokenException}, which Spring Security turns into a 401:
 * the token no longer identifies anyone who may act. An active user whose role has no permissions
 * is still authenticated, with no authorities, and gets a 403 only on endpoints that require one.
 * All four failures use the same message, because it reaches the client in the {@code
 * WWW-Authenticate} header and mustn't show whether an account exists or is inactive.
 */
@Component
public class StaffJwtAuthenticationConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  private final StaffUserRepository staffRepo;

  public StaffJwtAuthenticationConverter(StaffUserRepository staffRepo) {
    this.staffRepo = staffRepo;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    var subject = jwt.getSubject();
    if (subject == null) {
      throw new InvalidBearerTokenException("Invalid token");
    }
    UUID staffUserId;
    try {
      staffUserId = UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
      throw new InvalidBearerTokenException("Invalid token");
    }
    var staffUser =
        staffRepo
            .findWithRoleAndPermissionsById(staffUserId)
            .orElseThrow(() -> new InvalidBearerTokenException("Invalid token"));
    if (!staffUser.isActive()) {
      throw new InvalidBearerTokenException("Invalid token");
    }
    var authorities =
        staffUser.getRole().getPermissionNames().stream().map(SimpleGrantedAuthority::new).toList();

    return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
  }
}
