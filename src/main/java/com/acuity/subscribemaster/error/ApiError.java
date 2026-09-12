package com.acuity.subscribemaster.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

@Schema(name = "ApiError", description = "Standardized error response")
public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String code,
    String message,
    String path,
    List<FieldError> fieldErrors) {

  @Schema(name = "FieldError")
  public record FieldError(String field, String message) {}

  public static ApiError of(HttpStatus status, ErrorCode code, String message, String path) {
    return of(status, code, message, path, List.of());
  }

  public static ApiError of(
      HttpStatus status,
      ErrorCode code,
      String message,
      String path,
      List<FieldError> fieldErrors) {
    return new ApiError(
        Instant.now(),
        status.value(),
        status.getReasonPhrase(),
        code.name(),
        message,
        path,
        List.copyOf(fieldErrors));
  }
}
