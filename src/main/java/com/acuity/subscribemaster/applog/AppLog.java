package com.acuity.subscribemaster.applog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A structured application log row (table {@code app_logs}, {@code V1}). Append-only: {@code
 * created_at} only, no {@code updated_at} (NFR-07).
 *
 * <p>See {@link AppLogService} for the caveat on writing logs to the database vs. an external
 * aggregator (NFR-23).
 */

// TODO: Under review -- NFR-23 specifies SLF4J as the logging convention,
// not a DB-backed logger. This whole applog/ package may be removed or
// refactored once audit_logs (NFR-17) work clarifies where DB-backed
// logging actually belongs, if anywhere. See #66 for the exploratory
// time logged against this.

@Entity
@Table(name = "app_logs")
public class AppLog {
  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(nullable = false, updatable = false)
  private String level;

  @Column(nullable = false, updatable = false)
  private String source;

  @Column(nullable = false, updatable = false)
  private String message;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AppLog() {}

  public AppLog(String level, String source, String message) {
    this.level = level;
    this.source = source;
    this.message = message;
  }

  public UUID getId() {
    return id;
  }

  public String getLevel() {
    return level;
  }

  public String getSource() {
    return source;
  }

  public String getMessage() {
    return message;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
