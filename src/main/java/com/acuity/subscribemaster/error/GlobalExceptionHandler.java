package com.acuity.subscribemaster.error;

import com.acuity.subscribemaster.auth.UserAlreadyExistsException;
import com.acuity.subscribemaster.idempotency.IdempotentReplayCorruptedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(UserAlreadyExistsException.class)
  public ResponseEntity<ApiError> handleAlreadyExists(
          UserAlreadyExistsException ex, HttpServletRequest request) {
    ApiError body =
            ApiError.of(
                    HttpStatus.CONFLICT,
                    ErrorCode.USER_ALREADY_EXISTS,
                    "Email already registered",
                    request.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(IdempotentReplayCorruptedException.class)
  public ResponseEntity<ApiError> handleUnreadableStoredResponse(
          IdempotentReplayCorruptedException ex, HttpServletRequest request) {
    ApiError body =
            ApiError.of(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR,
                    ex.getMessage(),
                    request.getRequestURI());
    return ResponseEntity.internalServerError().body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
          MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<ApiError.FieldError> fieldErrors =
            ex.getBindingResult().getFieldErrors().stream()
                    .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                    .toList();
    ApiError body =
            ApiError.of(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_FAILED,
                    "Request validation failed",
                    request.getRequestURI(),
                    fieldErrors);
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpServletRequest request) {
    return body(
            HttpStatus.BAD_REQUEST,
            ErrorCode.MALFORMED_REQUEST,
            "Request body is missing or not valid JSON.",
            request);
  }

  private static ResponseEntity<ApiError> body(
          HttpStatus status, ErrorCode code, String message, HttpServletRequest request) {
    return ResponseEntity.status(status)
            .body(ApiError.of(status, code, message, request.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    logger.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);

    return body(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ErrorCode.INTERNAL_ERROR,
            "An unexpected error occurred. If it persists, contact support.",
            request);
  }
}