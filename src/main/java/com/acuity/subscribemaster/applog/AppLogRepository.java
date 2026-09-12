package com.acuity.subscribemaster.applog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// TODO: Under review -- NFR-23 specifies SLF4J as the logging convention,
// not a DB-backed logger. This whole applog/ package may be removed or
// refactored once audit_logs (NFR-17) work clarifies where DB-backed
// logging actually belongs, if anywhere. See #66 for the exploratory
// time logged against this.

public interface AppLogRepository extends JpaRepository<AppLog, UUID> {

  List<AppLog> findBySourceOrderByCreatedAtAsc(String source);

  long countByLevel(String level);
}
