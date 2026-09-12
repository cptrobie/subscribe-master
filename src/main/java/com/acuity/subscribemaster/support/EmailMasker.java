package com.acuity.subscribemaster.support;

/**
 * Masks an email address for logging. NFR-23 forbids logging PII; a registration flow still
 * benefits from a correlatable-but-non-identifying hint in the logs.
 */
public final class EmailMasker {

  private EmailMasker() {}

  public static String mask(String email) {
    if (email == null || email.isBlank()) {
      return "<none>";
    }
    int at = email.indexOf('@');
    if (at < 1 || at == email.length() - 1) {
      return "***";
    }
    String local = email.substring(0, at);
    String domain = email.substring(at + 1);
    String maskedLocal = local.charAt(0) + "***";
    int dot = domain.lastIndexOf('.');
    String maskedDomain = dot < 1 ? "***" : domain.charAt(0) + "***" + domain.substring(dot);
    return maskedLocal + "@" + maskedDomain;
  }
}
