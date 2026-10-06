package com.acuity.subscribemaster.notifyLog;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<NotificationLog, UUID> {}
