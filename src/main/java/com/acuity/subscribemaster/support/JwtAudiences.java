package com.acuity.subscribemaster.support;

/**
 * The {@code aud} (audience) claim values that tell a customer access token from a staff one
 * (FR-06). Customer and staff tokens are signed with the same key, so without an audience a staff
 * token would pass the customer security chain, and its {@code sub} (a {@code staff_users.id})
 * would be read as a customer id on {@code /api/v1/subscribe/**}, and the reverse.
 *
 * <p>{@link JwtIssuer} stamps one of these on every token it signs. Each security chain's decoder
 * accepts only its own audience, so a token presented to the wrong chain is rejected with 401. Both
 * sides use these constants, so they can't disagree.
 *
 * <p><b>Constants, not configuration, deliberately.</b> These values are an agreement inside the
 * code, between the issuer and the decoders, not something an environment tunes. As configuration,
 * a typo in one environment would quietly reject every token there. Changing a value invalidates
 * every token already issued with the old one, which costs at most one access-token lifetime
 * ({@code app.jwt.access-token-duration-minutes}) of forced re-logins. See {@code
 * design-docs/staff_authorization_design.md}.
 */
public final class JwtAudiences {

  /** The audience on tokens issued to customers by {@link JwtIssuer#issueCustomer}. */
  public static final String CUSTOMER = "customer-api";

  /** The audience on tokens issued to staff by {@link JwtIssuer#issueStaff}. */
  public static final String STAFF = "staff-api";

  private JwtAudiences() {
    // constants only; never instantiated
  }
}
