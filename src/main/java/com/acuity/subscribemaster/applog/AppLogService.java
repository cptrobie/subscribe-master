package com.acuity.subscribemaster.applog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// TODO: Under review -- NFR-23 specifies SLF4J as the logging convention,
// not a DB-backed logger. This whole applog/ package may be removed or
// refactored once audit_logs (NFR-17) work clarifies where DB-backed
// logging actually belongs, if anywhere. See #66 for the exploratory
// time logged against this.

@Service
public class AppLogService {
  private static final Logger logger = LoggerFactory.getLogger(AppLogService.class);

  private final AppLogRepository repository;
  private final TransactionTemplate transactionTemplate;

  public AppLogService(AppLogRepository repository, PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void write(AppLogLevel level, String source, String message) {
    try {
      transactionTemplate.executeWithoutResult(
          status -> repository.save(new AppLog(level.dbValue(), source, message)));
    } catch (RuntimeException e) {
      logger.error("failed to persist app_logs row (source={}, level={})", source, level, e);
    }
  }

  // Listed in severity order
  public void debug(String source, String message) {
    write(AppLogLevel.DEBUG, source, message);
  }

  public void info(String source, String message) {
    write(AppLogLevel.INFO, source, message);
  }

  public void warning(String source, String message) {
    write(AppLogLevel.WARNING, source, message);
  }

  public void error(String source, String message) {
    write(AppLogLevel.ERROR, source, message);
  }

  public void critical(String source, String message) {
    write(AppLogLevel.CRITICAL, source, message);
  }
}
