package com.acuity.subscribemaster.support;

import java.util.UUID;

/**
 * Reserved actor_id values for audit_logs entries written by non-human
 * system components (schedulers, background jobs), paired with a
 * matching audit_logs.actor_type value. Never reused for a real
 * customer/staff actor_id. See payment_retry_and_dunning.md.
 */
public class SystemActors {

    /** Pairs with actor_type = 'retry_scheduler'. */
    public static final UUID RETRY_SCHEDULER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private SystemActors() {}
}
