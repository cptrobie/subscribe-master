package com.acuity.subscribemaster.applog;

import java.util.Locale;

/**
 * Levels accepted by {@code app_logs.level}'s CHECK constraint ({@code debug, info, warning, error,
 * critical} — note {@code warning}, not {@code warn}).
 */

// TODO: Under review -- NFR-23 specifies SLF4J as the logging convention,
// not a DB-backed logger. This whole applog/ package may be removed or
// refactored once audit_logs (NFR-17) work clarifies where DB-backed
// logging actually belongs, if anywhere. See #66 for the exploratory
// time logged against this.

public enum AppLogLevel {
  DEBUG,
  INFO,
  WARNING,
  ERROR,
  CRITICAL;

  /** The lowercase form persisted to {@code app_logs.level}. */
  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
