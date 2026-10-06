package com.acuity.subscribemaster.error;

import com.acuity.subscribemaster.auth.AccountAlreadyExistsException;
import com.acuity.subscribemaster.auth.AccountLockedException;
import com.acuity.subscribemaster.auth.InvalidCredentialsException;
import com.acuity.subscribemaster.idempotency.IdempotentReplayCorruptedException;
import com.acuity.subscribemaster.subscribe.InvalidSubscriptionIdentityException;
import com.acuity.subscribemaster.support.EntityNotFoundException;
import com.acuity.subscribemaster.support.InvalidStateTransitionException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // NOTE: Since this is a generic Exception class, we use the ex.message
  // to provide some contextual details on what actually wasn't found
  @ExceptionHandler(EntityNotFoundException.class)
  public ResponseEntity<ApiError> handleEntityNotFound(
      EntityNotFoundException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiError> handleOptimisticLockConflict(
      ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.CONFLICT,
            ErrorCode.CONCURRENT_MODIFICATION,
            "This record was modified by another request. Refresh and try again.",
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(InvalidSubscriptionIdentityException.class)
  public ResponseEntity<ApiError> handleInvalidSubscriptionIdentity(
      InvalidSubscriptionIdentityException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.BAD_REQUEST,
            ErrorCode.INVALID_SUBSCRIPTION_IDENTITY,
            ex.getMessage(),
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(InvalidStateTransitionException.class)
  public ResponseEntity<ApiError> handleInvalidStateTransition(
      InvalidStateTransitionException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.CONFLICT,
            ErrorCode.INVALID_STATE_TRANSITION,
            ex.getMessage(),
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(AccountAlreadyExistsException.class)
  public ResponseEntity<ApiError> handleAlreadyExists(
      AccountAlreadyExistsException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.CONFLICT,
            ErrorCode.ACCOUNT_ALREADY_EXISTS,
            "Email already registered",
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ApiError> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.UNAUTHORIZED,
            ErrorCode.INVALID_CREDENTIALS,
            "Invalid email or password",
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<ApiError> handleLockedAccount(
      AccountLockedException ex, HttpServletRequest request) {
    ApiError body =
        ApiError.of(
            HttpStatus.TOO_MANY_REQUESTS,
            ErrorCode.ACCOUNT_LOCKED,
            "Account locked due to too many failed login attempts",
            request.getRequestURI());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
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

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return body(
        HttpStatus.BAD_REQUEST,
        ErrorCode.MALFORMED_REQUEST,
        "Invalid value for parameter '" + ex.getName() + "'",
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpServletRequest request) {
    return body(
        HttpStatus.BAD_REQUEST,
        ErrorCode.MALFORMED_REQUEST,
        "Request body is missing or not valid JSON.",
        request);
  }

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ApiError> handleNoHandlerFound(HttpServletRequest request) {
    return body(
        HttpStatus.BAD_REQUEST,
        ErrorCode.MALFORMED_REQUEST,
        "No matching route for this request.",
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
