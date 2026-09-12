package com.acuity.subscribemaster.error;

import com.acuity.subscribemaster.applog.AppLogService;
import com.acuity.subscribemaster.auth.UserAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppLogService appLog;

    public GlobalExceptionHandler(AppLogService appLog) {
        this.appLog = appLog;
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUserExists(
            UserAlreadyExistsException ex, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                        .sorted(Comparator.comparing(ApiError.FieldError::field))
                        .toList();
        return ResponseEntity.badRequest()
                .body(
                        ApiError.of(
                                HttpStatus.BAD_REQUEST,
                                ErrorCode.VALIDATION_FAILED,
                                "Request validation failed.",
                                request.getRequestURI(),
                                fieldErrors));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        appLog.error(
                "GlobalExceptionHandler",
                request.getMethod()
                        + " "
                        + request.getRequestURI()
                        + " -> "
                        + ex.getClass().getName());
        return body(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. If it persists, contact support.",
                request);
    }

    private static ResponseEntity<ApiError> body(
            HttpStatus status, ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status, code, message, request.getRequestURI()));
    }
}
