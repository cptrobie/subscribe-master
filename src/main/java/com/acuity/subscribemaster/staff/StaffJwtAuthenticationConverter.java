package com.acuity.subscribemaster.staff;

import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

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
