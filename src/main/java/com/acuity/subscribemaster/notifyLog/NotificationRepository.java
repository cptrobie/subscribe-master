package com.acuity.subscribemaster.notifyLog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationLog, UUID> {

}
