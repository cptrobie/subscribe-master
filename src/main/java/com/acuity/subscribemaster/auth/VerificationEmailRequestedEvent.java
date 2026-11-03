package com.acuity.subscribemaster.auth;

import com.acuity.subscribemaster.support.EmailMasker;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by the verification resend (FR-40) once earlier tokens have been marked used and a new
 * one saved -- and, for a corrected address, the customer's email changed -- all inside the
 * resend's transaction. {@link VerificationEmailListener} sends the email after that transaction
 * commits, through the same path as {@link CustomerRegisteredEvent}, so a rejected or rolled-back
 * resend never emails a link.
 *
 * <p>A separate event rather than re-publishing {@link CustomerRegisteredEvent}: that one is public
 * so other modules can react to a <em>new customer</em> (NFR-28), and a resend isn't one. This
 * record is package-private for the same reason -- nothing outside {@code auth} should react to it.
 *
 * <p>{@code email} is the address the link goes to: the corrected one if the request changed it,
 * otherwise the current one. {@code rawToken} is the only copy of the unhashed token and exists
 * solely for the emailed link. {@code ipAddress} is carried for the audit entry and may be null.
 *
 * <p><b>{@link #toString()} is overridden deliberately -- do not remove it.</b> A record's default
 * would print the raw token and the full email address; this prints only the customer id and a
 * masked email (NFR-23), as {@link CustomerRegisteredEvent} does. The same NFR-28 caveat applies
 * too: this is safe only while events stay in memory, since a persisted event publication registry
 * would store {@code rawToken} in plain text.
 */
record VerificationEmailRequestedEvent(
    UUID customerId, String email, String rawToken, String ipAddress) {

  VerificationEmailRequestedEvent {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(email, "email");
    Objects.requireNonNull(rawToken, "rawToken");
  }

  @Override
  public String toString() {
    return "VerificationEmailRequestedEvent["
        + "customerId="
        + customerId
        + ", email="
        + EmailMasker.mask(email)
        + "]";
  }
}
