package com.acuity.subscribemaster.auth;

/**
 * A registration was attempted for an email that already has an account.
 *
 * <p>The message is deliberately generic (no email address) so it is safe to log
 * and safe to return — it does not confirm to an anonymous caller whether a
 * given address is registered beyond the HTTP status itself.
 */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException() {
        super("An account with this email address already exists.");
    }

}
