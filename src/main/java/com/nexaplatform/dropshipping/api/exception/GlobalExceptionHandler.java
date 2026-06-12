package com.nexaplatform.dropshipping.api.exception;

import com.nexaplatform.dropshipping.api.dto.ApiResponseDtoOut;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Global exception handler. Mirrors the core's contract: each exception type
 * maps to an HTTP status and a uniform {@link ApiResponseDtoOut} body carrying
 * {@code code}, {@code message}, {@code details} and {@code timestamp}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static ApiResponseDtoOut<?> body(String code, String message, List<String> details) {
        return ApiResponseDtoOut.builder().code(code).message(message).details(details).timestamp(ZonedDateTime.now())
                .build();
    }

    // ---------------- Domain hierarchy ----------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleNotFound(NotFoundException ex) {
        log.warn("Entity not found: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ArgumentException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleArgument(ArgumentException ex) {
        log.warn("Argument exception: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.BAD_REQUEST);
    }

    /** Covers {@link DomainException} and its subclass {@link ConflictException}. */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleDomain(DomainException ex) {
        log.warn("Domain exception: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleBusiness(BusinessException ex) {
        log.warn("Business exception: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleRateLimit(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {} - Code: {}", ex.getMessage(), ex.getCode());
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()), HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Catch-all for any other {@link BaseException} → 500. */
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleBase(BaseException ex) {
        log.error("Base exception: {} - Code: {}", ex.getMessage(), ex.getCode(), ex);
        return new ResponseEntity<>(body(ex.getCode(), ex.getMessage(), ex.getDetail()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---------------- Framework / validation ----------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getAllErrors().stream().map(ObjectError::getDefaultMessage)
                .toList();
        return new ResponseEntity<>(body("VE001", "Validation error", details), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream().map(ConstraintViolation::getMessage).toList();
        return new ResponseEntity<>(body("VE001", "Validation error", details), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMissingParam(MissingServletRequestParameterException ex) {
        return new ResponseEntity<>(body("VE002", "Missing required parameter", List.of(ex.getMessage())),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return new ResponseEntity<>(body("VE003", "Invalid parameter type", List.of(ex.getMessage())),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleNotReadable(HttpMessageNotReadableException ex) {
        return new ResponseEntity<>(body("VE004", "Malformed JSON request", List.of()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return new ResponseEntity<>(
                body("DB001", "Data integrity violation", List.of(ex.getMostSpecificCause().getMessage())),
                HttpStatus.CONFLICT);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiResponseDtoOut<?>> handleForbidden(RuntimeException ex) {
        return new ResponseEntity<>(body("SE001", "Access denied", List.of(ex.getMessage())), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleUnauthorized(AuthenticationException ex) {
        return new ResponseEntity<>(body("SE002", "Unauthorized", List.of(ex.getMessage())), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleNoRoute(NoResourceFoundException ex) {
        return new ResponseEntity<>(body("ENF002", "Route not found: " + ex.getResourcePath(), List.of()),
                HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return new ResponseEntity<>(body("ME001", ex.getMessage(), List.of()), HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDtoOut<?>> handleGlobal(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return new ResponseEntity<>(body("IS001", "Internal server error", List.of()),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
