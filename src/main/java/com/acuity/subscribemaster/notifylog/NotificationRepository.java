package com.acuity.subscribemaster.notifylog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for {@link NotificationLog} entries. */
public interface NotificationRepository extends JpaRepository<NotificationLog, UUID> {}
