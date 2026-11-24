package com.acuity.subscribemaster.staff;

import com.acuity.subscribemaster.auth.InvalidCredentialsException;
import com.acuity.subscribemaster.staff.dto.StaffMeResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Staff operations other than logging in (FR-06). Today that's only the signed-in user's own
 * profile, {@link #currentStaffUser}, which backs {@code GET /api/v1/staff/me}. Staff account
 * administration (inviting, deactivating and changing the role of staff users, FR-45/FR-46) is
 * planned to live here too; see {@code design-docs/staff_account_administration.md}.
 *
 * <p>Login stays in {@link StaffAuthService}: it's the one staff operation that runs without a
 * token, and it has its own lockout and audit rules.
 *
 * <p>Every caller here has already passed the staff security chain, so the staff user is known to
 * exist and be active when a method is called. Methods still handle a missing user, because the row
 * can be deleted between the security check and the method.
 */
@Service
public class StaffService {
  private final StaffUserRepository staffRepo;

  // not needed at this time
  // private static final Logger logger = LoggerFactory.getLogger(StaffService.class);

  public StaffService(StaffUserRepository staffRepo) {
    this.staffRepo = staffRepo;
  }

  /**
   * The profile behind {@code GET /api/v1/staff/me}: id, email, role name and permission names.
   *
   * <p>Uses the same single query as {@link StaffJwtAuthenticationConverter}, which keeps only the
   * permission names, so the email and role name need loading again. That query fetches the role
   * and permissions eagerly, so nothing here loads lazily outside a transaction.
   *
   * @param staffUserId the caller's id, from the token's {@code sub}
   * @return the profile; {@link StaffMeResponse} sorts the permissions
   * @throws InvalidCredentialsException (401) if the user no longer exists. The converter has
   *     already checked this on the same request, so it can only happen if the row is deleted in
   *     between.
   */
  public StaffMeResponse currentStaffUser(UUID staffUserId) {
    var staffUser =
        staffRepo
            .findWithRoleAndPermissionsById(staffUserId)
            .orElseThrow(InvalidCredentialsException::new);
    return new StaffMeResponse(
        staffUserId,
        staffUser.getEmail(),
        staffUser.getRole().getName(),
        List.copyOf(staffUser.getRole().getPermissionNames()));
  }
}
